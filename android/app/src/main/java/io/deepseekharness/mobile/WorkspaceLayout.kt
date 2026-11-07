package io.deepseekharness.mobile

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import kotlin.math.roundToInt

/** Use the measured window for full pages, including split-screen and floating activities. */
internal class WorkspaceColumn(context: Context, private val verticalDp: Int = 14) : LinearLayout(context) {
    init { orientation = VERTICAL }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val density = resources.displayMetrics.density
        val edge = (width * .026f).coerceIn(14 * density, 32 * density)
        val gutter = maxOf(edge, (width - 1040 * density) / 2).roundToInt()
        val vertical = (verticalDp * density).roundToInt()
        if (paddingLeft != gutter || paddingTop != vertical) setPadding(gutter, vertical, gutter, vertical)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** Reflow the same cards when the available width or the user's font size changes. */
internal class WorkspaceCardGrid(context: Context, private val cards: List<View>) : LinearLayout(context) {
    private var columns = 0
    init { orientation = VERTICAL }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val fontScale = maxOf(1f, resources.configuration.fontScale)
        val next = if (cards.size > 1 && MeasureSpec.getSize(widthMeasureSpec) >= 560 * density * fontScale) 2 else 1
        if (next != columns) {
            columns = next
            cards.forEach { (it.parent as? ViewGroup)?.removeView(it) }
            removeAllViews()
            val gap = (10 * density).roundToInt()
            cards.chunked(columns).forEachIndexed { index, items ->
                val row = LinearLayout(context).apply { orientation = HORIZONTAL }
                repeat(columns) { cell ->
                    row.addView(items.getOrNull(cell) ?: Space(context), LayoutParams(0, -2, 1f).apply {
                        if (cell > 0) marginStart = gap
                    })
                }
                addView(row, LayoutParams(-1, -2).apply { if (index > 0) topMargin = gap })
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
