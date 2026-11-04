package io.deepseekharness.mobile.overlay

/** Safe screen band for one menu next to the pet. All coordinates are physical pixels. */
object OverlayBallSecondaryPolicy {
    data class Band(val x: Int, val top: Int, val bottom: Int, val anchorY: Int) {
        val maxHeight: Int get() = (bottom - top).coerceAtLeast(1)
        fun yFor(height: Int): Int = anchorY.coerceIn(top, (bottom - height).coerceAtLeast(top))
    }

    fun menuBand(
        petX: Int, petY: Int, petWidth: Int, petHeight: Int, menuWidth: Int,
        screenWidth: Int, top: Int, bottom: Int, gap: Int,
    ): Band {
        val right = petX + petWidth + gap
        val left = petX - gap - menuWidth
        if (right + menuWidth <= screenWidth - gap) return Band(right, top, bottom, petY)
        if (left >= gap) return Band(left, top, bottom, petY)
        // On narrow screens use the larger space above or below the pet. The menu can scroll.
        val endX = (screenWidth - menuWidth).coerceAtLeast(0)
        val margin = minOf(gap, endX / 2)
        val x = petX.coerceIn(margin, endX - margin)
        val above = (petY - gap).coerceIn(top, bottom)
        val below = (petY + petHeight + gap).coerceIn(top, bottom)
        if (above == top && below == bottom) return Band(x, top, bottom, petY)
        return if (above - top >= bottom - below) Band(x, top, above, above)
        else Band(x, below, bottom, below)
    }

    enum class AccessibilityAction { DISABLE_SELF, ALREADY_OFF }

    fun accessibilityState(serviceConnected: Boolean): AccessibilityAction =
        if (serviceConnected) AccessibilityAction.DISABLE_SELF else AccessibilityAction.ALREADY_OFF
}

/**
 * 对话小窗尺寸的纯策略。
 *
 * 尺寸以**像素**为单位存盘并由调用方换算，理由见类注释；这里只关心三件事：
 * 夹取、手柄几何、以及「存过的那一组尺寸在当前屏幕下还算不算数」。
 *
 * 一条刻意选择的取舍：**最大尺寸用 90% 的比例，但最小值是硬下限**。
 * 屏幕极小（分屏、平板自由窗口、外接小屏）时二者会冲突，此时下限优先 ——
 * 一个 90% 之下的窗口装不下对话内容，而一个略超比例但可用的窗口是用户要的。
 * 冲突的下限只有 [MIN_WIDTH_DP] / [MIN_HEIGHT_DP] 两条，因此不会失控。
 */
object OverlayConversationSizePolicy {

    /** 最小可用的宽度（dp）。保留标题、输入框和正文的基本操作空间。 */
    const val MIN_WIDTH_DP = 200

    /** 最小可用的高度（dp）。 */
    const val MIN_HEIGHT_DP = 300

    /** 在未指定自定义尺寸时的默认值（dp），与改动前的固定尺寸一致。 */
    const val DEFAULT_WIDTH_DP = 360

    const val DEFAULT_HEIGHT_DP = 560

    /** 窗口最多占屏幕可用区域的比例。 */
    const val MAX_SCREEN_RATIO = 0.9f

    /**
     * 缩放手柄的可点击边长（dp）。
     *
     * 等于二级球的下限：低于 40dp 在真机上会明显「点不中」，而手柄是拖动起点，
     * 点不中的代价是用户以为这个功能坏了。
     */
    const val HANDLE_TOUCH_SIZE_DP = 44

    /** 手柄的可视圆点直径（dp），居中放在 44dp 的触摸目标里。 */
    const val HANDLE_VISUAL_SIZE_DP = 28

    /** 手柄距面板右下角的视觉留白（dp）。 */
    const val HANDLE_INSET_DP = 4

    /** 存盘的尺寸是否还配得上当前屏幕。 */
    enum class Stored {
        /** 可用：调用方应当直接采用并夹取。 */
        USABLE,

        /** 没有存过（或存了半截）：用默认值。 */
        ABSENT,

        /** 存过，但屏幕换了（旋转、分屏、外接屏）：用默认值，避免一张「横屏时的宽窗」竖屏变成怪形状。 */
        STALE,
    }

