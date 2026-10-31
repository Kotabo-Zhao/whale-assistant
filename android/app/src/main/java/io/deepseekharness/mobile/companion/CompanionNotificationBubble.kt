package io.deepseekharness.mobile.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Compact, non-sensitive notification prompt; the caller owns click handling and positioning. */
internal object CompanionNotificationBubble {
    fun create(context: Context, title: String): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "$title，点击查看"
        }
        root.addView(EnvelopeIcon(context), LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        copy.addView(TextView(context).apply {
            text = title; textSize = 14f; setTextColor(Color.rgb(47, 64, 92))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
        })
        copy.addView(TextView(context).apply {
            text = "戳我看看  ›"; textSize = 12f; setTextColor(Color.rgb(75, 132, 178))
            setPadding(0, dp(3), 0, 0)
        })
        root.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        return root
    }

    private class EnvelopeIcon(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val s = minOf(width, height).toFloat()
            canvas.save(); canvas.scale(s / 38f, s / 38f)
            paint.color = Color.rgb(222, 240, 255); paint.style = Paint.Style.FILL
            canvas.drawCircle(19f, 19f, 19f, paint)
            paint.color = Color.WHITE
            canvas.drawRoundRect(RectF(8f, 11f, 30f, 27f), 4f, 4f, paint)
            paint.color = Color.rgb(98, 159, 204); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f
            canvas.drawRoundRect(RectF(8f, 11f, 30f, 27f), 4f, 4f, paint)
            path.reset(); path.moveTo(9f, 13f); path.lineTo(19f, 20f); path.lineTo(29f, 13f)
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL; paint.color = Color.rgb(246, 145, 172)
            canvas.drawCircle(28f, 10f, 4f, paint)
            canvas.restore()
        }
    }
}
