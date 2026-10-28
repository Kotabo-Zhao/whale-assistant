package io.deepseekharness.mobile.companion

import android.content.Context
import android.util.AtomicFile
import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** App-private and outside rootfs, so ordinary runtime upgrades do not replace it. */
class CompanionMemoryStore(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.noBackupFilesDir, "companion-memory-v1.json"))
    private val prefs = app.getSharedPreferences("companion_private_v1", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("memory_enabled", true)
        set(value) { prefs.edit().putBoolean("memory_enabled", value).apply() }

    fun execute(param: String): String = synchronized(lock) {
        if (!enabled) throw RuntimeFailure("MEMORY_DISABLED", "用户已关闭长期记忆")
        CompanionMemoryEngine(::load, ::write).execute(param)
    }

    /** UI management remains available even when model access is disabled. */
    fun listForUser(): JSONArray = synchronized(lock) { load() }
    fun exportForUser(): ByteArray = synchronized(lock) { CompanionMemoryExport.encode(load()) }
    internal fun previewImport(bytes: ByteArray): MemoryImportPlan = synchronized(lock) {
        CompanionMemoryImport.plan(bytes, load())
    }
    internal fun applyImport(plan: MemoryImportPlan): Int = synchronized(lock) {
        val current = load()
        require(current.toString() == plan.original) { "记忆已发生变化，请重新选择文件并核对" }
        val combined = JSONArray(plan.original)
        val additions = JSONArray(plan.additions)
        for (index in 0 until additions.length()) combined.put(additions.getJSONObject(index))
        if (additions.length() > 0) write(combined)
        additions.length()
    }
    fun approveForUser(id: String, expectedText: String): Boolean = synchronized(lock) {
        val rows = load()
        if (!CompanionMemoryApproval.approve(rows, id, System.currentTimeMillis(), expectedText)) return@synchronized false
        write(rows)
        true
    }
    fun approveEditedForUser(id: String, expectedText: String, edited: String): Boolean = synchronized(lock) {
        val rows = load()
        if (!CompanionMemoryApproval.approveEdited(rows, id, expectedText, edited, System.currentTimeMillis())) return@synchronized false
        write(rows)
        true
    }
    fun deleteForUser(id: String): Boolean = synchronized(lock) {
        val rows = load(); val kept = JSONArray(); var removed = false
        for (index in 0 until rows.length()) { val row = rows.getJSONObject(index); if (row.getString("id") == id) removed = true else kept.put(row) }
        if (removed) write(kept)
        removed
    }
    fun clearForUser() = synchronized(lock) { write(JSONArray()) }
    /** Explicit user-authored memory; does not ingest notification text automatically. */
    internal fun addForUser(text: String, source: String): UserMemoryResult = synchronized(lock) {
        val rows = load()
        val result = CompanionUserMemory.add(rows, text, source, System.currentTimeMillis())
        if (result == UserMemoryResult.ADDED) write(rows)
        result
    }
    fun updateForUser(id: String, text: String): Boolean = synchronized(lock) {
        val value = text.trim()
        require(value.isNotEmpty() && value.length <= 600 && !CompanionStore.sensitive(value)) { "内容为空、过长或可能含敏感信息" }
        val rows = load()
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("id") == id } ?: return@synchronized false
        row.put("text", value).put("source", "user_edit").put("updatedAt", System.currentTimeMillis())
        write(rows)
        true
    }

    private fun load(): JSONArray {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return JSONArray()
        try {
            val bytes = file.readFully()
            if (bytes.size > 256_000) throw IllegalStateException("memory too large")
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            if (root.getInt("version") != 1) throw IllegalStateException("unknown memory version")
            val rows = root.getJSONArray("items")
            if (rows.length() > 100) throw IllegalStateException("too many memories")
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                if (!row.getString("id").matches(Regex("[a-f0-9-]{36}")) || row.getString("text").length > 600) throw IllegalStateException("bad memory")
                if (row.has("status") && row.optString("status") !in setOf("pending", "confirmed")) throw IllegalStateException("unknown memory status")
            }
            return rows
        } catch (e: Exception) {
            throw RuntimeFailure("MEMORY_READ_FAILED", "无法读取记忆，原文件已保留", e)
        }
    }

    private fun write(rows: JSONArray) {
        val bytes = JSONObject().put("version", 1).put("items", rows).toString().toByteArray(Charsets.UTF_8)
        var stream: java.io.FileOutputStream? = null
        try { stream = file.startWrite(); stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { if (stream != null) file.failWrite(stream); throw RuntimeFailure("MEMORY_WRITE_FAILED", "记忆保存失败，原数据已保留", e) }
    }
    companion object { private val lock = Any() }
}

internal enum class UserMemoryResult { ADDED, EXISTING_PENDING, EXISTING_CONFIRMED }

