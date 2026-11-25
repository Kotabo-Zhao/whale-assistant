package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 事件驱动主动性引擎（Event-Driven Watchlist + Intervene-Value 打扰门控）。
 *
 * 将主动性从「纯时间桶定时冒泡」升级为「懂时机的事件闭环」：
 * 1. **隐式承诺自动捕获**：在日常聊天中零延迟识别带时间锚点的承诺/截止日（如「明天上午面试」「下周二交方案」），
 *    自动沉淀为待跟进 Watchlist 关注项；
 * 2. **打扰成本门控（Intervene-Value Gate）**：结合事件重要度、用户是否忙碌、距上次主动开口时长与连续忽略次数，
 *    自动降频自适应主人边界；
 * 3. **1 键闭环动作**：每条关注项附带可执行 `actionHint`，用户可一键「已完成 / 推迟 2 小时 / 忽略」。
 */
internal object WhaleProactiveEngine {

    private const val PREFS = "whale_proactive_watchlist_v1"
    private const val KEY_ITEMS = "items"
    private const val KEY_DISMISSED_STREAK = "dismissed_streak"
    private const val KEY_LAST_PROMPT_MS = "last_prompt_ms"
    private const val MAX_ITEMS = 24

    data class WatchItem(
        val id: String,
        val title: String,
        val category: String,
        val dueAtMs: Long,
        val actionHint: String,
        val status: String = "open", // open | snoozed | done | dismissed
        val createdAtMs: Long = System.currentTimeMillis(),
    ) {
        fun json(): JSONObject = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("category", category)
            .put("dueAtMs", dueAtMs)
            .put("actionHint", actionHint)
            .put("status", status)
            .put("createdAtMs", createdAtMs)
    }

    private val timeCueOffsetsHours: List<Pair<Regex, Long>> = listOf(
        Regex("今晚|今天晚上|待会儿|一会儿") to 3L,
        Regex("明天上午|明早|明天早上") to 16L,
        Regex("明天下午|明天晚上|明天") to 24L,
        Regex("后天") to 48L,
        Regex("这周末|本周末|周末") to 72L,
        Regex("下周一|下周二|下周三|下周四|下周五|下周") to 120L,
    )

    private val commitmentCues = listOf(
        "面试", "开会", "例会", "交方案", "提交", "汇报", "考试", "答辩",
        "出差", "航班", "高铁", "看病", "体检", "提醒我", "别忘了", "记得", "截止", "deadline",
    )

    /** 零延迟从用户对话中识别隐式时间承诺，返回待写入的 WatchItem（无承诺返回 null）。 */
    fun extractCommitment(userMessage: String, nowMs: Long = System.currentTimeMillis()): WatchItem? {
        val clean = userMessage.trim().replace(Regex("\\s+"), " ")
        if (clean.length < 5 || clean.length > 180) return null
        val lower = clean.lowercase()
        val matchedCue = commitmentCues.firstOrNull { lower.contains(it) } ?: return null
        val offsetHours = timeCueOffsetsHours.firstOrNull { it.first.containsMatchIn(clean) }?.second ?: return null
        val category = WhaleMemoryTaxonomy.classify(clean).key
        val hint = when (matchedCue) {
            "面试", "考试", "答辩" -> "帮你快速过一遍高频考点与准备清单"
            "开会", "例会", "汇报", "交方案", "提交" -> "帮你梳理要点或提前整理成简报"
            "出差", "航班", "高铁" -> "帮你核对行程与出发前检查项"
            else -> "到时候主动提醒你并协助处理"
        }
        return WatchItem(
            id = UUID.randomUUID().toString().take(12),
            title = clean.take(64),
            category = category,
            dueAtMs = nowMs + offsetHours * 3_600_000L,
            actionHint = hint,
            status = "open",
            createdAtMs = nowMs,
        )
    }

