package io.deepseekharness.mobile.companion

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Local user-selected text; never exposed to the model by this store. */
internal class CompanionKnowledgeStore(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "companion-knowledge-v1.json"))
    private fun semanticChange(id: String? = null) {
        val control = SemanticControl(app)
        if (!control.enabled) return
        if (id == null) control.added() else control.invalidate(id)
        runCatching { SemanticIndexJobs.schedule(app, 5_000) }
    }
    private fun scheduleSemantic() { if (SemanticControl(app).enabled) runCatching { SemanticIndexJobs.schedule(app, 5_000) } }

    fun search(query: String): JSONArray = synchronized(lock) { CompanionKnowledgeData.search(load(), query) }
    internal fun fingerprint(): String = synchronized(lock) {
        UnifiedKnowledgeDatabase(app, ::loadLegacy).use { it.fingerprint() }
    }
    internal fun recent(offset: Int, limit: Int): JSONArray = synchronized(lock) {
        UnifiedKnowledgeDatabase(app, ::loadLegacy).use { it.recent(offset, limit) }
    }
    internal fun pageAfter(position: Long, limit: Int): List<Pair<Long, JSONObject>> = synchronized(lock) {
        UnifiedKnowledgeDatabase(app, ::loadLegacy).use { it.pageAfter(position, limit) }
    }
    internal fun lexicalCandidates(query: String, limit: Int): List<JSONObject> = synchronized(lock) {
        UnifiedKnowledgeDatabase(app, ::loadLegacy).use { it.lexicalCandidates(query, limit) }
    }
    internal fun findById(id: String): JSONObject? = synchronized(lock) {
        UnifiedKnowledgeDatabase(app, ::loadLegacy).use { it.findById(id) }
    }
    fun exportForUser(): ByteArray = synchronized(lock) { CompanionKnowledgeBackup.encode(load()) }
    fun previewImport(bytes: ByteArray): KnowledgeImportPlan = synchronized(lock) { CompanionKnowledgeBackup.plan(bytes, load()) }
    fun applyImport(plan: KnowledgeImportPlan): Int = synchronized(lock) {
        val current = load()
        require(current.toString() == plan.original) { "资料已变化，请重新选择文件" }
        val additions = JSONArray(plan.additions)
        for (i in 0 until additions.length()) current.put(additions.getJSONObject(i))
        CompanionKnowledgeData.validateRows(current, false)
        if (additions.length() > 0) { semanticChange(); write(current); scheduleSemantic() }
        additions.length()
    }
    fun add(text: String, source: String = "shared_text"): Boolean = synchronized(lock) {
        semanticChange()
        UnifiedKnowledgeDatabase(app, ::loadLegacy).use { it.appendUniqueText(text, source, System.currentTimeMillis()) }.also { if (it) scheduleSemantic() }
    }
    internal fun addDocument(text: String, documentId: String, name: String = "", requireOriginal: Boolean = false): JSONArray = synchronized(lock) {
        require(documentId.matches(Regex("[a-f0-9]{64}")))
        val chunks = KnowledgeChunks.split(text)
        val displayName = name.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").trim().take(200)
        val coverage = if (text.contains("[内容过长，已截取]") || text.contains("[仅提取前")) "partial" else "extracted_text"
        semanticChange()
        UnifiedKnowledgeDatabase(app, ::loadLegacy).use { it.appendDocument(documentId, chunks, displayName, coverage, requireOriginal) }.also { if (it.length() > 0) scheduleSemantic() }
    }
    /** Records an original even if parsing or image understanding is unavailable. */
    internal fun registerOriginal(originalId: String, name: String, mime: String): JSONObject = synchronized(lock) {
        require(originalId.matches(Regex("[a-f0-9]{64}")))
        val id = java.util.UUID.nameUUIDFromBytes("original:$originalId".toByteArray(Charsets.UTF_8)).toString()
        val row = JSONObject().put("id", id).put("source", "original")
            .put("text", "[原件已保存，解析状态需另行核对]").put("createdAt", System.currentTimeMillis())
            .put("originalId", originalId).put("displayName", safeDisplayName(name))
            .put("mime", mime.take(200)).put("coverage", "not_parsed")
        UnifiedKnowledgeDatabase(app, ::loadLegacy).use { it.appendRecordIfAbsent(row, explicitReimport = true) }
    }
    internal fun registerImageAnalysis(originalId: String, text: String): JSONObject = synchronized(lock) {
        require(originalId.matches(Regex("[a-f0-9]{64}")))
        require(text.isNotBlank() && text.length <= CompanionKnowledgeData.MAX_TEXT && '\u0000' !in text)
        val id = java.util.UUID.nameUUIDFromBytes("image-analysis:$originalId".toByteArray(Charsets.UTF_8)).toString()
        val row = JSONObject().put("id", id).put("source", "image_analysis").put("text", text)
            .put("createdAt", System.currentTimeMillis()).put("originalId", originalId).put("coverage", "model_description")
        semanticChange()
        UnifiedKnowledgeDatabase(app, ::loadLegacy).use { it.appendRecordIfAbsent(row) }.also { scheduleSemantic() }
    }
    internal fun originalInfo(originalId: String): JSONObject? = synchronized(lock) {
        require(originalId.matches(Regex("[a-f0-9]{64}")))
        val id = java.util.UUID.nameUUIDFromBytes("original:$originalId".toByteArray(Charsets.UTF_8)).toString()
        UnifiedKnowledgeDatabase(app, ::loadLegacy).use { it.findById(id) }
    }
    internal fun updateOriginalCoverage(originalId: String, coverage: String) = synchronized(lock) {
        require(coverage in setOf("not_parsed", "partial", "extracted_text", "model_description"))
        val id = java.util.UUID.nameUUIDFromBytes("original:$originalId".toByteArray(Charsets.UTF_8)).toString()
        UnifiedKnowledgeDatabase(app, ::loadLegacy).use { db ->
            val row = db.findById(id) ?: return@synchronized
            check(row.optString("originalId") == originalId && row.optString("source") == "original")
            row.put("coverage", coverage)
            db.updateRecord(id, row)
        }
    }
    private fun safeDisplayName(value: String): String = value.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").trim().take(200)
    fun edit(id: String, expectedText: String, newText: String): Boolean = synchronized(lock) {
        require(newText.isNotBlank() && newText.length <= CompanionKnowledgeData.MAX_TEXT && '\u0000' !in newText) { "文字为空、包含无效字符或超过 8000 字" }
        UnifiedKnowledgeDatabase(app, ::loadLegacy).use { db ->
            val row = db.findById(id) ?: return@synchronized false
            if (row.getString("text") != expectedText) return@synchronized false
            require(row.optString("source") != "original") { "原件状态记录不能编辑" }
            require(!db.containsOtherText(id, newText)) { "已有相同资料" }
            if (expectedText != newText) semanticChange(id)
            if (expectedText != newText) CompanionMemoryStore(app).forgetSharedMaterial(expectedText, id)
            row.put("text", newText)
            db.updateRecord(id, row)
            if (expectedText != newText) scheduleSemantic()
            true
        }
    }
    /** Model annotation never edits source text or invalidates its derived facts. */
    internal fun annotate(id: String, expectedText: String, summary: String): Boolean = synchronized(lock) {
        require(summary.length <= 8000)
        UnifiedKnowledgeDatabase(app, ::loadLegacy).use { db ->
            val row = db.findById(id) ?: return@synchronized false
            if (row.getString("text") != expectedText) return@synchronized false
            db.updateRecord(id, row.put("analysisSummary", summary))
            true
        }
    }
    fun remove(id: String, expectedText: String): Boolean = synchronized(lock) {
        UnifiedKnowledgeDatabase(app, ::loadLegacy).use { db ->
            val row = db.findById(id) ?: return@synchronized false
            if (row.getString("text") != expectedText) return@synchronized false
            semanticChange(id)
            CompanionMemoryStore(app).forgetSharedMaterial(expectedText, id)
            db.trashRecord(id, expectedText, System.currentTimeMillis())
        }
    }
    /** Restores the selected record only; previously suppressed derived memories stay suppressed. */
    internal fun undoRemove(id: String): Boolean = synchronized(lock) {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        semanticChange()
        UnifiedKnowledgeDatabase(app, ::loadLegacy).use { it.undoTrash(id, System.currentTimeMillis(), 10 * 60_000L) }.also { if (it) scheduleSemantic() }
    }
    internal fun recentTrash(): JSONArray = synchronized(lock) {
        UnifiedKnowledgeDatabase(app, ::loadLegacy).use { it.recentTrash(System.currentTimeMillis(), 10 * 60_000L) }
    }
    /** Serialize the check with edits/deletes before publishing derived facts. */
    internal fun withUnchangedRecord(id: String, expectedText: String, action: () -> Unit): Boolean = synchronized(lock) {
        val current = UnifiedKnowledgeDatabase(app, ::loadLegacy).use { it.findById(id) }
        if (current?.optString("text") != expectedText) return@synchronized false
        action()
        true
    }
    private fun load(): JSONArray = UnifiedKnowledgeDatabase(app, ::loadLegacy).use { it.read() }
    private fun loadLegacy(): JSONArray {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return JSONArray()
        val bytes = file.readFully()
        return CompanionKnowledgeData.parseBytes(bytes)
    }
    private fun write(rows: JSONArray) {
        CompanionKnowledgeData.validateRows(rows, false)
        UnifiedKnowledgeDatabase(app, ::loadLegacy).use { it.write(rows) }
    }
    companion object { internal val lock = Any() }
}

