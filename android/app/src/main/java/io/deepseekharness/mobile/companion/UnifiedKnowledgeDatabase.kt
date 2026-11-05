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
                KnowledgeTextIndex.install(db)
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
    fun count(): Long = android.database.DatabaseUtils.queryNumEntries(db, "knowledge_records")
    fun exactPage(query: String, offset: Int, limit: Int, accepts: (JSONObject) -> Boolean): CompanionMemorySearch.Result? {
        var count = 0
        val items = mutableListOf<JSONObject>()
        KnowledgeTextIndex.candidates(db, query).use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject(cursor.getString(0))
                if (!accepts(row) || !KnowledgeTextIndex.contains(row,query)) continue
                count++
                if (count <= offset) continue
                if (items.size == limit) break
                items.add(row)
            }
        }
        if (count > 0) return CompanionMemorySearch.Result(items, "exact", count)
        val keywords = CompanionMemorySearch.terms(query)
        if (keywords.isEmpty()) return CompanionMemorySearch.Result(emptyList(), "none", 0)
        // Keep record IDs and scores only. Load full records for the requested page.
        val ranked = mutableListOf<Pair<String, Int>>()
        KnowledgeTextIndex.related(db, keywords).use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject(cursor.getString(0))
                if (!accepts(row)) continue
                CompanionMemorySearch.relatedScore(KnowledgeTextIndex.searchableText(row), keywords)?.let { ranked.add(row.getString("id") to it) }
            }
        }
        val page = ranked.sortedByDescending { it.second }.drop(offset).take(limit).mapNotNull { findById(it.first) }
        return CompanionMemorySearch.Result(page, if (ranked.isEmpty()) "none" else "related", ranked.size)
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
    fun searchPage(query: String, offset: Int, limit: Int, source: String? = null, coverage: String? = null): Pair<JSONArray, Int?> {
        require(query.length <= 100 && offset >= 0 && limit in 1..50)
        val normalized = query.trim()
        val result = JSONArray()
        var matched = 0
        KnowledgeTextIndex.candidates(db, normalized).use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject(cursor.getString(0))
                if (normalized.isNotEmpty() && !KnowledgeTextIndex.contains(row,normalized)) continue
                if (source != null && row.optString("source") != source) continue
                if (coverage != null && row.optString("coverage", "unknown") != coverage) continue
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
        KnowledgeTextIndex.candidates(db, query).use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject(cursor.getString(0))
                if (KnowledgeTextIndex.contains(row,query)) exact.add(row)
                if (exact.size == limit) break
            }
        }
        if (exact.isNotEmpty()) return exact
        val related = java.util.PriorityQueue<Candidate>(compareBy<Candidate> { it.score }.thenByDescending { it.order })
        val keywords = CompanionMemorySearch.terms(query)
        var order = 0L
        if (keywords.isEmpty()) return emptyList()
        KnowledgeTextIndex.related(db, keywords).use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject(cursor.getString(0))
                val score = CompanionMemorySearch.relatedScore(KnowledgeTextIndex.searchableText(row),keywords)
                if (score != null) {
                    related.add(Candidate(row,score,order))
                    if (related.size > limit) related.poll()
                }
                order++
            }
        }
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
    val captures get() = KnowledgeCaptureFeed(db, ::findById)
    fun findById(id: String): JSONObject? = db.rawQuery("SELECT body FROM knowledge_records WHERE id = ?", arrayOf(id)).use { cursor ->
        if (cursor.moveToFirst()) JSONObject(cursor.getString(0)) else null
    }
    /** Append without materializing or rewriting the existing collection. */
    fun appendUniqueText(text: String, source: String, now: Long, origin: WhaleShareSource? = null): CompanionKnowledgeStore.SavedText {
        val row = JSONArray()
        CompanionKnowledgeData.add(row, text, now, source, legacyLimit = false)
        val item = row.getJSONObject(0)
        origin?.annotate(item)
        db.beginTransaction()
        try {
            val duplicate = KnowledgeTextIndex.candidates(db, text.take(100)).use { cursor ->
                while (cursor.moveToNext()) {
                    val candidate = JSONObject(cursor.getString(0))
                    if (candidate.getString("text") == text) return@use candidate
                }
                null
            }
            if (duplicate != null) {
                if (origin != null) { origin.annotate(duplicate); updateRecord(duplicate.getString("id"), duplicate); captures.record(duplicate, now) }
                db.setTransactionSuccessful(); return CompanionKnowledgeStore.SavedText(false, listOf(duplicate.getString("id")))
            }
            val position = nextPosition()
            db.insertOrThrow("knowledge_records", null, ContentValues().apply {
                put("id", item.getString("id")); put("position", position); put("body", item.toString())
            })
            if (origin != null) captures.record(item, now)
            db.setTransactionSuccessful()
            return CompanionKnowledgeStore.SavedText(true, listOf(item.getString("id")))
        } finally { db.endTransaction() }
    }

    fun appendDocument(documentId: String, chunks: List<String>, displayName: String, coverage: String, requireOriginal: Boolean,
        origin: WhaleShareSource? = null, mediaKind: String? = null, capture: Boolean = false, prefixCount: Int = 0): JSONArray {
        val added = JSONArray()
        db.beginTransaction()
        try {
            if (requireOriginal) check(originalExists(documentId)) { "原件已删除，请重新导入" }
            var position = nextPosition()
            val totalChunks = prefixCount + chunks.size
            for (index in 0 until prefixCount) {
                val id = KnowledgeChunks.recordId(documentId,index)
                val row = findById(id) ?: continue // Never restore a removed transcript fragment.
                check(row.optString("documentId") == documentId && row.optString("mediaKind") == "audio_transcript")
                if (row.optInt("chunkCount") != totalChunks) updateRecord(id,row.put("chunkCount",totalChunks))
            }
            for ((localIndex, chunk) in chunks.withIndex()) {
                val index = prefixCount + localIndex
                val id = KnowledgeChunks.recordId(documentId, index)
                check(!isDeleted(id)) { "资料曾被删除，请先恢复或明确重新导入" }
                val existing = findById(id)
                if (existing != null) {
                    check(existing.optString("documentId") == documentId && existing.optInt("chunkIndex", -1) == index &&
                        existing.optInt("chunkCount", -1) == totalChunks && existing.optString("text") == chunk) {
                        "文档已保存为不同分段版本，原资料已保留"
                    }
                    // An explicit reimport can attach the newly preserved original to matching legacy chunks.
                    if (requireOriginal && existing.optString("originalId").isEmpty()) {
                        existing.put("originalId", documentId).put("displayName", displayName).put("coverage", coverage)
                        updateRecord(id, existing)
                    }
                    if (origin != null) { origin.annotate(existing); updateRecord(id, existing) }
                    continue
                }
                val item = JSONObject().put("id", id).put("text", chunk).put("source", "text_file")
                    .put("createdAt", System.currentTimeMillis()).put("documentId", documentId)
                    .put("chunkIndex", index).put("chunkCount", totalChunks)
                    .put("displayName", displayName).put("coverage", coverage)
                    .apply { if (requireOriginal) put("originalId", documentId) }
                origin?.annotate(item)
                if (mediaKind != null) item.put("mediaKind", mediaKind)
                CompanionKnowledgeData.validateRows(JSONArray().put(item), false)
                db.insertOrThrow("knowledge_records", null, ContentValues().apply {
                    put("id", id); put("position", position++); put("body", item.toString())
                })
                added.put(item)
            }
            if (capture) captures.record(requireNotNull(findById(KnowledgeChunks.recordId(documentId, 0))))
            db.setTransactionSuccessful()
            return added
        } finally { db.endTransaction() }
    }

    /** A stable ID never silently replaces an existing record. */
    fun appendRecordIfAbsent(item: JSONObject, explicitReimport: Boolean = false, captionIds: List<String> = emptyList(), reserveParse: (() -> Unit)? = null): JSONObject {
        CompanionKnowledgeData.validateRows(JSONArray().put(item), false)
        val id = item.getString("id")
        db.beginTransaction()
        try {
            val restoring = if (explicitReimport) {
                check(item.optString("source") == "original")
                allowExplicitReimport(item.getString("originalId"))
            } else false
            if (item.optString("source") == "image_analysis") check(originalExists(item.optString("originalId"))) { "原件已删除，请重新导入" }
            check(!isDeleted(id)) { "资料曾被删除，请先恢复或明确重新导入" }
            val existing = findById(id)
            if (existing != null) {
                check(existing.optString("originalId") == item.optString("originalId") && existing.optString("source") == item.optString("source"))
                if (item.has("shareOrigin")) {
                    val origin = WhaleShareSource(item.getString("shareOrigin"), item.optString("sourceUrl").ifEmpty { null })
                    origin.annotate(existing)
                    if (reserveParse != null && existing.optString("coverage") != "not_parsed")
                        addOriginToParsed(existing.getString("originalId"), origin)
                    updateRecord(id, existing)
                }
                if (restoring && reserveParse != null) existing.put("coverage", "not_parsed")
                if (reserveParse != null && existing.optString("parseState") == "needs_retry") {
                    // An explicit share may have better MIME or filename information than an old one.
                    for (field in listOf("mime", "displayName")) if (item.optString(field).isNotBlank()) existing.put(field, item.getString(field))
                }
                if (explicitReimport && item.has("shareOrigin")) recordOriginalCapture(existing)
                linkToCaptions(existing, captionIds)
                if (reserveParse != null) enqueueParse(existing, reserveParse)
                db.setTransactionSuccessful()
                return existing
            }
            db.insertOrThrow("knowledge_records", null, ContentValues().apply {
                put("id", id); put("position", nextPosition()); put("body", item.toString())
            })
            if (explicitReimport && item.has("shareOrigin")) recordOriginalCapture(item)
            linkToCaptions(item, captionIds)
            if (reserveParse != null) enqueueParse(item, reserveParse)
            db.setTransactionSuccessful()
            return item
        } finally { db.endTransaction() }
    }

    private fun recordOriginalCapture(original: JSONObject) {
        if (original.optString("attachmentGeneration").isEmpty()) {
            original.put("attachmentGeneration", java.util.UUID.randomUUID().toString())
            updateRecord(original.getString("id"), original)
        }
        captures.record(original)
    }

    private fun addOriginToParsed(hash: String, origin: WhaleShareSource) {
        val first = findById(KnowledgeChunks.recordId(hash, 0))
        for (index in 0 until (first?.optInt("chunkCount", 0) ?: 0).coerceIn(0, CompanionKnowledgeData.MAX_DOCUMENT_TEXT / CompanionKnowledgeData.MAX_RECORD_TEXT + 2)) {
            val row = if (index == 0) first!! else findById(KnowledgeChunks.recordId(hash, index)) ?: continue
            if (row.optString("originalId") == hash) { origin.annotate(row); updateRecord(row.getString("id"), row) }
        }
        val id = java.util.UUID.nameUUIDFromBytes("image-analysis:$hash".toByteArray(Charsets.UTF_8)).toString()
        findById(id)?.let { origin.annotate(it); updateRecord(id, it) }
    }

    private fun enqueueParse(anchor: JSONObject, reserve: () -> Unit) {
        if (anchor.optString("coverage") != "not_parsed" && !anchor.has("audioPrefixChunks")) return
        // Reserve the OS wakeup before committing. The service reads under the same store lock.
        reserve()
        if (anchor.has("parseTicket") && ticketAnchor(anchor.getString("parseTicket")) != null) return
        val ticket = "parse:${System.currentTimeMillis()}:${java.util.UUID.randomUUID()}"
        db.execSQL("INSERT INTO knowledge_meta(key,value) VALUES (?,?)", arrayOf(ticket, anchor.getString("id")))
        anchor.remove("parseFailure")
        anchor.put("parseTicket", ticket).put("parseState", "queued").put("parseAttempts", 0)
        updateRecord(anchor.getString("id"), anchor)
    }

    /** The old decoder only wrote this marker after it had reached the ten-minute cutoff. */
    private fun legacyAudioPrefix(anchor: JSONObject): Int? {
        if (anchor.has("audioTranscriptionVersion") || anchor.optString("coverage") != "partial") return null
        val hash = anchor.getString("originalId")
        val count = findById(KnowledgeChunks.recordId(hash,0))?.optInt("chunkCount",0) ?: return null
        if (count !in 1..CompanionKnowledgeData.MAX_DOCUMENT_TEXT / CompanionKnowledgeData.MAX_RECORD_TEXT + 2) return null
        val last = findById(KnowledgeChunks.recordId(hash,count-1)) ?: return null
        return count.takeIf { last.optString("originalId") == hash && last.optString("mediaKind") == "audio_transcript" &&
            last.optString("text").trimEnd().endsWith("[仅提取前 10 分钟语音]") }
    }

    fun canContinueAudio(row: JSONObject): Boolean = row.optString("source") == "original" &&
        !row.has("parseTicket") && row.optString("parseState") in setOf("complete","needs_retry") && legacyAudioPrefix(row) != null

    fun retryParse(id: String, expectedRow: String, reserve: () -> Unit): Boolean {
        db.beginTransaction()
        try {
            val row = findById(id) ?: return false
            if (row.toString() != expectedRow || row.optString("source") != "original" || row.has("parseTicket")) return false
            if (row.optString("coverage") == "partial") {
                val prefix = legacyAudioPrefix(row) ?: return false
                if (row.optString("parseState") !in setOf("complete","needs_retry")) return false
                row.put("audioFromSeconds",600).put("audioPrefixChunks",prefix)
            } else if (row.optString("coverage") != "not_parsed" || row.optString("parseState") != "needs_retry") return false
            enqueueParse(row, reserve)
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun hasPendingParse(): Boolean = db.rawQuery("SELECT 1 FROM knowledge_meta WHERE key GLOB 'parse:*' LIMIT 1", null).use { it.moveToFirst() }

    private fun ticketAnchor(ticket: String): JSONObject? {
        val id = db.rawQuery("SELECT value FROM knowledge_meta WHERE key=?", arrayOf(ticket)).use { if (it.moveToFirst()) it.getString(0) else null }
        return id?.let(::findById)?.takeIf { it.optString("source") == "original" && it.optString("parseTicket") == ticket }
    }

    fun claimParse(onChanged: () -> Unit = {}): JSONObject? {
        while (true) {
            val ticket = db.rawQuery("SELECT key FROM knowledge_meta WHERE key GLOB 'parse:*' ORDER BY key LIMIT 1", null).use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: return null
            val anchor = ticketAnchor(ticket)
            if (anchor == null) { db.delete("knowledge_meta", "key=?", arrayOf(ticket)); continue }
            val attempts = anchor.optInt("parseAttempts")
            if (attempts >= 3) {
                finishParse(ticket, null, WhaleParseFailure.INTERRUPTED)
                onChanged()
                continue
            }
            anchor.put("parseState", "processing").put("parseAttempts", attempts + 1)
            updateRecord(anchor.getString("id"), anchor)
            onChanged()
            return anchor
        }
    }

    /** Chunks, coverage and ticket removal share a transaction. A deleted/reimported anchor has a new ticket. */
    fun finishParse(ticket: String, parsed: WhaleParsedContent?, failure: WhaleParseFailure? = null): JSONArray {
        val rows = JSONArray()
        db.beginTransaction()
        try {
            val anchor = ticketAnchor(ticket) ?: return rows
            val hash = anchor.getString("originalId")
            val origin = anchor.optString("shareOrigin").ifEmpty { null }?.let { WhaleShareSource(it, anchor.optString("sourceUrl").ifEmpty { null }) }
            if (parsed != null) {
                if (parsed.description) {
                    require(parsed.text.isNotBlank() && parsed.text.length <= CompanionKnowledgeData.MAX_RECORD_TEXT && '\u0000' !in parsed.text)
                    val id = java.util.UUID.nameUUIDFromBytes("image-analysis:$hash".toByteArray(Charsets.UTF_8)).toString()
                    val row = JSONObject().put("id", id).put("source", "image_analysis").put("text", parsed.text).put("createdAt", System.currentTimeMillis())
                        .put("originalId", hash).put("coverage", "model_description")
                    origin?.annotate(row)
                    rows.put(appendRecordIfAbsent(row))
                } else {
                    val prefix = anchor.optInt("audioPrefixChunks",0)
                    if (prefix != 0) check(parsed.mediaKind == "audio_transcript" && legacyAudioPrefix(anchor) == prefix) { "已有转写发生变化，请重新打开原件" }
                    val chunks = if (prefix > 0 && parsed.text.isBlank()) emptyList() else KnowledgeChunks.split(parsed.text)
                    val added = appendDocument(hash, chunks, anchor.optString("displayName"), parsed.coverage,
                        requireOriginal = true, origin = origin, mediaKind = parsed.mediaKind, prefixCount = prefix)
                    for (i in 0 until added.length()) rows.put(added.getJSONObject(i))
                }
                val origins = anchor.optJSONArray("shareOrigins")
                if (origins != null && origins.length() > 1) {
                    for (i in 0 until rows.length()) {
                        val row = rows.getJSONObject(i).put("shareOrigins", origins)
                        updateRecord(row.getString("id"), row)
                    }
                }
                anchor.put("coverage", parsed.coverage)
                if (parsed.mediaKind == "audio_transcript") {
                    anchor.put("audioTranscriptionVersion",2)
                    anchor.remove("audioFromSeconds"); anchor.remove("audioPrefixChunks")
                }
            }
            anchor.remove("parseTicket")
            anchor.remove("parseAttempts")
            if (parsed == null) anchor.put("parseFailure",(failure ?: WhaleParseFailure.EMPTY_CONTENT).name)
            else anchor.remove("parseFailure")
            anchor.put("parseState", if (parsed == null) "needs_retry" else "complete")
            updateRecord(anchor.getString("id"), anchor)
            db.delete("knowledge_meta", "key=?", arrayOf(ticket))
            db.setTransactionSuccessful()
            return rows
        } finally { db.endTransaction() }
    }

    /** The links commit with the original, before OCR or transcription can fail. */
    private fun linkToCaptions(original: JSONObject, ids: List<String>) {
        if (ids.isEmpty()) return
        check(original.getString("source") == "original")
        val anchor = original.getString("id")
        val generation = original.optString("attachmentGeneration").ifBlank {
            java.util.UUID.randomUUID().toString().also {
                original.put("attachmentGeneration", it)
                updateRecord(anchor, original)
            }
        }
        for (id in ids) {
            if (id == anchor) continue
            val note = findById(id) ?: continue
            val links = note.optJSONObject("attachmentLinks") ?: JSONObject()
            if (links.optString(anchor) == generation) continue
            links.put(anchor, generation)
            note.put("attachmentLinks", links)
            updateRecord(id, note)
        }
    }

    fun attachmentPage(id: String, expectedText: String, offset: Int, limit: Int): Pair<JSONArray, Int?> {
        require(offset >= 0 && limit in 1..50)
        val note = findById(id)
        check(note != null && note.getString("text") == expectedText) { "说明已修改或删除，请重新检索。" }
        val result = JSONArray()
        val links = note.optJSONObject("attachmentLinks") ?: return result to null
        var matched = 0
        for (anchor in links.keys()) {
            val original = findById(anchor) ?: continue
            if (original.optString("source") != "original" || original.optString("attachmentGeneration") != links.getString(anchor)) continue
            if (matched++ < offset) continue
            if (result.length() == limit) return result to (offset + limit)
            result.put(original)
        }
        return result to null
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
    private fun allowExplicitReimport(originalId: String): Boolean {
            val updates = mutableListOf<Pair<String, String>>()
            db.rawQuery("SELECT key,value FROM knowledge_meta WHERE key LIKE 'deleted:%'", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val decision = JSONObject(cursor.getString(1))
                    if (decision.optString("originalId") == originalId && !decision.optBoolean("restored")) {
                        decision.remove("row")
                        decision.put("restored", true)
                        updates.add(cursor.getString(0) to decision.toString())
                    }
                }
            }
            for ((key, value) in updates) db.execSQL("UPDATE knowledge_meta SET value=? WHERE key=?", arrayOf(value, key))
            return updates.isNotEmpty()
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
    private fun cancelPendingParse(row: JSONObject) {
        if (row.optString("source") != "original" || !row.has("parseTicket")) return
        db.delete("knowledge_meta", "key=? AND value=?", arrayOf(row.getString("parseTicket"), row.getString("id")))
        CompanionKnowledgeBackup.clearPendingParse(row)
    }

    fun trashRecord(id: String, expectedText: String, now: Long): Boolean {
        db.beginTransaction()
        try {
            val row = findById(id) ?: return false
            if (row.getString("text") != expectedText) return false
            cancelPendingParse(row)
            val trash = JSONObject().put("row", row).put("deletedAt", now)
            row.optString("originalId").takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { trash.put("originalId", it) }
            db.execSQL("INSERT OR REPLACE INTO knowledge_meta(key,value) VALUES (?,?)", arrayOf("deleted:$id", trash.toString()))
            check(db.delete("knowledge_records", "id=?", arrayOf(id)) == 1)
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    /** Every selected row is checked before this transaction writes any deletion marker. */
    fun trashRecords(entries: List<Pair<String, String>>, now: Long): JSONArray? {
        require(entries.size in 1..20 && entries.map { it.first }.toSet().size == entries.size)
        db.beginTransaction()
        try {
            val rows = mutableListOf<JSONObject>()
            for ((id, expectedText) in entries) {
                val row = findById(id) ?: return null
                if (row.getString("text") != expectedText) return null
                rows.add(row)
            }
            rows.forEach { row ->
                val id = row.getString("id")
                cancelPendingParse(row)
                val trash = JSONObject().put("row", row).put("deletedAt", now)
                row.optString("originalId").takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { trash.put("originalId", it) }
                db.execSQL("INSERT OR REPLACE INTO knowledge_meta(key,value) VALUES (?,?)", arrayOf("deleted:$id", trash.toString()))
                check(db.delete("knowledge_records", "id=?", arrayOf(id)) == 1)
            }
            db.setTransactionSuccessful()
            return JSONArray().apply { rows.forEach { put(JSONObject(it.toString())) } }
        } finally { db.endTransaction() }
    }

    /** A deliberate whole-original deletion has no undo window: every linked record gets a tombstone. */
    fun deleteOriginal(originalId: String, anchorId: String, expectedAnchorText: String, now: Long): JSONArray {
        require(originalId.matches(Regex("[a-f0-9]{64}")) && anchorId.matches(Regex("[a-f0-9-]{36}")))
        db.beginTransaction()
        try {
            val anchor = findById(anchorId) ?: return JSONArray()
            if (anchor.optString("originalId") != originalId || anchor.optString("text") != expectedAnchorText) return JSONArray()
            val removed = JSONArray()
            db.rawQuery("SELECT id,body FROM knowledge_records", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = JSONObject(cursor.getString(1))
                    if (row.optString("originalId") == originalId) removed.put(row)
                }
            }
            if (removed.length() == 0) return removed
            for (index in 0 until removed.length()) {
                val row = removed.getJSONObject(index)
                cancelPendingParse(row)
                val tombstone = JSONObject().put("deletedAt", now).put("originalId", originalId).put("scope", "original")
                db.execSQL("INSERT OR REPLACE INTO knowledge_meta(key,value) VALUES (?,?)", arrayOf("deleted:${row.getString("id")}", tombstone.toString()))
                check(db.delete("knowledge_records", "id=?", arrayOf(row.getString("id"))) == 1)
            }
            db.setTransactionSuccessful()
            return removed
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

    /** Restores the complete reviewed batch or none of it; a missing/expired row rejects the batch. */
    fun undoTrashes(ids: List<String>, now: Long, windowMs: Long): Boolean {
        require(ids.size in 1..20 && ids.toSet().size == ids.size)
        db.beginTransaction()
        try {
            val restores = mutableListOf<Triple<String, JSONObject, JSONObject>>()
            for (id in ids) {
                val key = "deleted:$id"
                val value = db.rawQuery("SELECT value FROM knowledge_meta WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null } ?: return false
                val trash = JSONObject(value)
                val deletedAt = trash.getLong("deletedAt")
                if (now < deletedAt || now - deletedAt > windowMs || findById(id) != null) return false
                val row = trash.optJSONObject("row") ?: return false
                if (row.getString("id") != id) return false
                CompanionKnowledgeData.validateRows(JSONArray().put(row), false)
                restores.add(Triple(key, trash, row))
            }
            var position = nextPosition()
            restores.forEach { (key, trash, row) ->
                db.insertOrThrow("knowledge_records", null, ContentValues().apply { put("id", row.getString("id")); put("position", position++); put("body", row.toString()) })
                trash.remove("row"); trash.put("restored", true)
                db.execSQL("UPDATE knowledge_meta SET value=? WHERE key=?", arrayOf(trash.toString(), key))
            }
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
