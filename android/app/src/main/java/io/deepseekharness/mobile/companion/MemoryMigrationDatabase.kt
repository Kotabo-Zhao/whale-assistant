package io.deepseekharness.mobile.companion

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Transactional staging database. The legacy store remains authoritative until cutover is verified. */
internal class MemoryMigrationDatabase(path: File) : AutoCloseable {
    private val db = SQLiteDatabase.openOrCreateDatabase(path, null)

    init {
        try {
            db.beginTransaction()
            try {
                val version = db.version
                check(version == 0 || version == 1) { "unsupported memory database version" }
                db.execSQL("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS memories (position INTEGER NOT NULL UNIQUE, id TEXT PRIMARY KEY NOT NULL, body TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS memory_text_index (id TEXT PRIMARY KEY NOT NULL, digest TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS memory_text_digest ON memory_text_index(digest)")
                MemorySearchIndex.create(db)
                val indexed = db.rawQuery("SELECT value FROM metadata WHERE key='memory_index_version'", null).use { it.moveToFirst() && it.getString(0) == "1" }
                if (!indexed) {
                    db.delete("memory_text_index", null, null)
                    db.rawQuery("SELECT id,body FROM memories", null).use { cursor ->
                        while (cursor.moveToNext()) putDigest(cursor.getString(0), JSONObject(cursor.getString(1)).getString("text"))
                    }
                    db.execSQL("INSERT OR REPLACE INTO metadata(key,value) VALUES ('memory_index_version','1')")
                }
                val searchable = db.rawQuery("SELECT value FROM metadata WHERE key='memory_search_version'", null).use { it.moveToFirst() && it.getString(0) == "1" }
                if (!searchable) {
                    db.delete("memory_search_terms", null, null); db.delete("memory_search_meta", null, null)
                    db.rawQuery("SELECT id,body FROM memories", null).use { cursor -> while (cursor.moveToNext()) MemorySearchIndex.put(db, cursor.getString(0), JSONObject(cursor.getString(1))) }
                    db.execSQL("INSERT OR REPLACE INTO metadata(key,value) VALUES ('memory_search_version','1')")
                }
                if (version == 0) db.version = 1
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            db.close()
            throw e
        }
    }

    /** A failed validation or insertion rolls back both records and migration marker. */
    fun importLegacy(bytes: ByteArray): Int {
        val rows = CompanionMemoryFormat.parse(bytes)
        db.beginTransaction()
        try {
            check(!isImported()) { "legacy data already imported" }
            check(count() == 0L) { "migration destination is not empty" }
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val values = ContentValues().apply {
                    put("position", i)
                    put("id", row.getString("id"))
                    put("body", row.toString())
                }
                db.insertOrThrow("memories", null, values)
                putIndex(row.getString("id"), row)
            }
            check(readAll().toString() == rows.toString()) { "migration verification failed" }
            db.execSQL("INSERT INTO metadata (key, value) VALUES ('legacy_imported', '1')")
            db.execSQL("INSERT INTO metadata (key, value) VALUES ('revision', '1')")
            db.setTransactionSuccessful()
            return rows.length()
        } finally {
            db.endTransaction()
        }
    }

