package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * 纯模型档位选择器（Model Tier Selector：仅负责选模型与预算/高峰降档；执行模式与 Worker 生命周期调度统一归 [WhaleExecutionRouter]）。
 *
 * - L1 极速省钱档：短问候、轻量提醒、单步查询，或当日预算已超 80% 时的安全降级；
 * - L2 深度推理档：多步规划、代码/工坊创作、长文分析、多模态看图；
 * - 绝不篡改用户自定义的未知中转模型名，仅对已知同厂商官方模型家族做安全升降档。
 */
internal object WhaleModelRouter {
    enum class Tier { FAST, BALANCED, DEEP }

    data class Decision(
        val model: String,
        val tier: Tier,
        val reason: String,
    )

    private val deepHints = listOf(
        "架构", "重构", "规划", "方案", "对比", "深度", "分析", "代码", "算法", "写一个",
        "工坊", "复盘", "总结全文", "translate", "architecture", "debug", "refactor", "plan",
    )

    fun classify(query: String, hasImages: Boolean, dailyBudgetProgress: Float): Tier {
        val q = query.trim().lowercase(Locale.US)
        if (hasImages) return Tier.DEEP
        if (dailyBudgetProgress >= 0.85f && q.length < 180) return Tier.FAST
        if (q.length >= 160 || deepHints.any { it in q }) return Tier.DEEP
        if (q.length <= 24 && !q.contains("http")) return Tier.FAST
        return Tier.BALANCED
    }

    fun route(configuredModel: String, query: String, hasImages: Boolean, dailyBudgetProgress: Float): Decision {
        val tier = classify(query, hasImages, dailyBudgetProgress)
        val m = configuredModel.trim()
        val lower = m.lowercase(Locale.US)
        val peak = WhaleTokenMeter.isDeepSeekPeakUtc()
        // 在 DeepSeek 官方高价推理模型上：简短闲聊、预算超 85% 或工作日 2× 高峰期非深度任务自动切回 deepseek-chat 省钱
        if ((tier == Tier.FAST || (peak && tier == Tier.BALANCED)) && lower in setOf("deepseek-reasoner", "deepseek-v4-pro", "deepseek-r1")) {
            return Decision(
                model = "deepseek-chat",
                tier = Tier.FAST,
                reason = when {
                    dailyBudgetProgress >= 0.85f -> "budget_guard_fast"
                    peak -> "deepseek_peak_guard_fast"
                    else -> "quick_turn_fast"
                },
            )
        }
        return Decision(model = m, tier = tier, reason = "configured")
    }

    private const val PREFS = "whale_model_router_v1"

    fun readSceneOverrides(context: Context): Pair<String, String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("fast_model", "").orEmpty().trim() to prefs.getString("deep_model", "").orEmpty().trim()
    }

    fun saveSceneOverrides(context: Context, fastModel: String, deepModel: String): JSONObject {
        val fast = fastModel.trim().take(120)
        val deep = deepModel.trim().take(120)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("fast_model", fast)
            .putString("deep_model", deep)
            .apply()
        return JSONObject().put("fastModel", fast).put("deepModel", deep)
    }

    fun routeWithPrefs(
        context: Context,
        configuredModel: String,
        query: String,
        hasImages: Boolean,
        dailyBudgetProgress: Float,
    ): Decision {
        val base = route(configuredModel, query, hasImages, dailyBudgetProgress)
        val (fastOverride, deepOverride) = runCatching { readSceneOverrides(context) }.getOrDefault("" to "")
        return when {
            base.tier == Tier.FAST && fastOverride.isNotEmpty() -> base.copy(model = fastOverride, reason = "user_fast_scene")
            base.tier == Tier.DEEP && deepOverride.isNotEmpty() -> base.copy(model = deepOverride, reason = "user_deep_scene")
            else -> base
        }
    }

    /** 内部摘要/上下文压缩/记忆整理专用极速低价模型路由。 */
    fun routeForInternalTask(context: Context, configuredModel: String): String {
        val (fastOverride, _) = runCatching { readSceneOverrides(context) }.getOrDefault("" to "")
        if (fastOverride.isNotEmpty()) return fastOverride
        val m = configuredModel.trim()
        val lower = m.lowercase(Locale.US)
        return when {
            lower in setOf("deepseek-reasoner", "deepseek-v4-pro", "deepseek-r1") -> "deepseek-chat"
            lower.startsWith("gpt-4o") && !lower.contains("mini") -> "gpt-4o-mini"
            lower.contains("claude") && (lower.contains("opus") || lower.contains("sonnet")) -> "claude-haiku-4-5"
            else -> m
        }
    }
}
