package io.deepseekharness.mobile.companion

import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.runtime.ProcessProbe
import io.deepseekharness.mobile.runtime.RuntimeLaunchResolver
import io.deepseekharness.mobile.runtime.RuntimeStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PetDisruptionDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext

    private inner class Pet(val directory: File, writer: TaskStateWriter = TaskStateWriter()) : AutoCloseable {
        private val prefNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences(directory.name + name, mode).also { prefNames.add(directory.name + name) }
        }
        val history = CompanionBubbleHistory(context, writer)
        val desk = WorkerDashboardStore(context, writer)
        val replies = AtomicInteger()
        val firstReply = CountDownLatch(1)
        lateinit var window: CompanionBubbleWindow
        fun open(classify: (List<CompanionBubbleChat.Message>) -> PetIntentRouter.Decision?, receive: ((String,String,String,String,String) -> JSONObject)? = null, stall: (() -> Unit)? = null,
                 parallel: (List<CompanionBubbleChat.Message>,JSONObject?,(String)->Unit) -> String = { _,_,_ -> error("未配置并行回复夹具") }) {
            inst.runOnMainSync {
                window = CompanionBubbleWindow(context,app.getSystemService(WindowManager::class.java),
                    autoOpenOnDelivery=false, historyStore=history, classifyWaiting=classify, receiveTask=receive, parallelReply=parallel,
                    replyRunner={ _,_,_,_,_,_ ->
                        if (replies.incrementAndGet() == 1) {
                            firstReply.countDown()
                            if (stall == null) CountDownLatch(1).await(160,TimeUnit.SECONDS) else stall()
                        }
                        CompanionBubbleChat.Reply("夹具回复", emptyList(), emptyList())
                    }) { intArrayOf(32,120,100,100) }
                window.show()
            }
        }
        private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun labels(): List<TextView> {
            val root = window.javaClass.getDeclaredField("root").apply { isAccessible=true }.get(window) as View
            return views(root).filterIsInstance<TextView>()
        }
        fun send(text: String) = inst.runOnMainSync {
            val nodes = labels()
            nodes.filterIsInstance<EditText>().single().setText(text)
            nodes.single { it.text == "发送" || it.text == "继续发送" }.performClick()
        }
        fun draft(): String {
            var value = ""; inst.runOnMainSync { value=labels().filterIsInstance<EditText>().single().text.toString() }
            return value
        }
        fun click(label: String) = inst.runOnMainSync {
            labels().single { it.text == label || it.contentDescription == label }.performClick()
        }
        fun assignFromPet(text: String) = inst.runOnMainSync {
            window.composeTask()
            val panel = window.javaClass.getDeclaredField("workerPanel").apply { isAccessible=true }.get(window)
            val dialog = panel.javaClass.getDeclaredField("editDialog").apply { isAccessible=true }.get(panel) as AlertDialog
            views(checkNotNull(dialog.window).decorView).filterIsInstance<EditText>().single().setText(text)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        fun confirmRecovery() = inst.runOnMainSync {
            val dialog = window.javaClass.getDeclaredField("recoveryDialog").apply { isAccessible=true }.get(window) as AlertDialog
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            assertEquals("恢复交办",button.text.toString()); button.performClick()
        }
        override fun close() {
            if (::window.isInitialized) inst.runOnMainSync { window.destroy() }
            prefNames.forEach(app::deleteSharedPreferences)
            directory.deleteRecursively()
        }
    }
    private fun fresh() = File(app.cacheDir,"pet-disruption-"+UUID.randomUUID()).apply { check(mkdirs()) }
    @Test fun ownerDataNeedsNoSetupPanelInPetWindow() {
        for(style in listOf("cute","simple")) Pet(fresh()).use { pet ->
            pet.context.getSharedPreferences("whale-interface",0).edit().putString("style",style).commit()
            pet.open({ null })
            pet.click(PetUi(pet.context).string(io.deepseekharness.mobile.R.string.pet_menu_session))
            inst.runOnMainSync {
                assertFalse(pet.labels().any { it.text.toString().contains("主人档案") || it.text.toString().contains("秘书事项") })
                assertEquals(1,pet.labels().filterIsInstance<EditText>().size)
            }
        }
    }
    private fun until(timeout: Long = 8000, test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime()+timeout
        while (!test()) { check(SystemClock.elapsedRealtime()<end) { "设备状态等待超时" }; Thread.sleep(40) }
    }

    @Test fun independentConversationFinishesWhileOriginalTurnIsStillBlocked() {
        Pet(fresh()).use { pet ->
            val release = CountDownLatch(1)
            val reply = "并行回答：703，原任务仍在执行。"
            pet.open({ PetIntentRouter.Decision(PetIntentRouter.Intent.CHAT,independent=true) }, stall={ release.await(90,TimeUnit.SECONDS) },
                parallel={ history,_,partial -> assertEquals("37 乘 19 是多少",history.last().text); partial("并行回答…"); reply })
            pet.send("需要长时间执行的原任务")
            assertTrue(pet.firstReply.await(5,TimeUnit.SECONDS))
            pet.send("37 乘 19 是多少")
            until { pet.history.followups.entries().singleOrNull()?.stage == "answered" }
            until { var found=false; inst.runOnMainSync { found=pet.labels().any { it.text.toString()==reply } }; found }
            inst.runOnMainSync {
                assertTrue(pet.labels().any { it.text.toString() == reply })
                assertTrue(pet.window.javaClass.getDeclaredField("busy").apply { isAccessible=true }.getBoolean(pet.window))
                pet.window.hide(); pet.window.show()
                assertEquals(1,pet.labels().count { it.text.toString() == reply })
            }
            assertEquals(1,pet.replies.get())
            assertEquals(reply,pet.history.followups.entries().single().receipt)
            release.countDown()
        }
    }

    @Test fun realIndependentModelReplyAppearsWhileOriginalRequestIsBlocked() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveDuplexChecks")=="yes")
        val stored = RuntimeStore(app)
        val key = checkNotNull(stored.readModelCredential("deepseek")?.takeIf(String::isNotBlank))
        val model = stored.settings().defaultModels[io.deepseekharness.mobile.runtime.ModelProvider.DEEPSEEK] ?: "deepseek-flash"
        val release = CountDownLatch(1)
        try {
            Pet(fresh()).use { pet ->
                pet.open({ messages -> PetIntentRouter.classify(key,model,messages,whileWaiting=true) },
                    stall={ release.await(90,TimeUnit.SECONDS) },
                    parallel={ messages,evidence,partial -> PetDirectChat(app).readOnly(messages,evidence,partial) })
                pet.send("请写一份详细的旅行清单")
                assertTrue(pet.firstReply.await(5,TimeUnit.SECONDS))
                val start=SystemClock.elapsedRealtime()
                pet.send("独立回答一个新问题：37 乘 19 是多少？只回答数字，原请求继续。")
                until(60_000) { pet.history.followups.entries().singleOrNull()?.stage=="answered" }
                val elapsed=SystemClock.elapsedRealtime()-start
                val answer=pet.history.followups.entries().single().receipt
                assertTrue(answer.contains("703"))
                until { var visible=false; inst.runOnMainSync { visible=pet.labels().any { it.text.toString()==answer } }; visible }
                assertTrue(busy(pet.window))
                inst.runOnMainSync {
                    pet.window.hide(); pet.window.show()
                    assertEquals(1,pet.labels().count { it.text.toString()==answer })
                }
                assertEquals(1,pet.replies.get())
                inst.sendStatus(0,android.os.Bundle().apply { putLong("nativeIndependentReplyMs",elapsed); putString("answer",answer); putString("originalRequest","still blocked; injected wait, actual routing and reply model") })
            }
        } finally { release.countDown() }
    }

    @Test fun floatingPublishedToolKeepsFormWhilePetAcceptsAnotherOrder() {
        Pet(fresh()).use { pet ->
            val catalog = io.deepseekharness.mobile.features.WhaleFeatureStore(pet.context)
            val title = "浮窗计算器"
            val id = "floating-test"
            val html = """<input id="n" value="4"><button onclick="out.textContent=Number(n.value)*2">计算</button><output id="out"></output>"""
            val revision = catalog.draft(JSONObject().put("id", id).put("title", title).put("html", html)).getString("latest")
            catalog.activate(id, revision, "")
            pet.open({ null })
            pet.click("我的工具")
            until { runCatching { pet.click("打开工具 · $title"); true }.getOrDefault(false) }
            fun toolRoot() = android.view.inspector.WindowInspector.getGlobalWindowViews().singleOrNull {
                (it.layoutParams as? WindowManager.LayoutParams)?.title == "桌宠工具 · $title"
            }
            fun nodes(v: View): List<View> = listOf(v) + if (v is ViewGroup)
                (0 until v.childCount).flatMap { nodes(v.getChildAt(it)) } else emptyList()
            lateinit var web: android.webkit.WebView
            fun eval(code: String): String {
                val done = CountDownLatch(1)
                var result = ""
                inst.runOnMainSync { web.evaluateJavascript(code) { result = it; done.countDown() } }
                check(done.await(3, TimeUnit.SECONDS))
                return result
            }
            fun tap(x: Float, y: Float) {
                val down = SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { check(inst.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
            }
            until {
                var found = false
                inst.runOnMainSync { toolRoot()?.let(::nodes)?.filterIsInstance<android.webkit.WebView>()?.singleOrNull()?.let { web = it; found = true } }
                found && eval("!!document.querySelector('#n')") == "true"
            }
            val button = org.json.JSONArray(eval("""(() => { n.value='9'; const r=document.querySelector('button').getBoundingClientRect();
                return [(r.x+r.width/2)*devicePixelRatio,(r.y+r.height/2)*devicePixelRatio] })()"""))
            val location = IntArray(2)
            inst.runOnMainSync { web.getLocationOnScreen(location) }
            tap(location[0] + button.getDouble(0).toFloat(), location[1] + button.getDouble(1).toFloat())
            until { eval("Number(out.textContent)") == "18" }
            pet.send("第一条消息保持等待")
            assertTrue(pet.firstReply.await(3, TimeUnit.SECONDS))
            pet.send("插件开着时接收的下一条消息")
            assertEquals("插件开着时接收的下一条消息", pet.history.followups.entries().single().text)
            pet.assignFromPet("浮窗打开期间的独立后台工单")
            until { pet.desk.pending().length() == 1 }
            inst.runOnMainSync {
                pet.window.hide()
                val panel = checkNotNull(toolRoot())
                assertTrue(((panel.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0)
                nodes(panel).filterIsInstance<TextView>().single { it.text == "收起" }.performClick()
                nodes(panel).filterIsInstance<TextView>().single { it.text == "展开" }.performClick()
            }
            assertEquals("18", eval("Number(out.textContent)"))
            if (InstrumentationRegistry.getArguments().getString("testUnderlyingBrowser") == "yes") {
                val automation = inst.uiAutomation
                val info = automation.serviceInfo
                val flags = info.flags
                try {
                    info.flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    automation.serviceInfo = info
                    val addressBar = android.graphics.Rect()
                    fun visibleAddressBar() = automation.windows.mapNotNull { it.root }
                        .firstOrNull { it.packageName == "com.android.chrome" }
                        ?.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")?.firstOrNull { it.isVisibleToUser }
                    until {
                        visibleAddressBar()?.getBoundsInScreen(addressBar)
                        !addressBar.isEmpty
                    }
                    val tool = android.graphics.Rect()
                    inst.runOnMainSync {
                        val root = checkNotNull(toolRoot())
                        val xy = IntArray(2).also(root::getLocationOnScreen)
                        tool.set(xy[0], xy[1], xy[0] + root.width, xy[1] + root.height)
                    }
                    check(!tool.contains(addressBar.centerX(), addressBar.centerY()))
                    check(visibleAddressBar()?.isFocused == false) { "浏览器地址栏已提前获得焦点" }
                    tap(addressBar.exactCenterX(), addressBar.exactCenterY())
                    until { visibleAddressBar()?.isFocused == true }
                    automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                } finally { info.flags = flags; automation.serviceInfo = info }
            }
            assertEquals("20", eval("n.value=String(Number(n.value)+1);document.querySelector('button').click();Number(out.textContent)"))
            catalog.activate(id, "", revision)
            until { var gone = false; inst.runOnMainSync { gone = toolRoot() == null }; gone }
        }
    }

    @Test fun diskFullPreservesInputOldCommandsAndRetryIdentity() {
        val full = AtomicBoolean(false)
        val writer = TaskStateWriter { output, bytes ->
            if (full.get()) {
                output.write(bytes,0,bytes.size/2)
                val fd = android.system.Os.open("/dev/full",android.system.OsConstants.O_WRONLY,0)
                try {
                    val fullError = assertThrows(android.system.ErrnoException::class.java) {
                        android.system.Os.write(fd,bytes,0,1)
                    }
                    assertEquals(android.system.OsConstants.ENOSPC,fullError.errno)
                    throw java.io.IOException("ENOSPC during write",fullError)
                } finally { android.system.Os.close(fd) }
            }
            output.write(bytes)
        }
        Pet(fresh(),writer).use { pet ->
            pet.open({ null })
            full.set(true)
            pet.send("第一条")
            assertEquals("第一条",pet.draft()); assertEquals(0,pet.replies.get())
            full.set(false); pet.send("第一条")
            assertTrue(pet.firstReply.await(5,TimeUnit.SECONDS))
            pet.send("已保存的后续消息")
            val previous = pet.history.followups.entries().single()
            val firstId = UUID.randomUUID().toString()
            val nextId = UUID.randomUUID().toString()
            pet.desk.submit("工单一","read",firstId)
            full.set(true); pet.send("空间不足时的新单")
            assertEquals("空间不足时的新单",pet.draft())
            assertEquals(previous,pet.history.followups.entries().single())
            assertThrows(java.io.IOException::class.java) { pet.desk.submit("工单二","read",nextId) }
            assertThrows(java.io.IOException::class.java) { pet.desk.acknowledge(firstId) }
            assertEquals(firstId,pet.desk.pending().getJSONObject(0).getString("commandId"))
            full.set(false); pet.send("空间不足时的新单")
            assertEquals(listOf("已保存的后续消息","空间不足时的新单"),pet.history.followups.entries().map { it.text })
            repeat(2) { pet.desk.submit("工单二","read",nextId) }
            assertEquals(2,pet.desk.pending().length())
            pet.desk.acknowledge(firstId)
            assertEquals(nextId,pet.desk.pending().getJSONObject(0).getString("commandId"))
        }
    }

    @Test fun sustainedBacklogParksSlowClassifiersAndAcceptsFreshTopic() {
        Pet(fresh()).use { pet ->
            pet.open({ Thread.sleep(4000); null })
            pet.send("阻塞中的前台回合")
            assertTrue(pet.firstReply.await(5,TimeUnit.SECONDS))
            val accepted = (1..PetFollowupJournal.CAPACITY).map { "积压消息 "+it }
            val start = SystemClock.elapsedRealtime()
            accepted.take(60).forEach(pet::send)
            Thread.sleep(65_000)
            accepted.drop(60).forEach(pet::send)
            val rows = pet.history.followups.entries()
            assertEquals(accepted,rows.map { it.text }); assertEquals(rows.size,rows.map { it.id }.toSet().size)
            pet.send("队列满时的草稿"); assertEquals("队列满时的草稿",pet.draft())
            pet.click("结束等待")
            until { pet.history.load()?.pending != null && !busy(pet.window) }
            pet.click("会话选项"); pet.click("新话题")
            until { !busy(pet.window) && pet.history.load()==null }
            assertEquals("队列满时的草稿",pet.draft())
            pet.send("新话题中的新单")
            until { pet.replies.get()==2 && !busy(pet.window) }
            assertEquals(rows,pet.history.followups.entries())
            inst.sendStatus(0,android.os.Bundle().apply { putLong("backlogElapsedMs",SystemClock.elapsedRealtime()-start); putInt("retained",rows.size) })
        }
    }
    private fun busy(window: CompanionBubbleWindow): Boolean {
        var value = false
        inst.runOnMainSync { value=window.javaClass.getDeclaredField("busy").apply { isAccessible=true }.getBoolean(window) }
        return value
    }

    @Test fun modelAndRouterBothSilentStillAcceptAdditionalMessages() {
        val server = java.net.ServerSocket(0,3,java.net.InetAddress.getByName("127.0.0.1"))
        val accepted = CountDownLatch(2)
        val release = CountDownLatch(1)
        val peer = Thread {
            runCatching { server.accept().use { accepted.countDown(); server.accept().use {
                accepted.countDown(); release.await(20,TimeUnit.SECONDS)
            } } }
        }.apply { isDaemon=true; start() }
        val request = okhttp3.Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()
        fun silent() { PetModelHttp.execute(request,20_000) { it.readText() } }
        try {
            Pet(fresh()).use { pet ->
                pet.open({ silent(); null }, stall=::silent)
                pet.send("等待首个响应"); assertTrue(pet.firstReply.await(3,TimeUnit.SECONDS))
                pet.send("路由也没有响应的第二条")
                assertTrue(accepted.await(3,TimeUnit.SECONDS))
                pet.send("两个连接都挂起时的第三条")
                assertTrue(busy(pet.window))
                val queued = pet.history.followups.entries()
                assertEquals(listOf("路由也没有响应的第二条","两个连接都挂起时的第三条"), queued.map { it.text })
                assertTrue(queued.all { it.stage=="queued" }); assertEquals(2,queued.map { it.id }.distinct().size)
                assertEquals("",pet.draft())
                pet.assignFromPet("两个连接都挂起时明确交办的工单")
                until(4000) { pet.desk.pending().length()==1 }
                val order = pet.desk.pending().getJSONObject(0)
                assertEquals("两个连接都挂起时明确交办的工单",order.getString("task"))
                UUID.fromString(order.getString("commandId"))
                assertTrue(busy(pet.window))
                inst.sendStatus(0,android.os.Bundle().apply { putString("silentPaths","two durable messages and one explicit local work order while both sockets remain unanswered") })
            }
        } finally { release.countDown(); server.close(); peer.join(2000) }
    }

    @Test fun silentReplyAllowsLiveRouterAndWorkerToTakeNextOrder() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePetChecks")=="yes")
        withLivePetRuntime(app) {
            val token = "WhaleCheck"+UUID.randomUUID().toString().take(8)
            val stored = RuntimeStore(app)
            val key = checkNotNull(stored.readModelCredential("deepseek")?.takeIf(String::isNotBlank)) { "设备缺少 DeepSeek 凭据" }
            val model = stored.settings().defaultModels[io.deepseekharness.mobile.runtime.ModelProvider.DEEPSEEK] ?: "deepseek-flash"
            val server = java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))
            val waiting = CountDownLatch(1); val release = CountDownLatch(1)
            val peer = Thread {
                runCatching { server.accept().use { waiting.countDown(); release.await(50,TimeUnit.SECONDS) } }
            }.apply { isDaemon=true; start() }
            try {
                Pet(fresh()).use { pet ->
                    val task = "请独立在后台整理一份三点式旅行打包清单，标题为"+token+"，只需生成文字，完成后用 mobile_worker_report 回报。"
                    val desk = WorkerDashboardStore(app)
                    pet.open({ messages -> PetIntentRouter.classify(key,model,messages,whileWaiting=true) },
                        receive={ text, mode, id, _, needs -> WorkerRuntimeWake.submit(app,text,mode,commandId=id,title=token,needs=needs) },
                        stall={ PetModelHttp.execute(okhttp3.Request.Builder().url("http://127.0.0.1:${server.localPort}/").build(),45_000) { it.readText() } })
                    pet.send("写一封问候信")
                    assertTrue(waiting.await(4,TimeUnit.SECONDS))
                    val start = SystemClock.elapsedRealtime()
                    pet.send(task)
                    until(28_000) { pet.history.followups.entries().single().stage=="handoff" }
                    assertTrue("第一条必须仍在等网络",busy(pet.window))
                    val elapsed = SystemClock.elapsedRealtime()-start
                    fun workers() = desk.list().getJSONArray("items").let { rows ->
                        (0 until rows.length()).map(rows::getJSONObject).filter { it.optString("title").contains(token) }
                    }
                    until(120_000) { workers().singleOrNull()?.optString("state") in setOf("completed","failed","blocked") }
                    assertEquals("completed",workers().single().getString("state"))
                    assertTrue(workers().single().optString("summary").isNotBlank())
                    inst.sendStatus(0,android.os.Bundle().apply { putLong("handoffWhileBusyMs",elapsed); putString("worker","completed, unique title and report") })
                }
            } finally { release.countDown(); server.close(); peer.join(1000) }
        }
    }

    private fun restartDir(phase: String): File {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("petPhase")==phase)
        val token = UUID.fromString(args.getString("petRun")).toString()
        return File(app.cacheDir,"pet-kill-"+token)
    }
    // Exercise the installed guest's real phone intake and assignment persistence with a
    // deterministic local job in place of a model request. Each invocation starts a fresh Node process.
    private fun executeGuest(directory: File, commands: org.json.JSONArray): JSONObject {
        val store = RuntimeStore(app)
        val stage = File(store.currentRoot,"tmp/"+directory.name)
        if (!stage.isDirectory) check(stage.mkdir())
        File(stage,"commands.json").writeText(commands.toString())
        File(stage,"probe.mjs").writeText("""
            import {installSuperagent} from '/opt/dsh/plugins/dsh-mobile-shizuku/lib/superagent-runtime.js'
            import {readFileSync,appendFileSync} from 'node:fs'
            import {dirname,join} from 'node:path'
            const dir=dirname(new URL(import.meta.url).pathname), commands=JSON.parse(readFileSync(join(dir,'commands.json')))
            const host=installSuperagent({tools:{register(){}},on(){}},{directory:dir,defineTool:x=>x,
                start:async ({ticket})=>appendFileSync(join(dir,'executions'),ticket.id+'\n')})
            const ids={}
            for(const cmd of commands) ids[cmd.commandId]=await host.acceptPhone('session-whale-task-desk',cmd.commandId,{...cmd,mode:'read'})
            const runs=readFileSync(join(dir,'executions'),'utf8').trim().split(/\s+/)
            if(new Set(runs).size!==runs.length || host.domain.repo.tickets.list().length!==runs.length ||
                Object.values(ids).some(id=>!runs.includes(id))) throw Error('missing or duplicate execution')
            console.log('EXECUTIONS '+JSON.stringify(ids))
        """.trimIndent())
        val spec = RuntimeLaunchResolver(app,store,includeCredentials=false,selectHarness=false)
            .launch(listOf("/bin/sh","-c","exec node \"\$1\"","whale","/tmp/"+directory.name+"/probe.mjs"))
        val result = ProcessProbe.run(spec,store.currentRoot,60,outputLimit=16384)
        check(result.succeeded && result.output.contains("EXECUTIONS ")) { result.output.takeLast(1500) }
        return JSONObject(result.output.substringAfter("EXECUTIONS ").lineSequence().first())
    }

    @Test fun prepareDuringMissingReceipt() {
        val dir = restartDir("prepare"); check(dir.mkdir())
        val pet = Pet(dir)
        val ready = CountDownLatch(1)
        val error = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        pet.open({ PetIntentRouter.Decision(PetIntentRouter.Intent.BACKGROUND_READ, independent=true) },
            receive={ text,mode,id,title,needs ->
                try {
                    val ack = pet.desk.submit(text,mode,id,title,needs)
                    val ticket = executeGuest(dir,pet.desk.batch(0)).getString(id)
                    pet.desk.acknowledge(id)
                    File(dir,"marker.json").writeText(JSONObject().put("id",id).put("ticket",ticket)
                        .put("pid",android.os.Process.myPid()).put("pending",pet.history.load()!!.pending).toString())
                    ready.countDown()
                    CountDownLatch(1).await(120,TimeUnit.SECONDS)
                    ack
                } catch (failure: Throwable) { error.set(failure); ready.countDown(); throw failure }
            })
        pet.send("强杀前的消息"); assertTrue(pet.firstReply.await(5,TimeUnit.SECONDS))
        pet.send("交给后台的本地工单"); pet.send("尚未判断的下一条")
        assertTrue(ready.await(55,TimeUnit.SECONDS)); error.get()?.let { throw it }
        assertEquals(listOf("dispatch","queued"),pet.history.followups.entries().map { it.stage })
        File(dir,"ready").writeText("ready")
        Thread.sleep(120_000); fail("主机未终止准备进程")
    }

    @Test fun recoverAfterKill() {
        val dir = restartDir("recover")
        val marker = JSONObject(File(dir,"marker.json").readText())
        val stage = File(RuntimeStore(app).currentRoot,"tmp/"+dir.name)
        Pet(dir).use { pet ->
            assertNotEquals(marker.getInt("pid"),android.os.Process.myPid())
            assertEquals(marker.getString("pending"),pet.history.load()!!.pending)
            assertEquals(2,pet.history.followups.entries().size)
            val recovered = java.util.concurrent.atomic.AtomicReference<String>()
            pet.open({ null }, receive={ text,mode,id,title,needs ->
                check(id==marker.getString("id"))
                val ack = pet.desk.submit(text,mode,id,title,needs)
                recovered.set(executeGuest(dir,pet.desk.batch(0)).getString(id))
                pet.desk.acknowledge(id); ack
            })
            pet.click("待处理消息 · 2"); pet.confirmRecovery()
            until(55_000) { pet.history.followups.entries().first().stage=="handoff" }
            assertEquals(marker.getString("ticket"),recovered.get())
            pet.send("重启后接的新单")
            until { pet.history.followups.entries().size==3 }
            assertEquals(3,pet.history.followups.entries().map { it.id }.toSet().size)
            assertEquals(0,pet.desk.pending().length())
            inst.sendStatus(0,android.os.Bundle().apply { putInt("killedPid",marker.getInt("pid")); putInt("recoveredPid",android.os.Process.myPid()); putString("executions","1 ticket, 1 execution across 2 admissions") })
        }
        stage.deleteRecursively()
    }

    @Test fun dayOldCommandsRedeliverOnceAndDrainWithNewArrival() {
        Pet(fresh()).use { pet ->
            val stage = File(RuntimeStore(app).currentRoot,"tmp/"+pet.directory.name)
            val ids = (1..24).map { UUID.randomUUID().toString() }
            try {
                ids.forEachIndexed { index, id -> pet.desk.submit("积压工单 "+index,"read",id) }
                val aged = pet.desk.pending()
                val yesterday = System.currentTimeMillis()-TimeUnit.DAYS.toMillis(1)
                for (i in 0 until aged.length()) aged.getJSONObject(i).put("createdAt",yesterday)
                TaskStateWriter().replace(File(pet.directory,"worker-dashboard/commands.json"),aged.toString().toByteArray())
                fun batchSnapshot(): org.json.JSONArray {
                    val snapshot = org.json.JSONArray()
                    while (true) {
                        val part = pet.desk.batch(snapshot.length())
                        for (i in 0 until part.length()) snapshot.put(part.getJSONObject(i))
                        if (part.length()<4) return snapshot
                    }
                }
                val original = executeGuest(pet.directory,batchSnapshot())
                // Simulate losing all pickup acknowledgments, then add a new order during recovery.
                val late = UUID.randomUUID().toString()
                pet.desk.submit("恢复期间的新任务","read",late)
                val replay = executeGuest(pet.directory,batchSnapshot())
                assertEquals(ids.toSet()+late,replay.keys().asSequence().toSet())
                ids.forEach { assertEquals(original.getString(it),replay.getString(it)) }
                assertEquals(25,(ids+late).map(replay::getString).toSet().size)
                while (pet.desk.pending().length()>0) {
                    val batch = pet.desk.batch(0)
                    check(batch.length()>0)
                    for (i in 0 until batch.length()) pet.desk.acknowledge(batch.getJSONObject(i).getString("commandId"))
                }
                assertEquals(25,File(stage,"executions").readLines().size)
            } finally { stage.deleteRecursively() }
        }
    }
}