    /** Replaces the staged snapshot atomically; a failed write preserves the previous snapshot. */
    fun replaceAll(rows: JSONArray) {
        val checked = CompanionMemoryFormat.validateRows(rows, legacyLimit = false)
        db.beginTransaction()
        try {
            check(isImported()) { "legacy data not imported" }
            db.delete("memories", null, null)
            db.delete("memory_text_index", null, null)
            db.delete("memory_search_terms", null, null); db.delete("memory_search_meta", null, null)
            for (i in 0 until checked.length()) {
                val row = checked.getJSONObject(i)
                val values = ContentValues().apply {
                    put("position", i)
                    put("id", row.getString("id"))
                    put("body", row.toString())
                }
                db.insertOrThrow("memories", null, values)
                putIndex(row.getString("id"), row)
            }
            check(readAll().toString() == checked.toString()) { "snapshot verification failed" }
            db.execSQL("UPDATE metadata SET value = CAST(value AS INTEGER) + 1 WHERE key = 'revision'")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Facts, candidate tasks and durable completion are committed together.  A shared
     * document's model response is tied to its stable source event before it becomes
     * visible, so deleting that source can retract every derived task as well.
     */
    fun commitSharedJob(rows: JSONArray, job: SharedMemoryJobs.Job, result: String, beforeComplete: () -> Unit = {}): Boolean {
        db.beginTransaction()
        try {
            val generation = db.rawQuery("SELECT value FROM shared_jobs_meta WHERE key='generation'", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            if (generation != job.generation) return false
            val pending = db.rawQuery("SELECT 1 FROM shared_jobs WHERE record_id=? AND text_hash=? AND event_id=?", arrayOf(job.id, job.hash, job.event)).use { it.moveToFirst() }
            val text = db.rawQuery("SELECT body FROM knowledge_records WHERE id=?", arrayOf(job.id)).use { if (it.moveToFirst()) JSONObject(it.getString(0)).getString("text") else null }
            if (!pending || text == null || CompanionMemorySuppression.digest(text) != job.hash) return false
            applyIncrementalInTransaction(rows)
            persistSharedTasks(job, text, result)
            beforeComplete()
            db.execSQL("INSERT OR IGNORE INTO shared_completed(record_id,text_hash) VALUES (?,?)", arrayOf(job.id, job.hash))
            check(db.delete("shared_jobs", "record_id=? AND text_hash=? AND event_id=?", arrayOf(job.id, job.hash, job.event)) == 1)
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    /** Preserve stable positions and update only changed rows when existing order is unchanged. */
    fun applyIncremental(rows: JSONArray) {
        db.beginTransaction()
        try {
            applyIncrementalInTransaction(rows)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun applyIncrementalInTransaction(rows: JSONArray) {
        CompanionMemoryFormat.validateRows(rows, legacyLimit = false)
        check(isImported()) { "legacy data not imported" }
        val existing = linkedMapOf<String, Pair<Long, String>>()
        db.rawQuery("SELECT id,position,body FROM memories ORDER BY position", null).use { cursor ->
            while (cursor.moveToNext()) existing[cursor.getString(0)] = cursor.getLong(1) to cursor.getString(2)
        }
        val incoming = (0 until rows.length()).map { rows.getJSONObject(it) }
        val incomingIds = incoming.map { it.getString("id") }.toSet()
        val oldRetained = existing.keys.filter { it in incomingIds }
        val newRetained = incoming.map { it.getString("id") }.filter(existing::containsKey)
        val firstNew = incoming.indexOfFirst { !existing.containsKey(it.getString("id")) }
        val appendOnly = firstNew < 0 || incoming.drop(firstNew).none { existing.containsKey(it.getString("id")) }
        check(oldRetained == newRetained && appendOnly) { "snapshot reordering requires full replacement" }
        var changed = false
        for (id in existing.keys) if (id !in incomingIds) { db.delete("memories", "id=?", arrayOf(id)); db.delete("memory_text_index", "id=?", arrayOf(id)); MemorySearchIndex.delete(db, id); changed = true }
        var position = (existing.values.maxOfOrNull { it.first } ?: -1L) + 1
        for (row in incoming) {
            val id = row.getString("id"); val body = row.toString(); val previous = existing[id]
            if (previous == null) {
                db.insertOrThrow("memories", null, ContentValues().apply { put("id", id); put("position", position++); put("body", body) })
                putIndex(id, row)
                changed = true
            } else if (previous.second != body) {
                check(db.update("memories", ContentValues().apply { put("body", body) }, "id=?", arrayOf(id)) == 1)
                putIndex(id, row)
                changed = true
            }
        }
        if (changed) db.execSQL("UPDATE metadata SET value = CAST(value AS INTEGER) + 1 WHERE key = 'revision'")
    }

    private fun persistSharedTasks(job: SharedMemoryJobs.Job, text: String, result: String) {
        val source = "shared_material"
        val eventBody = text.substringAfter("\n\n文档提取内容：\n", text)
        val inputHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(CompanionKnowledgeData.utf8("${job.event.length}:${job.event}$eventBody"))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val forgotten = db.rawQuery("SELECT 1 FROM understanding_forget WHERE event_id=?", arrayOf(job.event)).use { it.moveToFirst() }
        if (forgotten) return
        val event = ContentValues().apply { put("id", job.event); put("source", source); put("input_hash", inputHash); put("epoch", 1); put("body", eventBody) }
        if (db.insertWithOnConflict("understanding_events", null, event, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
            check(db.rawQuery("SELECT source,input_hash,epoch,body FROM understanding_events WHERE id=?", arrayOf(job.event)).use { c ->
                c.moveToFirst() && c.getString(0) == source && c.getString(1) == inputHash && c.getLong(2) == 1L && c.getString(3) == eventBody
            }) { "shared event conflict" }
        }
        val jobId = "shared:${job.id}:${job.hash}"
        val row = ContentValues().apply { put("job_id", jobId); put("event_id", job.event); put("body", result) }
        if (db.insertWithOnConflict("understanding_results", null, row, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
            check(db.rawQuery("SELECT event_id,body FROM understanding_results WHERE job_id=?", arrayOf(jobId)).use { c ->
                c.moveToFirst() && c.getString(0) == job.event && c.getString(1) == result
            }) { "shared result conflict" }
        }
        val items = JSONObject(result).optJSONArray("items") ?: JSONArray()
        for (i in 0 until items.length()) {
            val title = items.getJSONObject(i).getString("title").trim()
            val id = java.util.UUID.nameUUIDFromBytes("$jobId:$title".toByteArray(Charsets.UTF_8)).toString()
            val item = ContentValues().apply { put("id", id); put("job_id", jobId); put("event_id", job.event); put("title", title); put("state", "candidate") }
            if (db.insertWithOnConflict("understanding_items", null, item, SQLiteDatabase.CONFLICT_IGNORE) != -1L)
                db.insertOrThrow("understanding_item_evidence", null, ContentValues().apply { put("item_id", id); put("event_id", job.event) })
        }
    }

    private fun putDigest(id: String, text: String) {
        db.insertWithOnConflict("memory_text_index", null, ContentValues().apply { put("id", id); put("digest", CompanionMemorySuppression.digest(text)) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun putIndex(id: String, row: JSONObject) { putDigest(id, row.getString("text")); MemorySearchIndex.put(db, id, row) }

    fun search(query: String, offset: Int): JSONObject {
        db.beginTransactionNonExclusive()
        try { check(isImported()); val result = MemorySearchIndex.search(db, query, offset); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }

    fun isImported(): Boolean = db.rawQuery("SELECT value FROM metadata WHERE key = 'legacy_imported'", null).use {
        it.moveToFirst() && it.getString(0) == "1"
    }

    fun count(): Long = db.rawQuery("SELECT COUNT(*) FROM memories", null).use { it.moveToFirst(); it.getLong(0) }
    fun findById(id: String): JSONObject? = db.rawQuery("SELECT body FROM memories WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }

    data class Page(val rows: JSONArray, val next: Long?, val revision: Long)
    fun pageNewest(before: Long?, limit: Int, expectedRevision: Long? = null): Page {
        require(before == null || before >= 0)
        require(limit in 1..100)
        db.beginTransactionNonExclusive()
        try {
            val currentRevision = revision()
            if (expectedRevision != null) check(expectedRevision == currentRevision) { "snapshot changed" }
            val sql = if (before == null) "SELECT position,body FROM memories ORDER BY position DESC LIMIT ?" else "SELECT position,body FROM memories WHERE position < ? ORDER BY position DESC LIMIT ?"
            val args = if (before == null) arrayOf((limit + 1).toString()) else arrayOf(before.toString(), (limit + 1).toString())
            val rows = JSONArray(); var last: Long? = null; var more = false
            db.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    if (rows.length() == limit) { more = true; break }
                    last = cursor.getLong(0); rows.put(JSONObject(cursor.getString(1)))
                }
            }
            db.setTransactionSuccessful()
            return Page(rows, if (more) last else null, currentRevision)
        } finally { db.endTransaction() }
    }

    fun readAll(): JSONArray = JSONArray().also { rows ->
        db.rawQuery("SELECT body FROM memories ORDER BY position", null).use { cursor ->
            while (cursor.moveToNext()) rows.put(JSONObject(cursor.getString(0)))
        }
    }

    fun revision(): Long = db.rawQuery("SELECT value FROM metadata WHERE key = 'revision'", null).use {
        check(it.moveToFirst()) { "legacy data not imported" }
        it.getString(0).toLong()
    }

    /** Cursor is valid only for the snapshot revision that issued it. */
    fun pageAfter(position: Long?, limit: Int, expectedRevision: Long? = null): List<Pair<Long, JSONObject>> {
        require(position == null || position >= 0) { "invalid cursor" }
        require(limit in 1..200) { "invalid page size" }
        db.beginTransactionNonExclusive()
        try {
            if (expectedRevision != null) check(revision() == expectedRevision) { "snapshot changed" }
            val result = mutableListOf<Pair<Long, JSONObject>>()
            val after = position ?: -1L
            db.rawQuery("SELECT position, body FROM memories WHERE position > ? ORDER BY position LIMIT ?", arrayOf(after.toString(), limit.toString())).use { cursor ->
                while (cursor.moveToNext()) result.add(cursor.getLong(0) to JSONObject(cursor.getString(1)))
            }
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = db.close()
}
