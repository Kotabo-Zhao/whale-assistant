package io.deepseekharness.mobile.companion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * 桌宠气泡专用高帧率动画角色状态视图：
 * 播放由 AI 生成的 12 帧透明角色动作序列条（支持「鲸鱼娘」与「乌萨奇」两套完整主题，
 * 7 种行为状态各具专属动作），并在 60 FPS 下叠加柔和弹性呼吸缩放。
 */
internal class WhaleVectorStatusView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()
    private var phase = 0f
    private var category = "thinking"
    private val themeKey = if (context.getSharedPreferences("whale-interface", 0).getString("style", "cute") == "usagi") "usagi" else "whale"
    private var stripBitmap: Bitmap? = null

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        loadStrip("thinking")
    }

    private fun normalizeCategory(raw: String): String = when (raw) {
        "code", "web", "file", "memory", "phone", "worker" -> raw
        else -> "thinking"
    }

    private fun loadStrip(cat: String) {
        val norm = normalizeCategory(cat)
        val key = "${themeKey}_$norm"
        val cached = stripCache[key]
        if (cached != null && !cached.isRecycled) {
            stripBitmap = cached
            return
        }
        val bmp = runCatching {
            context.applicationContext.assets.open("status-strips/$key.webp").use {
                BitmapFactory.decodeStream(it)
            }
        }.getOrNull()
        if (bmp != null) {
            stripCache[key] = bmp
            stripBitmap = bmp
        }
    }

    fun setCategoryColor(category: String) {
        val norm = normalizeCategory(category)
        if (norm != this.category || stripBitmap == null) {
            this.category = norm
            loadStrip(norm)
            invalidate()
        }
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
        val bmp = stripBitmap ?: return
        if (bmp.isRecycled || width <= 0 || height <= 0) return
        val frameCount = 12
        val frameW = (bmp.width / frameCount).coerceAtLeast(1)
        val frameIdx = (phase * frameCount).toInt().coerceIn(0, frameCount - 1)
        srcRect.set(frameIdx * frameW, 0, (frameIdx + 1) * frameW, bmp.height)
        val microBob = (sin(phase * Math.PI * 2.0) * height * 0.025).toFloat()
        dstRect.set(0f, microBob, width.toFloat(), height.toFloat() + microBob)
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
    }

    companion object {
        private val stripCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    }
}
