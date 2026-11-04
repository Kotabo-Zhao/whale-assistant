package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WhaleWorkerListPerfTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun fixture(i: Int): JSONObject {
        val id = UUID.nameUUIDFromBytes("perf-$i".toByteArray()).toString()
        val body = "资料整理样例仅用于测量 ".repeat(12)
        val inputs = JSONArray()
        val reports = JSONArray()
        repeat(24) { n ->
            inputs.put(JSONObject().put("id","input-$n").put("revision",n+1).put("text","$i/$n $body").put("kind","reply").put("delivery","delivered"))
            reports.put(JSONObject().put("revision",n+1).put("summary","回执 $body"))
        }
        return JSONObject().put("id",id).put("sessionId","session-worker-$id").put("owner","desk-${i%5}")
            .put("mode","read").put("group","目标 ${i%8}").put("title","测试任务 $i").put("text","测试任务 $i")
            .put("state",if (i%30==0) "waiting" else "completed").put("summary","结果 $i").put("inputs",inputs)
            .put("reports",reports).put("writeSeq",1).put("createdAt",1000L+i).put("updatedAt",1000L+i)
    }
    @Test fun groupReportIncludesResultsOutsideTheSelectedPage() {
        val root = File(inst.targetContext.cacheDir, "worker-group-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val context = object : ContextWrapper(inst.targetContext) {
            override fun getNoBackupFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        try {
            val store = WorkerDashboardStore(context)
            val states = List(7) { "completed" } + listOf("waiting", "failed", "running", "cancelled")
            states.forEachIndexed { i, state -> store.put(fixture(i).put("group", "同一目标").put("state", state)) }
            store.put(fixture(90).put("group", "其他目标"))
            val page = store.page("attention", "同一目标", limit = 1)
            assertEquals(1, page.getJSONArray("items").length())
            val report = page.getJSONObject("groupReport")
            assertEquals(2, report.getJSONObject("attention").getInt("count"))
            assertEquals(1, report.getJSONObject("working").getInt("count"))
            assertEquals(1, report.getJSONObject("cancelled").getInt("count"))
            val done = report.getJSONObject("done")
            assertEquals(7, done.getInt("count"))
            val samples = done.getJSONArray("items")
            assertEquals(4, samples.length())
            val source = samples.getJSONObject(0)
            assertEquals(source.getString("note"), store.detail(source.getString("id")).getString("summary"))
            assertFalse(store.page(limit = 0).has("groupReport"))
        } finally { root.deleteRecursively() }
    }

    @Test fun mixedFormatsPageAndDetailKeepHistoryControlAndDependencies() {
        val root = File(inst.targetContext.cacheDir,"worker-page-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val context = object : ContextWrapper(inst.targetContext) {
            override fun getNoBackupFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        try {
            val native = File(root,"worker-dashboard").apply { mkdir() }
            val guest = File(root,"dsh-runtime/current/root/.dsh/mobile-workers").apply { mkdirs() }
            val store = WorkerDashboardStore(context)
            fun install(i: Int, state: String, legacy: Boolean): JSONObject {
                val row = fixture(i).put("state",state)
                val id = row.getString("id")
                if (legacy) File(native,"$id.json").writeText(row.toString()) else store.put(row)
                File(guest,"$id.json").writeText("{}")
                return row
            }
            val parent = install(1,"failed",true)
            val child = install(2,"queued",false).put("dependsOn",JSONArray().put(parent.getString("id")))
            store.put(child.put("writeSeq",2))
            val missing = install(3,"waiting",true)
            check(File(guest,missing.getString("id")+".json").delete())
            val done = install(4,"completed",false)
            val all = store.page(limit=2)
            assertEquals(4,all.getInt("total"))
            val ids = (0..1).map { all.getJSONArray("items").getJSONObject(it).getString("id") }
            val second = store.page(offset=2,limit=2).getJSONArray("items")
            assertTrue((0..1).none { second.getJSONObject(it).getString("id") in ids })
            assertEquals(0,all.getInt("running")); assertEquals(2,all.getInt("attention")); assertEquals(0,all.getInt("asking"))
            val selected = store.page("attention",child.getString("group")).getJSONArray("items").getJSONObject(0)
            assertTrue(selected.getBoolean("blockedDependency"))
            assertEquals(child.getString("id"),selected.getString("id"))
            assertEquals(parent.getString("id"),selected.getJSONObject("dependencyTarget").getString("id"))
            assertTrue(selected.getString("waitReason").contains("失败，需重试"))
            assertFalse(selected.has("inputs")); assertFalse(selected.has("reports"))
            val history = store.detail(child.getString("id"))
            assertEquals(48,history.getInt("historyCount")); assertEquals(12,history.getJSONArray("events").length())
            val pages = (0 until 48 step 12).flatMap { n ->
                val events = store.detail(child.getString("id"),n).getJSONArray("events")
                (0 until events.length()).map { events.getJSONObject(it) }
            }
            assertEquals(48,pages.map { it.getString("type") + it.getInt("revision") }.distinct().size)
            assertEquals(child.getJSONArray("inputs").getJSONObject(0).getString("text"),pages.single { it.getInt("revision")==1 && it.getString("type")=="input" }.getString("text"))
            val childFile = File(native,child.getString("id")+".json")
            assertEquals(24,JSONObject(childFile.readText()).getJSONArray("inputs").length())
            val compat = store.list().getJSONArray("items")
            assertTrue((0 until compat.length()).none { compat.getJSONObject(it).has("_whaleWorkerCardV1") })
            assertTrue(store.detail(missing.getString("id")).optBoolean("missingLedger"))
            File(guest,missing.getString("id")+".json").writeText("{}")
            assertEquals(1,store.page(limit=0).getInt("asking"))
            store.put(parent.put("writeSeq",2).put("state","completed"))
            assertEquals(1,store.page(limit=0).getInt("running"))
            store.put(JSONObject(parent.toString()).put("writeSeq",1).put("state","failed"))
            assertEquals(1,store.page(limit=0).getInt("running"))
            val readyChild = store.page("running",child.getString("group")).getJSONArray("items").getJSONObject(0)
            assertEquals(0,readyChild.getInt("remainingDependencies"))
            assertFalse(readyChild.has("dependencyTarget"))
            store.put(JSONObject(parent.toString()).put("writeSeq",5).put("state","interrupted"))
            val parked = WorkerDashboardStore(context).page("attention",child.getString("group")).getJSONArray("items").getJSONObject(0)
            assertTrue(parked.getString("waitReason").contains("中断，需确认"))
            assertEquals(parent.getString("id"),parked.getJSONObject("dependencyTarget").getString("id"))
            store.put(JSONObject(parent.toString()).put("writeSeq",6).put("state","completed"))
            val gate = WorkerDashboardStore::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(null)
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                synchronized(gate) {
                    val receipt = executor.submit<JSONObject> { store.submit("继续交办", "read") }.get(2, java.util.concurrent.TimeUnit.SECONDS)
                    assertTrue(receipt.has("commandId"))
                }
            } finally { executor.shutdownNow() }
            assertEquals(2,store.page(limit=0).getInt("running"))
            assertEquals(1,store.page("all",done.getString("group")).getInt("total"))
        } finally { root.deleteRecursively() }
    }

    @Test fun badLegacyMirrorCannotHideOtherTasksOrPreventOwnerChecks() {
        val root = File(inst.targetContext.cacheDir,"worker-corrupt-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val ctx = object : ContextWrapper(inst.targetContext) {
            override fun getNoBackupFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        try {
            val dir = File(root,"worker-dashboard").apply { mkdir() }
            val good = fixture(0)
            File(dir,good.getString("id")+".json").writeText(good.toString())
            val damaged = fixture(1)
            val file = File(dir,damaged.getString("id")+".json")
            file.writeText("{incomplete")
            val store = WorkerDashboardStore(ctx)
            assertTrue(store.activeFor(good.getString("owner")))
            val page = store.page(withCounts=false)
            val items = page.getJSONArray("items")
            val bad = (0 until items.length()).map(items::getJSONObject).single { it.getString("id")==damaged.getString("id") }
            assertTrue(bad.getBoolean("recordUnreadable")); assertTrue(bad.getBoolean("missingLedger"))
            assertEquals("{incomplete",file.readText())
            file.writeText(damaged.toString())
            val repaired = store.page(withCounts=false).getJSONArray("items")
            assertFalse((0 until repaired.length()).any { repaired.getJSONObject(it).optBoolean("recordUnreadable") })
        } finally { root.deleteRecursively() }
    }

    @Test fun collapsedTaskSectionDoesNotPollChannelSettings() {
        val baseline = InstrumentationRegistry.getArguments().getString("baseline") == "true"
        val main = inst.startActivitySync(android.content.Intent(inst.targetContext,io.deepseekharness.mobile.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as io.deepseekharness.mobile.MainActivity
        fun js(script: String): String {
            val latch = java.util.concurrent.CountDownLatch(1); var value = ""
            inst.runOnMainSync { main.bridge.webView.evaluateJavascript(script) { value = it; latch.countDown() } }
            check(latch.await(5,java.util.concurrent.TimeUnit.SECONDS)); return value
        }
        fun until(message: String, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 12000
            while (!condition()) { check(SystemClock.elapsedRealtime() < end) { message }; Thread.sleep(100) }
        }
        val nav = "Array.from(document.querySelectorAll('.task-destinations button'))"
        try {
            until("事项入口未就绪") {
                inst.runOnMainSync { main.findViewById<android.view.View>(io.deepseekharness.mobile.R.id.harness_manage)?.takeIf { it.isShown }?.performClick() }
                js("!!document.querySelector('button[aria-label=事项]')") == "true"
            }
            js("""const cap=window.Capacitor; window.whalePollOriginal=cap.nativePromise; window.whalePollReads=0;
                cap.nativePromise=function(plugin,method,options){
                    if(plugin==='MobileRuntime' && method==='companionDashboard' && options?.request?.operation==='superagentSettings') window.whalePollReads++;
                    return window.whalePollOriginal.apply(this,arguments);
                }; document.querySelector('button[aria-label=事项]').click()""")
            until("后台任务设置未就绪") { js("!!document.querySelector('.worker-secondary-settings summary')") == "true" }
            js("document.querySelector('.worker-secondary-settings summary').click()")
            until("跨渠道入口未就绪") { js("!!document.querySelector('.worker-secondary-settings details summary')") == "true" }
            val closedProbe = InstrumentationRegistry.getArguments().getString("innerProbe")
            if (closedProbe != null) {
                Thread.sleep(11_000)
                val reads = js("window.whalePollReads").toInt()
                inst.sendStatus(0,Bundle().apply { putInt("unopenedChannelReads",reads); putInt("sampleMs",11000) })
                if (closedProbe == "before") { assertTrue(reads > 0); return }
                assertEquals(0,reads)
            }
            js("document.querySelector('.worker-secondary-settings details summary').click()")
            until("未读取展开的渠道设置") { js("window.whalePollReads > 0") == "true" }
            if (closedProbe == "after") {
                val inner = "document.querySelector('.worker-secondary-settings details')"
                val repo = "$inner.querySelector('input:not([type])')"
                until("渠道表单读取尚未完成") { js("!$repo.matches(':disabled')") == "true" }
                js("""(() => { const field=$repo;
                    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(field,'fixture-unsaved');
                    field.dispatchEvent(new InputEvent('input',{bubbles:true,data:'fixture-unsaved'})); })()""")
                until("未输入测试草稿") { js("$repo.value") == "\"fixture-unsaved\"" }
                js("$inner.querySelector('summary').click()")
                until("渠道设置未收起") { js("$inner.open") == "false" }
                val before = js("window.whalePollReads").toInt()
                Thread.sleep(11_000)
                val reads = js("window.whalePollReads").toInt() - before
                assertEquals(0,reads)
                js("document.querySelector('.worker-secondary-settings summary').click()")
                until("外层设置未收起") { js("document.querySelector('.worker-secondary-settings').open") == "false" }
                js("document.querySelector('.worker-secondary-settings summary').click()")
                js("$inner.querySelector('summary').click()")
                until("展开后未刷新渠道状态") { js("window.whalePollReads").toInt() > before }
                assertEquals("\"fixture-unsaved\"",js("$repo.value"))
                inst.sendStatus(0,Bundle().apply { putInt("recollapsedChannelReads",reads); putString("draft","preserved") })
            }
            js("$nav.find(b=>b.textContent==='我的待办').click()")
            until("没有离开后台任务页") { js("$nav.find(b=>b.textContent==='我的待办').getAttribute('aria-pressed')") == "\"true\"" }
            val start = js("window.whalePollReads").toInt()
            Thread.sleep(16_000)
            val hiddenReads = js("window.whalePollReads").toInt() - start
            inst.sendStatus(0,Bundle().apply { putInt("hiddenSectionChannelReads",hiddenReads); putInt("sampleMs",16000) })
            if (baseline) assertTrue("旧版本应复现后台读取", hiddenReads > 0) else assertEquals(0,hiddenReads)
            js("$nav.find(b=>b.textContent==='后台任务').click()")
            until("返回后渠道诊断没有刷新") { js("window.whalePollReads").toInt() > start + hiddenReads }
            if (!baseline) {
                js("""window.whaleWentHidden=false; window.whaleHiddenReads=0;
                    const observe=window.Capacitor.nativePromise;
                    window.Capacitor.nativePromise=function(plugin,method,options){
                        if(document.hidden && plugin==='MobileRuntime' && method==='companionDashboard' &&
                            ['superagentSettings','screenWatchStatus'].includes(options?.request?.operation)) window.whaleHiddenReads++;
                        return observe.apply(this,arguments);
                    };
                    document.addEventListener('visibilitychange',()=>{if(document.hidden) window.whaleWentHidden=true})""")
                val beforeHome = js("window.whalePollReads").toInt()
                android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")).use { it.readBytes() }
                Thread.sleep(16_000)
                inst.targetContext.startActivity(android.content.Intent(inst.targetContext,io.deepseekharness.mobile.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                until("应用返回时未恢复已展开的渠道设置") {
                    js("window.whaleWentHidden===true && !document.hidden && window.whalePollReads>$beforeHome") == "true"
                }
                val sleepingReads = js("window.whaleHiddenReads").toInt()
                inst.sendStatus(0,Bundle().apply { putInt("backgroundChannelAndScreenReads",sleepingReads); putInt("backgroundSampleMs",16000) })
                assertEquals(0,sleepingReads)
            }
        } finally {
            js("if(window.whalePollOriginal) window.Capacitor.nativePromise=window.whalePollOriginal")
            inst.runOnMainSync { main.finish() }
        }
    }

    @Test fun installedTaskPageFiltersPagesAndReadsOneActualTimeline() {
        val args = InstrumentationRegistry.getArguments()
        val known = requireNotNull(args.getString("authorWorker"))
        val store = WorkerDashboardStore(inst.targetContext)
        val task = store.detail(known)
        val title = JSONObject.quote(task.getString("title"))
        val main = inst.startActivitySync(android.content.Intent(inst.targetContext,io.deepseekharness.mobile.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as io.deepseekharness.mobile.MainActivity
        fun js(script: String): String {
            val latch = java.util.concurrent.CountDownLatch(1); var out = ""
            inst.runOnMainSync { main.bridge.webView.evaluateJavascript(script) { out=it; latch.countDown() } }
            check(latch.await(4, java.util.concurrent.TimeUnit.SECONDS)); return out
        }
        fun until(why: String, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 15_000
            while (!condition()) {
                check(SystemClock.elapsedRealtime() < end) {
                    why + " range=" + js("document.querySelector('.worker-page-nav span')?.textContent") +
                        " filter=" + js("document.querySelector('.worker-catalog-controls select')?.value")
                }
                Thread.sleep(60)
            }
        }
        try {
            until("主应用事项入口未就绪") {
                inst.runOnMainSync { main.findViewById<android.view.View>(io.deepseekharness.mobile.R.id.harness_manage)?.takeIf { it.isShown }?.performClick() }
                js("!!document.querySelector('button[aria-label=事项]')") == "true"
            }
            js("document.querySelector('button[aria-label=事项]').click()")
            val total = store.page(limit=0).getInt("total")
            check(total > 20)
            until("第一页未加载") { js("document.querySelector('.worker-page-nav span')?.textContent") == "\"1–20 / $total\"" }
            js("Array.from(document.querySelectorAll('.worker-page-nav button')).find(b=>b.textContent==='下一页').click()")
            until("第二页未加载") { js("document.querySelector('.worker-page-nav span')?.textContent").startsWith("\"21–") }
            js("""(() => {const s=document.querySelector('.worker-catalog-controls select');s.value='attention';s.dispatchEvent(new Event('change',{bubbles:true}))})()""")
            var attention = 0
            until("待处理筛选未刷新") {
                attention = store.page("attention",limit=0).getInt("total")
                if (attention == 0) js("document.body.innerText.includes('当前筛选下没有任务。')") == "true"
                else js("document.querySelector('.worker-page-nav span')?.textContent") == "\"1–${minOf(attention,20)} / $attention\""
            }
            js("""(() => {const s=document.querySelector('.worker-catalog-controls select');s.value='all';s.dispatchEvent(new Event('change',{bubbles:true}))})()""")
            until("恢复全部任务失败") { js("document.querySelector('.worker-page-nav span')?.textContent") == "\"1–20 / $total\"" }
            val locate = """Array.from(document.querySelectorAll('.assistant-task-list article')).find(a=>a.querySelector('strong')?.textContent===$title)"""
            var page = 0
            while (js("!!($locate)") != "true") {
                check((page+1)*20 < total) { "旧创作工单无法分页定位" }
                page++
                js("Array.from(document.querySelectorAll('.worker-page-nav button')).find(b=>b.textContent==='下一页').click()")
                until("翻页未完成") { js("document.querySelector('.worker-page-nav span')?.textContent").startsWith("\"${page*20+1}–") }
            }
            js("""Array.from(($locate).querySelectorAll('summary')).find(b=>b.textContent==='任务详情').click()""")
            until("未读到此工单的真实执行记录") { js("($locate).innerText.includes('回执') && ($locate).innerText.includes('已进入任务会话')") == "true" }
            inst.sendStatus(0,Bundle().apply { putInt("pagedTasks",total); putInt("attentionFilter",attention); putString("timeline","input and report") })
            val seconds = args.getString("idleSeconds")?.toIntOrNull()
            if (seconds != null) {
                require(seconds in 30..180)
                val token = "WhaleIdle-" + UUID.randomUUID().toString().take(8)
                val pid = android.os.Process.myPid()
                fun marker(value: String) = android.util.Log.i("WhaleDeskIdle","$token-$value")
                fun temp() = inst.targetContext.registerReceiver(null,android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    ?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE,-1) ?: -1
                marker("calibrate")
                js("Array.from(document.querySelectorAll('.worker-catalog-controls button')).find(b=>b.textContent==='刷新任务').click()")
                Thread.sleep(2000)
                marker("start")
                val t = SystemClock.elapsedRealtime(); val cpu = android.os.Process.getElapsedCpuTime(); val temperature = temp()
                Thread.sleep(seconds*1000L)
                val used = android.os.Process.getElapsedCpuTime()-cpu
                marker("end")
                val elapsed = SystemClock.elapsedRealtime()-t
                val endTemp = temp()
                inst.sendStatus(0,Bundle().apply {
                    putString("idleToken",token); putInt("profilePid",pid)
                    putLong("visibleIdleMs",elapsed); putLong("visibleIdleCpuMs",used)
                    putInt("startTemp",temperature); putInt("endTemp",endTemp)
                })
            }

        } finally { inst.runOnMainSync { main.finish() } }
    }
    @Test fun petOpensCompletedSourceWhileViewingOpenWork() {
        val app = inst.targetContext
        val root = File(app.cacheDir, "group-window-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val args = InstrumentationRegistry.getArguments()
        val style = args.getString("petStyle") ?: "cute"
        val mode = args.getString("petMode") ?: "light"
        val ctx = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = root
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, flags: Int) = app.getSharedPreferences(
                if (name in setOf("whale-interface","app_theme")) "${root.name}-$name" else name,flags)
        }
        check(ctx.getSharedPreferences("whale-interface",0).edit().putString("style",style).commit())
        io.deepseekharness.mobile.AppThemePreference.save(ctx,mode)
        val store = WorkerDashboardStore(ctx)
        val group = "联调目标"
        store.put(fixture(1).put("group",group).put("title","核对旅行日期").put("state","waiting").put("question","周五还是周六出发？"))
        store.put(fixture(3).put("group","另一目标").put("state","running"))
        store.put(fixture(2).put("group",group).put("state","completed"))
        lateinit var panel: CompanionWorkerPanel
        fun views(v: android.view.View): List<android.view.View> = listOf(v) + if (v is android.view.ViewGroup)
            (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        fun until(field: String, check: (List<android.widget.TextView>) -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 8000
            do {
                var ok = false
                inst.runOnMainSync {
                    val window = CompanionWorkerPanel::class.java.getDeclaredField(field).apply { isAccessible=true }.get(panel) as? android.app.AlertDialog
                    ok = window?.window?.decorView?.let { check(views(it).filterIsInstance<android.widget.TextView>()) } == true
                }
                if (ok) return
                Thread.sleep(80)
            } while (SystemClock.elapsedRealtime() < end)
            fail("桌宠窗口未呈现预期内容 " + field)
        }
        try {
            inst.runOnMainSync { panel = CompanionWorkerPanel(ctx, {}); panel.open() }
            until("dialog") { labels -> labels.singleOrNull { it.text == "待我处理" }?.performClick() == true }
            until("dialog") { labels -> labels.any { it.text == "回答" } && labels.none { it.text == "测试任务 3" } }
            fun dialog(field: String) = CompanionWorkerPanel::class.java.getDeclaredField(field).apply { isAccessible=true }.get(panel) as android.app.AlertDialog
            fun capture(field: String) {
                if (args.getString("captureProof") != "true") return
                inst.waitForIdleSync()
                inst.runOnMainSync {
                    val view = dialog(field).window!!.decorView
                    val bitmap = android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(bitmap))
                    File(app.getExternalFilesDir(null),"worker-$style-$mode-$field.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                    }
                    bitmap.recycle()
                }
            }
            capture("dialog")
            until("dialog") { labels -> labels.single { it.text == "回答" }.performClick() }
            until("editDialog") { labels -> labels.any { it is android.widget.EditText && it.hint == "回答任务问题" } }
            capture("editDialog")
            inst.runOnMainSync { dialog("editDialog").dismiss() }
            until("dialog") { labels -> labels.singleOrNull { it.text == "目标汇总" }?.performClick() == true }
            until("dialog") { labels ->
                labels.any { it.text == "已完成 · 1" } && labels.any { it.text == "需处理 · 1" } &&
                    labels.singleOrNull { it.text.toString() == "测试任务 2\n结果 2" }?.performClick() == true
            }
            until("detailDialog") { labels -> labels.any { it.text.contains("结果 2") } }
            capture("detailDialog")
            if (args.getString("groupHandoff") == "true") {
                inst.runOnMainSync { dialog("detailDialog").dismiss() }
                val monitor = inst.addMonitor(io.deepseekharness.mobile.MainActivity::class.java.name,null,false)
                try {
                    until("dialog") { labels -> labels.singleOrNull { it.text == "在主对话分析" }?.performClick() == true }
                    val host = requireNotNull(inst.waitForMonitorWithTimeout(monitor,8000))
                    try {
                        assertEquals(android.content.Intent.ACTION_SEND,host.intent.action)
                        assertEquals(store.analysisDraft(group),host.intent.getStringExtra(android.content.Intent.EXTRA_TEXT))
                    } finally { inst.runOnMainSync { host.finish() } }
                } finally { inst.removeMonitor(monitor) }
            }
        } finally {
            inst.runOnMainSync { panel.destroy() }; root.deleteRecursively()
            for (name in listOf("whale-interface","app_theme")) app.deleteSharedPreferences("${root.name}-$name")
        }
    }

    @Test fun petPanelPagesAndOpensSelectedTaskHistory() {
        val app = inst.targetContext
        val id = requireNotNull(InstrumentationRegistry.getArguments().getString("authorWorker"))
        val store = WorkerDashboardStore(app)
        val title = store.detail(id).getString("title")
        val panel = CompanionWorkerPanel(app, {})
        fun dialog(field: String) = CompanionWorkerPanel::class.java.getDeclaredField(field).apply { isAccessible=true }.get(panel) as? android.app.AlertDialog
        fun views(view: android.view.View): List<android.view.View> = listOf(view) + if (view is android.view.ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun waitUi(why: String, check: () -> Boolean) {
            val until = SystemClock.elapsedRealtime()+12000
            var ok = false
            while (!ok && SystemClock.elapsedRealtime() < until) { inst.runOnMainSync { ok = check() }; if (!ok) Thread.sleep(60) }
            assertTrue(why,ok)
        }
        fun click(label: String) = waitUi("未看到$label") {
            dialog("dialog")?.window?.decorView?.let(::views)?.filterIsInstance<android.widget.Button>()?.firstOrNull { it.text==label }?.performClick()==true
        }
        try {
            inst.runOnMainSync { panel.open() }
            click("全部任务"); click("下一页"); click("上一页")
            var page = 0
            while (true) {
                val snapshot = store.page("all",offset=page*20,limit=20)
                val items = snapshot.getJSONArray("items")
                if ((0 until items.length()).any { items.getJSONObject(it).getString("id")==id }) break
                check((page+1)*20 < snapshot.getInt("total")) { "缺少原工单" }
                click("下一页")
                val firstTitle = store.page("all",offset=(page+1)*20,limit=20).getJSONArray("items").getJSONObject(0).getString("title")
                waitUi("翻页未完成") { dialog("dialog")?.window?.decorView?.let(::views)?.any { it.contentDescription=="任务管理：$firstTitle" }==true }
                page++
            }
            waitUi("工单详情入口不可用") {
                val card = dialog("dialog")?.window?.decorView?.let(::views)?.firstOrNull { it.contentDescription=="任务管理：$title" }
                card?.let(::views)?.filterIsInstance<android.widget.Button>()?.firstOrNull { it.text=="任务详情" }?.performClick()==true
            }
            waitUi("执行记录未显示") {
                val labels = dialog("detailDialog")?.window?.decorView?.let(::views)?.filterIsInstance<android.widget.TextView>().orEmpty()
                labels.any { it.text.contains("任务输入") } && labels.any { it.text.contains("回执") } && labels.any { it.text=="查看任务范围与依据" }
            }
            val fullResult = store.detail(id).getString("summary")
            if (fullResult.length > 152) {
                waitUi("最新结果无法展开") {
                    dialog("detailDialog")?.window?.decorView?.let(::views)?.filterIsInstance<android.widget.TextView>()
                        ?.firstOrNull { it.text=="展开最新结果" }?.performClick()==true
                }
                waitUi("完整结果和权威记录不符") {
                    dialog("detailDialog")?.window?.decorView?.let(::views)?.filterIsInstance<android.widget.TextView>()?.any { it.text.toString()==fullResult }==true
                }
                waitUi("结果无法收起") {
                    dialog("detailDialog")?.window?.decorView?.let(::views)?.filterIsInstance<android.widget.TextView>()
                        ?.firstOrNull { it.text=="收起最新结果" }?.performClick()==true
                }
            }
            if (InstrumentationRegistry.getArguments().getString("captureProof")=="true") {
                inst.uiAutomation.waitForIdle(220,1500)
                val window = requireNotNull(dialog("detailDialog")?.window)
                val bitmap = android.graphics.Bitmap.createBitmap(window.decorView.width,window.decorView.height,android.graphics.Bitmap.Config.ARGB_8888)
                val copied = java.util.concurrent.CountDownLatch(1); var status = -1
                android.view.PixelCopy.request(window,bitmap,{status=it;copied.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))
                assertTrue(copied.await(4,java.util.concurrent.TimeUnit.SECONDS)); assertEquals(android.view.PixelCopy.SUCCESS,status)
                File(app.getExternalFilesDir(null),"whale-task-history.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
        } finally { inst.runOnMainSync { panel.destroy() } }
    }

    @Test fun measureInstalledLibraryWithoutLoggingTaskContents() {
        val store = WorkerDashboardStore(inst.targetContext)
        val old = store.list()
        val bytes = old.toString().toByteArray().size
        val current = store.page(withCounts=false)
        assertEquals(old.getJSONArray("items").length(),current.getInt("total"))
        val before = LongArray(7) { val started = SystemClock.elapsedRealtimeNanos(); store.list().toString(); (SystemClock.elapsedRealtimeNanos()-started)/1000 }
        val after = LongArray(7) { val started = SystemClock.elapsedRealtimeNanos(); store.page(withCounts=false).toString(); (SystemClock.elapsedRealtimeNanos()-started)/1000 }
        inst.sendStatus(0,Bundle().apply {
            putInt("actualTasks",current.getInt("total")); putInt("fullBytes",bytes); putInt("pageBytes",current.toString().toByteArray().size)
            putLong("fullMedianUs",before.sorted()[3]); putLong("pageMedianUs",after.sorted()[3])
        })
    }
    @Test fun measureWorkerPanelAndOwnerLookup() {
        val root = File(inst.targetContext.cacheDir,"workers-perf-"+UUID.randomUUID()).apply { check(mkdirs()) }
        val context = object : ContextWrapper(inst.targetContext) {
            override fun getNoBackupFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        try {
            val dir = File(root,"worker-dashboard").apply { mkdir() }
            var size = 0L
            val headers = InstrumentationRegistry.getArguments().getString("headers") == "true"
            repeat(240) { i ->
                val row = fixture(i)
                val f = File(dir,row.getString("id")+".json")
                if (headers) WorkerCardCatalog.write(f,row) else f.writeText(row.toString()); size += f.length(); check(f.setLastModified(1_700_000_000_000L+i))
            }
            val store = WorkerDashboardStore(context)
            val dashboard = CompanionDashboard(context)
            val legacy = InstrumentationRegistry.getArguments().getString("legacy") == "true"
            fun page() = dashboard.execute(JSONObject().put("operation", if (legacy) "workerList" else "workerPage").put("limit",20))
            val began = SystemClock.elapsedRealtimeNanos()
            val first = page()
            val firstBytes = first.toString().toByteArray().size
            val firstElapsed = SystemClock.elapsedRealtimeNanos()-began
            assertEquals(if (legacy) 240 else 20, first.getJSONArray("items").length())
            val wall = ArrayList<Long>(); val cpu = ArrayList<Long>()
            repeat(7) {
                val t = SystemClock.elapsedRealtimeNanos(); val c = Debug.threadCpuTimeNanos()
                check(page().toString().toByteArray().size == firstBytes)
                wall.add(SystemClock.elapsedRealtimeNanos()-t); cpu.add(Debug.threadCpuTimeNanos()-c)
            }
            val search = SystemClock.elapsedRealtimeNanos()
            assertFalse(store.activeFor("missing-owner"))
            val subsequent = SystemClock.elapsedRealtimeNanos()
            repeat(6) { assertFalse(store.activeFor("missing-owner")) }
            val ended = SystemClock.elapsedRealtimeNanos()
            val lookup = (ended-search)/7000
            inst.sendStatus(0, Bundle().apply {
                putLong("fixtureBytes", size); putInt("returnedBytes", firstBytes)
                putLong("firstPageUs", firstElapsed/1000); putLong("warmPageMedianUs", wall.sorted()[3]/1000)
                putLong("warmCpuMedianUs",cpu.sorted()[3]/1000); putLong("absentOwnerMeanUs",lookup)
                putLong("firstOwnerUs",(subsequent-search)/1000); putLong("warmOwnerUs",(ended-subsequent)/6000)
            })
        } finally { root.deleteRecursively() }
    }
}
