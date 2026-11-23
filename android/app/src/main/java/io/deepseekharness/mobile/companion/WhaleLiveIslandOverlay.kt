package io.deepseekharness.mobile.companion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 灵动任务岛实时状态总线（供主对话引擎、录制宏、后台任务零耦合上报状态）。
 */
internal object WhaleLiveIslandBus {
    data class ForegroundStatus(
        val active: Boolean = false,
        val title: String = "",
        val step: String = "",
        val detail: String = "",
        val completedOk: Boolean? = null,
        val updatedAtMs: Long = 0L,
    )

    @Volatile
    var foreground: ForegroundStatus = ForegroundStatus()
        private set

    fun reportForeground(title: String, step: String, detail: String = "") {
        foreground = ForegroundStatus(
            active = true,
            title = title.trim().take(18).ifEmpty { "鲸鱼助手" },
            step = step.trim().take(24).ifEmpty { "思考中…" },
            detail = detail.trim().take(160),
            completedOk = null,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    fun completeForeground(ok: Boolean, summary: String = "") {
        foreground = ForegroundStatus(
            active = false,
            title = if (ok) "已完成" else "已暂停",
            step = summary.trim().take(24).ifEmpty { if (ok) "回复已就绪" else "请查看详情" },
            detail = summary.trim().take(160),
            completedOk = ok,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    fun clearForeground() {
        foreground = ForegroundStatus()
    }
}

/**
 * 顶部灵动任务岛 V2（Whale Live Island）：
 *
 * - **全场景上岛**：自动聚合 ① 0-Token 录制宏状态（红点呼吸 + 一键停止保存）、
 *   ② 后台 Worker 池运行任务（支持查看实时预览与一键取消）、
 *   ③ 前台 Agent 思考/多轮工具进展（切去别的 App 等回复时顶部实时可见，完成瞬间弹 ✓ 提示 2.5 秒自动收起）；
 * - **双态原地展开**：紧凑胶囊点击后原地弹性伸展为操作卡片（详情预览 + 取消/停录/回对话/收起），无需跳转即可控制。
 */
internal class WhaleLiveIslandOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onTapOpenApp: () -> Unit,
) {
    private enum class Mode { NONE, MACRO_RECORDING, WORKER, FOREGROUND, COMPLETED }

    private data class IslandSnapshot(
        val mode: Mode = Mode.NONE,
        val title: String = "",
        val subtitle: String = "",
        val detail: String = "",
        val workerId: String = "",
        val runningCount: Int = 0,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var rootView: LinearLayout? = null
    private var compactRow: LinearLayout? = null
    private var expandedPanel: LinearLayout? = null
    private var titleView: TextView? = null
    private var chevronView: TextView? = null
    private var detailTitleView: TextView? = null
    private var detailBadgeView: TextView? = null
    private var detailBodyView: TextView? = null
    private var actionBtnPrimary: TextView? = null
    private var actionBtnDanger: TextView? = null
    private var indicatorView: IslandIndicatorView? = null

    private var currentSnap: IslandSnapshot = IslandSnapshot()
    private var expanded = false
    private var destroyed = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            syncAllSources()
            handler.postDelayed(this, 1000L)
        }
    }

    fun start() {
        destroyed = false
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, 1200L)
    }

    fun stop() {
        destroyed = true
        expanded = false
        handler.removeCallbacks(pollRunnable)
        hideNow()
    }

    private fun syncAllSources() {
        val next = computeSnapshot()
        if (next.mode == Mode.NONE) {
            if (!expanded) hideNow()
            return
        }
        currentSnap = next
        showOrUpdate(next)
    }

    private fun computeSnapshot(): IslandSnapshot {
        // 优先级 1：正在录制 0-Token 宏（随时可一键停止并保存）
        if (runCatching { WhaleMacroEngine.isRecording() }.getOrDefault(false)) {
            return IslandSnapshot(
                mode = Mode.MACRO_RECORDING,
                title = "正在录制 0-Token 宏",
                subtitle = "点开可停止并保存",
                detail = "正在记录你的点击步骤，操作完成后点击下方「停止并保存」即可永久复用。",
            )
        }

        // 优先级 2：后台 Worker 池活跃任务
        val snap = runCatching { WhaleNativeWorkerPool.snapshot() }.getOrNull()
        if (snap != null) {
            var activeId = ""
            var activeTitle = ""
            var activeStep = ""
            var activePreview = ""
            var runningCount = 0
            for (i in 0 until snap.length()) {
                val item = snap.optJSONObject(i) ?: continue
                val st = item.optString("state")
                if (st == "running" || st == "queued") {
                    runningCount += 1
                    if (activeId.isEmpty()) {
                        activeId = item.optString("id")
                        activeTitle = item.optString("title", "后台任务").take(16)
                        activeStep = item.optString("currentStep", "执行中").take(18)
                        activePreview = item.optString("livePreview").ifBlank { item.optString("summary") }.take(140)
                    }
                }
            }
            if (runningCount > 0) {
                val head = if (runningCount > 1) "$activeTitle 等${runningCount}项" else activeTitle
                return IslandSnapshot(
                    mode = Mode.WORKER,
                    title = head,
                    subtitle = activeStep,
                    detail = activePreview.ifBlank { "后台任务正在独立线程中执行，你可以继续使用其他应用。" },
                    workerId = activeId,
                    runningCount = runningCount,
                )
            }
        }

        // 优先级 3：前台 Agent 正在推理或执行工具 / 刚完成 2.5 秒提示
        val fg = WhaleLiveIslandBus.foreground
        val age = System.currentTimeMillis() - fg.updatedAtMs
        if (fg.active && age < 180_000L) {
            return IslandSnapshot(
                mode = Mode.FOREGROUND,
                title = fg.title,
                subtitle = fg.step,
                detail = fg.detail.ifBlank { "正在处理你的请求：${fg.step}" },
            )
        }
        if (fg.completedOk != null && age in 0L..2_800L) {
            return IslandSnapshot(
                mode = Mode.COMPLETED,
                title = fg.title,
                subtitle = fg.step,
                detail = fg.detail.ifBlank { "处理完成，点击「打开对话」查看完整回复。" },
            )
        }
        return IslandSnapshot(mode = Mode.NONE)
    }

    private fun showOrUpdate(snap: IslandSnapshot) {
        val existing = rootView
        if (existing == null || !existing.isAttachedToWindow) {
            buildAndAttachWindow()
        }
        bindSnapshot(snap)
    }

    private fun buildAndAttachWindow() {
        val ui = PetUi(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.shape(ui.surface, 26, ui.primary)
            setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7))
            elevation = ui.dp(10).toFloat()
            alpha = 0f
            scaleX = 0.86f
            scaleY = 0.86f
        }

