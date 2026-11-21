package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 主界面（App）与悬浮桌宠（Pet）双向同步的轻量共享聊天记录存储。
 */
internal object WhaleSharedChatStore {
    private const val FILE_NAME = "whale-shared-chat-v1.json"
    private const val MAX_ITEMS = 40

    private fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    @Synchronized
    fun list(context: Context, sinceMs: Long = 0L): JSONArray {
        val f = file(context)
        if (!f.isFile) return JSONArray()
        val arr = runCatching { JSONArray(f.readText(Charsets.UTF_8)) }.getOrDefault(JSONArray())
        if (sinceMs <= 0L) return arr
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optLong("atMs", 0L) > sinceMs) out.put(item)
        }
        return out
    }

    @Synchronized
    fun appendTurn(context: Context, userText: String, assistantText: String, source: String) {
        val u = userText.trim().take(2000)
        val a = assistantText.trim().take(4000)
        if (u.isEmpty() && a.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = list(context)
        val next = JSONArray()
        val keepFrom = (existing.length() - (MAX_ITEMS - 2)).coerceAtLeast(0)
        for (i in keepFrom until existing.length()) {
            next.put(existing.get(i))
        }
        if (u.isNotEmpty()) {
            next.put(
                JSONObject()
                    .put("id", "sync-u-$now")
                    .put("role", "user")
                    .put("text", u)
                    .put("source", source)
                    .put("atMs", now),
            )
        }
        if (a.isNotEmpty()) {
            next.put(
                JSONObject()
                    .put("id", "sync-a-${now + 1}")
                    .put("role", "assistant")
                    .put("text", a)
                    .put("source", source)
                    .put("atMs", now + 1),
            )
        }
        runCatching {
            val f = file(context)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(next.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(next.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }
}
