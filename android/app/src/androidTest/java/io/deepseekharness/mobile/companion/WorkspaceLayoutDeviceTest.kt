package io.deepseekharness.mobile.companion

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.AppForeground
import io.deepseekharness.mobile.MainActivity
import io.deepseekharness.mobile.R
import io.deepseekharness.mobile.WorkspaceColumn
import io.deepseekharness.mobile.features.WhaleFeatureActivity
import io.deepseekharness.mobile.features.WhalePetFeatureActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the installed WebView, native preference hierarchy and actual system keyboard. */
class WorkspaceLayoutDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext
    private val await = PetControlDeviceUi::await

    private fun <T : Activity> open(type: Class<T>): T = type.cast(inst.startActivitySync(
        Intent(app, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))!!

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureLayouts") != "true") return
        inst.uiAutomation.waitForIdle(350, 2500)
        android.os.SystemClock.sleep(220)
        val directory = File(app.getExternalFilesDir(null), "layout-review").apply { mkdirs() }
        val image = inst.uiAutomation.takeScreenshot() ?: return
        try { File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { image.recycle() }
    }

    private fun report(key: String, count: Int) = inst.sendStatus(0, Bundle().apply { putInt(key, count) })

    @Test fun webPagesFitAndReturnToTheirParent() {
        val main = open(MainActivity::class.java)
        fun js(script: String): String {
            val done = CountDownLatch(1)
            var result = ""
            inst.runOnMainSync { main.bridge.webView.evaluateJavascript(script) { result = it; done.countDown() } }
            check(done.await(5, TimeUnit.SECONDS))
            return result
        }
        fun tap(selector: String): Boolean = js("""
            (() => { const b = [...document.querySelectorAll(${JSONObject.quote(selector)})].find(x => x.offsetHeight);
              if (!b || b.disabled) return false; b.scrollIntoView({block:'nearest'}); b.click(); return true })()
        """) == "true"
        fun nav(label: String) = tap("nav button[aria-label='$label']")
        var count = 0
        fun page(selector: String, save: String? = null) {
            try { await("layout for $selector") {
                js("""(() => {
                  const page = document.querySelector(${JSONObject.quote(selector)}), m = document.querySelector('.app-main');
                  if (!page?.offsetHeight || !m) return false;
                  const h = m.querySelector('h1,h2'), box = m.getBoundingClientRect();
                  return h && Math.abs(h.getBoundingClientRect().left - box.left - parseFloat(getComputedStyle(m).paddingLeft)) < 2 &&
                    m.scrollWidth <= m.clientWidth + 1 && document.documentElement.scrollWidth <= innerWidth + 1;
                })()""") == "true"
            } } catch (failure: IllegalStateException) {
                val state = js("""(() => { const m = document.querySelector('.app-main'), h = m?.querySelector('h1,h2');
                    return [m?.firstElementChild?.className, m?.firstElementChild?.dataset.page, m?.clientWidth,
                      m?.scrollWidth, h?.getBoundingClientRect().left, m?.getBoundingClientRect().left, m && getComputedStyle(m).paddingLeft];
                })()""")
                throw AssertionError("$selector window geometry: $state", failure)
            }
            count++
            if (save != null) capture(save)
        }
        fun settingsEntry(query: String, selector: String) {
            assertTrue(nav("我的"))
            val group = when (query) {
                "后台", "文件管理", "版本管理", "Ubuntu 运行时", "诊断" -> "workspace"
                "设备 Shell 设置", "自动化设置" -> "device"
                "鲸鱼娘人格" -> "assistant"
                "主题" -> "appearance"
                else -> "models"
            }
            val toggle = "button[aria-controls='settings-group-$group']"
            await("settings directory") { js("!!document.querySelector('.account-workspace')") == "true" }
            if (js("!!document.querySelector(${JSONObject.quote(toggle)})") == "false") assertTrue(tap(".account-advanced-toggle"))
            await("settings category $group") { js("!!document.querySelector(${JSONObject.quote(toggle)})") == "true" }
            if (js("document.querySelector(${JSONObject.quote(toggle)}).getAttribute('aria-expanded')") != "\"true\"") assertTrue(tap(toggle))
            val label = JSONObject.quote(query)
            await("settings entry $query") {
                js("""(() => {const b = [...document.querySelectorAll('.management-row')].find(x =>
                    x.offsetHeight && x.querySelector('strong')?.textContent.includes($label)); if (!b) return false;
                    b.scrollIntoView({block:'center'}); b.click(); return true})()""") == "true"
            }
            page(selector)
        }
        try {
            await("open the management workspace") {
                inst.runOnMainSync { main.findViewById<View>(R.id.harness_manage)?.takeIf { it.isShown }?.performClick() }
                nav("资料")
            }
            page(".memory-workspace")
            for (tab in listOf("memory", "reminders", "knowledge")) {
                assertTrue(tap("#companion-workspace-tab-$tab"))
                page("#companion-workspace-panel-$tab")
            }
            assertTrue(tap(".library-intake [aria-label='记一笔']")); page(".library-editor")
            await("loaded local materials") {
                js("document.querySelector('.library-results-heading span')?.textContent.startsWith('已显示 ')") == "true"
            }
            if (js("!!document.querySelector('.library-result')") == "true") {
                assertTrue(tap(".library-result"))
                page(".knowledge-detail-page")
                await("reader and its close action fit the visible window") {
                    js("""(() => { const b = document.querySelector('.knowledge-detail-page').getBoundingClientRect();
                        const close = document.querySelector('.knowledge-detail-page header button').getBoundingClientRect();
                        return b.top >= -1 && b.bottom <= innerHeight + 1 && b.width <= innerWidth + 1 &&
                          close.top >= 0 && close.bottom <= innerHeight + 1 })()""") == "true"
                }
                assertTrue(tap(".knowledge-detail-page header button"))
                report("existingMaterialOpened", 1)
            }
            assertTrue(nav("任务")); page(".assistant-tasks")
            assertTrue(nav("工具")); page(".tools-workspace")
            assertTrue(tap(".tools-plugin-link")); page(".plugin-screen")
            assertTrue(tap(".plugin-workspace-nav button:first-child")); page(".mobile-market")
            assertTrue(tap(".plugin-workspace-nav button:last-child")); page(".plugin-tabs")
            assertTrue(tap("#plugin-import-title")); page(".plugin-import-collapsible[open] input")
            assertTrue(nav("我的")); page(".account-workspace", "settings")
            val pages = mapOf(
                "后台" to "runtime", "模型与密钥" to "models", "设备 Shell 设置" to "device-shell",
                "主题" to "terminal", "自动化设置" to "automation", "诊断" to "diagnostics")
            for ((query, id) in pages) {
                val parent = ".settings-detail-page[data-page='$id']"
                settingsEntry(query, parent)
                if (id == "runtime") assertTrue(js("document.querySelector('#pet-start-settings').getBoundingClientRect().width > 65") == "true")
                val children = when (id) {
                    "terminal" -> listOf("terminal-background", "terminal-dsh-background")
                    "automation" -> listOf("automation-accessibility", "automation-subscreen", "automation-params")
                    "diagnostics" -> listOf("diagnostics-log", "diagnostics-capability", "diagnostics-feedback")
                    else -> emptyList()
                }
                for ((index, child) in children.withIndex()) {
                    if (id == "terminal") assertTrue(tap(".appearance-details:has(> .settings-subpage-links):not([open]) > summary") ||
                        js("!!document.querySelector('.appearance-details[open] .settings-subpage-links')") == "true")
                    val title = js("document.querySelector('.settings-detail-heading h1').textContent")
                    assertTrue(tap(".settings-subpage-links button:nth-child(${index + 1})"))
                    page(".settings-detail-page[data-page='$child']")
                    assertEquals("parent label of $child", title, js("document.querySelector('.page-parent').textContent.trim()"))
                    if (child == "automation-subscreen") {
                        assertEquals("1", js("document.querySelectorAll('.settings-detail-page h1').length"))
                        capture("subscreen")
                    }
                    assertTrue(tap(".page-parent"))
                    page(parent)
                }
            }
            for ((label, selector) in listOf("鲸鱼娘人格" to ".persona-page", "文件管理" to ".files-screen",
                "版本管理" to ".versions-screen", "Ubuntu 运行时" to ".environment-screen")) {
                settingsEntry(label, selector)
                if (selector == ".persona-page") {
                    assertTrue(tap(".persona-tabs button:nth-child(2)")); page(".persona-discover")
                    assertTrue(tap(".persona-tabs button:nth-child(3)")); page(".persona-transfer")
                }
            }
            assertTrue(tap("button[aria-label='会话管理']")); page(".session-screen")
            report("webPageVisits", count)
        } finally { inst.runOnMainSync { main.finish() } }
    }

    @Test fun nativePreferencesOpenEveryLevelAndKeepTheSearchDialogAboveIme() {
        val activity = open(CompanionActivity::class.java)
        fun views(): List<View> {
            fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup)
                (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
            return descendants(activity.window.decorView)
        }
        val visited = mutableSetOf<CompanionPreferencePage>()
        fun verify(page: CompanionPreferencePage) {
            await("native page $page") {
                var fits = false
                inst.runOnMainSync {
                    val all = views()
                    val title = all.filterIsInstance<TextView>().firstOrNull { ViewCompat.isAccessibilityHeading(it) && it.text == activity.getString(page.title) }
                    val column = all.filterIsInstance<WorkspaceColumn>().singleOrNull()
                    fits = title != null && title.isShown && title.width > 0 && title.layout != null && column != null &&
                        column.width > column.paddingLeft + column.paddingRight && column.paddingLeft > 0 &&
                        title.layout.getEllipsisCount(0) == 0
                }
                fits
            }
            visited.add(page)
        }
        fun visit(page: CompanionPreferencePage) {
            verify(page)
            if (page == CompanionPreferencePage.HOME) capture("native-home")
            if (page == CompanionPreferencePage.BACKUP) capture("native-backup")
            if (page == CompanionPreferencePage.LIBRARY) {
                var search: View? = null
                inst.runOnMainSync { search = views().filterIsInstance<TextView>().first { it.text == "查看或搜索资料" } }
                inst.runOnMainSync { search!!.performClick() }
                onView(isAssignableFrom(EditText::class.java)).inRoot(isDialog()).perform(click())
                onView(withText("关闭")).inRoot(isDialog()).check { close, _ ->
                    val visible = android.graphics.Rect()
                    val xy = IntArray(2)
                    close.getLocationOnScreen(xy)
                    close.getWindowVisibleDisplayFrame(visible)
                    assertTrue("dialog search keyboard is visible", ViewCompat.getRootWindowInsets(close)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
                    assertTrue("close action stays above IME", xy[1] + close.height <= visible.bottom + 1)
                }.perform(click())
            }
            val children = mutableListOf<CompanionPreferencePage>()
            inst.runOnMainSync {
                val links = views().filter(View::isClickable).mapNotNull { it.contentDescription?.toString() }
                children += CompanionPreferencePage.entries.filter { p ->
                    p.summary != 0 && links.any { it.startsWith(activity.getString(p.title) + "，") }
                }
            }
            for (child in children) {
                inst.runOnMainSync { views().first { it.isClickable && it.contentDescription?.toString()
                    ?.startsWith(activity.getString(child.title) + "，") == true }.performClick() }
                visit(child)
                inst.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
                verify(page)
            }
        }
        try {
            assertTrue("full-screen preferences own the foreground", AppForeground.isForeground())
            visit(CompanionPreferencePage.HOME)
            assertEquals(CompanionPreferencePage.entries.toSet(), visited)
            report("nativePreferencePages", visited.size)
        } finally { inst.runOnMainSync { activity.finish() } }
    }

    @Test fun workshopManagementRemainsReachable() {
        val activity = open(WhaleFeatureActivity::class.java)
        try {
            assertTrue("full-screen workshop owns the foreground", AppForeground.isForeground())
            onView(withText("工坊设置")).perform(click())
            onView(withText("刷新功能库")).check { button, _ ->
                assertTrue(button.isShown && button.width >= 48 * activity.resources.displayMetrics.density)
            }
            capture("native-workshop")
        } finally { inst.runOnMainSync { activity.finish() } }
    }

    @Test fun floatingToolKeepsTheDesktopPetAvailable() {
        val wasForeground = AppForeground.isForeground()
        val activity = open(WhalePetFeatureActivity::class.java)
        try {
            assertTrue(activity.window.isFloating)
            assertEquals("compact tool should not claim full-screen ownership", wasForeground, AppForeground.isForeground())
        } finally { inst.runOnMainSync { activity.finish() } }
    }
}
