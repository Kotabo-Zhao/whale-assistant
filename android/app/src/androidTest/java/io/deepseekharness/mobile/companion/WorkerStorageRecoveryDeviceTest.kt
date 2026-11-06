package io.deepseekharness.mobile.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.runtime.RuntimeEventSink
import io.deepseekharness.mobile.runtime.RuntimeHost
import io.deepseekharness.mobile.runtime.RuntimeStateSnapshot
import io.deepseekharness.mobile.runtime.RuntimeStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkerStorageRecoveryDeviceTest {
    @Test fun damagedOwnRecordWaitsForReviewWhileAnotherRealWorkerCompletes() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=inst.targetContext
        val sink=object:RuntimeEventSink {
            override fun onProgress(snapshot:RuntimeStateSnapshot)=Unit
            override fun onTerminalOutput(sessionId:String,dataBase64:String,suppressPublicOutput:Boolean)=Unit
            override fun onTerminalExit(sessionId:String,exitCode:Int)=Unit
        }
        val host=RuntimeHost.acquire(app,sink)
        fun start() {RuntimeHost.ensureDeviceBridge(host);RuntimeHost.startHarness(host)}
        fun await(label:String,check:()->Boolean) {
            val end=System.currentTimeMillis()+150000
            while(!check()) {check(System.currentTimeMillis()<end){label};Thread.sleep(200)}
        }
        try {
            if(runCatching{host.openHarnessAccess()}.isFailure) start()
            var client=HarnessBubbleClient(host.openHarnessAccess())
            val owner=client.call("session/create",JSONObject()).getString("sessionId")
            client.command(owner,"/permission read-only")
            val store=WorkerDashboardStore(app)
            fun rows():List<JSONObject> {
                val all=store.list().getJSONArray("items")
                return (0 until all.length()).map(all::getJSONObject).filter{it.optString("owner")==owner}
            }
            fun send(text:String) {
                val request=UUID.randomUUID().toString()
                PetReplyOperation(150000).run {
                    client.followReplyWithImages(owner,request) {
                        client.call("session/prompt",JSONObject().put("sessionId",owner).put("requestId",request).put("mode","queue")
                            .put("content",JSONArray().put(JSONObject().put("type","text").put("text",text))))
                    }
                }
            }
            send("请立即派发一个 mode=read 的独立工单，标题存储恢复验收，任务=计算123+456，并用mobile_worker_report completed回执579。派发后结束本轮。")
            await("原工单未完成"){rows().size==1&&rows()[0].optString("state")=="completed"}
            val original=rows().single()
            val id=original.getString("id")
            val all=store.list().getJSONArray("items")
            org.junit.Assume.assumeTrue("暂不重启其他正在执行的用户工单",(0 until all.length()).map(all::getJSONObject)
                .none{!it.optBoolean("missingLedger") && it.optString("owner")!=owner && it.optString("state") in setOf("starting","running","cancelling")})
            host.stopRuntime()
            val directory=File(RuntimeStore(app).currentRoot,"root/.dsh/mobile-workers")
            assertTrue(File(directory,".checkpoints/$id.json.snapshot").isFile)
            File(directory,"$id.json").writeText("{partial")
            start()
            client=HarnessBubbleClient(host.openHarnessAccess())
            await("损坏工单没有转为待核对"){rows().firstOrNull{it.optString("id")==id}?.optBoolean("recoveryRequired")==true}
            val held=rows().single{it.getString("id")==id}
            assertEquals("interrupted",held.getString("state"))
            val recovery=held.getJSONObject("recovery")
            assertTrue(recovery.getBoolean("usable"))
            assertTrue(recovery.getJSONObject("preview").getString("summary").contains("579"))
            send("请再独立派发一个mode=read的工单，标题健康工单验收，任务计算20+22并回执completed 42。保留此前待核对工单，派发后结束本轮。")
            await("健康工单受到损坏记录影响"){rows().any{it.optString("id")!=id&&it.optString("state")=="completed"&&it.optString("summary").contains("42")}}
            assertTrue(rows().single{it.getString("id")==id}.optBoolean("recoveryRequired"))
            val command=JSONObject().put("id",id).put("action","recover").put("recoveryIncident",recovery.getString("incidentId"))
            assertTrue("缺少用户核对仍被解锁",runCatching{store.command(command)}.isFailure)
            command.put("recoveryAcknowledged",true)
            store.command(command)
            await("核对命令未解锁原工单"){rows().single{it.getString("id")==id}.optBoolean("recoveryRequired")==false}
            store.command(JSONObject().put("id",id).put("action","resume").put("task","用户已核对恢复副本。请在本工单计算50+17，并回执completed 67。"))
            await("原工单没有按新确认内容继续"){rows().single{it.getString("id")==id}.let{it.optString("state")=="completed"&&it.optString("summary").contains("67")}}
            assertEquals("恢复产生了重复工单",2,rows().size)
            inst.sendStatus(0,android.os.Bundle().apply {
                putBoolean("realRecordCorruptionHeld",true);putBoolean("verifiedSnapshotReadable",true)
                putBoolean("healthyWorkerCompleted",true);putBoolean("explicitReviewRequired",true);putBoolean("sameWorkerResumed",true)
            })
        } finally {RuntimeHost.detachPluginSink(sink)}
    }
}
