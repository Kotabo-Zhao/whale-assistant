package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Verified one-time import; legacy file is retained and never reimported after cutover. */
internal class UnifiedKnowledgeDatabase(context: Context, legacy: () -> JSONArray) : AutoCloseable {
    private val db: SQLiteDatabase
    init {
        val path = UnifiedUnderstandingStore.path(context)
        val prefs = context.getSharedPreferences("companion_private_v1", Context.MODE_PRIVATE)
        check(!prefs.getBoolean("knowledge_sqlite_active", false) || path.isFile) { "资料数据库缺失，已停止读取旧副本以避免复活已删除资料" }
        db = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS knowledge_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS knowledge_records (id TEXT PRIMARY KEY, position INTEGER NOT NULL UNIQUE, body TEXT NOT NULL)")
                val imported = db.rawQuery("SELECT value FROM knowledge_meta WHERE key='legacy_imported'", null).use { it.moveToFirst() && it.getString(0) == "1" }
                if (!imported) {
                    check(!prefs.getBoolean("knowledge_sqlite_active", false)) { "资料迁移标记缺失" }
                    val rows = legacy()
                    replaceRows(rows)
                    check(read().toString() == rows.toString()) { "资料迁移核对失败" }
                    db.execSQL("INSERT INTO knowledge_meta VALUES ('legacy_imported','1')")
                }
                pruneExpiredTrash(System.currentTimeMillis())
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            if (!prefs.getBoolean("knowledge_sqlite_active", false))
                check(prefs.edit().putBoolean("knowledge_sqlite_active", true).commit()) { "无法保存资料迁移状态" }
        } catch (e: Exception) { db.close(); throw e }
    }
    fun read(): JSONArray = JSONArray().also { rows ->
        db.rawQuery("SELECT body FROM knowledge_records ORDER BY position", null).use { cursor ->
            while (cursor.moveToNext()) rows.put(JSONObject(cursor.getString(0)))
        }
    }
    /** Newest first; position is indexed by the UNIQUE constraint. Fetch one extra for pagination. */
    fun recent(offset: Int, limit: Int): JSONArray {
        require(offset >= 0 && limit in 1..4)
        val result = JSONArray()
        db.rawQuery("SELECT body FROM knowledge_records ORDER BY position DESC LIMIT ? OFFSET ?", arrayOf(limit.toString(), offset.toString())).use { cursor ->
            while (cursor.moveToNext()) result.put(JSONObject(cursor.getString(0)))
        }
        return result
    }

    /**
     * UI pagination never materializes the full collection. Text matching stays local and uses the
     * same case-insensitive substring semantics as the legacy JSON store; callers receive one
     * extra-row-derived continuation rather than an assertion that this is the whole result set.
     */
    fun searchPage(query: String, offset: Int, limit: Int): Pair<JSONArray, Int?> {
        require(query.length <= 100 && offset >= 0 && limit in 1..50)
        val normalized = query.trim()
        val result = JSONArray()
        var matched = 0
        db.rawQuery("SELECT body FROM knowledge_records ORDER BY position DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject(cursor.getString(0))
                if (normalized.isNotEmpty() && !row.getString("text").contains(normalized, ignoreCase = true)) continue
                if (matched++ < offset) continue
                if (result.length() == limit) return result to (offset + result.length())
                result.put(row)
            }
        }
        return result to null
    }
    fun pageAfter(position: Long, limit: Int): List<Pair<Long, JSONObject>> {
        require(position >= -1 && limit in 1..128)
        return db.rawQuery("SELECT position,body FROM knowledge_records WHERE position>? ORDER BY position LIMIT ?", arrayOf(position.toString(), limit.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to JSONObject(cursor.getString(1))) }
        }
    }
    /** Match semantics mirror CompanionMemorySearch while retaining only the best candidates. */
    fun lexicalCandidates(query: String, limit: Int): List<JSONObject> {
        require(query.isNotEmpty() && query.length <= 100 && limit in 1..100)
        data class Candidate(val row: JSONObject, val score: Int, val order: Long)
        val exact = mutableListOf<JSONObject>()
        val related = java.util.PriorityQueue<Candidate>(compareBy<Candidate> { it.score }.thenByDescending { it.order })
        val keywords = CompanionMemorySearch.terms(query)
        var order = 0L
        db.rawQuery("SELECT body FROM knowledge_records ORDER BY position DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject(cursor.getString(0))
                val text = row.getString("text")
                if (text.contains(query, ignoreCase = true)) {
                    exact.add(row)
                    if (exact.size == limit) break
                } else if (exact.isEmpty() && keywords.isNotEmpty()) {
                    val score = CompanionMemorySearch.relatedScore(text, keywords)
                    if (score != null) {
                        related.add(Candidate(row, score, order))
                        if (related.size > limit) related.poll()
                    }
                }
                order++
            }
        }
        if (exact.isNotEmpty()) return exact
        return related.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.order }).map { it.row }
    }
    /** Hash rows incrementally so session scope checks do not construct the entire collection. */
    fun fingerprint(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        db.rawQuery("SELECT position, body FROM knowledge_records ORDER BY position", null).use { cursor ->
            while (cursor.moveToNext()) {
                val bytes = cursor.getString(1).toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(12).putLong(cursor.getLong(0)).putInt(bytes.size).array())
                digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun findById(id: String): JSONObject? = db.rawQuery("SELECT body FROM knowledge_records WHERE id = ?", arrayOf(id)).use { cursor ->
        if (cursor.moveToFirst()) JSONObject(cursor.getString(0)) else null
    }
    /** Append without materializing or rewriting the existing collection. */
    fun appendUniqueText(text: String, source: String, now: Long): Boolean {
        val row = JSONArray()
        CompanionKnowledgeData.add(row, text, now, source, legacyLimit = false)
        val item = row.getJSONObject(0)
        db.beginTransaction()
        try {
            val duplicate = db.rawQuery("SELECT body FROM knowledge_records", null).use { cursor ->
                var found = false
                while (cursor.moveToNext()) if (JSONObject(cursor.getString(0)).getString("text") == text) { found = true; break }
                found
            }
            if (duplicate) { db.setTransactionSuccessful(); return false }
            val position = nextPosition()
            db.insertOrThrow("knowledge_records", null, ContentValues().apply {
                put("id", item.getString("id")); put("position", position); put("body", item.toString())
            })
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun appendDocument(documentId: String, chunks: List<String>, displayName: String, coverage: String, requireOriginal: Boolean): JSONArray {
        val added = JSONArray()
        db.beginTransaction()
        try {
            if (requireOriginal) check(originalExists(documentId)) { "原件已删除，请重新导入" }
            var position = nextPosition()
            for ((index, chunk) in chunks.withIndex()) {
                val id = java.util.UUID.nameUUIDFromBytes("$documentId:$index".toByteArray(Charsets.UTF_8)).toString()
                check(!isDeleted(id)) { "资料曾被删除，请先恢复或明确重新导入" }
                val existing = findById(id)
                if (existing != null) {
                    check(existing.optString("documentId") == documentId && existing.optInt("chunkIndex", -1) == index &&
                        existing.optInt("chunkCount", -1) == chunks.size && existing.optString("text") == chunk) {
                        "文档已保存为不同分段版本，原资料已保留"
                    }
                    // An explicit reimport can attach the newly preserved original to matching legacy chunks.
                    if (requireOriginal && existing.optString("originalId").isEmpty()) {
                        existing.put("originalId", documentId).put("displayName", displayName).put("coverage", coverage)
                        updateRecord(id, existing)
                    }
                    continue
                }
                val item = JSONObject().put("id", id).put("text", chunk).put("source", "text_file")
                    .put("createdAt", System.currentTimeMillis()).put("documentId", documentId)
                    .put("chunkIndex", index).put("chunkCount", chunks.size)
                    .put("displayName", displayName).put("coverage", coverage).put("originalId", documentId)
                CompanionKnowledgeData.validateRows(JSONArray().put(item), false)
                db.insertOrThrow("knowledge_records", null, ContentValues().apply {
                    put("id", id); put("position", position++); put("body", item.toString())
                })
                added.put(item)
            }
            db.setTransactionSuccessful()
            return added
        } finally { db.endTransaction() }
    }

    /** A stable ID never silently replaces an existing record. */
    fun appendRecordIfAbsent(item: JSONObject, explicitReimport: Boolean = false): JSONObject {
        CompanionKnowledgeData.validateRows(JSONArray().put(item), false)
        val id = item.getString("id")
        db.beginTransaction()
        try {
            if (explicitReimport) {
                check(item.optString("source") == "original")
                allowExplicitReimport(item.getString("originalId"))
            }
            if (item.optString("source") == "image_analysis") check(originalExists(item.optString("originalId"))) { "原件已删除，请重新导入" }
            check(!isDeleted(id)) { "资料曾被删除，请先恢复或明确重新导入" }
            val existing = findById(id)
            if (existing != null) {
                check(existing.optString("originalId") == item.optString("originalId") && existing.optString("source") == item.optString("source"))
                db.setTransactionSuccessful()
                return existing
            }
            db.insertOrThrow("knowledge_records", null, ContentValues().apply {
                put("id", id); put("position", nextPosition()); put("body", item.toString())
            })
            db.setTransactionSuccessful()
            return item
        } finally { db.endTransaction() }
    }

    private fun nextPosition(): Long = db.rawQuery("SELECT COALESCE(MAX(position), -1) + 1 FROM knowledge_records", null).use {
        check(it.moveToFirst()); it.getLong(0).also { position -> check(position >= 0) }
    }
    fun containsOtherText(id: String, text: String): Boolean = db.rawQuery(
        "SELECT body FROM knowledge_records WHERE id<>?", arrayOf(id)
    ).use { cursor ->
        while (cursor.moveToNext()) if (JSONObject(cursor.getString(0)).getString("text") == text) return@use true
        false
    }
    fun updateRecord(id: String, row: JSONObject) {
        CompanionKnowledgeData.validateRows(JSONArray().put(row), false)
        check(row.getString("id") == id)
        check(db.update("knowledge_records", ContentValues().apply { put("body", row.toString()) }, "id=?", arrayOf(id)) == 1)
    }
    private fun isDeleted(id: String): Boolean = db.rawQuery("SELECT value FROM knowledge_meta WHERE key=?", arrayOf("deleted:$id")).use {
        it.moveToFirst() && !JSONObject(it.getString(0)).optBoolean("restored", false)
    }
    private fun originalExists(originalId: String): Boolean {
        val id = java.util.UUID.nameUUIDFromBytes("original:$originalId".toByteArray(Charsets.UTF_8)).toString()
        return findById(id)?.optString("originalId") == originalId && !isDeleted(id)
    }

    /** Only called when the user explicitly imports the same original again. */
    private fun allowExplicitReimport(originalId: String) {
            val updates = mutableListOf<Pair<String, String>>()
            db.rawQuery("SELECT key,value FROM knowledge_meta WHERE key LIKE 'deleted:%'", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val decision = JSONObject(cursor.getString(1))
                    if (decision.optString("originalId") == originalId) {
                        decision.remove("row")
                        decision.put("restored", true)
                        updates.add(cursor.getString(0) to decision.toString())
                    }
                }
            }
            for ((key, value) in updates) db.execSQL("UPDATE knowledge_meta SET value=? WHERE key=?", arrayOf(value, key))
    }

    private fun pruneExpiredTrash(now: Long) {
        val last = db.rawQuery("SELECT value FROM knowledge_meta WHERE key='last_trash_prune'", null).use {
            if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0L else 0L
        }
        if (now >= last && now - last < 60_000L) return
        val updates = mutableListOf<Pair<String, String>>()
        db.rawQuery("SELECT key,value FROM knowledge_meta WHERE key LIKE 'deleted:%'", null).use { cursor ->
            while (cursor.moveToNext()) {
                val decision = JSONObject(cursor.getString(1))
                val deletedAt = decision.optLong("deletedAt")
                if (decision.has("row") && deletedAt > 0 && now >= deletedAt && now - deletedAt > 10 * 60_000L) {
                    decision.remove("row")
                    updates.add(cursor.getString(0) to decision.toString())
                }
            }
        }
        for ((key, value) in updates) db.execSQL("UPDATE knowledge_meta SET value=? WHERE key=?", arrayOf(value, key))
        db.execSQL("INSERT OR REPLACE INTO knowledge_meta(key,value) VALUES ('last_trash_prune',?)", arrayOf(now.toString()))
    }

    /** Trash and deletion marker commit together; old snapshots cannot silently resurrect the row. */
    fun trashRecord(id: String, expectedText: String, now: Long): Boolean {
        db.beginTransaction()
        try {
            val row = findById(id) ?: return false
            if (row.getString("text") != expectedText) return false
            val trash = JSONObject().put("row", row).put("deletedAt", now)
            row.optString("originalId").takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { trash.put("originalId", it) }
            db.execSQL("INSERT OR REPLACE INTO knowledge_meta(key,value) VALUES (?,?)", arrayOf("deleted:$id", trash.toString()))
            check(db.delete("knowledge_records", "id=?", arrayOf(id)) == 1)
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun undoTrash(id: String, now: Long, windowMs: Long): Boolean {
        db.beginTransaction()
        try {
            val key = "deleted:$id"
            val value = db.rawQuery("SELECT value FROM knowledge_meta WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null } ?: return false
            val trash = JSONObject(value)
            val deletedAt = trash.getLong("deletedAt")
            if (now < deletedAt || now - deletedAt > windowMs || findById(id) != null) return false
            val row = trash.optJSONObject("row") ?: return false
            check(row.getString("id") == id)
            CompanionKnowledgeData.validateRows(JSONArray().put(row), false)
            db.insertOrThrow("knowledge_records", null, ContentValues().apply {
                put("id", id); put("position", nextPosition()); put("body", row.toString())
            })
            // Keep a local decision marker so restoring an older backup cannot undo this explicit undo.
            trash.remove("row")
            trash.put("restored", true)
            db.execSQL("UPDATE knowledge_meta SET value=? WHERE key=?", arrayOf(trash.toString(), key))
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun recentTrash(now: Long, windowMs: Long): JSONArray {
        val decisions = mutableListOf<JSONObject>()
        db.rawQuery("SELECT value FROM knowledge_meta WHERE key LIKE 'deleted:%'", null).use { cursor ->
            while (cursor.moveToNext()) {
                val decision = JSONObject(cursor.getString(0))
                val deletedAt = decision.optLong("deletedAt")
                if (!decision.optBoolean("restored", false) && deletedAt > 0 && now >= deletedAt && now - deletedAt <= windowMs && decision.optJSONObject("row") != null) {
                    decisions.add(decision)
                }
            }
        }
        return JSONArray(decisions.sortedByDescending { it.getLong("deletedAt") }.take(50))
    }
    private fun replaceRows(rows: JSONArray) {
        db.delete("knowledge_records", null, null)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            db.insertOrThrow("knowledge_records", null, ContentValues().apply {
                put("id", row.getString("id")); put("position", i); put("body", row.toString())
            })
        }
    }
    fun write(rows: JSONArray) {
        db.beginTransaction()
        try {
            val positions = mutableMapOf<String, Int>()
            db.rawQuery("SELECT id, position FROM knowledge_records", null).use { cursor ->
                while (cursor.moveToNext()) positions[cursor.getString(0)] = cursor.getInt(1)
            }
            val ids = (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }
            require(ids.toSet().size == ids.size) { "重复资料标识" }
            require(ids.none(::isDeleted)) { "备份包含已删除的资料，请先确认恢复范围" }
            val wanted = ids.toSet()
            for (id in positions.keys) if (id !in wanted) db.delete("knowledge_records", "id=?", arrayOf(id))
            // 先腾出变动位置，支持任意重排且不触发 UNIQUE 冲突。
            for ((index, id) in ids.withIndex()) if (positions[id] != null && positions[id] != index) {
                db.execSQL("UPDATE knowledge_records SET position=? WHERE id=?", arrayOf(-index - 1, id))
            }
            db.compileStatement("UPDATE knowledge_records SET position=?, body=? WHERE id=? AND (position<>? OR body<>?)").use { update ->
                for ((index, id) in ids.withIndex()) {
                    val body = rows.getJSONObject(index).toString()
                    if (id !in positions) {
                        db.insertOrThrow("knowledge_records", null, ContentValues().apply { put("id", id); put("position", index); put("body", body) })
                    } else {
                        update.clearBindings()
                        update.bindLong(1, index.toLong()); update.bindString(2, body); update.bindString(3, id)
                        update.bindLong(4, index.toLong()); update.bindString(5, body)
                        update.executeUpdateDelete()
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    override fun close() = db.close()
}