    /**
     * 主动打扰门控（Intervene-Value Gate）：
     * - `eventValue`：0..100（到期紧急事项 80+，普通跟进 55，闲聊问候 30）；
     * - 用户忙碌、刚开口不久或连续被忽略时自动提高门槛，避免变成话痨。
     */
    fun shouldIntervene(
        eventValue: Int,
        isUserBusy: Boolean,
        minutesSinceLastPrompt: Long,
        dismissedStreak: Int,
        isQuietHours: Boolean = false,
    ): Boolean {
        if (isQuietHours && eventValue < 90) return false
        var cost = 35
        if (isUserBusy) cost += 35
        if (minutesSinceLastPrompt < 30L) cost += 40
        else if (minutesSinceLastPrompt < 90L) cost += 18
        cost += (dismissedStreak.coerceAtLeast(0) * 16)
        return eventValue >= cost
    }

    fun list(context: Context, includeClosed: Boolean = false): List<WatchItem> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        val out = mutableListOf<WatchItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val status = o.optString("status", "open")
            if (!includeClosed && status in setOf("done", "dismissed")) continue
            out.add(
                WatchItem(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    category = o.optString("category", "project"),
                    dueAtMs = o.optLong("dueAtMs", 0L),
                    actionHint = o.optString("actionHint", ""),
                    status = status,
                    createdAtMs = o.optLong("createdAtMs", 0L),
                ),
            )
        }
        return out.sortedBy { it.dueAtMs }
    }

    fun observeAndCapture(context: Context, userMessage: String, nowMs: Long = System.currentTimeMillis()): WatchItem? {
        val candidate = extractCommitment(userMessage, nowMs) ?: return null
        val cur = list(context, includeClosed = true).toMutableList()
        if (cur.any { it.status == "open" && it.title == candidate.title }) return null
        cur.add(candidate)
        while (cur.size > MAX_ITEMS) cur.removeAt(0)
        persist(context, cur)
        return candidate
    }

    fun act(context: Context, id: String, action: String, nowMs: Long = System.currentTimeMillis()): JSONObject {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = list(context, includeClosed = true).toMutableList()
        val idx = cur.indexOfFirst { it.id == id }
        if (idx < 0) return JSONObject().put("ok", false).put("error", "未找到该关注项")
        val item = cur[idx]
        val updated = when (action.lowercase()) {
            "done" -> {
                prefs.edit().putInt(KEY_DISMISSED_STREAK, 0).apply()
                item.copy(status = "done")
            }
            "snooze" -> item.copy(status = "snoozed", dueAtMs = maxOf(item.dueAtMs, nowMs) + 2 * 3_600_000L)
            "dismiss" -> {
                val streak = prefs.getInt(KEY_DISMISSED_STREAK, 0) + 1
                prefs.edit().putInt(KEY_DISMISSED_STREAK, streak).putLong(KEY_LAST_PROMPT_MS, nowMs).apply()
                item.copy(status = "dismissed")
            }
            else -> return JSONObject().put("ok", false).put("error", "不支持的操作：$action")
        }
        cur[idx] = updated
        persist(context, cur)
        return JSONObject()
            .put("ok", true)
            .put("item", updated.json())
            .put("dismissedStreak", prefs.getInt(KEY_DISMISSED_STREAK, 0))
    }

    fun buildDynamicWatchlistPrompt(context: Context, nowMs: Long = System.currentTimeMillis()): String {
        val upcoming = list(context, includeClosed = false)
            .filter { it.dueAtMs <= nowMs + 36 * 3_600_000L }
            .take(3)
        if (upcoming.isEmpty()) return ""
        return buildString {
            append("[近期主动关注事项（如与当前话题相关可自然顺带跟进，无需生硬播报）]\n")
            upcoming.forEach { item ->
                val hrs = ((item.dueAtMs - nowMs) / 3_600_000L).coerceAtLeast(0L)
                val timing = if (hrs <= 0L) "已到跟进窗口" else "约 ${hrs}h 后"
                append("- ").append(item.title).append("（").append(timing).append("｜建议：").append(item.actionHint).append("）\n")
            }
        }.trim()
    }

    private fun persist(context: Context, items: List<WatchItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.json()) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, arr.toString())
            .apply()
    }
}
