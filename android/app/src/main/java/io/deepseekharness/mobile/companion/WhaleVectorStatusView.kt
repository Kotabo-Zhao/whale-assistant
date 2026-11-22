package io.deepseekharness.mobile.companion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * 桌宠气泡专用原生矢量动画状态指示器（60 FPS Canvas 矢量双环轨道 + 呼吸核心点，零静态表情）。
 */
internal class WhaleVectorStatusView(context: Context) : View(context) {
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.4f * resources.displayMetrics.density
        color = Color.parseColor("#38BDF8")
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.0f * resources.displayMetrics.density
        color = Color.parseColor("#818CF8")
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#60A5FA")
    }
    private val outerRect = RectF()
    private val innerRect = RectF()
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1600L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun setCategoryColor(category: String) {
        val hex = when (category) {
            "code" -> "#34D399"
            "web" -> "#38BDF8"
            "file" -> "#F59E0B"
            "memory" -> "#A78BFA"
            "phone" -> "#FB7185"
            "worker" -> "#22D3EE"
            else -> "#60A5FA"
        }
        outerPaint.color = Color.parseColor(hex)
        corePaint.color = Color.parseColor(hex)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val size = minOf(w, h)
        if (size <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val padOuter = outerPaint.strokeWidth
        val rOuter = (size / 2f) - padOuter
        outerRect.set(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter)
        canvas.drawArc(outerRect, phase, 220f, false, outerPaint)

        val rInner = rOuter * 0.62f
        innerRect.set(cx - rInner, cy - rInner, cx + rInner, cy + rInner)
        canvas.drawArc(innerRect, -phase * 1.4f, 160f, false, innerPaint)

        val pulse = 0.72f + 0.28f * sin(Math.toRadians((phase * 2f).toDouble())).toFloat()
        canvas.drawCircle(cx, cy, rOuter * 0.24f * pulse, corePaint)
    }
}
