package io.deepseekharness.mobile.companion

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** User-owned commitments share the existing task, reminder and backup tables. */
internal class SecretaryLedger(private val db: SQLiteDatabase, private val queue: UnderstandingQueue) {
    fun list(query: String = "", offset: Int = 0, activeOnly: Boolean = false, limit: Int = 20): JSONObject {
        require(query.length <= 100 && offset >= 0 && limit in 1..20)
        val where=if (activeOnly) "AND i.state='open'" else ""
        val rows=JSONArray()
        db.rawQuery("SELECT i.id FROM understanding_items i JOIN secretary_items s ON s.item_id=i.id WHERE instr(lower(i.title),lower(?))>0 $where ORDER BY CASE WHEN i.state='open' THEN 0 ELSE 1 END,CASE WHEN s.due_at IS NULL THEN 1 ELSE 0 END,s.due_at,i.rowid DESC LIMIT ? OFFSET ?",
            arrayOf(query,(limit+1).toString(),offset.toString())).use { c -> while (c.moveToNext()) rows.put(public(c.getString(0))) }
        val more=rows.length()>limit; if (more) rows.remove(limit)
        return JSONObject().put("items",rows).put("nextOffset",if (more) offset+limit else JSONObject.NULL)
    }
    fun public(id: String): JSONObject {
        val item=queue.item(id) ?: throw RuntimeFailure("TASK_NOT_FOUND","事项已删除")
        val body=item.secretary ?: throw RuntimeFailure("TASK_NOT_FOUND","该事项不属于秘书台账")
        return JSONObject(body).put("id",item.id).put("title",item.title).put("state",item.state)
            .put("revision",CompanionTaskModel.revision(item)).put("reminderStatus",when(item.reminderStatus){1->if((item.reminderAtMs ?: 0)>System.currentTimeMillis()) "scheduled" else "elapsed";2->"needs_review";else->"none"})
            .put("reminderAtMs",item.reminderAtMs ?: JSONObject.NULL).put("reminderRevision",item.reminderRevision)
    }
    fun closedMatters(): JSONArray = JSONArray().also { rows ->
        db.rawQuery("SELECT i.id,i.title,i.state FROM understanding_items i JOIN secretary_items s ON s.item_id=i.id WHERE i.state IN ('done','cancelled') ORDER BY i.rowid DESC LIMIT 8",null).use { c ->
            while(c.moveToNext()) rows.put(JSONObject().put("id",c.getString(0)).put("title",c.getString(1)).put("state",c.getString(2)))
        }
    }
    fun execute(request: JSONObject, owner: String, now: Long = System.currentTimeMillis()): JSONObject {
        val action=request.optString("action")
        if (action=="list") {
            val offset=request.opt("offset") ?: 0
            require(offset is Number && offset.toLong() in 0..Int.MAX_VALUE.toLong() && offset.toDouble()==offset.toInt().toDouble()) { "事项分页参数无效" }
            return list(request.optString("query"),offset.toInt(),request.optBoolean("activeOnly"))
        }
        require(action in setOf("create","update","delete") && BubbleSessionRegistry.valid(owner)) { "秘书事项参数无效" }
        val operation=request.optString("operationId")
        require(operation.matches(Regex("[a-f0-9-]{36}"))) { "缺少事项操作编号" }
        val operationKey=CompanionMemorySuppression.digest("$owner:$operation")
        val canonical=JSONObject(); request.keys().asSequence().sorted().filter { it !in setOf("callerSession","operationId") }.forEach { canonical.put(it,request.opt(it)) }
        val requestHash=CompanionMemorySuppression.digest(canonical.toString())
        db.beginTransaction()
        try {
            db.rawQuery("SELECT request_hash,item_id FROM secretary_operations WHERE operation_key=?",arrayOf(operationKey)).use { c ->
                if(c.moveToFirst()) {
                    require(c.getString(0)==requestHash) { "操作编号不能用于不同事项变更" }
                    val id=c.getString(1)
                    val result=JSONObject().put("idempotent",true).put("id",id)
                    if(queue.item(id)==null) result.put("removed",true) else result.put("item",public(id))
                    db.setTransactionSuccessful();return result
                }
            }
            val id=if(action=="create") UUID.nameUUIDFromBytes("secretary:$operationKey".toByteArray()).toString() else request.optString("id")
            require(id.matches(Regex("[a-f0-9-]{36}"))) { "事项标识无效" }
            val old=if(action=="create") null else queue.item(id) ?: throw RuntimeFailure("TASK_NOT_FOUND","事项已删除")
            if (old != null && (old.secretary==null || request.optString("expectedRevision")!=CompanionTaskModel.revision(old)))
                throw RuntimeFailure("TASK_CONFLICT","事项内容、状态或提醒已变化，请重新读取")
            val event="secretary:$id"
            if(action=="delete") {
                db.insertWithOnConflict("understanding_forget",null,ContentValues().apply { put("event_id",event) },SQLiteDatabase.CONFLICT_IGNORE)
                queue.deleteItem(id,old!!.state,CompanionTaskModel.revision(old))
            } else {
                val body=old?.secretary?.let(::JSONObject) ?: JSONObject().put("createdAt",now).put("sourceSession",owner)
                val title=if(request.has("title")) text(request,"title",140,false) else old?.title ?: error("需要事项名称")
                if(old==null) db.rawQuery("SELECT i.id FROM understanding_items i JOIN secretary_items s ON s.item_id=i.id WHERE i.state='open' AND lower(i.title)=lower(?) LIMIT 1",arrayOf(title)).use { c ->
                    if(c.moveToFirst()) throw RuntimeFailure("TASK_EXISTS","已有同名待处理事项 ${c.getString(0)}，请读取后更新，不要重复建提醒")
                }
                for((key,max) in listOf("note" to 400,"nextAction" to 240,"waitingFor" to 100)) if(request.has(key)) body.put(key,text(request,key,max,true))
                val state=request.optString("state",old?.state ?: "open")
                require(state in setOf("open","done","cancelled")) { "事项状态无效" }
                require(!(request.has("dueAtMs") && request.optBoolean("clearDue")) && !(request.has("reminderAtMs") && request.optBoolean("clearReminder"))) { "时间参数冲突" }
                if(request.has("dueAtMs")) body.put("dueAtMs",time(request,"dueAtMs"))
                if(request.optBoolean("clearDue")) body.remove("dueAtMs")
                if (request.has("reminderAtMs")) require(state=="open" && CompanionReminderPolicy.isFuture(time(request,"reminderAtMs"),now)) { "提醒应为待处理事项的明确未来时间" }
                body.put("updatedAt",now).put("lastOperationId",operation).put("version",1)
                if (old==null) {
                    require(!db.rawQuery("SELECT 1 FROM understanding_forget WHERE event_id=?",arrayOf(event)).use { it.moveToFirst() }) { "已删除事项不能重放" }
                    val eventBody=JSONObject().put("source","user_request").put("session",owner).put("title",title).toString()
                    val hash=CompanionMemorySuppression.digest("${event.length}:$event$eventBody")
                    db.insertOrThrow("understanding_events",null,ContentValues().apply {put("id",event);put("source","conversation");put("input_hash",hash);put("epoch",1);put("body",eventBody)})
                    val result=JSONObject().put("version",1).put("claims",JSONArray()).put("items",JSONArray().put(JSONObject().put("title",title).put("evidence_ids",JSONArray().put(event))))
                    db.insertOrThrow("understanding_results",null,ContentValues().apply {put("job_id",id);put("event_id",event);put("body",result.toString())})
                    db.insertOrThrow("understanding_items",null,ContentValues().apply {put("id",id);put("job_id",id);put("event_id",event);put("title",title);put("state",state)})
                    db.insertOrThrow("understanding_item_evidence",null,ContentValues().apply {put("item_id",id);put("event_id",event)})
                } else {
                    db.update("understanding_items",ContentValues().apply {put("title",title);put("state",state)},"id=?",arrayOf(id))
                }
                db.insertWithOnConflict("secretary_items",null,ContentValues().apply {
                    put("item_id",id);put("body",body.toString())
                    if(body.has("dueAtMs")) put("due_at",body.getLong("dueAtMs")) else putNull("due_at")
                },SQLiteDatabase.CONFLICT_REPLACE)
                // A changed deadline invalidates the previous reminder unless a replacement is explicit.
                val changedDue=old!=null && JSONObject(old.secretary!!).optLong("dueAtMs",0)!=body.optLong("dueAtMs",0)
                if (state!="open" || request.optBoolean("clearReminder") || changedDue && !request.has("reminderAtMs"))
                    db.execSQL("UPDATE understanding_item_reminders SET enabled=0,revision=revision+1 WHERE item_id=?",arrayOf(id))
                if (old!=null && old.title!=title && !request.has("reminderAtMs"))
                    db.execSQL("UPDATE understanding_item_reminders SET revision=revision+1 WHERE item_id=? AND enabled=1",arrayOf(id))
                if(request.has("reminderAtMs")) check(queue.setItemReminder(id,state,time(request,"reminderAtMs"),now))
            }
            db.insertOrThrow("secretary_operations",null,ContentValues().apply {put("operation_key",operationKey);put("request_hash",requestHash);put("item_id",id)})
            val response=JSONObject().put("id",id).put("saved",action!="delete").put("removed",action=="delete")
            if(action!="delete") response.put("item",public(id))
            db.setTransactionSuccessful();return response
        } finally {db.endTransaction()}
    }
    fun exportMetadata(): JSONObject {
        val items=JSONArray();val operations=JSONArray()
        db.rawQuery("SELECT item_id,body FROM secretary_items",null).use { c -> while(c.moveToNext()) items.put(JSONObject().put("id",c.getString(0)).put("body",JSONObject(c.getString(1)))) }
        db.rawQuery("SELECT operation_key,request_hash,item_id FROM secretary_operations",null).use { c -> while(c.moveToNext()) operations.put(JSONObject().put("key",c.getString(0)).put("hash",c.getString(1)).put("id",c.getString(2))) }
        return JSONObject().put("version",1).put("items",items).put("operations",operations)
    }
    fun importMetadata(root: JSONObject?) {
        db.execSQL("DELETE FROM secretary_items WHERE item_id NOT IN (SELECT id FROM understanding_items)")
        if(root==null) return
        require(root.getInt("version")==1)
        val items=root.getJSONArray("items")
        for(i in 0 until items.length()) {
            val row=items.getJSONObject(i);val id=row.getString("id");val body=row.getJSONObject("body")
            require(id.matches(Regex("[a-f0-9-]{36}")) && body.toString().length <= 2000 && body.getInt("version")==1 && BubbleSessionRegistry.valid(body.getString("sourceSession")))
            for((key,max) in listOf("note" to 400,"nextAction" to 240,"waitingFor" to 100)) if(body.has(key)) text(body,key,max,true)
            if(body.has("dueAtMs")) time(body,"dueAtMs")
            require(body.getLong("createdAt")>0 && body.getLong("updatedAt")>=body.getLong("createdAt"))
            if(queue.item(id)?.eventId!="secretary:$id") continue
            // Existing current metadata wins; importing an old backup cannot undo a correction.
            db.insertWithOnConflict("secretary_items",null,ContentValues().apply {
                put("item_id",id);put("body",body.toString())
                if(body.has("dueAtMs")) put("due_at",body.getLong("dueAtMs")) else putNull("due_at")
            },SQLiteDatabase.CONFLICT_IGNORE)
        }
        val operations=root.getJSONArray("operations")
        for(i in 0 until operations.length()) {
            val row=operations.getJSONObject(i);val key=row.getString("key");val hash=row.getString("hash");val id=row.getString("id")
            require(key.matches(Regex("[a-f0-9]{64}")) && hash.matches(Regex("[a-f0-9]{64}")) && id.matches(Regex("[a-f0-9-]{36}")))
            val inserted=db.insertWithOnConflict("secretary_operations",null,ContentValues().apply {put("operation_key",key);put("request_hash",hash);put("item_id",id)},SQLiteDatabase.CONFLICT_IGNORE)
            if(inserted == -1L) check(db.rawQuery("SELECT request_hash,item_id FROM secretary_operations WHERE operation_key=?",arrayOf(key)).use { it.moveToFirst() && it.getString(0)==hash && it.getString(1)==id }) { "事项操作备份冲突" }
        }
    }
    companion object {
        fun create(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS secretary_items (item_id TEXT PRIMARY KEY NOT NULL, body TEXT NOT NULL, due_at INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS secretary_due ON secretary_items(due_at)")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS secretary_item_cleanup AFTER DELETE ON understanding_items BEGIN DELETE FROM secretary_items WHERE item_id=OLD.id; END")
            db.execSQL("CREATE TABLE IF NOT EXISTS secretary_operations (operation_key TEXT PRIMARY KEY NOT NULL, request_hash TEXT NOT NULL, item_id TEXT NOT NULL)")
        }
        private fun text(request: JSONObject,key: String,max: Int,empty: Boolean): String {
            val value=(request.opt(key) as? String)?.trim() ?: error("事项文字无效")
            require(value.length <= max && (empty || value.isNotBlank()) && value.none { it.isISOControl() && it!='\n' } && !CompanionStore.sensitive(value)) { "事项文字无效或含敏感内容" }
            CompanionKnowledgeData.utf8(value);return value
        }
        private fun time(request: JSONObject,key: String): Long {
            val n=request.opt(key) as? Number ?: error("事项时间无效")
            val at=n.toLong();require(at>0 && at.toDouble()==n.toDouble());return at
        }
    }
}
