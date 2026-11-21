package io.deepseekharness.mobile.companion

import java.util.Locale

/**
 * 智能三级模型路由器（结合复杂度识别 + 预算自适应）。
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
        // 仅在 DeepSeek 官方推理/高价模型上对超简短闲聊或高预算场景自动切回 deepseek-chat 省钱
        if (tier == Tier.FAST && lower in setOf("deepseek-reasoner", "deepseek-v4-pro", "deepseek-r1")) {
            return Decision(
                model = "deepseek-chat",
                tier = Tier.FAST,
                reason = if (dailyBudgetProgress >= 0.85f) "budget_guard_fast" else "quick_turn_fast",
            )
        }
        return Decision(model = m, tier = tier, reason = "configured")
    }
}