/** Manual confirmation is explicit; no notification metadata or content is copied implicitly. */
internal object CompanionUserMemory {
    fun add(rows: JSONArray, text: String, source: String, now: Long): UserMemoryResult {
        val value = text.trim()
        require(source in setOf("user_edit", "user_notification")) { "记忆来源无效" }
        require(value.isNotEmpty() && value.length <= 600 && !CompanionStore.sensitive(value)) { "内容为空、过长或可能含敏感信息" }
        val existing = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("text") == value }
        if (existing != null) return if (existing.optString("status") == "pending") UserMemoryResult.EXISTING_PENDING else UserMemoryResult.EXISTING_CONFIRMED
        require(rows.length() < 100) { "记忆已满，请先删除不需要的条目" }
        rows.put(JSONObject().put("id", UUID.randomUUID().toString()).put("text", value)
            .put("source", source).put("status", "confirmed").put("createdAt", now).put("confirmedAt", now))
        return UserMemoryResult.ADDED
    }
}

/** Pure operation layer; store serializes access and provides atomic persistence. */
internal class CompanionMemoryEngine(private val load: () -> JSONArray, private val write: (JSONArray) -> Unit) {
    fun execute(param: String): String {
        val request = try { JSONObject(param) } catch (_: Exception) { throw invalid() }
        val op = request.opt("op") as? String ?: throw invalid()
        return when (op) {
            "save" -> {
                val text = (request.opt("text") as? String)?.trim() ?: throw invalid()
                if (text.isBlank() || text.length > 600 || CompanionStore.sensitive(text)) throw invalid()
                val rows = load()
                val existing = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("text") == text }
                if (existing != null) return JSONObject().put("id", existing.getString("id")).put("alreadyExists", true)
                    .put("pending", existing.optString("status") == "pending").toString()
                if (rows.length() >= 100) throw RuntimeFailure("MEMORY_FULL", "记忆已满，请先删除不需要的条目")
                val row = JSONObject().put("id", UUID.randomUUID().toString()).put("text", text)
                    .put("createdAt", System.currentTimeMillis()).put("source", "assistant_tool").put("status", "pending")
                rows.put(row); write(rows)
                JSONObject().put("id", row.getString("id")).put("alreadyExists", false).put("pending", true).toString()
            }
            "search" -> {
                val query = (request.opt("query") as? String)?.trim() ?: throw invalid()
                if (query.length > 100) throw invalid()
                val rows = load()
                val confirmed = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("status") != "pending" }
                val result = CompanionMemorySearch.find(confirmed, query)
                JSONObject().put("items", JSONArray(result.items)).put("total", confirmed.size).put("matchMode", result.mode)
                    .put("notice", if (result.mode == "related") "关键词关联结果可能无关；使用或删除前请核对内容。" else JSONObject.NULL).toString()
            }
            "delete" -> {
                val id = request.opt("id") as? String ?: throw invalid()
                if (!id.matches(Regex("[a-f0-9-]{36}"))) throw invalid()
                val rows = load(); val kept = JSONArray(); var removed = false
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    if (row.getString("id") == id) removed = true else kept.put(row)
                }
                if (removed) write(kept)
                JSONObject().put("removed", removed).toString()
            }
            else -> throw invalid()
        }
    }

    private fun invalid() = RuntimeFailure("MEMORY_INPUT_INVALID", "记忆参数无效或包含敏感内容")
}

/** Bounded lexical fallback for rephrased queries; it does not infer facts or use pending items. */
internal object CompanionMemorySearch {
    data class Result(val items: List<JSONObject>, val mode: String)
    private val chinese = Regex("[\\u4e00-\\u9fff]{2,}")
    private val words = Regex("[a-z0-9]{3,}")
    private val commonWords = setOf("the", "and", "what", "which", "that", "this", "with", "for", "you", "your", "remember")
    private val commonPairs = setOf("什么", "如何", "怎么", "是否", "可以", "记得", "记住", "我的", "我们", "你说", "请问", "一下", "多少", "这个", "那个")
    private fun terms(text: String): Set<String> {
        val normalized = text.lowercase(java.util.Locale.ROOT)
        val result = mutableSetOf<String>()
        for (word in words.findAll(normalized)) if (word.value !in commonWords) result.add(word.value)
        for (run in chinese.findAll(normalized)) {
            val value = run.value
            for (i in 0 until value.length - 1) {
                val pair = value.substring(i, i + 2)
                if (pair !in commonPairs) result.add(pair)
            }
        }
        return result
    }
    fun relatedAnchor(text: String, query: String): Int = terms(query)
        .sortedWith(compareByDescending<String> { it.length }.thenBy { it })
        .firstNotNullOfOrNull { term -> text.indexOf(term, ignoreCase = true).takeIf { it >= 0 } } ?: 0
    fun find(rows: List<JSONObject>, query: String): Result {
        val newestFirst = rows.asReversed()
        if (query.isEmpty()) return Result(newestFirst.take(10), "recent")
        val exact = newestFirst.filter { it.getString("text").contains(query, ignoreCase = true) }
        if (exact.isNotEmpty()) return Result(exact.take(10), "exact")
        val keywords = terms(query)
        if (keywords.isEmpty()) return Result(emptyList(), "none")
        val matches = newestFirst.mapIndexedNotNull { index, row ->
            val shared = terms(row.getString("text")).intersect(keywords)
            val latin = shared.count { it[0] in 'a'..'z' || it[0] in '0'..'9' }
            val han = shared.size - latin
            // A single Chinese bigram is too broad; English words need at least four characters.
            val meaningfulWord = shared.any { it.length >= 4 && (it[0] in 'a'..'z' || it[0] in '0'..'9') }
            if (han < 2 && !meaningfulWord) null else Triple(row, han + latin * 2, index)
        }.sortedWith(compareByDescending<Triple<JSONObject, Int, Int>> { it.second }.thenBy { it.third })
        return Result(matches.take(10).map { it.first }, if (matches.isEmpty()) "none" else "related")
    }
}