        // 1. 顶部紧凑胶囊行
        val compact = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                toggleExpanded()
            }
        }
        val indicator = IslandIndicatorView(context, ui.primary)
        val compactTitle = TextView(context).apply {
            textSize = 12.2f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ui.ink)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val chevron = TextView(context).apply {
            text = "▾"
            textSize = 11.5f
            setTextColor(ui.muted)
            setPadding(ui.dp(6), 0, 0, 0)
        }
        compact.addView(indicator, LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { marginEnd = ui.dp(8) })
        compact.addView(compactTitle, LinearLayout.LayoutParams(0, -2, 1f))
        compact.addView(chevron, LinearLayout.LayoutParams(-2, -2))
        root.addView(compact, LinearLayout.LayoutParams(-1, -2))

        // 2. 原地展开交互卡片区
        val exp = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(ui.dp(2), ui.dp(10), ui.dp(2), ui.dp(4))
        }
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dTitle = TextView(context).apply {
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ui.ink)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val dBadge = TextView(context).apply {
            textSize = 10.5f
            setTextColor(ui.onPrimary)
            background = ui.shape(ui.primary, 999)
            setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
        }
        headerRow.addView(dTitle, LinearLayout.LayoutParams(0, -2, 1f))
        headerRow.addView(dBadge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
        exp.addView(headerRow, LinearLayout.LayoutParams(-1, -2))

        val dBody = TextView(context).apply {
            textSize = 12f
            setTextColor(ui.muted)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(ui.dp(2).toFloat(), 1f)
            setPadding(0, ui.dp(6), 0, ui.dp(10))
        }
        exp.addView(dBody, LinearLayout.LayoutParams(-1, -2))

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val btnDanger = TextView(context).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#DC2626"))
            background = ui.shape(ui.canvas, 999, ui.line)
            setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
            setOnClickListener { handleDangerAction() }
        }
        val btnPrimary = TextView(context).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ui.onPrimary)
            background = ui.shape(ui.primary, 999)
            setPadding(ui.dp(13), ui.dp(6), ui.dp(13), ui.dp(6))
            setOnClickListener { handlePrimaryAction() }
        }
        val btnCollapse = TextView(context).apply {
            text = "收起"
            textSize = 12f
            setTextColor(ui.muted)
            setPadding(ui.dp(10), ui.dp(6), ui.dp(6), ui.dp(6))
            setOnClickListener { setExpandedState(false) }
        }
        btnRow.addView(btnDanger, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) })
        btnRow.addView(btnPrimary, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(4) })
        btnRow.addView(btnCollapse, LinearLayout.LayoutParams(-2, -2))
        exp.addView(btnRow, LinearLayout.LayoutParams(-1, -2))
        root.addView(exp, LinearLayout.LayoutParams(-1, -2))

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
            windowManager.addView(root, params)
            rootView = root
            compactRow = compact
            expandedPanel = exp
            titleView = compactTitle
            chevronView = chevron
            detailTitleView = dTitle
            detailBadgeView = dBadge
            detailBodyView = dBody
            actionBtnPrimary = btnPrimary
            actionBtnDanger = btnDanger
            indicatorView = indicator
            root.animate().alpha(0.97f).scaleX(1f).scaleY(1f).setDuration(260L)
                .setInterpolator(OvershootInterpolator(1.1f)).start()
        }
    }

    private fun bindSnapshot(snap: IslandSnapshot) {
        val compactLabel = if (snap.subtitle.isNotBlank()) "${snap.title} · ${snap.subtitle}" else snap.title
        titleView?.text = compactLabel
        chevronView?.text = if (expanded) "▴" else "▾"
        detailTitleView?.text = snap.title
        detailBodyView?.text = snap.detail
        when (snap.mode) {
            Mode.MACRO_RECORDING -> {
                indicatorView?.setVisualMode(IslandIndicatorView.VisualMode.RECORDING)
                detailBadgeView?.text = "录制中"
                actionBtnPrimary?.text = "停止并保存"
                actionBtnPrimary?.visibility = View.VISIBLE
                actionBtnDanger?.visibility = View.GONE
            }
            Mode.WORKER -> {
                indicatorView?.setVisualMode(IslandIndicatorView.VisualMode.SPINNING)
                detailBadgeView?.text = if (snap.runningCount > 1) "${snap.runningCount} 项进行中" else "后台运行"
                actionBtnPrimary?.text = "打开对话"
                actionBtnPrimary?.visibility = View.VISIBLE
                actionBtnDanger?.text = "取消任务"
                actionBtnDanger?.visibility = View.VISIBLE
            }
            Mode.FOREGROUND -> {
                indicatorView?.setVisualMode(IslandIndicatorView.VisualMode.SPINNING)
                detailBadgeView?.text = "执行中"
                actionBtnPrimary?.text = "回到对话"
                actionBtnPrimary?.visibility = View.VISIBLE
                actionBtnDanger?.visibility = View.GONE
            }
            Mode.COMPLETED -> {
                indicatorView?.setVisualMode(IslandIndicatorView.VisualMode.DONE)
                detailBadgeView?.text = "已完成"
                actionBtnPrimary?.text = "查看回复"
                actionBtnPrimary?.visibility = View.VISIBLE
                actionBtnDanger?.visibility = View.GONE
            }
            Mode.NONE -> Unit
        }
    }

    private fun toggleExpanded() {
        setExpandedState(!expanded)
    }

    private fun setExpandedState(open: Boolean) {
        val root = rootView ?: return
        if (!root.isAttachedToWindow) return
        expanded = open
        expandedPanel?.visibility = if (open) View.VISIBLE else View.GONE
        chevronView?.text = if (open) "▴" else "▾"
        val ui = PetUi(context)
        val lp = root.layoutParams as? WindowManager.LayoutParams
        if (lp != null) {
            val targetW = if (open) {
                minOf(context.resources.displayMetrics.widthPixels - ui.dp(28), ui.dp(348))
            } else {
                WindowManager.LayoutParams.WRAP_CONTENT
            }
            lp.width = targetW
            runCatching { windowManager.updateViewLayout(root, lp) }
        }
        root.scaleX = if (open) 0.94f else 1.03f
        root.scaleY = if (open) 0.94f else 1.03f
        root.animate().scaleX(1f).scaleY(1f).setDuration(220L)
            .setInterpolator(OvershootInterpolator(1.15f)).start()
    }

    private fun handlePrimaryAction() {
        when (currentSnap.mode) {
            Mode.MACRO_RECORDING -> {
                val res = runCatching { WhaleMacroEngine.stopRecording(context) }.getOrNull()
                val ok = res?.optBoolean("ok", false) == true
                Toast.makeText(
                    context,
                    if (ok) "已保存 0-Token 宏「${res?.optString("name").orEmpty()}」" else (res?.optString("error") ?: "结束录制失败"),
                    Toast.LENGTH_SHORT,
                ).show()
                setExpandedState(false)
                syncAllSources()
            }
            else -> {
                setExpandedState(false)
                onTapOpenApp()
            }
        }
    }

    private fun handleDangerAction() {
        val wid = currentSnap.workerId
        if (wid.isNotBlank()) {
            val ok = runCatching { WhaleNativeWorkerPool.cancel(wid) }.getOrDefault(false)
            Toast.makeText(context, if (ok) "已取消后台任务" else "任务已结束", Toast.LENGTH_SHORT).show()
        }
        setExpandedState(false)
        syncAllSources()
    }

    private fun hideNow() {
        expanded = false
        val v = rootView ?: return
        rootView = null
        compactRow = null
        expandedPanel = null
        titleView = null
        chevronView = null
        detailTitleView = null
        detailBadgeView = null
        detailBodyView = null
        actionBtnPrimary = null
        actionBtnDanger = null
        indicatorView?.stopAnim()
        indicatorView = null
        runCatching { windowManager.removeViewImmediate(v) }
    }

    private class IslandIndicatorView(context: Context, private val accentColor: Int) : View(context) {
        enum class VisualMode { SPINNING, RECORDING, DONE }

        private val dp = resources.displayMetrics.density
        private var mode = VisualMode.SPINNING
        private var phase = 0f

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
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val rect = RectF()
        private val anim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1050L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }

        fun setVisualMode(next: VisualMode) {
            if (mode != next) {
                mode = next
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
            val cx = width / 2f
            val cy = height / 2f
            when (mode) {
                VisualMode.SPINNING -> {
                    val pad = 2.2f * dp
                    rect.set(pad, pad, width - pad, height - pad)
                    canvas.drawOval(rect, trackPaint)
                    canvas.drawArc(rect, phase, 115f, false, arcPaint)
                }
                VisualMode.RECORDING -> {
                    val pulse = 0.65f + 0.35f * kotlin.math.sin(Math.toRadians(phase.toDouble())).toFloat()
                    fillPaint.color = Color.argb((90 * pulse).toInt().coerceIn(20, 120), 239, 68, 68)
                    canvas.drawCircle(cx, cy, (width * 0.48f), fillPaint)
                    fillPaint.color = Color.parseColor("#EF4444")
                    canvas.drawCircle(cx, cy, (width * 0.28f), fillPaint)
                }
                VisualMode.DONE -> {
                    fillPaint.color = Color.parseColor("#10B981")
                    canvas.drawCircle(cx, cy, width * 0.45f, fillPaint)
                    arcPaint.color = Color.WHITE
                    arcPaint.strokeWidth = 1.9f * dp
                    canvas.drawLine(cx - 3.0f * dp, cy + 0.2f * dp, cx - 0.6f * dp, cy + 2.6f * dp, arcPaint)
                    canvas.drawLine(cx - 0.6f * dp, cy + 2.6f * dp, cx + 3.4f * dp, cy - 2.4f * dp, arcPaint)
                    arcPaint.color = accentColor
                    arcPaint.strokeWidth = 2.2f * dp
                }
            }
        }
    }
}