    /** 夹取后的宽度：不小于最小宽度，也不超过可用区域的 [MAX_SCREEN_RATIO]。 */
    fun clampWidth(
        width: Int,
        availableWidth: Int,
        minWidth: Int = MIN_WIDTH_DP,
        maxRatio: Float = MAX_SCREEN_RATIO,
    ): Int {
        val ratioMax = (availableWidth * maxRatio).toInt()
        val max = if (ratioMax < minWidth) minWidth else ratioMax
        return width.coerceIn(minWidth, max)
    }

    /** 夹取后的高度，规则同 [clampWidth]。 */
    fun clampHeight(
        height: Int,
        availableHeight: Int,
        minHeight: Int = MIN_HEIGHT_DP,
        maxRatio: Float = MAX_SCREEN_RATIO,
    ): Int {
        val ratioMax = (availableHeight * maxRatio).toInt()
        val max = if (ratioMax < minHeight) minHeight else ratioMax
        return height.coerceIn(minHeight, max)
    }

    /**
     * 手柄的左上角坐标（相对面板窗口）。
     *
     * 放在右下角：那是「放大」方向最自然的起点，也是用户对缩放手柄的既有预期。
     * [HANDLE_INSET_DP] 让它稍微内缩，避免与系统手势条区域重叠。
     */
    fun handlePosition(
        panelWidth: Int,
        panelHeight: Int,
        handleSize: Int,
        inset: Int,
    ): Pair<Int, Int> {
        val x = (panelWidth - handleSize - inset).coerceAtLeast(0)
        val y = (panelHeight - handleSize - inset).coerceAtLeast(0)
        return x to y
    }

    /**
     * 手柄是否落在某个点附近（用于判断一次按下是否起手于手柄）。
     *
     * 用中心点 + 半径而不是矩形包含：手柄是圆点，矩形判定会让「贴着圆点外侧一点」
     * 也算命中，而那里其实是面板内容。
     */
    fun hitsHandle(
        touchX: Float,
        touchY: Float,
        handleX: Int,
        handleY: Int,
        handleSize: Int,
        tolerance: Float = 0f,
    ): Boolean {
        val centerX = handleX + handleSize / 2f
        val centerY = handleY + handleSize / 2f
        val radius = handleSize / 2f + tolerance
        val dx = touchX - centerX
        val dy = touchY - centerY
        return dx * dx + dy * dy <= radius * radius
    }

    /**
     * 拖动后的新尺寸：以「按下时的尺寸 + 手指位移」计算，并夹取到合法范围。
     *
     * 以**按下时的尺寸**而不是当前尺寸为基准，是为了让拖动可逆：
     * 用当前尺寸逐帧累加会把夹取结果也当成用户意图，手指回到原处时窗口却回不到原大小。
     */
    fun resized(
        startWidth: Int,
        startHeight: Int,
        deltaX: Int,
        deltaY: Int,
        availableWidth: Int,
        availableHeight: Int,
        minWidth: Int = MIN_WIDTH_DP,
        minHeight: Int = MIN_HEIGHT_DP,
        maxRatio: Float = MAX_SCREEN_RATIO,
    ): Pair<Int, Int> = clampWidth(startWidth + deltaX, availableWidth, minWidth, maxRatio) to
        clampHeight(startHeight + deltaY, availableHeight, minHeight, maxRatio)

    /**
     * 判定存盘的尺寸在当前屏幕下还算不算数。
     *
     * 判定依据是**存盘时记录的屏幕宽高**是否与当前一致：一致就直接用（用户上次调好的大小
     * 就是他想要的），不一致就退回默认值（那多半是旋转或分屏，硬套过去会得到怪形状）。
     */
    fun storedState(
        storedWidth: Int?,
        storedHeight: Int?,
        storedScreenWidth: Int?,
        storedScreenHeight: Int?,
        screenWidth: Int,
        screenHeight: Int,
    ): Stored {
        if (storedWidth == null || storedHeight == null) return Stored.ABSENT
        if (storedWidth <= 0 || storedHeight <= 0) return Stored.ABSENT
        if (storedScreenWidth == null || storedScreenHeight == null) return Stored.STALE
        return if (storedScreenWidth == screenWidth && storedScreenHeight == screenHeight) {
            Stored.USABLE
        } else {
            Stored.STALE
        }
    }
}
