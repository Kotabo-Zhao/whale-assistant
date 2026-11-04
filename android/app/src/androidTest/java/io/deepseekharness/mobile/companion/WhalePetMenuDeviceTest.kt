package io.deepseekharness.mobile.companion

import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.OverlayBallService
import io.deepseekharness.mobile.runtime.RuntimeStore
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhalePetMenuDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext
    private fun waitFor(message: String, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10000
        while (!check()) { if (SystemClock.elapsedRealtime() >= end) fail(message); Thread.sleep(70) }
    }
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun main(read: () -> Unit) = inst.runOnMainSync(read)
    private fun bounds(view: View): Rect {
        val xy = IntArray(2); view.getLocationOnScreen(xy)
        return Rect(xy[0], xy[1], xy[0]+view.width, xy[1]+view.height)
    }
    private fun touch(x: Int, y: Int, hold: Boolean = false) {
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(inst.uiAutomation.injectInputEvent(e, true)) } finally { e.recycle() }
            if (action == MotionEvent.ACTION_DOWN) Thread.sleep(if (hold) 850 else 45)
        }
    }
    private fun views(v: View): Sequence<View> = sequence {
        yield(v); if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }

    @Test fun longPressActionsAboveAnotherApp() {
        val runtime = RuntimeStore(app)
        val enabled = runtime.overlayBallEnabled()
        val running = OverlayBallService.isRunning
        val active = OverlayBallService::class.java.getDeclaredField("activeService").apply { isAccessible = true }
        val source = inst.context.packageName
        lateinit var service: OverlayBallService
        var pet = Rect()
        val note = "menu-clip-" + java.util.UUID.randomUUID()
        var ownsCopy = false
        val store = CompanionKnowledgeStore(app)
        fun sourceControl(text: String): Rect? {
            val root = inst.uiAutomation.rootInActiveWindow ?: return null
            if (root.packageName != source) return null
            val node = root.findAccessibilityNodeInfosByText(text).firstOrNull() ?: return null
            return Rect().also { node.getBoundsInScreen(it) }.takeUnless { it.isEmpty }
        }
        val baseline = InstrumentationRegistry.getArguments().getString("oldMenu") == "true"
        try {
            runtime.setOverlayBallEnabled(true); OverlayBallService.start(app)
            app.startActivity(Intent().setComponent(ComponentName(source, ForeignDropSource::class.java.name))
                .putExtra("note", "menu-probe").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitFor("foreign source") { inst.uiAutomation.rootInActiveWindow?.packageName == source }
            waitFor("pet visible") {
                OverlayBallService.updateForHostAppForeground(false)
                main {
                    (active.get(null) as? OverlayBallService)?.let { s ->
                        service = s
                        (field(s, "ballView") as? View)?.takeIf { it.isShown }?.let { pet = bounds(it) }
                    }
                }
                !pet.isEmpty
            }
            fun longPress() { touch(pet.centerX(), pet.centerY(), true) }
            fun saveImage(view: View, name: String) {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                File(app.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            longPress()
            if (baseline) {
                var count = 0
                waitFor("old menu") { main { count = (field(service,"secondaryViews") as List<*>).size }; count == 5 }
                inst.sendStatus(0, Bundle().apply { putInt("legacyMenuWindows", count) })
                return
            }
            fun menu() = field(service, "actionMenu") as? View
            fun label(text: String): Rect {
                var result = Rect()
                waitFor("missing pet action $text") {
                    main { menu()?.let { root -> views(root).filterIsInstance<TextView>().firstOrNull { it.text == text && it.isShown }?.let { result = bounds(it) } } }
                    !result.isEmpty
                }
                return result
            }
            fun tap(text: String) { val rect = label(text); touch(rect.centerX(), rect.centerY()) }
            fun dialog(member: String, nested: Boolean = false): AlertDialog? {
                val bubble = (field(service, "bubbleChat\$delegate") as Lazy<*>).value!!
                return field(if (nested) field(bubble, "workerPanel")!! else bubble, member) as? AlertDialog
            }
            fun checkDialog(member: String, nested: Boolean = false) {
                var shown = false
                waitFor("dialog $member") { main { shown = dialog(member, nested)?.isShowing == true && menu() == null }; shown }
                main { dialog(member, nested)?.dismiss() }
            }
            val minimum = (48 * app.resources.displayMetrics.density).toInt()
            for (name in listOf("桌宠对话","交办任务","存资料","后台任务")) assertTrue(name, label(name).height() >= minimum)
            var menuRect = Rect()
            main {
                val view = menu()!!; menuRect = bounds(view)
                val params = view.layoutParams as WindowManager.LayoutParams
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                assertTrue(menuRect.left >= 0 && menuRect.top >= 0)
                assertTrue(menuRect.right <= app.resources.displayMetrics.widthPixels && menuRect.bottom <= app.resources.displayMetrics.heightPixels)
                saveImage(view, "pet-action-menu.png")
            }
            // Capture only the pet and its menu above the known external fixture.
            assertNotNull(sourceControl("测试来源 · 长按投放"))
            val frame = requireNotNull(inst.uiAutomation.takeScreenshot())
            try {
                val crop = Rect(menuRect).apply { union(pet); inset(-8, -8) }
                check(crop.intersect(0, 0, frame.width, frame.height))
                val pair = Bitmap.createBitmap(frame, crop.left, crop.top, crop.width(), crop.height())
                File(app.getExternalFilesDir(null), "pet-live-menu.png").outputStream().use { pair.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (pair !== frame) pair.recycle()
            } finally { frame.recycle() }
            tap("设备与显示"); listOf("Harness 对话小窗","目标应用副屏","回到主应用","隐藏桌宠").forEach { label(it) }
            tap("返回"); tap("我的工具"); label("功能工坊"); tap("返回")
            val metrics = app.resources.displayMetrics
            val x = listOf(metrics.widthPixels/8, metrics.widthPixels*7/8).first { !menuRect.contains(it, metrics.heightPixels/2) && !pet.contains(it, metrics.heightPixels/2) }
            touch(x, metrics.heightPixels/2)
            waitFor("touch passed through to source") { inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("测试来源 · 已收到点击")?.isNotEmpty() == true }
            var closed = false
            waitFor("outside dismiss") { main { closed = menu() == null }; closed }
            longPress(); tap("存资料"); label("收藏剪贴板"); label("写一段文字")
            main { saveImage(menu()!!, "pet-capture-sources.png") }
            tap("写一段文字"); checkDialog("captureDialog")

            longPress(); tap("存资料"); tap("选本地文件")
            waitFor("document picker") { inst.uiAutomation.rootInActiveWindow?.packageName?.toString()?.endsWith("documentsui") == true }
            assertTrue(inst.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            waitFor("picker returned to source") { inst.uiAutomation.rootInActiveWindow?.packageName == source }

            longPress(); tap("存资料"); tap("收藏这一屏")
            waitFor("projection consent") { inst.uiAutomation.rootInActiveWindow?.packageName == "com.android.systemui" }
            assertTrue(inst.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            waitFor("screen consent returned to source") { inst.uiAutomation.rootInActiveWindow?.packageName == source }

            app.startActivity(Intent().setComponent(ComponentName(source, ForeignDropSource::class.java.name))
                .putExtra("mode", "clipboard").putExtra("note", note)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitFor("copy button") { sourceControl("复制验收文字") != null }
            val copy = requireNotNull(sourceControl("复制验收文字"))
            touch(copy.centerX(), copy.centerY())
            waitFor("copy state") { sourceControl("已有剪贴板内容") != null || sourceControl("清除本次测试副本") != null }
            ownsCopy = sourceControl("清除本次测试副本") != null
            if (ownsCopy) {
                longPress(); tap("存资料"); tap("收藏剪贴板")
                waitFor("indexed selected copy") { store.searchPage(note, 0, 1).first.optJSONObject(0)?.optString("shareOrigin") == "剪贴板投放" }
                waitFor("copy returned to source") { sourceControl("清除本次测试副本") != null }
            }
            inst.sendStatus(0, Bundle().apply { putString("clipboard", if (ownsCopy) "indexed test copy, source resumed" else "skipped to preserve existing user clipboard") })
            if (ownsCopy) sourceControl("清除本次测试副本")?.let { touch(it.centerX(), it.centerY()) }
            longPress(); tap("交办任务"); checkDialog("editDialog", true)
            longPress(); tap("后台任务"); checkDialog("dialog", true)
            longPress(); tap("桌宠对话")
            waitFor("chat") {
                var visible = false
                main { visible = ((field(service, "bubbleChat\$delegate") as Lazy<*>).value as CompanionBubbleWindow).visible }
                visible
            }
            inst.sendStatus(0, Bundle().apply { putString("petMenu", "one bounded window, four 48dp targets, source receives outside touch, tools, devices, capture, task composer, inbox, chat") })
        } finally {
            if (ownsCopy) sourceControl("清除本次测试副本")?.let { touch(it.centerX(), it.centerY()) }
            store.searchPage(note, 0, 1).first.optJSONObject(0)?.let { store.remove(it.getString("id"), note) }
            runtime.setOverlayBallEnabled(enabled)
            if (!running) OverlayBallService.stop(app)
        }
    }
}
