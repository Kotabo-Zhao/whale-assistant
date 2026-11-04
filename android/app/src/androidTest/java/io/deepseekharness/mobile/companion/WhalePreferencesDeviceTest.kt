package io.deepseekharness.mobile.companion

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhalePreferencesDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }

    @Test fun groupsLoadOnDemandAndReturnToTheirOrigin() {
        val old = InstrumentationRegistry.getArguments().getString("oldPreferences") == "true"
        fun launch(extras: Intent.() -> Unit = {}): CompanionActivity = inst.startActivitySync(
            Intent(app, CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(extras)) as CompanionActivity
        val start = SystemClock.elapsedRealtime()
        var activity = launch()
        fun tree() = views(activity.window.decorView)
        fun title(name: String) = tree().filterIsInstance<TextView>().firstOrNull { it.text == name }
        fun click(name: String) = inst.runOnMainSync {
            val label = requireNotNull(title(name)) { name }
            var target: View = label
            while (!target.isClickable) target = target.parent as View
            assertTrue(target.performClick())
        }
        fun back() = inst.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
        fun displayed(name: String) = inst.runOnMainSync { assertNotNull(name, title(name)) }
        try {
            inst.waitForIdleSync()
            inst.runOnMainSync {
                val nodes = tree().count()
                val actions = tree().count { it.isClickable }
                inst.sendStatus(0, Bundle().apply {
                    putInt("attachedViews", nodes); putInt("clickableViews", actions)
                    putLong("openToIdleMs", SystemClock.elapsedRealtime()-start)
                    val screenTitle = title("助手偏好")
                    if (screenTitle != null) {
                        val pos = IntArray(2); (screenTitle.parent as View).getLocationOnScreen(pos)
                        putInt("toolbarY",pos[1])
                        val bars = androidx.core.view.ViewCompat.getRootWindowInsets(screenTitle)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                        putInt("systemInsetTop", bars?.top ?: -1); putInt("screenWidthDp", activity.resources.configuration.screenWidthDp)
                        if (!old && bars != null) assertTrue("toolbar should follow status bar: ${pos[1]} / ${bars.top}", pos[1] <= bars.top + 2)
                    }
                })
                if (old) { assertTrue(actions > 60); assertNotNull(title("长期记忆")); assertNotNull(title("桌面形象")) }
                else {
                    assertTrue(actions <= 6)
                    assertNull(title("清空全部长期记忆")); assertNull(title("刷新通知记录"))
                    val root = activity.findViewById<View>(android.R.id.content)
                    val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(image))
                    File(app.getExternalFilesDir(null), "pet-preferences-home.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                    image.recycle()
                }
            }
            if (old) return
            click("桌宠与权限"); displayed("减少鲸鱼娘动态效果")
            click("系统权限"); displayed("打开悬浮窗权限"); back(); displayed("减少鲸鱼娘动态效果"); back()
            click("记忆与资料"); click("本地资料"); displayed("允许助手对话访问本地资料"); back()
            click("备份与恢复"); displayed("导出加密个人知识包"); back()
            click("长期记忆"); displayed("允许助手使用长期记忆"); back(); back()
            click("通知与提醒"); click("提醒与免打扰"); displayed("新建提醒"); back()
            click("后台通知整理"); displayed("查看后台理解状态"); back()
            click("通知记录"); displayed("刷新通知记录"); back(); back()
            click("日常助手"); click("日历与简报"); displayed("查看本地简报"); back()
            click("常用应用"); displayed("查看常用应用建议"); back(); back()
            inst.runOnMainSync { activity.finish() }; inst.waitForIdleSync()
            activity = launch { putExtra("open_notification_list",true) }
            displayed("刷新通知记录")
            back()
            inst.waitForIdleSync()
            assertTrue(activity.isFinishing || activity.isDestroyed)
        } finally { inst.runOnMainSync { if (!activity.isFinishing) activity.finish() } }
    }
    @Test fun restoresNestedPageAndItsBackPath() {
        val intent = Intent(app, CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<CompanionActivity>(intent).use { scenario ->
            fun go(label: String) = scenario.onActivity { activity ->
                var item: View = views(activity.window.decorView).filterIsInstance<TextView>().single { it.text == label }
                while (!item.isClickable) item = item.parent as View
                assertTrue(item.performClick())
            }
            fun checkTitle(label: String) = scenario.onActivity { activity ->
                val title = views(activity.window.decorView).filterIsInstance<TextView>().first { androidx.core.view.ViewCompat.isAccessibilityHeading(it) }
                assertEquals(label, title.text.toString())
            }
            go("记忆与资料"); go("备份与恢复"); go("旧格式与数据维护")
            scenario.recreate(); inst.waitForIdleSync(); checkTitle("旧格式与数据维护")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; checkTitle("备份与恢复")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; checkTitle("记忆与资料")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; checkTitle("助手偏好")
        }
        val authorized = CompanionStore(app).knowledgeModelAccess
        ActivityScenario.launch<CompanionActivity>(Intent(intent).putExtra("whale_preferences_page", "LIBRARY")).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text == "允许助手对话访问本地资料" })
                assertEquals(authorized, CompanionStore(app).knowledgeModelAccess)
            }
        }
    }

    @Test fun narrowWindowStacksFourFullWidthGroups() {
        val intent = Intent(app, CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<CompanionActivity>(intent).use { scenario ->
            // Exercise the native layout at a real allocated width without changing the user's display.
            lateinit var pane: View
            scenario.onActivity { activity ->
                pane = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val lp = pane.layoutParams
                lp.width = (300 * activity.resources.displayMetrics.density).toInt()
                pane.layoutParams = lp
            }
            inst.waitForIdleSync()
            scenario.onActivity { activity ->
                val groups = listOf("桌宠与权限","记忆与资料","通知与提醒","日常助手").map { label ->
                    views(activity.window.decorView).first { it.contentDescription?.toString()?.startsWith("$label，") == true }
                }
                val rows = groups.map { view -> IntArray(2).also { view.getLocationOnScreen(it) } }
                assertEquals(1, rows.map { it[0] }.distinct().size)
                assertTrue(rows.zipWithNext().all { (a,b) -> a[1] < b[1] })
                val widthDp = (pane.width / activity.resources.displayMetrics.density).toInt()
                assertTrue(widthDp in 299..301)
                val image = Bitmap.createBitmap(pane.width, pane.height, Bitmap.Config.ARGB_8888)
                pane.draw(Canvas(image))
                File(app.getExternalFilesDir(null),"pet-preferences-compact.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                image.recycle()
                inst.sendStatus(0, Bundle().apply { putInt("compactWidthDp", widthDp) })
            }
        }
    }

}
