package io.deepseekharness.mobile.companion

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 本地轻量 Token 计费与额度计量器（零网络、零外部依赖）。
 *
 * 按自然日累计输入/输出 Token 与估算费用，实时推送给桌宠头顶的可爱进度条。
 */
internal object WhaleTokenMeter {
    private const val PREFS = "whale_token_meter_v1"
    private const val KEY_DAY = "day"
    private const val KEY_PROMPT = "prompt_tokens"
    private const val KEY_COMPLETION = "completion_tokens"
    private const val KEY_BUDGET = "daily_budget_tokens"
    private const val DEFAULT_DAILY_BUDGET = 200_000L

    // 默认按 DeepSeek 主流模型综合单价估算：输入约 ¥2/1M，输出约 ¥8/1M
    private const val INPUT_YUAN_PER_MILLION = 2.0
    private const val OUTPUT_YUAN_PER_MILLION = 8.0

    data class Snapshot(
        val day: String,
        val promptTokens: Long,
        val completionTokens: Long,
        val dailyBudgetTokens: Long,
    ) {
        val totalTokens: Long get() = promptTokens + completionTokens
        val progress: Float
            get() = if (dailyBudgetTokens <= 0L) 0f else (totalTokens.toFloat() / dailyBudgetTokens.toFloat()).coerceIn(0f, 1f)
        val estimatedYuan: Double
            get() = (promptTokens * INPUT_YUAN_PER_MILLION + completionTokens * OUTPUT_YUAN_PER_MILLION) / 1_000_000.0

        fun compactLabel(): String {
            val tokens = totalTokens
            val tokenStr = when {
                tokens >= 1_000_000L -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
                tokens >= 1_000L -> String.format(Locale.US, "%.1fk", tokens / 1_000.0)
                else -> tokens.toString()
            }
            val yuan = estimatedYuan
            return if (yuan >= 0.01) {
                String.format(Locale.US, "✨ %s · ¥%.2f", tokenStr, yuan)
            } else {
                "✨ $tokenStr"
            }
        }
    }

    private val listeners = CopyOnWriteArraySet<(Snapshot) -> Unit>()

    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    @Synchronized
    fun snapshot(context: Context): Snapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayKey()
        val savedDay = prefs.getString(KEY_DAY, "") ?: ""
        val budget = prefs.getLong(KEY_BUDGET, DEFAULT_DAILY_BUDGET).coerceAtLeast(10_000L)
        if (savedDay != today) {
            prefs.edit()
                .putString(KEY_DAY, today)
                .putLong(KEY_PROMPT, 0L)
                .putLong(KEY_COMPLETION, 0L)
                .apply()
            return Snapshot(today, 0L, 0L, budget)
        }
        return Snapshot(
            day = today,
            promptTokens = prefs.getLong(KEY_PROMPT, 0L).coerceAtLeast(0L),
            completionTokens = prefs.getLong(KEY_COMPLETION, 0L).coerceAtLeast(0L),
            dailyBudgetTokens = budget,
        )
    }

    @Synchronized
    fun record(context: Context, promptTokens: Long, completionTokens: Long): Snapshot {
        val addPrompt = promptTokens.coerceAtLeast(0L)
        val addCompletion = completionTokens.coerceAtLeast(0L)
        val current = snapshot(context)
        if (addPrompt == 0L && addCompletion == 0L) return current
        val next = current.copy(
            promptTokens = current.promptTokens + addPrompt,
            completionTokens = current.completionTokens + addCompletion,
        )
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DAY, next.day)
            .putLong(KEY_PROMPT, next.promptTokens)
            .putLong(KEY_COMPLETION, next.completionTokens)
            .apply()
        listeners.forEach { runCatching { it(next) } }
        return next
    }

    fun subscribe(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }
}
