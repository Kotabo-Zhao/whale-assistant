package io.deepseekharness.mobile.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.runtime.RuntimeEventSink
import io.deepseekharness.mobile.runtime.RuntimeHost
import io.deepseekharness.mobile.runtime.RuntimeStateSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkerWatchdogDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val app=inst.targetContext
    private fun await(message: String, timeout: Long=90000, predicate: ()->Boolean) {
        val until=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<until) { if(predicate()) return;Thread.sleep(150) }
        error(message)
    }
    private fun <T> withClient(block: (HarnessBubbleClient)->T):T {
        val sink=object:RuntimeEventSink {
            override fun onProgress(snapshot:RuntimeStateSnapshot)=Unit
            override fun onTerminalOutput(sessionId:String,dataBase64:String,suppressPublicOutput:Boolean)=Unit
            override fun onTerminalExit(sessionId:String,exitCode:Int)=Unit
        }
        val host=RuntimeHost.acquire(app,sink)
        try {
            if(runCatching{host.openHarnessAccess()}.isFailure) {RuntimeHost.ensureDeviceBridge(host);RuntimeHost.startHarness(host)}
            return block(HarnessBubbleClient(host.openHarnessAccess()))
        } finally {RuntimeHost.detachPluginSink(sink)}
    }
    private fun send(client: HarnessBubbleClient, session: String, text: String) {
        val requestId=UUID.randomUUID().toString()
        PetReplyOperation(150000).run {
            client.followReplyWithImages(session,requestId) {
                client.call("session/prompt",JSONObject().put("sessionId",session).put("requestId",requestId).put("mode","queue")
                    .put("content",JSONArray().put(JSONObject().put("type","text").put("text",text))))
            }
        }
    }

    @Test fun independentRealModelPausesFaultEvidenceWhileAnotherWorkerFinishes() = withClient { client ->
        val store=WorkerDashboardStore(app)
        val parent=client.call("session/create",JSONObject()).getString("sessionId")
        client.command(parent,"/permission read-only")
        val tag="监督故障注入-"+UUID.randomUUID().toString().take(8)
        send(client,parent,"请立即用 mobile_workers start 两个 read 工单，标题分别为 $tag 甲 和 $tag 乙。"+
            "甲的任务是只读等待测试，连续调用 mobile_device_wait(milliseconds=10000) 十二次，之后报告 completed 等待结束。"+
            "乙的任务是计算 123 加 456 并用 mobile_worker_report completed 报告结果。独立派发后立即结束本轮，不轮询。")
        fun rows()=store.list().getJSONArray("items").let { all -> (0 until all.length()).map(all::getJSONObject).filter{it.optString("owner")==parent} }
        var observed:JSONObject?=null
        await("未取得正在运行的真实 Worker 及其工具证据",120000) {
            observed=rows().firstOrNull{it.optString("title").contains("甲") && it.optString("state")=="running" &&
                (it.optJSONObject("health")?.optJSONArray("recent")?.length() ?: 0)>=2}
            observed!=null
        }
        var verdict="superseded"
        repeat(3) {
            if(verdict=="hold") return@repeat
            val current=store.watchdogRow(requireNotNull(observed).getString("id")) ?: error("工单消失")
            assertEquals("running",current.getString("state"))
            // Fault injection changes only the observer's evidence copy. Execution and
            // command validation still use the live worker's actual run and sequence.
            val fault=JSONObject(current.toString())
            val health=fault.getJSONObject("health")
            health.put("task","计算 123 加 456 并汇报结果，已有信息足够，不需读取文件。")
                .put("repeated",8)
            val recent=health.getJSONArray("recent")
            for(index in 0 until recent.length()) recent.getJSONObject(index).put("kind","tool")
                .put("tool","read").put("digest","same-unrelated-file").put("error",true)
            verdict=WorkerWatchdog.review(app,fault,System.currentTimeMillis())
            observed=current
        }
        assertEquals("独立真实模型必须识别注入的重复空转证据","hold",verdict)
        val id=requireNotNull(observed).getString("id")
        await("监督命令未在原工单暂停") {rows().firstOrNull{it.optString("id")==id}?.optString("state")=="blocked"}
        val blocked=rows().single{it.getString("id")==id}
        assertEquals("stalled",blocked.getJSONObject("watchdog").getString("reason"))
        await("另一项独立任务没有完成") {rows().any{it.optString("title").contains("乙")&&it.optString("state")=="completed"&&it.optString("summary").contains("579")}}
        store.command(JSONObject().put("id",id).put("action","resume").put("task","用户确认测试等待已结束。请直接计算 123 加 456 并报告 completed 579，不再等待。"))
        await("原工单未按确认内容续接",120000) {rows().firstOrNull{it.optString("id")==id}?.let{it.optString("state")=="completed"&&it.optString("summary").contains("579")}==true}
        assertFalse("旧监督结果不得暂停已恢复的任务",WorkerWatchdog.hold(app,requireNotNull(observed),"stalled","旧证据",System.currentTimeMillis()))
        inst.sendStatus(0,android.os.Bundle().apply {
            putBoolean("faultInjectedEvidence",true);putBoolean("realIndependentModel",true)
            putBoolean("originalWorkerPausedAndResumed",true);putBoolean("otherWorkerCompleted",true);putBoolean("staleHoldRejected",true)
        })
    }
    @Test fun nativeTimerDetectsFrozenGuestAndKeepsOneDurablePause() = withClient { client ->
        val store=WorkerDashboardStore(app)
        val parent=client.call("session/create",JSONObject()).getString("sessionId")
        client.command(parent,"/permission read-only")
        val tag="冻结恢复-"+UUID.randomUUID().toString().take(8)
        send(client,parent,"请立即派发一个 mode=read 工单，标题 $tag，任务=连续调用 mobile_device_wait(milliseconds=10000) 三十次用于等待恢复测试，之后报告 completed。派发后立刻结束本轮，不轮询。")
        var row:JSONObject?=null
        await("未启动只读冻结探针") {
            val all=store.list().getJSONArray("items")
            row=(0 until all.length()).map(all::getJSONObject).firstOrNull{
                it.optString("owner")==parent && it.optString("state")=="running" && it.has("health")}
            row!=null
        }
        val id=requireNotNull(row).getString("id")
        val pidFile=io.deepseekharness.mobile.runtime.RuntimeStore(app).harnessPidFile.absolutePath
        fun identity(pid:Int):String?=runCatching {
            val stat=java.io.File("/proc/$pid/stat").readText()
            stat.substringAfterLast(')').trim().split(Regex("\\s+"))[19]
        }.getOrNull()
        val nodes=java.io.File("/proc").listFiles().orEmpty().mapNotNull { entry ->
            val pid=entry.name.toIntOrNull() ?: return@mapNotNull null
            runCatching {
                if(android.system.Os.stat(entry.path).st_uid!=android.os.Process.myUid()) return@runCatching null
                val marked=java.io.File(entry,"environ").readText().split('\u0000').contains("DSH_PIDFILE=$pidFile")
                val executable=java.io.File(entry,"cmdline").readText().substringBefore('\u0000')
                val proot=java.io.File(entry,"comm").readText().trim()=="proot"
                if(!marked || (!executable.endsWith("/node") && !proot)) return@runCatching null
                pid to requireNotNull(identity(pid))
            }.getOrNull()
        }
        val ordered=nodes.sortedBy { (pid,_) -> if(java.io.File("/proc/$pid/comm").readText().trim()=="proot") 0 else 1 }
        assertTrue("未识别到本应用运行环境的 Node 与 PRoot",ordered.size>=3)
        try {
            for((pid,stamp) in ordered) if(identity(pid)==stamp) android.system.Os.kill(pid,android.system.OsConstants.SIGSTOP)
            inst.sendStatus(0,android.os.Bundle().apply {putBoolean("guestFrozen",true);putInt("ownedFrozenProcesses",ordered.size)})
            val initial=store.watchdogRow(id)!!.getJSONObject("health").getLong("heartbeatAt")
            Thread.sleep(20000)
            assertEquals("PRoot 冻结后任务心跳仍在推进",initial,store.watchdogRow(id)!!.getJSONObject("health").getLong("heartbeatAt"))
            await("Node 冻结后独立原生监督未记录暂停",160000) {
                val all=store.pending()
                (0 until all.length()).map(all::getJSONObject).any{it.optString("id")==id && it.optString("action")=="watchdog"}
            }
            Thread.sleep(16000)
            val all=store.pending()
            assertEquals("冻结期间同一工单重复请求暂停",1,(0 until all.length()).map(all::getJSONObject).count{
                it.optString("id")==id && it.optString("action")=="watchdog"})
        } finally {
            for((pid,stamp) in ordered.asReversed()) if(identity(pid)==stamp) runCatching {android.system.Os.kill(pid,android.system.OsConstants.SIGCONT)}
        }
        await("恢复后主服务没有继续处理监督或用户命令",60000) {
            val all=store.pending()
            (0 until all.length()).map(all::getJSONObject).none{it.optString("id")==id && it.optString("action")=="watchdog"}
        }
        store.command(JSONObject().put("id",id).put("action","cancel"))
        await("冻结探针未正常取消") {store.list().getJSONArray("items").let{all->
            (0 until all.length()).map(all::getJSONObject).any{it.optString("id")==id&&it.optString("state")=="cancelled"}}}
        inst.sendStatus(0,android.os.Bundle().apply {
            putBoolean("nativeTimerSurvivedGuestFreeze",true);putBoolean("oneDurablePause",true);putBoolean("guestRecovered",true)
        })
    }

    @Test fun conflictingReportsRequireCorrectionAndFreshGoalEvidence() = withClient { client ->
        val store=WorkerDashboardStore(app)
        val owner=client.call("session/create",JSONObject()).getString("sessionId")
        client.command(owner,"/permission read-only")
        val group="冲突核验-"+UUID.randomUUID().toString().take(8)
        fun members():List<JSONObject> {
            val all=store.list().getJSONArray("items")
            return (0 until all.length()).map(all::getJSONObject).filter{it.optString("group")==group}
        }
        val ledger=java.io.File(app.noBackupFilesDir,"dsh-runtime/current/root/.dsh/mobile-workers/goals/ledger.json")
        fun goal():JSONObject {
            val all=JSONArray(ledger.readText())
            return (0 until all.length()).map(all::getJSONArray).single{it.getString(0)==group}.getJSONObject(1)
        }
        send(client,owner,"请做冲突来源核验。先 mobile_goals define，group=$group，outcome=核对两份独立来源，criteria=[两份原始声明的123加456结果都等于579]。派发两个 mode=read 的工单，同一group，标题甲和乙。甲任务=从测试引文『来源甲声明123加456等于580』原样提取声明并报告completed，不自行修正引文。乙任务=从引文『来源乙声明123加456等于579』原样提取声明并报告completed。派发后结束本轮。")
        await("两份来源报告未完成",150000) {members().size==2 && members().all{it.optString("state")=="completed"}}
        send(client,owner,"现在核验目标 $group。读取该group完整工单和目标status，自己重新计算123+456，对照全部原文声明。存在矛盾时必须 mobile_goals verify 记录 passed=false，引用错误来源的工单id并写清正确值。使用刚读到的fingerprint，结束本轮，不追加新工单。")
        assertFalse("矛盾证据被误记为通过",goal().getJSONObject("verification").getBoolean("passed"))
        val before=goal().getJSONObject("verification").getString("fingerprint")
        val incorrect=members().single{it.optString("summary").contains("580")}
        val id=incorrect.getString("id")
        store.command(JSONObject().put("id",id).put("action","add")
            .put("task","用户提供新版测试原文『来源甲更正，123加456等于579』。按新版引文重新报告，保留本工单id。"))
        await("更正未回到原工单",120000) {members().any{it.optString("id")==id && it.optLong("revision")>incorrect.optLong("revision") &&
            it.optString("state")=="completed" && it.optString("summary").contains("579")}}
        send(client,owner,"重新核验目标 $group。先读取最新status和全部完整回执，独立算123+456。确认当前两份声明一致后，用最新fingerprint逐项verify并记录来源版本变化，结束本轮。")
        val final=goal()
        assertEquals(owner,final.getString("owner"))
        assertTrue("更正后的目标未通过独立核验",final.getJSONObject("verification").getBoolean("passed"))
        assertNotEquals("核验没有绑定新证据",before,final.getJSONObject("verification").getString("fingerprint"))
        assertEquals("发生了重复派单",2,members().size)
        inst.sendStatus(0,android.os.Bundle().apply {
            putBoolean("conflictingEvidenceFailed",true);putBoolean("correctedOriginalWorker",true)
            putBoolean("freshFingerprintVerified",true);putBoolean("noDuplicateWorker",true)
        })
    }

    @Test fun lostNetworkKeepsTaskAndRestoresTheSameWorker() = withClient { client ->
        val initial=android.provider.Settings.Global.getInt(app.contentResolver,"wifi_on",0)
        org.junit.Assume.assumeTrue("需要已连接的 Wi-Fi",initial==1)
        val store=WorkerDashboardStore(app)
        val owner=client.call("session/create",JSONObject()).getString("sessionId")
        client.command(owner,"/permission read-only")
        val tag="断网续接-"+UUID.randomUUID().toString().take(8)
        send(client,owner,"请派发一个 mode=read 工单，标题 $tag。任务=连续调用 mobile_device_wait(milliseconds=10000) 十二次用于临时断网测试，最后报告completed。立即派发并结束本轮。")
        var row:JSONObject?=null
        await("断网测试未启动") {
            val all=store.list().getJSONArray("items")
            row=(0 until all.length()).map(all::getJSONObject).firstOrNull{it.optString("owner")==owner &&
                it.optString("state")=="running" && it.has("health")}
            row!=null
        }
        val id=requireNotNull(row).getString("id")
        val automation=inst.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun wifi(enable:Boolean) {
            automation.executeShellCommand("svc wifi "+if(enable) "enable" else "disable").close()
        }
        try {
            wifi(false)
            val connectivity=app.getSystemService(android.net.ConnectivityManager::class.java)
            await("测试网络未断开",15000) {connectivity.activeNetwork==null}
            val current=store.watchdogRow(id)!!
            assertEquals("unavailable",WorkerWatchdog.review(app,current,System.currentTimeMillis()))
            assertEquals("running",store.watchdogRow(id)!!.optString("state"))
            val pending=store.pending()
            assertFalse("网络故障被误认为需暂停任务",(0 until pending.length()).map(pending::getJSONObject).any{
                it.optString("id")==id && it.optString("action")=="watchdog"})
        } finally {wifi(true)}
        await("Wi-Fi 未恢复",30000) {app.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork!=null}
        store.command(JSONObject().put("id",id).put("action","cancel"))
        await("网络恢复后原任务未受控取消",60000) {store.list().getJSONArray("items").let{all->
            (0 until all.length()).map(all::getJSONObject).any{it.optString("id")==id&&it.optString("state")=="cancelled"}}}
        assertEquals(1,store.list().getJSONArray("items").let { all ->
            (0 until all.length()).map(all::getJSONObject).count{it.optString("owner")==owner}})
        inst.sendStatus(0,android.os.Bundle().apply {
            putBoolean("realNetworkDisconnected",true);putBoolean("reviewFailureKeptTask",true)
            putBoolean("wifiRestored",true);putBoolean("noDuplicateWorker",true)
        })
    }

}
