package io.deepseekharness.mobile.companion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 顶部灵动任务岛（Whale Live Island）：
 * 当后台 Worker 正在执行代码、网页抓取或宏任务时，在屏幕顶部挖孔区下方伸展一条主题原生胶囊，
 * 实时展示呼吸状态点、当前任务文案与旋转进度弧；全部任务结束后自动淡出收起。
 */
internal class WhaleLiveIslandOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onTapIsland: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var rootView: LinearLayout? = null
    private var titleView: TextView? = null
    private var ringView: IslandProgressRing? = null
    private var destroyed = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            syncWorkerState()
            handler.postDelayed(this, 1800L)
        }
    }

    fun start() {
        destroyed = false
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, 1500L)
    }

    fun stop() {
        destroyed = true
        handler.removeCallbacks(pollRunnable)
        hideNow()
    }

    private fun syncWorkerState() {
        val snap = runCatching { WhaleNativeWorkerPool.snapshot() }.getOrNull() ?: return
        var activeTitle = ""
        var activeStep = ""
        var runningCount = 0
        for (i in 0 until snap.length()) {
            val item = snap.optJSONObject(i) ?: continue
            val st = item.optString("state")
            if (st == "running" || st == "queued") {
                runningCount += 1
                if (activeTitle.isEmpty()) {
                    activeTitle = item.optString("title", "后台任务").take(14)
                    activeStep = item.optString("currentStep", "执行中").take(14)
                }
            }
        }
        if (runningCount <= 0) {
            hideNow()
            return
        }
        val label = if (runningCount > 1) {
            "$activeTitle 等${runningCount}项 · $activeStep"
        } else {
            "$activeTitle · $activeStep"
        }
        showOrUpdate(label)
    }

    private fun showOrUpdate(label: String) {
        val existing = rootView
        if (existing != null && existing.isAttachedToWindow) {
            titleView?.text = label
            return
        }
        val ui = PetUi(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.shape(ui.surface, 999, ui.primary)
            setPadding(ui.dp(12), ui.dp(6), ui.dp(14), ui.dp(6))
            elevation = ui.dp(8).toFloat()
            alpha = 0f
            scaleX = 0.85f
            scaleY = 0.85f
            setOnClickListener { onTapIsland() }
        }
        val ring = IslandProgressRing(context, ui.primary)
        val tv = TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(ui.ink)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        container.addView(ring, LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { marginEnd = ui.dp(8) })
        container.addView(tv, LinearLayout.LayoutParams(-2, -2))

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val statusBarH = runCatching {
            val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) context.resources.getDimensionPixelSize(resId) else ui.dp(28)
        }.getOrDefault(ui.dp(28))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarH + ui.dp(6)
        }
        runCatching {
            windowManager.addView(container, params)
            rootView = container
            titleView = tv
            ringView = ring
            container.animate().alpha(0.96f).scaleX(1f).scaleY(1f).setDuration(240L).start()
        }
    }

    private fun hideNow() {
        val v = rootView ?: return
        rootView = null
        titleView = null
        ringView?.stopAnim()
        ringView = null
        runCatching { windowManager.removeViewImmediate(v) }
    }

    private class IslandProgressRing(context: Context, accentColor: Int) : View(context) {
        private val dp = resources.displayMetrics.density
        private var sweepOffset = 0f
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.1f * dp
            color = Color.argb(56, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        }
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 2.2f * dp
            color = accentColor
        }
        private val rect = RectF()
        private val anim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweepOffset = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            anim.start()
        }

        override fun onDetachedFromWindow() {
            anim.cancel()
            super.onDetachedFromWindow()
        }

        fun stopAnim() { anim.cancel() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = 2.2f * dp
            rect.set(pad, pad, width - pad, height - pad)
            canvas.drawOval(rect, trackPaint)
            canvas.drawArc(rect, sweepOffset, 110f, false, arcPaint)
        }
    }
}
