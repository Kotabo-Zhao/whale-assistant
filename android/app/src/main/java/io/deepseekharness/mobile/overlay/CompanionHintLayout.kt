package io.deepseekharness.mobile.overlay

/** Computes an on-screen hint position from the measured bubble size. */
internal object CompanionHintLayout {
    data class Position(val x: Int, val y: Int)
    fun place(screenWidth: Int, screenHeight: Int, ballX: Int, ballY: Int, ballWidth: Int, ballHeight: Int,
              bubbleWidth: Int, bubbleHeight: Int, margin: Int, gap: Int): Position {
        require(screenWidth > 0 && screenHeight > 0 && ballWidth > 0 && ballHeight > 0 && bubbleWidth in 1..screenWidth && bubbleHeight in 1..screenHeight)
        require(margin >= 0 && gap >= 0)
        val safeMargin = minOf(margin, (screenHeight - bubbleHeight) / 2)
        val maxX = screenWidth - bubbleWidth
        val minY = safeMargin
        val maxY = screenHeight - bubbleHeight - safeMargin
        val x = (ballX.toLong() + ballWidth - bubbleWidth).coerceIn(0L, maxX.toLong()).toInt()
        val above = ballY.toLong() - gap - bubbleHeight
        val below = ballY.toLong() + ballHeight + gap
        val preferred = if (above >= minY) above else if (below <= maxY) below else above
        return Position(x, preferred.coerceIn(minY.toLong(), maxY.toLong()).toInt())
    }
}
