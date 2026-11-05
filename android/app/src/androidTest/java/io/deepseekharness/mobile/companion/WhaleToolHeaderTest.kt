package io.deepseekharness.mobile.companion

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.features.WhalePetFeatureActivity
import io.deepseekharness.mobile.features.WhaleFeatureStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WhaleToolHeaderTest {
    @Test fun previewAndResultActionsRemainInsidePetWindow() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val store = WhaleFeatureStore(app)
        val id = "header-"+java.util.UUID.randomUUID().toString().take(8)
        val title = "海星工时换算与每日进度"
        val revision = store.draft(JSONObject().put("id",id).put("title",title).put("baseRevision","")
            .put("html","<h1>工时换算</h1><output data-whale-result>三天</output>")).getString("latest")
        val activity = inst.startActivitySync(Intent(app,WhalePetFeatureActivity::class.java)
            .putExtra("featureId",id).putExtra("activeRevision",revision).putExtra("draftPreview",true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
        fun await(check: () -> Boolean) {
            val end = android.os.SystemClock.elapsedRealtime()+8000
            while (true) {
                var ok = false; inst.runOnMainSync { ok = check() }
                if (ok) return
                assertTrue("工具未完成布局",android.os.SystemClock.elapsedRealtime()<end); Thread.sleep(80)
            }
        }
        fun verify(action: String) {
            await { all(activity.window.decorView).filterIsInstance<Button>().any { it.text == action && it.isShown && it.width > 0 } }
            inst.runOnMainSync {
                val decor = activity.window.decorView
                val nodes = all(decor).filterIsInstance<TextView>()
                val heading = nodes.single { it.text == title }
                assertTrue("标题被按钮挤压",heading.width > decor.width / 2)
                val buttons = nodes.filterIsInstance<Button>().filter { it.isShown }
                assertEquals(setOf("返回",action),buttons.map { it.text.toString() }.toSet())
                for (b in buttons) {
                    val rect = android.graphics.Rect(0,0,b.width,b.height)
                    (decor as ViewGroup).offsetDescendantRectToMyCoords(b,rect)
                    assertTrue("操作超出窗口：${b.text}",rect.left >= 0 && rect.right <= decor.width && rect.top >= 0 && rect.bottom <= decor.height)
                    assertTrue("按钮文字被裁切",b.layout != null && (0 until b.layout.lineCount).all { b.layout.getEllipsisCount(it) == 0 })
                }
                assertTrue(all(decor).filterIsInstance<android.webkit.WebView>().single().height > 0)
            }
            inst.waitForIdleSync()
            val window = activity.window
            val bitmap = android.graphics.Bitmap.createBitmap(window.decorView.width,window.decorView.height,android.graphics.Bitmap.Config.ARGB_8888)
            val ready = java.util.concurrent.CountDownLatch(1)
            var copied = -1
            android.view.PixelCopy.request(window,bitmap,{ copied = it; ready.countDown() },android.os.Handler(android.os.Looper.getMainLooper()))
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(android.view.PixelCopy.SUCCESS,copied)
            File(app.getExternalFilesDir(null),"tool-header-${if (action == "启用草稿") "preview" else "active"}.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
            bitmap.recycle()
        }
        try {
            verify("启用草稿")
            inst.runOnMainSync { all(activity.window.decorView).filterIsInstance<Button>().single { it.text == "启用草稿" }.performClick() }
            await { store.read(id)?.optString("active") == revision }
            verify("使用结果")
            inst.runOnMainSync { all(activity.window.decorView).filterIsInstance<Button>().single { it.text == "返回" }.performClick() }
            await { activity.isFinishing }
        } finally {
            inst.runOnMainSync { activity.finish() }
            store.read(id)?.optString("active")?.takeIf { it.isNotEmpty() }?.let { store.activate(id,"",it) }
            android.util.AtomicFile(File(app.noBackupFilesDir,"whale-features-v1/$id.json")).delete()
        }
    }
}
