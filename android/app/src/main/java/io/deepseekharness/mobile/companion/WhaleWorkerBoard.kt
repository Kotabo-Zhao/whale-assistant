package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Router 与并行后台 Worker 共享的轻量持久化键值黑板（Blackboard）。
 *
 * 用于多 Worker 并行执行时实时交换结构化中间产物（文件路径、提炼要点、接口返回、状态标记），
 * 无需等待整个 Worker 结束即可协同读写。
 */
internal object WhaleWorkerBoard {
    private const val FILE_NAME = "whale-worker-board-v1.json"
    private const val MAX_ENTRIES = 64
    private const val MAX_VALUE_CHARS = 8_000

    private fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    @Synchronized
    private fun load(context: Context): JSONObject {
        val f = file(context)
        if (!f.isFile) return JSONObject()
        return runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrElse { JSONObject() }
    }

    @Synchronized
    private fun save(context: Context, root: JSONObject) {
        val f = file(context)
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.writeText(root.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    @Synchronized
    fun put(context: Context, key: String, value: String, author: String = "router"): JSONObject {
        val cleanKey = key.trim().replace(Regex("[^a-zA-Z0-9._:/-]"), "_").take(80)
        require(cleanKey.isNotEmpty()) { "黑板 key 不能为空" }
        val cleanVal = value.trim().take(MAX_VALUE_CHARS)
        val root = load(context)
        val now = System.currentTimeMillis()
        val entry = JSONObject()
            .put("key", cleanKey)
            .put("value", cleanVal)
            .put("author", author.take(48))
            .put("updatedAt", now)
        root.put(cleanKey, entry)
        // LRU trim if over limit
        if (root.length() > MAX_ENTRIES) {
            val ordered = root.keys().asSequence()
                .map { k -> k to (root.optJSONObject(k)?.optLong("updatedAt", 0L) ?: 0L) }
                .sortedBy { it.second }
                .take(root.length() - MAX_ENTRIES)
                .map { it.first }
                .toList()
            ordered.forEach { root.remove(it) }
        }
        save(context, root)
        return JSONObject().put("ok", true).put("key", cleanKey).put("chars", cleanVal.length).put("updatedAt", now)
    }

    @Synchronized
    fun get(context: Context, key: String): JSONObject? {
        val cleanKey = key.trim()
        if (cleanKey.isEmpty()) return null
        return load(context).optJSONObject(cleanKey)
    }

    @Synchronized
    fun remove(context: Context, key: String): Boolean {
        val cleanKey = key.trim()
        if (cleanKey.isEmpty()) return false
        val root = load(context)
        if (!root.has(cleanKey)) return false
        root.remove(cleanKey)
        save(context, root)
        return true
    }

    @Synchronized
    fun list(context: Context, prefix: String = ""): JSONArray {
        val cleanPrefix = prefix.trim()
        val root = load(context)
        val items = root.keys().asSequence()
            .filter { cleanPrefix.isEmpty() || it.startsWith(cleanPrefix, ignoreCase = true) }
            .mapNotNull { root.optJSONObject(it) }
            .sortedByDescending { it.optLong("updatedAt", 0L) }
            .toList()
        val out = JSONArray()
        for (item in items) {
            val v = item.optString("value")
            out.put(
                JSONObject()
                    .put("key", item.optString("key"))
                    .put("author", item.optString("author"))
                    .put("chars", v.length)
                    .put("preview", v.take(180))
                    .put("updatedAt", item.optLong("updatedAt", 0L)),
            )
        }
        return out
    }

    fun promptKeysSummary(context: Context, limit: Int = 8): String {
        val items = list(context)
        if (items.length() == 0) return ""
        val keys = (0 until minOf(limit, items.length())).mapNotNull { items.optJSONObject(it)?.optString("key") }
        return if (keys.isEmpty()) "" else "[共享黑板已有键（可用 whale_worker_board 读取）] ${keys.joinToString(", ")}"
    }
}
