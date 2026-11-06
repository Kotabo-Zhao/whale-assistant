package io.deepseekharness.mobile.companion

import android.content.ContentValues
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Android owns both dispatch and durable facts. A receipt never proves a target App's business result. */
internal class ScreenExecutionJournal(file: File, private val generation: String=UUID.randomUUID().toString()) : AutoCloseable {
    private val db: SQLiteDatabase
    private val abandoned=LinkedHashMap<String,String>()
    init {
        file.parentFile?.mkdirs()
        db=SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.CREATE_IF_NECESSARY,
            DatabaseErrorHandler { throw RuntimeFailure("SCREEN_JOURNAL_CORRUPT","执行记录损坏，已停止手机操作；保留原文件待恢复") })
        try {
            db.enableWriteAheadLogging()
            db.execSQL("PRAGMA synchronous=FULL")
            db.setForeignKeyConstraintsEnabled(true)
            transaction {
                if(db.version !in 0..1) throw RuntimeFailure("SCREEN_JOURNAL_VERSION","执行记录版本不兼容")
                db.execSQL("CREATE TABLE IF NOT EXISTS operations(id TEXT PRIMARY KEY,kind TEXT NOT NULL,request_hash TEXT NOT NULL,session_id TEXT NOT NULL,call_id TEXT NOT NULL,package_name TEXT NOT NULL,owner TEXT NOT NULL,state TEXT NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,reconciled INTEGER NOT NULL DEFAULT 0,received INTEGER NOT NULL DEFAULT 0,error TEXT NOT NULL DEFAULT '',receipt TEXT NOT NULL DEFAULT '{}')")
                db.execSQL("CREATE TABLE IF NOT EXISTS steps(operation_id TEXT NOT NULL REFERENCES operations(id),step INTEGER NOT NULL,state TEXT NOT NULL,action TEXT NOT NULL,evidence TEXT NOT NULL DEFAULT '{}',updated INTEGER NOT NULL,PRIMARY KEY(operation_id,step))")
                // Minimal tombstones outlive detailed receipts. Never silently forget a retained operation id.
                db.execSQL("CREATE TABLE IF NOT EXISTS seen(id TEXT PRIMARY KEY)")
                db.execSQL("CREATE INDEX IF NOT EXISTS operations_updated ON operations(updated)")
                db.version=1
                db.execSQL("UPDATE operations SET state='unknown',error='PROCESS_INTERRUPTED',updated=? WHERE state='running' AND owner<>?",arrayOf(System.currentTimeMillis(),generation))
            }
        } catch(error:Throwable) { db.close(); throw error }
    }
    private fun <T> transaction(block:()->T):T {
        db.beginTransaction()
        try { val value=block();db.setTransactionSuccessful();return value } finally {db.endTransaction()}
    }
    private fun valid(id:String) {
        if(runCatching{UUID.fromString(id).toString()==id}.getOrDefault(false)!=true)
            throw RuntimeFailure("SCREEN_INPUT_INVALID","操作编号无效")
    }
    private fun count(sql:String,args:Array<String>?=null):Long=db.rawQuery(sql,args).use{it.moveToFirst();it.getLong(0)}
    private fun owns(id:String)=count("SELECT count(*) FROM operations WHERE id=? AND owner=? AND state='running'",arrayOf(id,generation))==1L
    private fun requireOwner(id:String) { if(!owns(id)) throw RuntimeFailure("SCREEN_OPERATION_INACTIVE","执行代次已失效") }
    private fun repairAbandoned() {
        for((id,code) in abandoned.toMap()) {
            if(owns(id)) update(id,"state" to "unknown","error" to code)
            // Remove only after the outer transaction commits; repeating the update is harmless.
        }
    }
    private fun values(vararg entries:Pair<String,Any>)=ContentValues().apply { entries.forEach { (k,v)->when(v) {
        is Long -> put(k,v); is Int -> put(k,v); else -> put(k,v.toString())
    } } }
    private fun update(id:String,vararg fields:Pair<String,Any>) {
        if(db.update("operations",values(*fields,"updated" to System.currentTimeMillis()),"id=?",arrayOf(id))!=1)
            throw RuntimeFailure("SCREEN_OPERATION_MISSING","执行记录不存在")
    }
    @Synchronized fun begin(id:String,kind:String,request:JSONObject):Boolean=transaction {
        valid(id)
        repairAbandoned()
        val previous=read(id)
        if(previous!=null) {
            if(previous.optString("state")=="cancelled") throw RuntimeFailure("SCREEN_SEQUENCE_CANCELLED","操作已撤销")
            if(previous.optString("requestHash")!=digest(request.toString()))
                throw RuntimeFailure("SCREEN_OPERATION_CONFLICT","同一操作编号的内容发生变化")
            if(previous.optString("state")=="running") throw RuntimeFailure("SCREEN_OPERATION_RUNNING","操作仍在执行")
            return@transaction false
        }
        if(count("SELECT count(*) FROM seen WHERE id=?",arrayOf(id))>0)
            throw RuntimeFailure("SCREEN_OPERATION_ARCHIVED","该操作已执行且详情已归档，禁止重放")
        if(count("SELECT count(*) FROM operations WHERE state IN ('unknown','running') AND reconciled=0")>0)
            throw RuntimeFailure("SCREEN_RECOVERY_REQUIRED","有中断的手机操作，请先用 mobile_screen_recover 读取记录并观察当前页面，禁止重放")
        // At capacity, fail before the external action. Corruption/full disk must not mean an empty history.
        if(count("SELECT count(*) FROM seen")>=65536)
            throw RuntimeFailure("SCREEN_JOURNAL_FULL","执行日志容量已满，请先维护日志")
        if(count("SELECT count(*) FROM operations")>=2048) {
            db.execSQL("DELETE FROM steps WHERE operation_id IN (SELECT id FROM operations WHERE state IN ('completed','stopped','cancelled') ORDER BY updated LIMIT 256)")
            db.execSQL("DELETE FROM operations WHERE id IN (SELECT id FROM operations WHERE state IN ('completed','stopped','cancelled') ORDER BY updated LIMIT 256)")
            if(count("SELECT count(*) FROM operations")>=2048) throw RuntimeFailure("SCREEN_JOURNAL_FULL","待核实的执行记录过多")
        }
        db.insertOrThrow("seen",null,values("id" to id))
        val now=System.currentTimeMillis()
        db.insertOrThrow("operations",null,values("id" to id,"kind" to kind,"request_hash" to digest(request.toString()),
            "session_id" to request.optString("sessionId").take(160),"call_id" to request.optString("callId").take(160),
            "package_name" to request.optString("packageName").take(160),"owner" to generation,
            "state" to "running","created" to now,"updated" to now))
        true
    }
    @Synchronized fun dispatch(id:String,step:Int,action:String) = transaction {
        check(step in 1..6 && action in setOf("tap","swipe","back","text"))
        requireOwner(id)
        db.insertOrThrow("steps",null,values("operation_id" to id,"step" to step,"state" to "dispatching","action" to action,"updated" to System.currentTimeMillis()))
        update(id,"state" to "running")
    }
    @Synchronized fun injected(id:String,step:Int) = stepState(id,step,"injected",JSONObject())
    @Synchronized fun observed(id:String,step:Int,observation:JSONObject) = stepState(id,step,"observed",evidence(observation))
    private fun stepState(id:String,step:Int,state:String,evidence:JSONObject) = transaction {
        requireOwner(id)
        if(db.update("steps",values("state" to state,"evidence" to evidence.toString(),"updated" to System.currentTimeMillis()),
            "operation_id=? AND step=?",arrayOf(id,step.toString()))!=1) throw RuntimeFailure("SCREEN_OPERATION_MISSING","步骤记录不存在")
        update(id,"state" to "running")
    }
    @Synchronized fun finish(id:String,result:JSONObject) = transaction {
        requireOwner(id)
        val uncertain=count("SELECT count(*) FROM steps WHERE operation_id=? AND state<>'observed'",arrayOf(id))>0
        val status=if(uncertain) "unknown" else if(result.optString("status","completed")=="completed") "completed" else "stopped"
        val summary=JSONObject().put("completed",result.optInt("completed",if(status=="completed") 1 else 0))
            .put("visibleTextStable",result.optBoolean("visibleTextStable")).put("elapsedMs",result.optLong("elapsedMs"))
            .put("verification","仅记录输入及观察，不证明目标业务成功")
        update(id,"state" to status,"receipt" to summary.toString(),"error" to result.optString("errorCode",result.optString("observationError")))
    }
    @Synchronized fun interrupted(id:String,code:String) {
        abandoned[id]=code.take(100)
        transaction {
            if(!owns(id)) return@transaction
            val uncertain=count("SELECT count(*) FROM steps WHERE operation_id=? AND state<>'observed'",arrayOf(id))>0
            update(id,"state" to if(uncertain) "unknown" else "stopped","error" to code.take(100))
        }
        abandoned.remove(id)
    }
    @Synchronized fun cancelBeforeStart(id:String) = transaction {
        valid(id)
        if(read(id)!=null || count("SELECT count(*) FROM seen WHERE id=?",arrayOf(id))>0) return@transaction
        if(count("SELECT count(*) FROM seen")>=65536 || count("SELECT count(*) FROM operations")>=2048)
            throw RuntimeFailure("SCREEN_JOURNAL_FULL","执行日志容量已满")
        val now=System.currentTimeMillis()
        db.insertOrThrow("seen",null,values("id" to id))
        db.insertOrThrow("operations",null,values("id" to id,"kind" to "screenSequence","request_hash" to "","session_id" to "","call_id" to "","package_name" to "",
            "owner" to generation,"state" to "cancelled","created" to now,"updated" to now))
    }
    @Synchronized fun reconcile(id:String,observation:JSONObject,foreground:String):JSONObject = transaction {
        val row=read(id) ?: throw RuntimeFailure("SCREEN_OPERATION_MISSING","执行记录不存在")
        if(row.optString("state")=="running") throw RuntimeFailure("SCREEN_OPERATION_RUNNING","操作尚未结束")
        val expected=row.optString("packageName")
        if(expected.isNotEmpty() && foreground!=expected)
            throw RuntimeFailure("SCREEN_RECOVERY_APP_MISMATCH","当前应用与中断时不同，请先回到目标应用再核实；旧操作不会重放")
        val summary=row.getJSONObject("receipt").put("recoveryEvidence",evidence(observation).put("packageName",foreground))
        // Observation permits a new decision; it does not relabel an unknown side effect as successful.
        update(id,"reconciled" to System.currentTimeMillis(),"receipt" to summary.toString())
        read(id)!!
    }
    @Synchronized fun received(id:String) { valid(id);transaction { update(id,"received" to System.currentTimeMillis()) } }
    @Synchronized fun read(id:String):JSONObject? {
        valid(id)
        if(abandoned.isNotEmpty() && !db.inTransaction()) {
            transaction { repairAbandoned() }
            abandoned.clear()
        }
        return db.rawQuery("SELECT * FROM operations WHERE id=?",arrayOf(id)).use { c ->
            if(!c.moveToFirst()) return@use null
            fun text(k:String)=c.getString(c.getColumnIndexOrThrow(k))
            fun number(k:String)=c.getLong(c.getColumnIndexOrThrow(k))
            val steps=JSONArray()
            db.rawQuery("SELECT step,state,action,evidence FROM steps WHERE operation_id=? ORDER BY step",arrayOf(id)).use { s ->
                while(s.moveToNext()) steps.put(JSONObject().put("step",s.getInt(0)).put("state",s.getString(1)).put("action",s.getString(2)).put("evidence",JSONObject(s.getString(3))))
            }
            JSONObject().put("operationId",id).put("kind",text("kind")).put("requestHash",text("request_hash"))
                .put("sessionId",text("session_id")).put("callId",text("call_id")).put("packageName",text("package_name"))
                .put("state",text("state")).put("createdAt",number("created")).put("updatedAt",number("updated"))
                .put("reconciledAt",number("reconciled")).put("clientReceivedAt",number("received")).put("errorCode",text("error"))
                .put("steps",steps).put("receipt",JSONObject(text("receipt"))).put("doNotReplay",true)
        }
    }
    @Synchronized fun recent():JSONObject {
        if(abandoned.isNotEmpty()) { transaction {repairAbandoned()};abandoned.clear() }
        val items=JSONArray()
        db.rawQuery("SELECT id FROM operations ORDER BY (state='unknown' AND reconciled=0) DESC,updated DESC LIMIT 20",null).use { c->
            while(c.moveToNext()) items.put(read(c.getString(0)))
        }
        return JSONObject().put("items",items).put("pendingRecovery",count("SELECT count(*) FROM operations WHERE state='unknown' AND reconciled=0"))
            .put("nextStep","中断后先按 operationId 读取新观察；未知结果不会自动重放或视为成功")
    }
    override fun close() = db.close()
    companion object {
        private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        private fun evidence(observation:JSONObject):JSONObject {
            val image=observation.optJSONObject("image")
            return JSONObject().put("observedAt",image?.optLong("observedAtMs") ?: System.currentTimeMillis())
                .put("width",observation.optInt("width")).put("height",observation.optInt("height"))
                .put("digest",digest((image?.optString("frameId") ?: observation.optJSONObject("tree")?.toString()).orEmpty()))
        }
    }
}
