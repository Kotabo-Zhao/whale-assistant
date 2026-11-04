package io.deepseekharness.mobile.companion

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 桌宠会话里「不是用户这一轮发起的」回合（后台任务回执、屏幕陪看唤醒等）的本地收件箱。
 *
 * 为什么单独存一份、而不写进 [CompanionBubbleHistory]：
 * 历史文件的编码要求严格「用户/助手」成对交替（见 `CompanionBubbleHistory.encode` 的
 * `messages.size % 2 == 0` 与 `role == if (index % 2 == 0) "user" else "assistant"`），
 * 而回执是「凭空多出来的一条助手消息」，硬塞进去只能伪造一条用户消息。
 * 收件箱只负责「让用户看得到结果」，不是对话记录，也不参与恢复与导出，因此单独成文件最干净。
 *
 * 边界：最多 [MAX_ITEMS] 条、单条文本 [MAX_TEXT] 字、整个文件 128 KB；只存摘要，不存正文来源。
 */
internal class CompanionDeliveryInbox(context: Context) {
    data class Item(val turn: Int, val text: String, val atMs: Long, val sessionId: String)

    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "companion-delivery-inbox-v1.json"))
    private val seen = context.getSharedPreferences("companion_delivery_displayed", Context.MODE_PRIVATE)

    init {
        if (!seen.getBoolean("initialized", false)) {
            val existing = load().map { displayId(it) }.toSet()
            seen.edit().putStringSet("ids", existing).putBoolean("initialized", true).commit()
        }
    }

    private fun displayId(item: Item) = "${item.sessionId}:${item.turn}"
    fun unseen(): List<Item> {
        val ids = seen.getStringSet("ids", emptySet()).orEmpty()
        return load().filter { displayId(it) !in ids }
    }
    fun markSeen(items: Collection<Item>) {
        val existing = load().map(::displayId).toSet()
        val ids = (seen.getStringSet("ids", emptySet()).orEmpty() + items.map(::displayId)).intersect(existing)
        seen.edit().putStringSet("ids", ids).commit()
    }

    fun load(): List<Item> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        return runCatching {
            val root = JSONObject(file.readFully().toString(Charsets.UTF_8))
            val rows = root.optJSONArray("items") ?: JSONArray()
            (0 until rows.length()).mapNotNull { index ->
                rows.optJSONObject(index)?.let { row ->
                    val text = row.optString("text").trim()
                    if (text.isBlank()) null else Item(
                        turn = row.optInt("turn", -1),
                        text = text.take(MAX_TEXT),
                        atMs = row.optLong("atMs", 0L),
                        sessionId = row.optString("sessionId"),
                    )
                }
            }.takeLast(MAX_ITEMS)
        }.getOrElse { emptyList() }
    }

    /** 追加一条；同一 turn 只保留一份（轮询重试/重连时可能重复发现同一轮）。 */
    @Synchronized
    fun add(item: Item) {
        val text = item.text.trim().take(MAX_TEXT)
        if (text.isBlank()) return
        val next = (load().filterNot { it.turn == item.turn && it.sessionId == item.sessionId } + item.copy(text = text))
            .takeLast(MAX_ITEMS)
        write(next)
    }

    @Synchronized
    fun clear() {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) runCatching { file.delete() }
    }

    /** 已推送到桌宠的最大回合号；用于重启/重开后不重复推送，也用于决定从哪一轮开始盯。 */
    fun lastSeenTurn(): Int = runCatching {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return -1
        JSONObject(file.readFully().toString(Charsets.UTF_8)).optInt("lastSeenTurn", -1)
    }.getOrDefault(-1)

    /** 记录本轮已经看到过的回合号（只增不减），供下一次布防使用。 */
    @Synchronized
    fun rememberTurn(turn: Int) {
        if (turn < 0) return
        val known = lastSeenTurn()
        if (turn <= known) return
        val items = load()
        val rows = JSONArray()
        items.forEach { item ->
            rows.put(JSONObject().put("turn", item.turn).put("text", item.text.take(MAX_TEXT))
                .put("atMs", item.atMs).put("sessionId", item.sessionId.take(MAX_SESSION)))
        }
        val bytes = JSONObject().put("version", 1).put("lastSeenTurn", turn).put("items", rows)
            .toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite(); output.write(bytes); file.finishWrite(output)
        } catch (_: Exception) {
            if (output != null) file.failWrite(output)
        }
    }

    private fun write(items: List<Item>) {
        val rows = JSONArray()
        items.forEach { item ->
            rows.put(
                JSONObject().put("turn", item.turn).put("text", item.text.take(MAX_TEXT))
                    .put("atMs", item.atMs).put("sessionId", item.sessionId.take(MAX_SESSION)),
            )
        }
        val bytes = JSONObject().put("version", 1).put("lastSeenTurn", lastSeenTurn()).put("items", rows)
            .toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite(); output.write(bytes); file.finishWrite(output)
        } catch (error: Exception) {
            if (output != null) file.failWrite(output)
            // 收件箱是「尽力而为」的展示层：写失败不能影响对话本身。
        }
    }

    private companion object {
        const val MAX_ITEMS = 5
        const val MAX_TEXT = 4_000
        const val MAX_SESSION = 256
        const val MAX_BYTES = 128 * 1024
    }
}