/** Export is a user-selected snapshot; it does not alter or migrate the stored data. */
internal object CompanionMemoryExport {
    fun encode(items: JSONArray): ByteArray = JSONObject().put("version", 1).put("items", items)
        .toString(2).toByteArray(Charsets.UTF_8)
}

internal data class MemoryImportPlan(val original: String, val additions: String, val skipped: Int)

/** Strict, bounded import; existing identifiers and text win, with no implicit replacement. */
internal object CompanionMemoryImport {
    const val MAX_BYTES = 256_000
    fun plan(bytes: ByteArray, current: JSONArray): MemoryImportPlan {
        require(bytes.size <= MAX_BYTES) { "文件超过大小上限" }
        val decoded = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val root = JSONObject(decoded)
        require(root.getInt("version") == 1) { "不支持的记忆文件版本" }
        val incoming = root.getJSONArray("items")
        require(incoming.length() <= 100 && current.length() <= 100) { "记忆条目过多" }
        val ids = mutableSetOf<String>()
        val texts = mutableSetOf<String>()
        for (index in 0 until current.length()) {
            val row = current.getJSONObject(index)
            ids.add(row.getString("id")); texts.add(row.getString("text"))
        }
        val additions = JSONArray()
        var skipped = 0
        for (index in 0 until incoming.length()) {
            val row = incoming.getJSONObject(index)
            val id = row.opt("id") as? String ?: error("记忆 ID 无效")
            val text = row.opt("text") as? String ?: error("记忆内容无效")
            require(id.matches(Regex("[a-f0-9-]{36}")) && text.isNotBlank() && text.length <= 600 && !CompanionStore.sensitive(text)) { "记忆内容无效或可能含敏感信息" }
            if (id in ids || text in texts) { skipped++; continue }
            ids.add(id); texts.add(text)
            // Preserve only known bounded metadata; unknown fields are not imported.
            val copy = JSONObject().put("id", id).put("text", text)
            val status = row.optString("status", "confirmed")
            require(status in setOf("confirmed", "pending")) { "记忆状态无效" }
            if (status == "pending") copy.put("status", "pending")
            val source = row.optString("source", "")
            if (source in setOf("assistant_tool", "user_edit", "user_notification")) copy.put("source", source)
            for (key in listOf("createdAt", "updatedAt", "confirmedAt")) {
                val value = row.opt(key)
                if (value is Number && value.toLong() >= 0) copy.put(key, value.toLong())
            }
            additions.put(copy)
        }
        require(current.length() + additions.length() <= 100) { "合并后超过 100 条上限" }
        return MemoryImportPlan(current.toString(), additions.toString(), skipped)
    }
}

internal object CompanionMemoryApproval {
    fun approve(rows: JSONArray, id: String, now: Long, expectedText: String? = null): Boolean {
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("id") == id } ?: return false
        if (row.optString("status") != "pending" || (expectedText != null && row.optString("text") != expectedText)) return false
        row.put("status", "confirmed").put("confirmedAt", now)
        return true
    }
    fun approveEdited(rows: JSONArray, id: String, expectedText: String, edited: String, now: Long): Boolean {
        val value = edited.trim()
        require(value.isNotEmpty() && value.length <= 600 && !CompanionStore.sensitive(value)) { "内容为空、过长或可能含敏感信息" }
        val all = (0 until rows.length()).map { rows.getJSONObject(it) }
        val row = all.firstOrNull { it.optString("id") == id } ?: return false
        if (row.optString("status") != "pending" || row.optString("text") != expectedText) return false
        require(all.none { it !== row && it.optString("text") == value }) { "相同内容的记忆已存在，请先核对" }
        row.put("text", value).put("source", "user_edit").put("updatedAt", now).put("confirmedAt", now).put("status", "confirmed")
        return true
    }
}

/** Only a newly persisted candidate warrants a generic, content-free overlay hint. */
internal object CompanionMemoryHint {
    fun shouldNotify(request: String, result: String): Boolean = try {
        val input = JSONObject(request)
        val output = JSONObject(result)
        input.optString("op") == "save" && output.optBoolean("pending", false) && !output.optBoolean("alreadyExists", true)
    } catch (_: Exception) { false }
}
