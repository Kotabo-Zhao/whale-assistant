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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 桌宠气泡专用「鲸鱼娘」60 FPS 原生矢量动画指示器：
 * 冰蓝双马尾、摇摆小鲸鱼尾鳍发卡、自然眨眼大眼睛、粉红腮红 + 按当前真实行为切换的水花/星光特效。
 */
internal class WhaleVectorStatusView(context: Context) : View(context) {
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#7DD3FC")
    }
    private val twinTailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#38BDF8")
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#38BDF8")
    }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFF3EB")
    }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0F2942")
    }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#B8FDA4AF")
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#C26378")
    }
    private val rect = RectF()
    private val path = Path()
    private var phase = 0f
    private var category = "thinking"

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun setCategoryColor(category: String) {
        this.category = category
        val hex = when (category) {
            "code" -> "#34D399"
            "web" -> "#38BDF8"
            "file" -> "#FBBF24"
            "memory" -> "#C084FC"
            "phone" -> "#FB7185"
            "worker" -> "#22D3EE"
            else -> "#38BDF8"
        }
        accentPaint.color = Color.parseColor(hex)
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
        val s = minOf(w, h)
        if (s <= 0f) return

        val rad = Math.toRadians(phase.toDouble())
        val bob = (sin(rad) * s * 0.045f).toFloat()
        val wag = (sin(rad * 1.5) * 9.0).toFloat()
        val cx = w / 2f
        val cy = h / 2f + s * 0.06f + bob

        // 顶部随行为变化的小水花/轨道微粒
        val spoutProgress = (phase % 180f) / 180f
        accentPaint.alpha = ((1f - abs(spoutProgress - 0.5f) * 2f) * 235).toInt().coerceIn(40, 255)
        when (category) {
            "worker" -> {
                val ox = cx + (cos(rad) * s * 0.42f).toFloat()
                val oy = cy + (sin(rad) * s * 0.42f).toFloat()
                canvas.drawCircle(ox, oy, s * 0.065f, accentPaint)
            }
            else -> {
                val dy = spoutProgress * s * 0.16f
                canvas.drawCircle(cx - s * 0.12f, cy - s * 0.34f - dy, s * 0.048f, accentPaint)
                canvas.drawCircle(cx + s * 0.12f, cy - s * 0.31f - dy * 0.85f, s * 0.04f, accentPaint)
            }
        }
        accentPaint.alpha = 255

        // 冰蓝双马尾（随呼吸轻摆）
        canvas.save()
        canvas.rotate(wag * 0.55f, cx - s * 0.22f, cy)
        rect.set(cx - s * 0.40f, cy - s * 0.04f, cx - s * 0.18f, cy + s * 0.28f)
        canvas.drawOval(rect, twinTailPaint)
        canvas.restore()

        canvas.save()
        canvas.rotate(-wag * 0.55f, cx + s * 0.22f, cy)
        rect.set(cx + s * 0.18f, cy - s * 0.04f, cx + s * 0.40f, cy + s * 0.28f)
        canvas.drawOval(rect, twinTailPaint)
        canvas.restore()

        // 头顶小鲸鱼尾鳍发卡
        canvas.save()
        canvas.rotate(wag, cx, cy - s * 0.24f)
        path.reset()
        path.moveTo(cx, cy - s * 0.22f)
        path.cubicTo(cx - s * 0.05f, cy - s * 0.35f, cx - s * 0.17f, cy - s * 0.39f, cx - s * 0.21f, cy - s * 0.31f)
        path.cubicTo(cx - s * 0.14f, cy - s * 0.26f, cx - s * 0.06f, cy - s * 0.23f, cx, cy - s * 0.22f)
        path.cubicTo(cx + s * 0.06f, cy - s * 0.23f, cx + s * 0.14f, cy - s * 0.26f, cx + s * 0.21f, cy - s * 0.31f)
        path.cubicTo(cx + s * 0.17f, cy - s * 0.39f, cx + s * 0.05f, cy - s * 0.35f, cx, cy - s * 0.22f)
        path.close()
        canvas.drawPath(path, accentPaint)
        canvas.restore()

        // 头发主体
        canvas.drawCircle(cx, cy, s * 0.28f, hairPaint)

        // 脸蛋
        rect.set(cx - s * 0.21f, cy - s * 0.16f, cx + s * 0.21f, cy + s * 0.22f)
        canvas.drawOval(rect, facePaint)

        // 空气刘海
        rect.set(cx - s * 0.25f, cy - s * 0.27f, cx + s * 0.25f, cy - s * 0.02f)
        canvas.drawArc(rect, 180f, 180f, true, twinTailPaint)

        // 自然眨眼
        val blinkScale = if (phase in 168f..188f) 0.18f else 1f
        val eyeRx = s * 0.042f
        val eyeRy = s * 0.055f * blinkScale
        val eyeY = cy + s * 0.04f
        rect.set(cx - s * 0.095f - eyeRx, eyeY - eyeRy, cx - s * 0.095f + eyeRx, eyeY + eyeRy)
        canvas.drawOval(rect, eyePaint)
        rect.set(cx + s * 0.095f - eyeRx, eyeY - eyeRy, cx + s * 0.095f + eyeRx, eyeY + eyeRy)
        canvas.drawOval(rect, eyePaint)
        if (blinkScale > 0.5f) {
            canvas.drawCircle(cx - s * 0.11f, eyeY - s * 0.02f, s * 0.017f, shinePaint)
            canvas.drawCircle(cx + s * 0.08f, eyeY - s * 0.02f, s * 0.017f, shinePaint)
        }

        // 腮红与微笑
        rect.set(cx - s * 0.175f, cy + s * 0.08f, cx - s * 0.105f, cy + s * 0.125f)
        canvas.drawOval(rect, blushPaint)
        rect.set(cx + s * 0.105f, cy + s * 0.08f, cx + s * 0.175f, cy + s * 0.125f)
        canvas.drawOval(rect, blushPaint)

        strokePaint.strokeWidth = maxOf(1.2f, s * 0.036f)
        rect.set(cx - s * 0.045f, cy + s * 0.095f, cx + s * 0.045f, cy + s * 0.145f)
        canvas.drawArc(rect, 18f, 144f, false, strokePaint)
    }
}
