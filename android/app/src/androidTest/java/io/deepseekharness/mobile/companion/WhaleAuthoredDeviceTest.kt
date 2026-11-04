package io.deepseekharness.mobile.companion

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.features.WhaleFeatureActivity
import io.deepseekharness.mobile.features.WhaleFeatureStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WhaleAuthoredDeviceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = inst.targetContext
    private val title = "阅读摘录卡"
    private val id = "whale-reading-card"
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun waitFor(why: String, action: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) { if (action()) return; Thread.sleep(60) }
        val names = listOf("工作台", "工具", "我的工具", "去对话创作", title, "更新运行环境")
        val focus = shell("dumpsys window").lineSequence().firstOrNull { it.contains("mCurrentFocus=") }
        // Only include known navigation labels and counts in diagnostics.
        val catalog = io.deepseekharness.mobile.features.WhaleFeatureStore(app).activeTools().size
        error("$why · 窗口=$focus · 根包=${inst.uiAutomation.rootInActiveWindow?.packageName} · 可见入口=${names.filter { labelled(it) != null }} · 目录=$catalog")
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun web(activity: WhaleFeatureActivity): WebView {
        var view: WebView? = null
        waitFor("工具页面未打开") { inst.runOnMainSync { view = views(activity.window.decorView).filterIsInstance<WebView>().firstOrNull() }; view != null }
        return requireNotNull(view)
    }
    private fun js(page: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        inst.runOnMainSync { page.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result
    }
    private fun click(activity: WhaleFeatureActivity, label: String, card: String? = null) {
        waitFor("找不到 $label") {
            var hit = false
            inst.runOnMainSync { hit = views(activity.window.decorView).filterIsInstance<Button>().firstOrNull { b ->
                b.isShown && b.text == label && (card == null || views(b.parent as View).filterIsInstance<TextView>().any { it.text == card })
            }?.performClick() == true }
            hit
        }
    }
    private fun find(node: AccessibilityNodeInfo?, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (test(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), test)?.let { return it }
        return null
    }
    private fun labelled(label: String) = find(inst.uiAutomation.rootInActiveWindow) { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun tap(node: AccessibilityNodeInfo) {
        var parent: AccessibilityNodeInfo? = node
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            parent = parent.parent
        }
        val r = Rect(); node.getBoundsInScreen(r); shell("input tap ${r.centerX()} ${r.centerY()}")
    }
    private fun tap(label: String) {
        waitFor("找不到界面入口 $label") { labelled(label)?.let { tap(it); true } ?: false }
    }
    private fun openedFrom(action: () -> Unit): WhaleFeatureActivity {
        val monitor = inst.addMonitor(WhaleFeatureActivity::class.java.name, null, false)
        try {
            action()
            return requireNotNull(inst.waitForMonitorWithTimeout(monitor, 12_000) as? WhaleFeatureActivity) { "未打开自建工具" }
        } finally { inst.removeMonitor(monitor) }
    }
    private fun enter(page: WebView, passage: String) {
        waitFor("生成页面缺少书名与摘录输入框") { js(page, "!!(document.querySelector('#book') && document.querySelector('#passage'))") == "true" }
        js(page, """(() => {
            for (const [id, text] of [['book','鲸鱼的读书笔记'],['passage',${JSONObject.quote(passage)}]]) {
                const field=document.getElementById(id); field.value=text; field.dispatchEvent(new Event('input',{bubbles:true}));
            }
        })()""")
        waitFor("真实页面未生成阅读卡") { js(page, "document.querySelector('output[data-whale-result]')?.value || ''").contains(passage) }
    }

    @Test fun oldShortcutFollowsPublicationAndRevokedVersionCannotCollect() {
        val feature = "qa-" + UUID.randomUUID()
        val tag = "版本保护 " + UUID.randomUUID()
        val store = WhaleFeatureStore(app)
        val knowledge = CompanionKnowledgeStore(app)
        assertNull(store.read(feature))
        fun draft(label: String, previous: String = "") = store.draft(JSONObject().put("id", feature).put("title", label).put("baseRevision", previous)
            .put("html", "<h2>$label</h2><output data-whale-result>$tag</output>")).getString("latest")
        fun launch(expected: String) = openedFrom { WhaleFeatureActivity.openTool(app, feature, expected) }
        var active: WhaleFeatureActivity? = null
        try {
            val first = draft("第一版")
            store.activate(feature, first, "")
            val next = draft("第二版", first)
            assertEquals("第一版", store.activeTools().single { it.id == feature }.title)
            store.activate(feature, next, first)
            active = launch(first)
            val page = web(active)
            waitFor("陈旧入口未解析到新启用版本") { js(page, "document.querySelector('h2')?.innerText") == "\"第二版\"" }
            click(active, "收藏结果")
            waitFor("核对框没有打开") { labelled("要收藏的结果") != null }
            store.activate(feature, first, next)
            tap("保存到资料")
            waitFor("旧版核对框仍在等待") { labelled("要收藏的结果") == null }
            assertEquals(0, knowledge.searchPage(tag,0,2).first.length())
            val shown = active
            inst.runOnMainSync { shown.finish() }
            active = launch(next)
            val rolledBack = web(active)
            waitFor("入口未跟随回滚") { js(rolledBack, "document.querySelector('h2')?.innerText") == "\"第一版\"" }
            val rolledActivity = active
            inst.runOnMainSync { rolledActivity.finish() }
            store.activate(feature, "", first)
            assertTrue(store.activeTools().none { it.id == feature })
            active = launch(next)
            waitFor("停用后旧入口没有回到工坊") { labelled("刷新功能库") != null }
            var rendered = false
            inst.runOnMainSync { rendered = views(active.window.decorView).any { it is WebView } }
            assertFalse(rendered)
        } finally {
            active?.let { current -> inst.runOnMainSync { current.finish() } }
            val row = store.read(feature)
            if (row != null && row.optString("active").isNotEmpty()) store.activate(feature, "", row.getString("active"))
            val fixture = File(app.noBackupFilesDir, "whale-features-v1/$feature.json")
            if (fixture.exists()) check(fixture.delete())
            knowledge.searchPage(tag,0,2).first.optJSONObject(0)?.let { knowledge.remove(it.getString("id"), it.getString("text")) }
        }
    }

    @Test fun taskCardOpensRealWorkerDraftAndPublishesReviewedVersion() {
        val worker = requireNotNull(InstrumentationRegistry.getArguments().getString("authorWorker"))
        val row = WorkerDashboardStore(app).list().getJSONArray("items").let { items ->
            (0 until items.length()).map(items::getJSONObject).single { it.optString("id") == worker }
        }
        val draft = row.getJSONObject("authoredDraft")
        val feature = draft.getString("id"); val version = draft.getString("revision")
        val store = WhaleFeatureStore(app)
        assertTrue(store.read(feature)!!.optString("active").isEmpty())
        val panel = CompanionWorkerPanel(app, {}, { _, _, _ -> })
        var activity: WhaleFeatureActivity? = null
        try {
            val opened = openedFrom {
                inst.runOnMainSync { panel.open() }
                waitFor("桌宠全部任务页未就绪") { labelled("全部任务")?.let { tap(it); true } ?: false }
                waitFor("任务卡没有草稿入口") {
                    var ok = false
                    inst.runOnMainSync {
                        val field = CompanionWorkerPanel::class.java.getDeclaredField("dialog").apply { isAccessible = true }
                        val dialog = field.get(panel) as? android.app.AlertDialog
                        val card = dialog?.window?.decorView?.let(::views)?.firstOrNull { it.contentDescription == "任务管理：" + row.getString("title") }
                        ok = card?.let(::views)?.filterIsInstance<Button>()?.firstOrNull { it.text == "预览工具草稿" }?.performClick() == true
                    }
                    ok
                }
            }
            activity = opened
            val page = web(opened)
            waitFor("未打开真实生成的换算器") { js(page, "!!document.querySelector('#hours')") == "true" }
            js(page, """const h = document.querySelector('#hours'); h.value='20'; h.dispatchEvent(new Event('input',{bubbles:true}));""")
            waitFor("20 小时未换算为 2.5 天") { js(page,"document.querySelector('output[data-whale-result]')?.value || ''").contains("2.5") }
            assertNull(labelled("收藏结果"))
            assertEquals("true", js(page, "location.hostname.endsWith('.preview.whale.invalid')"))
            click(opened, "启用草稿")
            waitFor("用户启用未落盘") { store.activeTools().any { it.id == feature && it.revision == version } }
            waitFor("没有切换到启用版") { js(web(opened),"location.hostname").contains(".features.whale.invalid") }
            js(web(opened), """const h = document.querySelector('#hours'); h.value='20'; h.dispatchEvent(new Event('input',{bubbles:true}));""")
            waitFor("启用版结果错误") { js(web(opened),"document.querySelector('output[data-whale-result]')?.value || ''").contains("2.5") }
            inst.uiAutomation.waitForIdle(280, 1500)
            if (InstrumentationRegistry.getArguments().getString("captureProof") == "true") {
                val screen = requireNotNull(inst.uiAutomation.takeScreenshot())
                File(app.getExternalFilesDir(null), "whale-worker-tool.png").outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                screen.recycle()
            }
            inst.sendStatus(0, Bundle().apply { putString("publishedWorkerTool", feature); putString("verifiedConversion", "20 h = 2.5 days") })
        } finally { inst.runOnMainSync { activity?.finish(); panel.destroy() } }
    }

    @Test fun followUpUpdatesSameToolWithoutChangingPublishedVersion() {
        val arguments = InstrumentationRegistry.getArguments()
        val worker = requireNotNull(arguments.getString("authorWorker"))
        val first = requireNotNull(arguments.getString("firstRevision"))
        val workers = WorkerDashboardStore(app)
        fun row() = workers.list().getJSONArray("items").let { data ->
            (0 until data.length()).map(data::getJSONObject).single { it.optString("id") == worker }
        }
        val initial = row()
        val featureId = initial.getJSONObject("authoredDraft").getString("id")
        val features = WhaleFeatureStore(app)
        assertEquals(first, features.read(featureId)!!.getString("active"))
        val request = "请在这项工时换算工具上追加按周换算（5个工作日为一周）。先 read 查看原稿，传 baseRevision 修改同一工具。保留 #hours 输入和每天工时选择，同时在同一个 output data-whale-result 显示天数与周数。按8小时/天，输入20小时显示2.5天和0.5周。请为横屏掌机紧凑排列输入区与结果区。"
        val wake = WorkerRuntimeWake(app)
        var activity: WhaleFeatureActivity? = null
        try {
            wake.wake()
            val inputs = initial.getJSONArray("inputs")
            if ((0 until inputs.length()).none { inputs.getJSONObject(it).optString("text") == request }) {
                CompanionDashboard(app).execute(JSONObject().put("operation","workerControl").put("id",worker).put("action","add").put("task",request))
            }
            val until = SystemClock.elapsedRealtime() + 420_000
            var latest = first
            var lastState = ""
            while (SystemClock.elapsedRealtime() < until) {
                val current = row()
                val state = current.optString("state")
                if (lastState != state) {
                    lastState = state
                    inst.sendStatus(0,Bundle().apply { putString("followUpState",state) })
                }
                check(state !in setOf("failed","blocked","cancelled","interrupted")) { "追加创作中断：$state " + current.optString("summary").take(200) }
                val draft = current.getJSONObject("authoredDraft")
                assertEquals(featureId, draft.getString("id"))
                latest = draft.getString("revision")
                if (state == "completed" && latest != first) break
                Thread.sleep(400)
            }
            assertNotEquals("没有收到同一工具的新版草稿", first, latest)
            assertEquals("completed",row().getString("state"))
            assertEquals(first, features.read(featureId)!!.getString("active"))
            assertEquals(latest, features.read(featureId)!!.getString("latest"))
            val opened = openedFrom {
                CompanionDashboard(app).execute(JSONObject().put("operation","workerPreviewDraft").put("id",worker).put("revision",latest))
            }
            activity = opened
            fun calculate(expectedHost: String): String {
                waitFor("工具没有进入预期版本") {
                    js(web(opened),"!!document.querySelector('#hours') && location.hostname.endsWith('$expectedHost')") == "true"
                }
                val page = web(opened)
                js(page,"""(() => { const h=document.getElementById('hours'); h.value='20'; h.dispatchEvent(new Event('input',{bubbles:true})); })()""")
                var result = ""
                waitFor("工具没有同时返回 2.5 天与 0.5 周") {
                    result = js(page,"document.querySelector('output[data-whale-result]')?.value || ''")
                    "2.5" in result && "0.5" in result
                }
                return result
            }
            calculate(".preview.whale.invalid")
            click(opened,"启用草稿")
            val value = calculate(".features.whale.invalid")
            assertEquals(latest,features.read(featureId)!!.getString("active"))
            inst.uiAutomation.waitForIdle(250,1800)
            if (arguments.getString("captureProof") == "true") {
                val screen = requireNotNull(inst.uiAutomation.takeScreenshot())
                File(app.getExternalFilesDir(null),"whale-worker-tool-v2.png").outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                screen.recycle()
            }
            features.activate(featureId, first, latest)
            assertEquals(first,features.activeTools().single { it.id == featureId }.revision)
            features.activate(featureId, latest, first)
            inst.sendStatus(0,Bundle().apply {
                putString("previousRevision",first); putString("newRevision",latest); putString("evaluatedOutput",value.take(240))
            })
        } finally { activity?.let { a -> inst.runOnMainSync { a.finish() } }; wake.close() }
    }

    @Test fun previewAndPublishReadingCard() {
        val store = WhaleFeatureStore(app)
        val row = requireNotNull(store.read(id))
        val version = row.getString("latest")
        val activity = inst.startActivitySync(Intent(app, WhaleFeatureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WhaleFeatureActivity
        try {
            assertTrue(row.optString("active").isEmpty())
            click(activity, "预览新版本", title)
            val page = web(activity)
            enter(page, "预览验收，草稿中的内容只在预览中显示。")
            assertNull(labelled("收藏结果"))
            assertEquals("true", js(page, "location.hostname.endsWith('.preview.whale.invalid') && typeof window.Android === 'undefined'"))
            inst.runOnMainSync { activity.onBackPressed() }
            click(activity, "启用此版本", title)
            waitFor("启用未持久化") { store.activeTools().any { it.id == id && it.revision == version && it.title == title } }
        } finally { inst.runOnMainSync { activity.finish() } }
    }

    @Test fun launchFromManagementCollectEditedResultAndReturnToSourceFromPet() {
        val pkg = app.packageName
        val flags = inst.uiAutomation.serviceInfo
        flags.flags = flags.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        inst.uiAutomation.serviceInfo = flags
        shell("am start -W -n $pkg/.MainActivity")
        waitFor("主界面导航未就绪") {
            if (labelled("工具") != null) true else {
                find(inst.uiAutomation.rootInActiveWindow) { it.viewIdResourceName?.endsWith(":id/harness_manage") == true }?.let(::tap)
                false
            }
        }
        waitFor("工具页未显示已启用的工具") {
            if (labelled("去对话创作") != null) {
                if (labelled(title) != null) true else {
                    find(inst.uiAutomation.rootInActiveWindow) { it.text?.toString()?.startsWith("显示其余 ") == true }?.let(::tap)
                    false
                }
            } else {
                val tab = find(inst.uiAutomation.rootInActiveWindow) { it.viewIdResourceName?.endsWith(":id/harness_tab_plugins") == true } ?: labelled("工具")
                tab?.let { node ->
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (!bounds.isEmpty) shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                }
                false
            }
        }
        inst.uiAutomation.waitForIdle(300, 3000)
        val activity = openedFrom {
            val chip = requireNotNull(labelled(title))
            chip.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            inst.uiAutomation.waitForIdle(180, 1800)
            val target = requireNotNull(labelled(title))
            val r = Rect(); target.getBoundsInScreen(r)
            check(!r.isEmpty && target.isVisibleToUser)
            tap(target)
        }
        val note = "核对原句 " + UUID.randomUUID().toString().take(8)
        val edited = "我收藏的阅读摘录 · $note"
        val knowledge = CompanionKnowledgeStore(app)
        try {
            val page = web(activity)
            enter(page, note)
            click(activity, "收藏结果")
            waitFor("未打开收藏核对框") { labelled("要收藏的结果") != null }
            val preview = requireNotNull(labelled("要收藏的结果"))
            assertTrue(preview.text.toString().contains(note))
            tap("取消")
            waitFor("取消后核对框未关闭") { labelled("要收藏的结果") == null }
            inst.uiAutomation.waitForIdle(200, 1500)
            assertEquals(0, knowledge.searchPage(note, 0, 4).first.length())
            click(activity, "收藏结果")
            waitFor("第二次核对框未打开") { labelled("要收藏的结果")?.isEditable == true }
            waitFor("不能编辑要收藏的内容") {
                labelled("要收藏的结果")?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, edited)
                }) == true
            }
            tap("保存到资料")
            waitFor("资料未保存") { knowledge.searchPage(note,0,3).first.length() > 0 }
            val saved = knowledge.searchPage(note,0,3).first.getJSONObject(0)
            assertEquals(edited, saved.getString("text")); assertEquals("自建功能 · $title", saved.getString("shareOrigin"))
            waitFor("核对框未关闭") { labelled("要收藏的结果") == null }
            inst.uiAutomation.waitForIdle(220, 2000)
            val rendered = inst.uiAutomation.takeScreenshot()
            if (rendered != null && InstrumentationRegistry.getArguments().getString("captureProof") == "true") {
                File(app.getExternalFilesDir(null), "whale-reading-card.png").outputStream().use { rendered.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            rendered?.recycle()
        } finally {
            knowledge.searchPage(note,0,3).first.optJSONObject(0)?.let { knowledge.remove(it.getString("id"),it.getString("text")) }
            inst.runOnMainSync { activity.finish() }
        }
        shell("am start -W -a android.intent.action.VIEW -d about:blank -p com.android.chrome")
        waitFor("Chrome 没有在前台") { inst.uiAutomation.rootInActiveWindow?.packageName == "com.android.chrome" }
        var pet: CompanionBubbleWindow? = null
        try {
            lateinit var inline: WebView
            run {
                inst.runOnMainSync {
                    pet = CompanionBubbleWindow(app, app.getSystemService(android.view.WindowManager::class.java), autoOpenOnDelivery = false) { intArrayOf(36,140,120,120) }
                    pet!!.show()
                    val root = CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible = true }.get(pet) as View
                    views(root).filterIsInstance<android.widget.TextView>().single { it.text == app.getString(io.deepseekharness.mobile.R.string.pet_menu_tools) }.performClick()
                }
                // The foreground browser can own accessibility focus. Click the visible native chip in the overlay.
                waitFor("桌宠没有显示已启用的工具") {
                    var clicked = false
                    inst.runOnMainSync {
                        val root = CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible = true }.get(pet) as? View
                        clicked = root?.let(::views)?.firstOrNull { it.isShown && it.contentDescription == "打开工具 · $title" }?.performClick() == true
                    }
                    clicked
                }
            }
            waitFor("桌宠没有挂载工具页面") {
                var found = false
                inst.runOnMainSync {
                    val root = CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible = true }.get(pet) as View
                    val shelf = views(root).filterIsInstance<WhalePetToolShelf>().firstOrNull()
                    shelf?.let(::views)?.filterIsInstance<WebView>()?.firstOrNull()?.let { inline = it; found = true }
                }
                found
            }
            enter(inline, "桌宠直接打开同一份启用版本")
            assertEquals("true", js(inline, "typeof window.Android === 'undefined' && typeof window.Capacitor === 'undefined'"))
            inst.runOnMainSync { pet!!.hide() }
            waitFor("桌宠工具关闭后没有返回原浏览器") { inst.uiAutomation.rootInActiveWindow?.packageName == "com.android.chrome" }
            inst.sendStatus(0, Bundle().apply { putString("entryPoints", "main management, pet above Chrome, Chrome resumed"); putString("savedSource", "自建功能 · $title") })
        } finally { inst.runOnMainSync { pet?.destroy() } }
    }
}
