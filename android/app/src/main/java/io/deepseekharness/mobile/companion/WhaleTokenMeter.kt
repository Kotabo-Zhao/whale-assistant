package io.deepseekharness.mobile.companion

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 本地轻量 Token 计费与额度计量器（零网络、零外部依赖）。
 *
 * 优先累计大模型 API 响应中返回的真实 `usage`（含 DeepSeek `prompt_cache_hit_tokens`），
 * 仅在第三方接口未返回 `usage` 时回退到字符估算；金额按主流模型公开单价估算供实时参考。
 */
internal object WhaleTokenMeter {
    private const val PREFS = "whale_token_meter_v1"
    private const val KEY_DAY = "day"
    private const val KEY_PROMPT = "prompt_tokens"
    private const val KEY_CACHE_HIT = "prompt_cache_hit_tokens"
    private const val KEY_COMPLETION = "completion_tokens"
    private const val KEY_COST_MICRO_YUAN = "cost_micro_yuan"
    private const val KEY_BUDGET = "daily_budget_tokens"
    private const val DEFAULT_DAILY_BUDGET = 200_000L

    data class Snapshot(
        val day: String,
        val promptTokens: Long,
        val cacheHitTokens: Long,
        val completionTokens: Long,
        val costMicroYuan: Long,
        val dailyBudgetTokens: Long,
    ) {
        val totalTokens: Long get() = promptTokens + completionTokens
        val progress: Float
            get() = if (dailyBudgetTokens <= 0L) 0f else (totalTokens.toFloat() / dailyBudgetTokens.toFloat()).coerceIn(0f, 1f)
        val estimatedYuan: Double
            get() = costMicroYuan / 1_000_000.0

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

    /** 按常见模型公开价格（元 / 百万 Token）返回 (未命中输入, 缓存命中输入, 输出)。 */
    private fun ratesPerMillionYuan(model: String): Triple<Double, Double, Double> {
        val m = model.lowercase(Locale.US)
        return when {
            "reasoner" in m || "r1" in m -> Triple(4.0, 1.0, 16.0)
            "deepseek" in m -> Triple(2.0, 0.5, 8.0)
            "gpt-4o-mini" in m || "flash" in m -> Triple(1.1, 0.55, 4.4)
            "gpt-4o" in m || "claude" in m -> Triple(18.0, 9.0, 72.0)
            else -> Triple(2.0, 0.5, 8.0)
        }
    }

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
                .putLong(KEY_CACHE_HIT, 0L)
                .putLong(KEY_COMPLETION, 0L)
                .putLong(KEY_COST_MICRO_YUAN, 0L)
                .apply()
            return Snapshot(today, 0L, 0L, 0L, 0L, budget)
        }
        return Snapshot(
            day = today,
            promptTokens = prefs.getLong(KEY_PROMPT, 0L).coerceAtLeast(0L),
            cacheHitTokens = prefs.getLong(KEY_CACHE_HIT, 0L).coerceAtLeast(0L),
            completionTokens = prefs.getLong(KEY_COMPLETION, 0L).coerceAtLeast(0L),
            costMicroYuan = prefs.getLong(KEY_COST_MICRO_YUAN, 0L).coerceAtLeast(0L),
            dailyBudgetTokens = budget,
        )
    }

    @Synchronized
    fun record(
        context: Context,
        promptTokens: Long,
        completionTokens: Long,
        cacheHitTokens: Long = 0L,
        model: String = "deepseek-chat",
    ): Snapshot {
        val addPrompt = promptTokens.coerceAtLeast(0L)
        val addCompletion = completionTokens.coerceAtLeast(0L)
        val addCacheHit = cacheHitTokens.coerceIn(0L, addPrompt)
        val current = snapshot(context)
        if (addPrompt == 0L && addCompletion == 0L) return current
        val (inRate, hitRate, outRate) = ratesPerMillionYuan(model)
        val missPrompt = (addPrompt - addCacheHit).coerceAtLeast(0L)
        val deltaYuan = (missPrompt * inRate + addCacheHit * hitRate + addCompletion * outRate) / 1_000_000.0
        val addMicroYuan = (deltaYuan * 1_000_000.0).toLong().coerceAtLeast(0L)
        val next = current.copy(
            promptTokens = current.promptTokens + addPrompt,
            cacheHitTokens = current.cacheHitTokens + addCacheHit,
            completionTokens = current.completionTokens + addCompletion,
            costMicroYuan = current.costMicroYuan + addMicroYuan,
        )
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DAY, next.day)
            .putLong(KEY_PROMPT, next.promptTokens)
            .putLong(KEY_CACHE_HIT, next.cacheHitTokens)
            .putLong(KEY_COMPLETION, next.completionTokens)
            .putLong(KEY_COST_MICRO_YUAN, next.costMicroYuan)
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
