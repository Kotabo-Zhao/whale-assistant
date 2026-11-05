package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.Toast
import io.deepseekharness.mobile.features.WhaleFeatureActivity
import io.deepseekharness.mobile.features.WhaleFeatureStore
import io.deepseekharness.mobile.features.loadWhaleTool
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** The overlay service owns these windows. Dismissing the launcher or chat keeps the tool alive. */
internal class PetToolWindows(private val context: Context, private val wm: WindowManager) {
    private val windows = mutableMapOf<String, ToolWindow>()
    private val store = WhaleFeatureStore(context)
    private val main = Handler(Looper.getMainLooper())
    private val reader = Executors.newSingleThreadExecutor()
    private val changed: (String, String) -> Unit = { id, revision ->
        main.post { windows[id]?.let { if (it.tool.revision != revision) it.close() } }
        Unit
    }

    fun open(tool: WhaleFeatureStore.Tool) {
        val existing = windows[tool.id]
        if (existing != null && existing.tool.revision != tool.revision) existing.close()
        val window = windows[tool.id] ?: ToolWindow(tool)
        try {
            if (windows[tool.id] === window) window.expand() else {
                if (windows.isEmpty()) WhaleFeatureStore.subscribe(changed)
                windows[tool.id] = window
                window.show()
            }
        } catch (_: WindowManager.BadTokenException) {
            window.close()
            Toast.makeText(context, "无法显示工具，请检查悬浮窗权限", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            window.close()
            Toast.makeText(context, "请先允许悬浮窗", Toast.LENGTH_SHORT).show()
        }
    }

    fun destroy() {
        windows.values.toList().forEach { it.close() }
        reader.shutdownNow()
    }

    private inner class ToolWindow(val tool: WhaleFeatureStore.Tool) {
        private val ui = PetUi(context)
        private val panel = object : LinearLayout(context) {
            override fun onConfigurationChanged(config: Configuration) {
                super.onConfigurationChanged(config)
                post { if (isAttachedToWindow) layoutWindow() }
            }
        }
        private val params = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            title = "桌宠工具 · " + tool.title
        }
        private val toggle = ui.text("收起", 13f, ui.accent)
        private var page: WebView? = null
        private var collapsed = false

        fun show() {
            panel.orientation = LinearLayout.VERTICAL
            panel.background = ui.shape(ui.surface, 16, ui.line)
            panel.clipToOutline = true
            val bar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val title = ui.text(tool.title, 15f, ui.ink, true).apply {
                setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(ui.dp(12), 0, 0, 0)
                contentDescription = "拖动工具窗口 · " + tool.title
            }
            var offsetX = 0f
            var offsetY = 0f
            title.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { offsetX = params.x - event.rawX; offsetY = params.y - event.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (event.rawX + offsetX).roundToInt()
                        params.y = (event.rawY + offsetY).roundToInt()
                        layoutWindow()
                    }
                }
                true
            }
            bar.addView(title, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
            val details = ui.text("详情", 13f, ui.accent).apply {
                contentDescription = "打开工具详情"; gravity = Gravity.CENTER
                setOnClickListener { WhaleFeatureActivity.openTool(context, tool.id, tool.revision, pet = true); setCollapsed(true) }
            }
            bar.addView(details, ui.dp(48), ui.dp(48))
            toggle.gravity = Gravity.CENTER
            toggle.setOnClickListener { setCollapsed(!collapsed) }
            bar.addView(toggle, ui.dp(48), ui.dp(48))
            bar.addView(ui.close(::close))
            panel.addView(bar)
            params.x = context.resources.displayMetrics.widthPixels - ui.dp(352)
            params.y = ui.dp(30) + ((windows.size - 1) % 4) * ui.dp(42)
            layoutWindow()
            wm.addView(panel, params)
            reader.execute {
                val html = runCatching { store.withActive(tool.id, tool.revision) { it.getString("html") } }.getOrNull()
                main.post {
                    if (windows[tool.id] !== this) return@post
                    if (html == null) { close(); return@post }
                    val web = WebView(context)
                    page = web
                    panel.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
                    web.visibility = if (collapsed) View.GONE else View.VISIBLE
                    web.loadWhaleTool(tool.id, html, gone = { close() })
                    if (collapsed) web.onPause()
                }
            }
        }

        private fun layoutWindow() {
            val metrics = context.resources.displayMetrics
            params.width = minOf(ui.dp(if (collapsed) 252 else 352), (metrics.widthPixels - ui.dp(12)).coerceAtLeast(1))
            params.height = if (collapsed) ui.dp(48) else minOf(ui.dp(350), (metrics.heightPixels * .63f).roundToInt())
            params.x = params.x.coerceIn(0, (metrics.widthPixels - params.width).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (metrics.heightPixels - params.height).coerceAtLeast(0))
            if (panel.isAttachedToWindow) wm.updateViewLayout(panel, params)
        }

        fun expand() {
            setCollapsed(false)
            // The pet menu or another tool can be above an existing window. Reattach
            // the same view tree so selecting this tool raises its current form.
            if (panel.parent != null) {
                wm.removeViewImmediate(panel)
                wm.addView(panel, params)
            }
        }
        private fun setCollapsed(value: Boolean) {
            collapsed = value
            toggle.text = if (value) "展开" else "收起"
            page?.apply {
                visibility = if (value) View.GONE else View.VISIBLE
                if (value) onPause() else onResume()
            }
            params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                (if (value) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0)
            layoutWindow()
        }

        fun close() {
            if (windows[tool.id] !== this) return
            windows.remove(tool.id)
            if (windows.isEmpty()) WhaleFeatureStore.unsubscribe(changed)
            if (panel.parent != null) wm.removeViewImmediate(panel)
            page?.let { panel.removeView(it); it.stopLoading(); it.destroy() }
            page = null
        }
    }
}
