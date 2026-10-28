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
    fun deleteForUser(id: String): Boolean = synchronized(lock) {
        val rows = load(); val kept = JSONArray(); var removed = false
        for (index in 0 until rows.length()) { val row = rows.getJSONObject(index); if (row.getString("id") == id) removed = true else kept.put(row) }
        if (removed) write(kept)
        removed
    }
    fun clearForUser() = synchronized(lock) { write(JSONArray()) }
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
                if (existing != null) return JSONObject().put("id", existing.getString("id")).put("alreadyExists", true).toString()
                if (rows.length() >= 100) throw RuntimeFailure("MEMORY_FULL", "记忆已满，请先删除不需要的条目")
                val row = JSONObject().put("id", UUID.randomUUID().toString()).put("text", text)
                    .put("createdAt", System.currentTimeMillis()).put("source", "assistant_tool")
                rows.put(row); write(rows)
                JSONObject().put("id", row.getString("id")).put("alreadyExists", false).toString()
            }
            "search" -> {
                val query = (request.opt("query") as? String)?.trim() ?: throw invalid()
                if (query.length > 100) throw invalid()
                val rows = load()
                val results = JSONArray()
                for (index in rows.length() - 1 downTo 0) {
                    val row = rows.getJSONObject(index)
                    if ((query.isEmpty() || row.getString("text").contains(query, ignoreCase = true)) && results.length() < 10) results.put(row)
                }
                JSONObject().put("items", results).put("total", rows.length()).toString()
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

/** Export is a user-selected snapshot; it does not alter or migrate the stored data. */
internal object CompanionMemoryExport {
    fun encode(items: JSONArray): ByteArray = JSONObject().put("version", 1).put("items", items)
        .toString(2).toByteArray(Charsets.UTF_8)
}
