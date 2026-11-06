package io.deepseekharness.mobile.companion

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import io.deepseekharness.mobile.DeviceControlState

/** A compact always-visible control strip, separate from transient chat hints. */
internal class DeviceControlOverlay(private val context: Context, private val wm: WindowManager) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var labelView: android.widget.TextView? = null
    private var pauseView: android.widget.TextView? = null
    private var paletteKey = ""
    private val listener: (DeviceControlState.Snapshot) -> Unit = { state -> handler.post { render(state) } }
    init { DeviceControlState.subscribe(listener); handler.post { render(DeviceControlState.current) } }
    private fun render(state: DeviceControlState.Snapshot) {
        if (state.phase == "idle" || !android.provider.Settings.canDrawOverlays(context)) {
            root?.let { runCatching { wm.removeView(it) } }; root = null
            return
        }
        val ui = PetUi(context)
        val label = when(state.phase) {
            "observing" -> "正在观察屏幕"
            "operating" -> "正在操作手机"
            "paused" -> "后续操作已暂停"
            "stopped" -> "后续操作已停止"
            "failed" -> "手机操作未成功"
            else -> "操作已返回，等待下一步"
        }
        val text = label + " · " + state.action
        val key = ui.surface.toString() + "|" + ui.accent
        if (root != null && key == paletteKey) {
            labelView?.text = text
            pauseView?.text = if(state.phase in setOf("paused","stopped")) "继续" else "暂停"
            root?.background = ui.shape(ui.surface,14,if(state.phase=="operating") ui.alert else ui.line)
            return
        }
        root?.let { runCatching { wm.removeView(it) } }; root = null
        paletteKey = key
        val panel = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12),ui.dp(4),ui.dp(4),ui.dp(4))
            background = ui.shape(ui.surface,14,if(state.phase == "operating") ui.alert else ui.line)
            contentDescription = "手机操作状态"
        }
        labelView = ui.text(text,13f,ui.ink)
        panel.addView(labelView,LinearLayout.LayoutParams(0,-2,1f))
        fun action(text: String, work: () -> Unit): android.widget.TextView {
            val view = ui.text(text,13f,ui.accent,true).apply {
                minHeight=ui.dp(44); gravity=Gravity.CENTER; setPadding(ui.dp(10),0,ui.dp(10),0)
                isFocusable=true; setOnClickListener { work() }
            }
            panel.addView(view)
            return view
        }
        pauseView = action(if(state.phase in setOf("paused","stopped")) "继续" else "暂停") {
            if(DeviceControlState.current.phase in setOf("paused","stopped")) DeviceControlState.resume() else DeviceControlState.pause()
        }
        action("停止") { DeviceControlState.stop() }
        val params = WindowManager.LayoutParams(
            minOf(ui.dp(460),context.resources.displayMetrics.widthPixels-ui.dp(24)),
            WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT
        ).apply { gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL; y=ui.dp(38); title="鲸鱼助手 · 手机操作状态" }
        io.deepseekharness.mobile.WhaleMotion.overlay(params,critical=true)
        if(runCatching { wm.addView(panel,params) }.isSuccess) root=panel
    }
    fun refresh() { render(DeviceControlState.current) }
    fun destroy() { DeviceControlState.unsubscribe(listener); handler.removeCallbacksAndMessages(null); root?.let { runCatching { wm.removeView(it) } }; root=null }
}