/** Bounded operations also used when loading; corrupt data must not be overwritten. */
internal object CompanionKnowledgeData {
    const val MAX_BYTES = 512_000
    const val MAX_TEXT = 8_000
    const val MAX_ITEMS = 100
    fun utf8(value: String): ByteArray {
        val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val buffer = encoder.encode(java.nio.CharBuffer.wrap(value))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
    fun parseBytes(bytes: ByteArray): JSONArray {
        require(bytes.size <= MAX_BYTES) { "资料库超过大小限制，原文件已保留" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return parse(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString())
    }
    fun parse(raw: String, legacyLimit: Boolean = true): JSONArray {
        val root = JSONObject(raw)
        val version = root.opt("version")
        require((version is Int || version is Long) && (version as Number).toLong() == 1L) { "资料库版本不支持" }
        val rows = root.getJSONArray("items")
        return validateRows(rows, legacyLimit)
    }
    fun validateRows(rows: JSONArray, legacyLimit: Boolean = true): JSONArray {
        if (legacyLimit) require(rows.length() <= MAX_ITEMS) { "资料过多" }
        val ids = mutableSetOf<String>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            require(row.opt("id") is String && row.opt("text") is String && row.opt("source") is String && row.opt("createdAt") is Number) { "资料格式无效" }
            val id = row.getString("id")
            require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) && ids.add(id)) { "资料标识无效" }
            val text = row.getString("text")
            utf8(text)
            require(text.isNotBlank() && text.length <= MAX_TEXT && row.getString("source") in setOf("shared_text", "text_file") && row.getLong("createdAt") > 0) { "资料内容无效" }
        }
        return rows
    }
    fun add(rows: JSONArray, text: String, now: Long, source: String = "shared_text", legacyLimit: Boolean = true): Boolean {
        require(source in setOf("shared_text", "text_file")) { "资料来源无效" }
        require(text.isNotBlank() && text.length <= MAX_TEXT && now > 0) { "文字为空或超过 8000 字" }
        for (i in 0 until rows.length()) if (rows.getJSONObject(i).getString("text") == text) return false
        if (legacyLimit) require(rows.length() < MAX_ITEMS) { "资料已满，请先删除部分资料" }
        rows.put(JSONObject().put("id", UUID.randomUUID().toString()).put("text", text).put("source", source).put("createdAt", now))
        return true
    }
    fun search(rows: JSONArray, query: String): JSONArray {
        require(query.length <= 200) { "搜索词过长" }
        val result = JSONArray()
        for (i in rows.length() - 1 downTo 0) {
            val row = rows.getJSONObject(i)
            if (query.isBlank() || row.getString("text").contains(query.trim(), ignoreCase = true)) result.put(row)
        }
        return result
    }
    /** Return a copy; reject stale edits and duplicate text without mutating the input. */
    fun edit(rows: JSONArray, id: String, expectedText: String, newText: String): JSONArray? {
        require(newText.isNotBlank() && newText.length <= MAX_TEXT && '\u0000' !in newText) { "文字为空、包含无效字符或超过 8000 字" }
        val index = (0 until rows.length()).firstOrNull { rows.getJSONObject(it).getString("id") == id } ?: return null
        if (rows.getJSONObject(index).getString("text") != expectedText) return null
        require((0 until rows.length()).none { it != index && rows.getJSONObject(it).getString("text") == newText }) { "已有相同资料" }
        val updated = JSONArray(rows.toString())
        updated.getJSONObject(index).put("text", newText)
        return updated
    }
    fun remove(rows: JSONArray, id: String, expectedText: String): JSONArray? {
        val found = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("id") == id } ?: return null
        if (found.getString("text") != expectedText) return null
        val kept = JSONArray()
        for (i in 0 until rows.length()) if (rows.getJSONObject(i).getString("id") != id) kept.put(rows.getJSONObject(i))
        return kept
    }
}

