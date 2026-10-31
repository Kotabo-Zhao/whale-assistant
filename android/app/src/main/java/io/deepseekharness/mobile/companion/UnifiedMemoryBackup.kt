package io.deepseekharness.mobile.companion

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A consistent SQLite snapshot, copied as a stream by the user-facing SAF flow. */
internal object UnifiedMemoryBackup {
    const val MAX_BYTES = 256L * 1024 * 1024
    private val sets = setOf("memory_deleted_hashes", "memory_deleted_events", "memory_deleted_signatures", "automatic_memory_suppressed_sources")
    private val booleans = setOf("automatic_memory_suppressed_all")
    private fun <T> locked(block: () -> T): T = synchronized(CompanionKnowledgeStore.lock) { synchronized(CompanionMemoryStore.lock) { block() } }
    private fun prepare(context: Context) {
        CompanionKnowledgeStore(context).recent(0, 1)
        CompanionMemoryStore(context).migrateToDatabase()
        SharedMemoryJobs.pendingCount(context)
        UnifiedUnderstandingStore.open(context).close()
    }
    fun export(context: Context): File = locked {
        prepare(context)
        val target = File(context.cacheDir, "memory-backup-${UUID.randomUUID()}.sqlite")
        try {
            SQLiteDatabase.openDatabase(UnifiedUnderstandingStore.path(context).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("VACUUM INTO ?", arrayOf(target.absolutePath))
            }
            SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS backup_private (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                db.delete("backup_private", null, null)
                val prefs = context.getSharedPreferences("companion_private_v1", Context.MODE_PRIVATE)
                for (key in sets) db.execSQL("INSERT INTO backup_private VALUES (?,?)", arrayOf(key, JSONArray(prefs.getStringSet(key, emptySet()).orEmpty().sorted()).toString()))
                for (key in booleans) db.execSQL("INSERT INTO backup_private VALUES (?,?)", arrayOf(key, prefs.getBoolean(key, false).toString()))
            }
            require(target.length() <= MAX_BYTES) { "备份超过 256 MiB，请分批导出资料" }
            target
        } catch (e: Exception) { target.delete(); throw e }
    }
    private fun schemas(db: SQLiteDatabase): Map<String, String> = buildMap {
        db.rawQuery("SELECT name,sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='backup_private' ORDER BY name", null).use { c ->
            while (c.moveToNext()) { val name = c.getString(0); require(name.matches(Regex("[a-z_]+"))); put(name, c.getString(1)) }
        }
    }
    private fun knowledgeDecisions(db: SQLiteDatabase): Map<String, String> = buildMap {
        db.rawQuery("SELECT key,value FROM knowledge_meta WHERE key LIKE 'deleted:%'", null).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0)
                val id = key.removePrefix("deleted:")
                require(key == "deleted:$id" && id.matches(Regex("[a-f0-9-]{36}"))) { "资料删除记录无效" }
                val value = cursor.getString(1)
                val decision = JSONObject(value)
                require(decision.opt("deletedAt") is Number && decision.getLong("deletedAt") > 0)
                if (decision.has("restored")) require(decision.opt("restored") is Boolean)
                if (decision.has("row")) {
                    val row = decision.getJSONObject("row")
                    require(row.getString("id") == id)
                    CompanionKnowledgeData.validateRows(JSONArray().put(row), false)
                }
                put(key, value)
            }
        }
    }
    /** Replaces data only; never imports SQL, authorizations, credentials or executable files. */
    fun restore(context: Context, snapshot: File, beforeCommit: () -> Unit = {}) = locked {
        require(snapshot.length() in 1..MAX_BYTES)
        prepare(context)
        val path = UnifiedUnderstandingStore.path(context)
        val prefs = context.getSharedPreferences("companion_private_v1", Context.MODE_PRIVATE)
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { live ->
            val schema = schemas(live)
            val localKnowledgeDecisions = knowledgeDecisions(live)
            val localRestoredRows = mutableMapOf<String, String>()
            for ((key, value) in localKnowledgeDecisions) {
                if (!JSONObject(value).optBoolean("restored", false)) continue
                val id = key.removePrefix("deleted:")
                live.rawQuery("SELECT body FROM knowledge_records WHERE id=?", arrayOf(id)).use { cursor ->
                    if (cursor.moveToFirst()) localRestoredRows[id] = cursor.getString(0)
                }
            }
            val privateValues = mutableMapOf<String, String>()
            SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { source ->
                require(source.version == live.version && schemas(source) == schema) { "备份版本或表结构不兼容" }
                source.rawQuery("PRAGMA integrity_check", null).use { require(it.moveToFirst() && it.getString(0) == "ok") { "备份损坏" } }
                source.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('trigger','view')", null).use { require(!it.moveToFirst()) }
                source.rawQuery("SELECT body FROM memories", null).use { c -> while (c.moveToNext()) CompanionMemoryFormat.validateRows(JSONArray().put(JSONObject(c.getString(0))), false) }
                source.rawQuery("SELECT body FROM knowledge_records", null).use { c -> while (c.moveToNext()) CompanionKnowledgeData.validateRows(JSONArray().put(JSONObject(c.getString(0))), false) }
                knowledgeDecisions(source)
                source.rawQuery("SELECT key,value FROM backup_private", null).use { c -> while (c.moveToNext()) { val key=c.getString(0); require(key in sets || key in booleans); privateValues[key]=c.getString(1) } }
            }
            require(privateValues.keys == sets + booleans) { "备份缺少删除保护记录" }
            val hashes = prefs.getStringSet("memory_deleted_hashes", emptySet()).orEmpty().toMutableSet()
            val events = prefs.getStringSet("memory_deleted_events", emptySet()).orEmpty().toMutableSet()
            val signatures = prefs.getStringSet("memory_deleted_signatures", emptySet()).orEmpty().toMutableSet()
            val editor = prefs.edit()
            for (key in sets) {
                val values = JSONArray(privateValues.getValue(key))
                val merged = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
                for (i in 0 until values.length()) { require(values.opt(i) is String); merged.add(values.getString(i)) }
                editor.putStringSet(key, merged)
                when(key) { "memory_deleted_hashes" -> hashes.addAll(merged); "memory_deleted_events" -> events.addAll(merged); "memory_deleted_signatures" -> signatures.addAll(merged) }
            }
            for (key in booleans) { require(privateValues[key] in setOf("true", "false")); editor.putBoolean(key, prefs.getBoolean(key,false) || privateValues[key] == "true") }
            // Validate deletion metadata using the same contract as ordinary memory import.
            val checkBackup = CompanionMemoryBackup.encode(JSONArray(), privateValues.getValue("automatic_memory_suppressed_all") == "true",
                JSONArray(privateValues.getValue("automatic_memory_suppressed_sources")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
                hashes, events, signatures)
            CompanionMemoryImport.plan(checkBackup, JSONArray(), false)
            // Conservative union before replacing data: a crash can add suppression, never remove it.
            check(editor.commit()) { "无法保存删除保护" }
            // Embeddings are derived from the old database; invalidate before any source replacement.
            SemanticControl(context).invalidate()
            live.execSQL("ATTACH DATABASE ? AS incoming_backup", arrayOf(snapshot.absolutePath))
            try {
                live.beginTransaction()
                try {
                    val forgottenEvents = mutableListOf<String>()
                    live.rawQuery("SELECT event_id FROM understanding_forget", null).use { c -> while(c.moveToNext()) forgottenEvents.add(c.getString(0)) }
                    val generation = live.rawQuery("SELECT value FROM shared_jobs_meta WHERE key='generation'",null).use { if(it.moveToFirst()) it.getLong(0) else 0L }
                    for (table in schema.keys) {
                        if (table in setOf("sources", "jobs")) continue
                        live.delete(table, null, null)
                        live.execSQL("INSERT INTO $table SELECT * FROM incoming_backup.$table")
                    }
                    // Local explicit delete/undo decisions win over older imported decisions.
                    for ((key, value) in localKnowledgeDecisions) {
                        live.execSQL("INSERT OR REPLACE INTO knowledge_meta(key,value) VALUES (?,?)", arrayOf(key, value))
                    }
                    for ((key, value) in knowledgeDecisions(live)) {
                        if (!JSONObject(value).optBoolean("restored", false)) {
                            live.delete("knowledge_records", "id=?", arrayOf(key.removePrefix("deleted:")))
                        }
                    }
                    for ((id, body) in localRestoredRows) {
                        live.execSQL("INSERT OR IGNORE INTO knowledge_records(id,position,body) VALUES (?,(SELECT COALESCE(MAX(position),-1)+1 FROM knowledge_records),?)", arrayOf(id, body))
                    }
                    for (event in forgottenEvents) live.execSQL("INSERT OR IGNORE INTO understanding_forget VALUES (?)", arrayOf(event))
                    live.execSQL("DELETE FROM understanding_items WHERE event_id IN (SELECT event_id FROM understanding_forget)")
                    live.execSQL("DELETE FROM understanding_item_reminders WHERE item_id NOT IN (SELECT id FROM understanding_items)")
                    live.execSQL("DELETE FROM understanding_item_evidence WHERE item_id NOT IN (SELECT id FROM understanding_items) OR event_id IN (SELECT event_id FROM understanding_forget)")
                    live.execSQL("DELETE FROM understanding_event_threads WHERE event_id IN (SELECT event_id FROM understanding_forget)")
                    live.execSQL("DELETE FROM understanding_results WHERE event_id IN (SELECT event_id FROM understanding_forget)")
                    live.execSQL("DELETE FROM understanding_events WHERE id IN (SELECT event_id FROM understanding_forget)")
                    live.execSQL("UPDATE sources SET epoch=epoch+1")
                    live.execSQL("UPDATE jobs SET state='cancelled',token=NULL")
                    live.delete("shared_jobs",null,null)
                    live.execSQL("INSERT OR REPLACE INTO shared_jobs_meta VALUES ('generation',?)", arrayOf((generation+1).toString()))
                    live.execSQL("UPDATE understanding_item_reminders SET enabled=0")
                    val forgotten = mutableListOf<String>()
                    live.rawQuery("SELECT id,body FROM memories",null).use { c -> while(c.moveToNext()) {
                        val row=JSONObject(c.getString(1)); val text=row.getString("text")
                        if (CompanionMemorySuppression.digest(text) in hashes || CompanionMemorySuppression.matches(text, signatures) || CompanionMemorySuppression.digest("event:${row.optString("evidenceEventId")}") in events) forgotten.add(c.getString(0))
                    } }
                    for(id in forgotten) { live.delete("memories","id=?",arrayOf(id)); live.delete("memory_text_index","id=?",arrayOf(id)); MemorySearchIndex.delete(live,id) }
                    live.delete("memory_text_index",null,null)
                    live.delete("memory_search_terms",null,null)
                    live.delete("memory_search_meta",null,null)
                    live.rawQuery("SELECT id,body FROM memories",null).use { c -> while(c.moveToNext()) {
                        val row=JSONObject(c.getString(1)); val id=c.getString(0)
                        live.execSQL("INSERT INTO memory_text_index VALUES (?,?)",arrayOf(id,CompanionMemorySuppression.digest(row.getString("text"))))
                        MemorySearchIndex.put(live,id,row)
                    } }
                    beforeCommit()
                    live.setTransactionSuccessful()
                } finally { live.endTransaction() }
            } finally { live.execSQL("DETACH DATABASE incoming_backup") }
        }
        if (SemanticControl(context).enabled) runCatching { SemanticIndexJobs.schedule(context, 5_000) }
    }
}
