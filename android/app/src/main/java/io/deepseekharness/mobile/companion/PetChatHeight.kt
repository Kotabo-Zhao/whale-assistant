package io.deepseekharness.mobile.companion

/**
 * 自适应底部抽屉高度策略（A+B 融合形态）：
 * - 空会话/短句速记时保持轻盈紧凑，不遮挡上方正在使用的 App；
 * - 有多轮对话或长回复时自动舒展至竖屏 68%（大窗模式 88%），不再被桌宠位置（nearPet）卡死高度；
 * - 键盘弹起时始终保障输入框与可读消息区空间。
 */
internal object PetChatHeight {
    fun controls(available: Int, mandatory: Int, density: Float): Int =
        minOf((208 * density).toInt(), available / 3, (available - mandatory - (72 * density).toInt()).coerceAtLeast(1)).coerceAtLeast(1)

    fun total(
        available: Int,
        @Suppress("UNUSED_PARAMETER") nearPet: Int,
        chrome: Int,
        natural: Int,
        mode: String,
        portrait: Boolean,
        keyboard: Boolean,
        tool: Boolean,
        density: Float,
    ): Int {
        val space = available.coerceAtLeast(1)
        val minimum = ((if (tool) 180 else if (keyboard) 64 else 88) * density).toInt().coerceAtLeast(1)
        val minimumTotal = (chrome + minimum).coerceAtMost(space)
        val fraction = when {
            tool -> if (portrait) .78f else .88f
            mode == "small" -> .46f
            mode == "large" -> .88f
            portrait && keyboard -> .88f
            portrait -> .68f
            else -> .82f
        }
        var ceiling = (space * fraction).toInt()
        if (portrait && mode == "auto" && !keyboard && !tool) {
            ceiling = minOf(ceiling, (640 * density).toInt())
        }
        val requested = if (mode == "auto" || tool) minOf(ceiling, chrome + natural.coerceAtLeast(minimum)) else ceiling
        return requested.coerceIn(minimumTotal, space)
    }
}
