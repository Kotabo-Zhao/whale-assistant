package io.deepseekharness.mobile.companion

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** A single outlined speech balloon, including a small pointer toward its speaker. */
internal class SpeechBubbleDrawable(
    private val fillColor: Int,
    private val density: Float,
    private val tailAtTop: Boolean,
    private val tailAtRight: Boolean,
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor; style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(151, 195, 216); style = Paint.Style.STROKE; strokeWidth = density
        strokeJoin = Paint.Join.ROUND
    }
    private val shape = Path()

    override fun draw(canvas: Canvas) {
        val inset = density
        val tail = 8f * density
        val left = bounds.left + inset
        val right = bounds.right - inset
        val top = bounds.top + inset + if (tailAtTop) tail else 0f
        val bottom = bounds.bottom - inset - if (tailAtTop) 0f else tail
        if (right <= left || bottom <= top) return
        val radius = minOf(15f * density, (right - left) / 2f, (bottom - top) / 2f)
        val tailX = if (tailAtRight) right - minOf(22f * density, (right - left) / 2f) else left + minOf(22f * density, (right - left) / 2f)
        val center = tailX.coerceIn(left + radius, right - radius)
        val triangle = Path().apply {
            if (tailAtTop) {
                moveTo(center - 7f * density, top + density)
                lineTo(center + 7f * density, top + density)
                lineTo(center + (if (tailAtRight) 5f else -5f) * density, top - tail)
            } else {
                moveTo(center - 7f * density, bottom - density)
                lineTo(center + 7f * density, bottom - density)
                lineTo(center + (if (tailAtRight) 5f else -5f) * density, bottom + tail)
            }
            close()
        }
        shape.reset()
        shape.addRoundRect(RectF(left, top, right, bottom), radius, radius, Path.Direction.CW)
        shape.op(triangle, Path.Op.UNION)
        canvas.drawPath(shape, fill)
        canvas.drawPath(shape, outline)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; outline.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; outline.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
