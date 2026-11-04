package io.deepseekharness.mobile.companion

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.OverlayBallService
import io.deepseekharness.mobile.runtime.RuntimeStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WhaleDropDeviceTest {
    @Test fun realCrossUidDragSavesTextAndPrivateUriThenReleasesGrant() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val source = inst.context.packageName
        val note = "foreign-drop-" + UUID.randomUUID()
        val uri = android.net.Uri.parse("content://$source.dragprobe/$note.txt")
        val runtime = RuntimeStore(app)
        val wasEnabled = runtime.overlayBallEnabled()
        val wasRunning = OverlayBallService.isRunning
        val store = CompanionKnowledgeStore(app)
        val type = OverlayBallService::class.java
        val active = type.getDeclaredField("activeService").apply { isAccessible = true }
        val ball = type.getDeclaredField("ballView").apply { isAccessible = true }
        val rect = Rect()
        val sourceRect = Rect()
        fun granted() = app.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        fun waitFor(ms: Long, condition: () -> Boolean): Boolean {
            val end = SystemClock.elapsedRealtime() + ms
            while (SystemClock.elapsedRealtime() < end) { if (condition()) return true; Thread.sleep(80) }
            return condition()
        }
        try {
            assertFalse(granted())
            runtime.setOverlayBallEnabled(true)
            OverlayBallService.start(app)
            app.startActivity(Intent().setComponent(ComponentName(source, ForeignDropSource::class.java.name))
                .putExtra("note", note).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue("外部来源未到前台", waitFor(10000) {
                val root = inst.uiAutomation.rootInActiveWindow ?: return@waitFor false
                val item = root.findAccessibilityNodeInfosByText("测试来源 · 长按投放").firstOrNull() ?: return@waitFor false
                item.getBoundsInScreen(sourceRect); root.packageName == source && !sourceRect.isEmpty
            })
            assertTrue("悬浮投放目标未出现", waitFor(8000) {
                OverlayBallService.updateForHostAppForeground(false)
                inst.runOnMainSync {
                    val view = active.get(null)?.let { ball.get(it) as? View }
                    if (view != null && view.isShown && view.width > 0) {
                        val xy = IntArray(2); view.getLocationOnScreen(xy); rect.set(xy[0], xy[1], xy[0]+view.width, xy[1]+view.height)
                    }
                }
                !rect.isEmpty
            })
            val start = SystemClock.uptimeMillis()
            fun touch(action: Int, progress: Float) {
                val x = sourceRect.exactCenterX() * (1-progress) + rect.exactCenterX() * progress
                val y = sourceRect.exactCenterY() * (1-progress) + rect.exactCenterY() * progress
                val e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
                e.source = InputDevice.SOURCE_TOUCHSCREEN
                try { check(inst.uiAutomation.injectInputEvent(e, true)) } finally { e.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN, 0f); Thread.sleep(800)
            for (i in 1..24) { touch(MotionEvent.ACTION_MOVE, i/24f); Thread.sleep(18) }
            Thread.sleep(180); touch(MotionEvent.ACTION_UP, 1f)
            var attached: org.json.JSONObject? = null
            assertTrue("跨应用拖放未保存原件及说明", waitFor(15000) {
                val row = store.searchPage(note, 0, 20).first.let { rows -> (0 until rows.length()).map(rows::getJSONObject).firstOrNull { it.getString("text") == note } }
                if (row == null) return@waitFor false
                attached = store.attachmentPage(row.getString("id"), note, 0, 10).first.optJSONObject(0)
                attached?.optString("displayName") == "$note.txt"
            })
            assertTrue(waitFor(6000) { !granted() && inst.uiAutomation.rootInActiveWindow?.packageName == source })
            assertEquals("桌宠投放", attached?.getString("shareOrigin"))
        } finally {
            val rows = store.searchPage(note, 0, 20).first
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                if (r.optString("source") == "original") store.removeOriginal(r.getString("originalId"), r.getString("id"), r.getString("text"))
                else if (r.optString("text") == note) store.remove(r.getString("id"), note)
            }
            runtime.setOverlayBallEnabled(wasEnabled)
            if (!wasRunning) OverlayBallService.stop(app)
        }
    }
}
