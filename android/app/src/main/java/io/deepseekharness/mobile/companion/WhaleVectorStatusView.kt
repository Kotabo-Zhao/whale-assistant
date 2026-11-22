package io.deepseekharness.mobile.companion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * 桌宠端 3 套主题 × 10 种状态独立极简微光矢量动画（60 FPS 原生 Canvas）：
 * - usagi（乌萨奇）：暖金长兔耳/兔耳气泡/讨伐棒星芒/金星/小旗
 * - cute（鲸鱼娘）：海蓝鲸尾水泡/双层海浪/航海罗盘/珍珠光晕
 * - simple（极简）：Grok 同款六瓣星花/菱形棱镜/声呐雷达/双齿轮
 */
internal class WhaleVectorStatusView(context: Context) : View(context) {
    private val stylePref = context.getSharedPreferences("whale-interface", 0).getString("style", "cute") ?: "cute"
    private val baseColor = when (stylePref) {
        "usagi" -> Color.parseColor("#F59E0B")
        "cute" -> Color.parseColor("#38BDF8")
        else -> Color.parseColor("#2DD4BF")
    }

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
    private val path = Path()
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
        val rock = (sin(rad) * 8.0).toFloat()

        when (category) {
            "code" -> {
                rect.set(cx - r, cy - r * 0.82f, cx + r, cy + r * 0.82f)
                canvas.drawRoundRect(rect, s * 0.14f, s * 0.14f, softPaint)
                val px = cx - r * 0.45f
                canvas.drawLine(px, cy - r * 0.28f, px + r * 0.34f, cy, strokePaint)
                canvas.drawLine(px + r * 0.34f, cy, px, cy + r * 0.28f, strokePaint)
                strokePaint.alpha = (100 + 155 * (0.5 + 0.5 * sin(rad * 2.0))).toInt()
                canvas.drawLine(cx + r * 0.08f, cy + r * 0.28f, cx + r * 0.52f, cy + r * 0.28f, strokePaint)
                strokePaint.alpha = 255
            }
            "web" -> {
                canvas.drawCircle(cx, cy, r, softPaint)
                if (stylePref == "cute") {
                    canvas.save(); canvas.rotate(ang, cx, cy)
                    path.reset()
                    path.moveTo(cx, cy - r * 0.78f); path.lineTo(cx + r * 0.28f, cy)
                    path.lineTo(cx, cy + r * 0.78f); path.lineTo(cx - r * 0.28f, cy); path.close()
                    canvas.drawPath(path, strokePaint)
                    canvas.restore()
                } else {
                    rect.set(cx - r, cy - r, cx + r, cy + r)
                    canvas.drawArc(rect, ang, 100f, false, strokePaint)
                    canvas.drawCircle(cx + (cos(rad) * r).toFloat(), cy + (sin(rad) * r).toFloat(), sw * 0.9f, fillPaint)
                }
            }
            "file" -> {
                rect.set(cx - r * 0.78f, cy - r, cx + r * 0.78f, cy + r)
                canvas.drawRoundRect(rect, s * 0.12f, s * 0.12f, softPaint)
                for (i in 0..2) {
                    val y = cy - r * 0.42f + i * r * 0.42f
                    val prog = ((t + i * 0.26f) % 1f)
                    val len = r * (0.35f + 0.55f * sin(prog * Math.PI).toFloat())
                    canvas.drawLine(cx - r * 0.42f, y, cx - r * 0.42f + len, y, strokePaint)
                }
            }
            "tasks" -> {
                canvas.drawCircle(cx, cy, r * 0.92f, softPaint)
                val pulse = 0.65f + 0.35f * sin(rad).toFloat()
                strokePaint.alpha = (160 + 95 * pulse).toInt().coerceIn(0, 255)
                canvas.drawLine(cx - r * 0.42f, cy + r * 0.02f, cx - r * 0.1f, cy + r * 0.36f, strokePaint)
                canvas.drawLine(cx - r * 0.1f, cy + r * 0.36f, cx + r * 0.46f, cy - r * 0.32f, strokePaint)
                strokePaint.alpha = 255
            }
            "memory" -> {
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
                for (i in 0..1) {
                    val prog = (t + i * 0.5f) % 1f
                    strokePaint.alpha = ((1f - prog) * 240).toInt().coerceIn(0, 255)
                    canvas.drawCircle(cx, cy, r * (0.25f + prog * 0.82f), strokePaint)
                }
                strokePaint.alpha = 255
                canvas.drawCircle(cx, cy, sw * 0.9f, fillPaint)
            }
            "worker" -> {
                drawSpokes(canvas, cx - r * 0.34f, cy - r * 0.22f, r * 0.48f, ang, 6)
                drawSpokes(canvas, cx + r * 0.36f, cy + r * 0.26f, r * 0.38f, -ang * 1.25f, 6)
            }
            "analyzing", "reasoning" -> {
                if (stylePref == "usagi") {
                    // 乌萨奇兔耳气泡 + 跳动三点
                    rect.set(cx - r * 0.9f, cy - r * 0.45f, cx + r * 0.9f, cy + r * 0.78f)
                    canvas.drawRoundRect(rect, r * 0.45f, r * 0.45f, strokePaint)
                    for (i in -1..1) {
                        val lift = sin(rad * 2.0 + i * 1.1).toFloat() * r * 0.14f
                        canvas.drawCircle(cx + i * r * 0.4f, cy + r * 0.16f - lift, sw * 0.75f, fillPaint)
                    }
                } else if (stylePref == "cute") {
                    // 鲸鱼娘双层海浪波纹
                    for (row in 0..1) {
                        val baseY = cy + (if (row == 0) -r * 0.28f else r * 0.32f)
                        path.reset()
                        for (k in 0..10) {
                            val fx = cx - r + (2f * r * k / 10f)
                            val fy = baseY + sin(rad * 1.5 + k * 0.75 + row * 1.5).toFloat() * r * 0.22f
                            if (k == 0) path.moveTo(fx, fy) else path.lineTo(fx, fy)
                        }
                        canvas.drawPath(path, if (row == 0) strokePaint else softPaint)
                    }
                } else {
                    // 极简菱形棱镜旋转
                    canvas.save(); canvas.rotate(ang * 0.7f, cx, cy)
                    path.reset()
                    path.moveTo(cx, cy - r); path.lineTo(cx + r, cy); path.lineTo(cx, cy + r); path.lineTo(cx - r, cy); path.close()
                    canvas.drawPath(path, strokePaint)
                    canvas.restore()
                }
            }
            else -> {
                if (stylePref == "usagi") {
                    // 乌萨奇长兔耳轻晃 + 顶部小星点
                    canvas.save(); canvas.rotate(rock, cx, cy + r * 0.3f)
                    rect.set(cx - r * 0.46f, cy - r * 0.98f, cx - r * 0.08f, cy + r * 0.15f)
                    canvas.drawRoundRect(rect, r * 0.22f, r * 0.22f, strokePaint)
                    rect.set(cx + r * 0.08f, cy - r * 0.98f, cx + r * 0.46f, cy + r * 0.15f)
                    canvas.drawRoundRect(rect, r * 0.22f, r * 0.22f, strokePaint)
                    rect.set(cx - r * 0.75f, cy - r * 0.08f, cx + r * 0.75f, cy + r * 0.92f)
                    canvas.drawArc(rect, 0f, 180f, false, softPaint)
                    canvas.restore()
                } else if (stylePref == "cute") {
                    // 鲸鱼娘轻摆尾鳍 + 冒升小水泡
                    canvas.save(); canvas.rotate(rock, cx, cy + r * 0.4f)
                    path.reset()
                    path.moveTo(cx, cy + r * 0.75f)
                    path.lineTo(cx, cy)
                    path.quadTo(cx - r * 0.65f, cy - r * 0.12f, cx - r * 0.85f, cy - r * 0.68f)
                    path.quadTo(cx - r * 0.25f, cy - r * 0.55f, cx, cy - r * 0.18f)
                    path.quadTo(cx + r * 0.25f, cy - r * 0.55f, cx + r * 0.85f, cy - r * 0.68f)
                    path.quadTo(cx + r * 0.65f, cy - r * 0.12f, cx, cy)
                    canvas.drawPath(path, strokePaint)
                    canvas.restore()
                    val by = ((t * 1.4f) % 1f) * r * 0.5f
                    canvas.drawCircle(cx - r * 0.55f, cy + r * 0.55f - by, sw * 0.65f, fillPaint)
                } else {
                    drawSpokes(canvas, cx, cy, r, ang * 0.85f, 6)
                    val pulse = 0.78f + 0.3f * sin(rad * 2.0).toFloat()
                    canvas.drawCircle(cx, cy, sw * 0.95f * pulse, fillPaint)
                }
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
