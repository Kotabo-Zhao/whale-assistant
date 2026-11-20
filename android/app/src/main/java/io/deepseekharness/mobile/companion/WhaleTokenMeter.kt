package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 本地精准 Token 计费与额度计量器（零网络、零外部依赖）。
 *
 * - 兼容 DeepSeek (`prompt_cache_hit_tokens`)、OpenAI/SiliconFlow/Qwen/GLM (`prompt_tokens_details.cached_tokens`)
 *   与 Anthropic (`cache_read_input_tokens`) 三种缓存命中上报格式。
 * - 按 2026-11 主流模型公开单价（折合人民币元 / 百万 Token，含 DeepSeek 工作日峰谷时段 2× 区分）实时核算。
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
    private const val USD_TO_CNY = 7.20

    data class Snapshot(
        val day: String,
        val promptTokens: Long,
        val cacheHitTokens: Long,
        val completionTokens: Long,
        val costMicroYuan: Long,
        val dailyBudgetTokens: Long,
    ) {
        val totalTokens: Long get() = promptTokens + completionTokens
        val cacheHitRatio: Float
            get() = if (promptTokens <= 0L) 0f else (cacheHitTokens.toFloat() / promptTokens.toFloat()).coerceIn(0f, 1f)
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
                String.format(Locale.US, "🐳 %s · ¥%.2f", tokenStr, yuan)
            } else {
                "🐳 $tokenStr"
            }
        }
    }

    private val listeners = CopyOnWriteArraySet<(Snapshot) -> Unit>()

    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** DeepSeek 高峰时段：周一至周五 UTC 01:00–04:00 及 06:00–10:00（价格为非高峰的 2 倍）。 */
    fun isDeepSeekPeakUtc(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMillis }
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return hour in 1..3 || hour in 6..9
    }

    /** 统一提取各厂商 API `usage` 中的缓存命中 Token 数。 */
    fun extractCacheHitTokens(usageObj: JSONObject): Long {
        val direct = usageObj.optLong("prompt_cache_hit_tokens", 0L)
        if (direct > 0L) return direct
        val details = usageObj.optJSONObject("prompt_tokens_details")
        val openaiCached = details?.optLong("cached_tokens", 0L) ?: 0L
        if (openaiCached > 0L) return openaiCached
        val anthropicCached = usageObj.optLong("cache_read_input_tokens", 0L)
        return anthropicCached.coerceAtLeast(0L)
    }

    /**
     * 按 2026-11 主流模型公开单价（人民币元 / 百万 Token）返回 (未命中输入, 缓存命中输入, 输出)。
     */
    fun ratesPerMillionYuan(model: String, nowMillis: Long = System.currentTimeMillis()): Triple<Double, Double, Double> {
        val m = model.lowercase(Locale.US)
        return when {
            // DeepSeek V4 Pro / Reasoner / R1 ($0.66 / $0.022 / $1.98 off-peak, 2x peak)
            "deepseek" in m && ("pro" in m || "reasoner" in m || "r1" in m) -> {
                val mult = if (isDeepSeekPeakUtc(nowMillis)) 2.0 else 1.0
                Triple(0.66 * USD_TO_CNY * mult, 0.022 * USD_TO_CNY * mult, 1.98 * USD_TO_CNY * mult)
            }
            // DeepSeek V4.1 Flash / Chat ($0.15 / $0.003 / $0.60 off-peak, 2x peak)
            "deepseek" in m -> {
                val mult = if (isDeepSeekPeakUtc(nowMillis)) 2.0 else 1.0
                Triple(0.15 * USD_TO_CNY * mult, 0.003 * USD_TO_CNY * mult, 0.60 * USD_TO_CNY * mult)
            }
            // Claude Haiku 5 / 5.5 ($0.10 / $0.01 / $0.50) vs Haiku 4.5 ($1 / $0.10 / $5)
            "claude" in m && "haiku" in m -> {
                if ("5" in m && "4.5" !in m) Triple(0.10 * USD_TO_CNY, 0.01 * USD_TO_CNY, 0.50 * USD_TO_CNY)
                else Triple(1.0 * USD_TO_CNY, 0.10 * USD_TO_CNY, 5.0 * USD_TO_CNY)
            }
            // Claude Opus 5.5 ($4 / $0.20 / $20) vs Opus 4.x ($5 / $0.50 / $25)
            "claude" in m && "opus" in m -> {
                if ("5" in m) Triple(4.0 * USD_TO_CNY, 0.20 * USD_TO_CNY, 20.0 * USD_TO_CNY)
                else Triple(5.0 * USD_TO_CNY, 0.50 * USD_TO_CNY, 25.0 * USD_TO_CNY)
            }
            // Claude Sonnet 5 / 5.5 ($2 / $0.10 / $10) vs Sonnet 4.x ($3 / $0.30 / $15)
            "claude" in m -> {
                if ("5" in m && "4.5" !in m) Triple(2.0 * USD_TO_CNY, 0.10 * USD_TO_CNY, 10.0 * USD_TO_CNY)
                else Triple(3.0 * USD_TO_CNY, 0.30 * USD_TO_CNY, 15.0 * USD_TO_CNY)
            }
            // GPT-4o-mini / mini / nano ($0.15 / $0.075 / $0.60)
            "mini" in m || "nano" in m -> Triple(0.15 * USD_TO_CNY, 0.075 * USD_TO_CNY, 0.60 * USD_TO_CNY)
            // GPT-4o / GPT-4.1 ($2.50 / $1.25 / $10.00)
            "gpt-4" in m || "gpt-5" in m -> Triple(2.50 * USD_TO_CNY, 1.25 * USD_TO_CNY, 10.0 * USD_TO_CNY)
            // Gemini Flash ($0.15 / $0.0375 / $0.60)
            "flash" in m -> Triple(0.15 * USD_TO_CNY, 0.0375 * USD_TO_CNY, 0.60 * USD_TO_CNY)
            // Qwen / GLM / Kimi / Moonshot / Doubao domestic models
            "qwen" in m || "glm" in m || "kimi" in m || "moonshot" in m || "doubao" in m -> Triple(1.0, 0.2, 4.0)
            else -> Triple(1.08, 0.10, 4.32)
        }
    }

    fun estimateCostMicroYuan(
        promptTokens: Long,
        completionTokens: Long,
        cacheHitTokens: Long,
        model: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        val addPrompt = promptTokens.coerceAtLeast(0L)
        val addCompletion = completionTokens.coerceAtLeast(0L)
        val addCacheHit = cacheHitTokens.coerceIn(0L, addPrompt)
        val (inRate, hitRate, outRate) = ratesPerMillionYuan(model, nowMillis)
        val missPrompt = (addPrompt - addCacheHit).coerceAtLeast(0L)
        val deltaYuan = (missPrompt * inRate + addCacheHit * hitRate + addCompletion * outRate) / 1_000_000.0
        return (deltaYuan * 1_000_000.0).toLong().coerceAtLeast(0L)
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
        val addMicroYuan = estimateCostMicroYuan(addPrompt, addCompletion, addCacheHit, model)
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
