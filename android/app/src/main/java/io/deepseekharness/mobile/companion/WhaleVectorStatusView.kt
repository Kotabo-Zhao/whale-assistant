package io.deepseekharness.mobile.companion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Grok Bot 风格极简微光矢量动画状态指示器（60 FPS 原生 Canvas 几何动效）：
 * 7 种真实行为状态各具独立矢量动作（星花旋转、终端光标、声呐雷达、文档流光、星图脉冲、触控波纹、双齿轮咬合）。
 */
internal class WhaleVectorStatusView(context: Context) : View(context) {
    private val isUsagi = context.getSharedPreferences("whale-interface", 0).getString("style", "cute") == "usagi"
    private val baseColor = if (isUsagi) Color.parseColor("#F59E0B") else Color.parseColor("#2DD4BF")

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = baseColor
    }
    private val softPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = baseColor
        alpha = 96
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = baseColor
    }
    private val rect = RectF()
    private var t = 0f
    private var category = "thinking"

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    fun setCategoryColor(category: String) {
        this.category = category
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
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val r = s * 0.38f
        val sw = maxOf(1.6f, s * 0.088f)
        strokePaint.strokeWidth = sw
        softPaint.strokeWidth = sw * 0.85f
        val ang = t * 360f
        val rad = t * Math.PI * 2.0

        when (category) {
            "code" -> {
                // 极简终端框 + 动态闪烁光标
                rect.set(cx - r, cy - r * 0.85f, cx + r, cy + r * 0.85f)
                canvas.drawRoundRect(rect, s * 0.14f, s * 0.14f, softPaint)
                val px = cx - r * 0.45f
                canvas.drawLine(px, cy - r * 0.28f, px + r * 0.34f, cy, strokePaint)
                canvas.drawLine(px + r * 0.34f, cy, px, cy + r * 0.28f, strokePaint)
                strokePaint.alpha = (110 + 145 * (0.5 + 0.5 * sin(rad * 2.0))).toInt()
                canvas.drawLine(cx + r * 0.08f, cy + r * 0.28f, cx + r * 0.52f, cy + r * 0.28f, strokePaint)
                strokePaint.alpha = 255
            }
            "web" -> {
                // 极简声呐雷达圈 + 旋转扫频弧
                canvas.drawCircle(cx, cy, r, softPaint)
                canvas.drawCircle(cx, cy, r * 0.52f, softPaint)
                rect.set(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(rect, ang, 100f, false, strokePaint)
                val ox = cx + (cos(rad) * r).toFloat()
                val oy = cy + (sin(rad) * r).toFloat()
                canvas.drawCircle(ox, oy, sw * 0.9f, fillPaint)
            }
            "file" -> {
                // 极简文档轮廓 + 流动书写线
                rect.set(cx - r * 0.78f, cy - r, cx + r * 0.78f, cy + r)
                canvas.drawRoundRect(rect, s * 0.12f, s * 0.12f, softPaint)
                for (i in 0..2) {
                    val y = cy - r * 0.42f + i * r * 0.42f
                    val prog = ((t + i * 0.26f) % 1f)
                    val len = r * (0.35f + 0.55f * sin(prog * Math.PI).toFloat())
                    canvas.drawLine(cx - r * 0.42f, y, cx - r * 0.42f + len, y, strokePaint)
                }
            }
            "memory" -> {
                // 极简三节点星图 + 脉冲光点
                val pts = Array(3) { i ->
                    val a = rad * 0.5 + i * (Math.PI * 2.0 / 3.0)
                    Pair(cx + (cos(a) * r * 0.82f).toFloat(), cy + (sin(a) * r * 0.82f).toFloat())
                }
                for (i in 0..2) {
                    val j = (i + 1) % 3
                    canvas.drawLine(pts[i].first, pts[i].second, pts[j].first, pts[j].second, softPaint)
                }
                pts.forEachIndexed { i, (x, y) ->
                    val p = 0.7f + 0.45f * sin(rad * 2.0 + i * 2.0).toFloat()
                    canvas.drawCircle(x, y, sw * 0.85f * p, fillPaint)
                }
            }
            "phone" -> {
                // 极简触控扩散波纹
                for (i in 0..1) {
                    val prog = (t + i * 0.5f) % 1f
                    strokePaint.alpha = ((1f - prog) * 240).toInt().coerceIn(0, 255)
                    canvas.drawCircle(cx, cy, r * (0.25f + prog * 0.82f), strokePaint)
                }
                strokePaint.alpha = 255
                canvas.drawCircle(cx, cy, sw * 0.9f, fillPaint)
            }
            "worker" -> {
                // 极简双齿轮反旋咬合
                drawSpokes(canvas, cx - r * 0.34f, cy - r * 0.22f, r * 0.48f, ang, 6)
                drawSpokes(canvas, cx + r * 0.36f, cy + r * 0.26f, r * 0.38f, -ang * 1.25f, 6)
            }
            else -> {
                // Grok Bot 同款：丝滑呼吸旋转六瓣星花
                drawSpokes(canvas, cx, cy, r, ang * 0.85f, 6)
                val pulse = 0.78f + 0.3f * sin(rad * 2.0).toFloat()
                canvas.drawCircle(cx, cy, sw * 0.95f * pulse, fillPaint)
            }
        }
    }

    private fun drawSpokes(canvas: Canvas, cx: Float, cy: Float, radius: Float, deg: Float, count: Int) {
        canvas.save()
        canvas.rotate(deg, cx, cy)
        val inner = radius * 0.36f
        for (i in 0 until count) {
            val a = Math.toRadians(i * (360.0 / count))
            val wave = 0.78f + 0.22f * sin(t * Math.PI * 2.0 + i * 1.05).toFloat()
            val outer = radius * wave
            canvas.drawLine(
                cx + (cos(a) * inner).toFloat(),
                cy + (sin(a) * inner).toFloat(),
                cx + (cos(a) * outer).toFloat(),
                cy + (sin(a) * outer).toFloat(),
                strokePaint,
            )
        }
        canvas.restore()
    }
}