internal data class KnowledgeImportPlan(val original: String, val additions: String, val skipped: Int)

/** Explicit user backup: versioned, bounded, and merged only if the preview is still current. */
internal object CompanionKnowledgeBackup {
    const val MAX_BYTES = 32_000_000
    private const val FORMAT = "whale-knowledge-backup"
    fun encode(rows: JSONArray): ByteArray {
        val bytes = CompanionKnowledgeData.utf8(JSONObject().put("format", FORMAT).put("version", 1).put("items", rows).toString())
        require(bytes.size <= MAX_BYTES) { "资料备份超过大小上限" }
        return bytes
    }
    fun plan(bytes: ByteArray, current: JSONArray): KnowledgeImportPlan {
        require(bytes.size <= MAX_BYTES) { "备份超过大小上限" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val raw = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val root = JSONObject(raw)
        require(root.opt("format") == FORMAT) { "不是资料库备份" }
        val incoming = CompanionKnowledgeData.parse(raw, legacyLimit = false)
        val ids = mutableSetOf<String>()
        val texts = mutableSetOf<String>()
        for (i in 0 until current.length()) {
            val row = current.getJSONObject(i)
            ids.add(row.getString("id")); texts.add(row.getString("text"))
        }
        val additions = JSONArray()
        var skipped = 0
        for (i in 0 until incoming.length()) {
            val row = incoming.getJSONObject(i)
            val id = row.getString("id"); val text = row.getString("text")
            if (id in ids || text in texts) { skipped++; continue }
            ids.add(id); texts.add(text); additions.put(row)
        }
        val merged = JSONArray(current.toString())
        for (i in 0 until additions.length()) merged.put(additions.getJSONObject(i))
        encode(merged)
        return KnowledgeImportPlan(current.toString(), additions.toString(), skipped)
    }
}

/** Strictly decode a user-selected text file; no truncation or silent charset replacement. */
internal object CompanionKnowledgeTextFile {
    const val MAX_FILE_BYTES = 32_000
    fun decode(bytes: ByteArray): String {
        require(bytes.size <= MAX_FILE_BYTES) { "文件超过大小上限" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require(text.isNotBlank() && text.length <= CompanionKnowledgeData.MAX_TEXT && '\u0000' !in text) { "文件为空、过长或不是文本" }
        return text
    }
}
