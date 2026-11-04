package io.deepseekharness.mobile.companion

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.features.WhaleFeatureActivity
import io.deepseekharness.mobile.features.WhaleFeatureStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WhaleWorkshopLayoutTest {
    @Test fun managementIsGroupedAndUsableInBothThemes() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val prefs = app.getSharedPreferences("whale-interface",0)
        val old = prefs.getString("style",null)
        val mode = io.deepseekharness.mobile.AppThemePreference.current(app)
        val store = WhaleFeatureStore(app)
        val id = "aaa-layout-"+java.util.UUID.randomUUID().toString().take(8)
        val title = "海星工时卡 · 界面验收"
        val revision = store.draft(JSONObject().put("id",id).put("title",title)
            .put("html","<p>界面验收</p>").put("baseRevision","")).getString("latest")
        fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
        fun waitFor(check: () -> Boolean) {
            val until = android.os.SystemClock.elapsedRealtime()+8000
            while (true) {
                var ready = false
                inst.runOnMainSync { ready = check() }
                if (ready) return
                assertTrue("工坊布局未就绪",android.os.SystemClock.elapsedRealtime()<until)
                Thread.sleep(80)
            }
        }
        try {
            for (style in listOf("simple","cute")) for (appearance in listOf("light","dark")) {
                prefs.edit().putString("style",style).commit()
                io.deepseekharness.mobile.AppThemePreference.save(app,appearance)
                val activity = inst.startActivitySync(Intent(app,WhaleFeatureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                fun texts() = all(activity.window.decorView).filterIsInstance<TextView>()
                fun card() = texts().firstOrNull { it.text == title }?.parent as? ViewGroup
                fun button(label: String) = card()?.let(::all)?.filterIsInstance<Button>()?.singleOrNull { it.text == label }
                try {
                    waitFor { button("启用此版本") != null && button("管理")?.width != 0 }
                    inst.runOnMainSync {
                        assertFalse(button("启用此版本")!!.isShown)
                        assertTrue(button("预览新版本")!!.isShown)
                        val cardWidth = card()!!.width
                        assertTrue("卡片宽度不足",cardWidth > app.resources.displayMetrics.density * 160)
                        assertTrue("卡片超出窗口",cardWidth <= activity.window.decorView.width)
                        val grid = card()!!.parent as android.widget.GridLayout
                        val expected = if (activity.resources.configuration.screenWidthDp / activity.resources.configuration.fontScale >= 600) 2 else 1
                        assertEquals(expected,grid.columnCount)
                        val options = texts().single { it.text == "工坊设置" }
                        assertFalse(texts().single { it.text == "导入原生功能清单" }.isShown)
                        options.performClick()
                        assertTrue(texts().single { it.text == "导入原生功能清单" }.isShown)
                        options.performClick()
                    }
                    inst.waitForIdleSync(); Thread.sleep(400)
                    val bitmap = inst.uiAutomation.takeScreenshot()
                    File(app.getExternalFilesDir(null),"workshop-$style-$appearance.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                    }
                    bitmap.recycle()
                    inst.runOnMainSync { button("管理")!!.performClick(); assertTrue(button("启用此版本")!!.isShown); button("启用此版本")!!.performClick() }
                    waitFor { store.read(id)?.optString("active") == revision && button("打开功能") != null }
                    inst.runOnMainSync { assertFalse(button("停用")!!.isShown); button("管理")!!.performClick(); button("停用")!!.performClick() }
                    waitFor { store.read(id)?.optString("active") == "" && button("预览新版本") != null }
                } finally { inst.runOnMainSync { activity.finish() }; inst.waitForIdleSync() }
            }
        } finally {
            prefs.edit().apply { if (old == null) remove("style") else putString("style",old) }.commit()
            io.deepseekharness.mobile.AppThemePreference.save(app,mode)
            android.util.AtomicFile(File(app.noBackupFilesDir,"whale-features-v1/$id.json")).delete()
        }
    }
}
