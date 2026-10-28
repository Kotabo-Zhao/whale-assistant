package io.deepseekharness.mobile.companion

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Local user-selected text; never exposed to the model by this store. */
internal class CompanionKnowledgeStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "companion-knowledge-v1.json"))

    fun search(query: String): JSONArray = synchronized(lock) { CompanionKnowledgeData.search(load(), query) }
    fun exportForUser(): ByteArray = synchronized(lock) { CompanionKnowledgeBackup.encode(load()) }
    fun previewImport(bytes: ByteArray): KnowledgeImportPlan = synchronized(lock) { CompanionKnowledgeBackup.plan(bytes, load()) }
    fun applyImport(plan: KnowledgeImportPlan): Int = synchronized(lock) {
        val current = load()
        require(current.toString() == plan.original) { "资料已变化，请重新选择文件" }
        val additions = JSONArray(plan.additions)
        for (i in 0 until additions.length()) current.put(additions.getJSONObject(i))
        CompanionKnowledgeBackup.encode(current) // Check aggregate size before modifying disk.
        if (additions.length() > 0) write(current)
        additions.length()
    }
    fun add(text: String): Boolean = synchronized(lock) {
        val rows = load()
        if (!CompanionKnowledgeData.add(rows, text, System.currentTimeMillis())) return@synchronized false
        write(rows)
        true
    }
    fun remove(id: String, expectedText: String): Boolean = synchronized(lock) {
        val rows = load()
        val kept = CompanionKnowledgeData.remove(rows, id, expectedText) ?: return@synchronized false
        write(kept)
        true
    }
    private fun load(): JSONArray {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return JSONArray()
        val bytes = file.readFully()
        require(bytes.size <= CompanionKnowledgeData.MAX_BYTES) { "资料库超过大小限制，原文件已保留" }
        return CompanionKnowledgeData.parse(String(bytes, Charsets.UTF_8))
    }
    private fun write(rows: JSONArray) {
        CompanionKnowledgeBackup.encode(rows) // Keep every newly written state exportable.
        val bytes = JSONObject().put("version", 1).put("items", rows).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= CompanionKnowledgeData.MAX_BYTES) { "资料库已满，请先删除部分资料" }
        var stream: java.io.FileOutputStream? = null
        try { stream = file.startWrite(); stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { if (stream != null) file.failWrite(stream); throw e }
    }
    companion object { private val lock = Any() }
}

/** Bounded operations also used when loading; corrupt data must not be overwritten. */
internal object CompanionKnowledgeData {
    const val MAX_BYTES = 512_000
    const val MAX_TEXT = 8_000
    const val MAX_ITEMS = 100
    fun parse(raw: String): JSONArray {
        val root = JSONObject(raw)
        require(root.getInt("version") == 1) { "资料库版本不支持" }
        val rows = root.getJSONArray("items")
        require(rows.length() <= MAX_ITEMS) { "资料过多" }
        val ids = mutableSetOf<String>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            require(row.opt("id") is String && row.opt("text") is String && row.opt("source") is String && row.opt("createdAt") is Number) { "资料格式无效" }
            val id = row.getString("id")
            require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) && ids.add(id)) { "资料标识无效" }
            val text = row.getString("text")
            require(text.isNotBlank() && text.length <= MAX_TEXT && row.getString("source") == "shared_text" && row.getLong("createdAt") > 0) { "资料内容无效" }
        }
        return rows
    }
    fun add(rows: JSONArray, text: String, now: Long): Boolean {
        require(text.isNotBlank() && text.length <= MAX_TEXT && now > 0) { "文字为空或超过 8000 字" }
        for (i in 0 until rows.length()) if (rows.getJSONObject(i).getString("text") == text) return false
        require(rows.length() < MAX_ITEMS) { "资料已满，请先删除部分资料" }
        rows.put(JSONObject().put("id", UUID.randomUUID().toString()).put("text", text).put("source", "shared_text").put("createdAt", now))
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
    private const val FORMAT = "whale-knowledge-backup"
    fun encode(rows: JSONArray): ByteArray {
        val bytes = JSONObject().put("format", FORMAT).put("version", 1).put("items", rows).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= CompanionKnowledgeData.MAX_BYTES) { "资料备份超过大小上限" }
        return bytes
    }
    fun plan(bytes: ByteArray, current: JSONArray): KnowledgeImportPlan {
        require(bytes.size <= CompanionKnowledgeData.MAX_BYTES) { "备份超过大小上限" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val raw = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val root = JSONObject(raw)
        require(root.opt("format") == FORMAT) { "不是资料库备份" }
        val incoming = CompanionKnowledgeData.parse(raw)
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
        require(current.length() + additions.length() <= CompanionKnowledgeData.MAX_ITEMS) { "合并后资料超过 100 条" }
        val merged = JSONArray(current.toString())
        for (i in 0 until additions.length()) merged.put(additions.getJSONObject(i))
        encode(merged)
        return KnowledgeImportPlan(current.toString(), additions.toString(), skipped)
    }
}
