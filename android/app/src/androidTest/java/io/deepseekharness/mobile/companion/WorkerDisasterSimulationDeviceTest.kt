package io.deepseekharness.mobile.companion

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.runtime.ProcessProbe
import io.deepseekharness.mobile.runtime.RuntimeLaunchResolver
import io.deepseekharness.mobile.runtime.RuntimeStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Destructive writes are confined to fresh test directories, with no runtime credentials. */
@RunWith(AndroidJUnit4::class)
class WorkerDisasterSimulationDeviceTest {
    @Test fun guestStorageFaultsExposeTheirActualBlastRadius() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=inst.targetContext
        val store=RuntimeStore(app)
        val resolver=RuntimeLaunchResolver(app,store,includeCredentials=false)
        val script="""
            import assert from 'node:assert/strict'
            import {mkdtempSync,mkdirSync,readFileSync,writeFileSync,rmSync} from 'node:fs'
            import {join} from 'node:path'
            import {WorkerQueue} from '/opt/dsh/plugins/dsh-mobile-shizuku/lib/workers.js'
            import {WorkerGoals} from '/opt/dsh/plugins/dsh-mobile-shizuku/lib/worker-goals.js'
            const root=mkdtempSync('/tmp/whale-disaster-')
            const report={}
            try {
              const dir=join(root,'queue'),queue=new WorkerQueue(dir)
              const good={id:'good',owner:'parent',sessionId:'session-good',mode:'read',state:'completed',revision:1,summary:'579'}
              queue.save(good)
              const before=readFileSync(join(dir,'good.json'),'utf8')
              writeFileSync(join(dir,'good.json.tmp'),'{partial')
              assert.equal(new WorkerQueue(dir).rows.get('good').summary,'579')
              report.incompleteTemporaryWritePreservedPublishedRecord=true
              writeFileSync(join(dir,'broken.json'),'{partial')
              const isolated=new WorkerQueue(dir)
              assert.equal(isolated.rows.get('broken').recoveryRequired,true)
              assert.equal(isolated.rows.get('good').summary,'579')
              assert.equal(readFileSync(join(dir,'good.json'),'utf8'),before)
              report.singleCorruptRecordIsolated=true
              rmSync(join(dir,'broken.json'));rmSync(join(dir,'good.json.tmp'))
              mkdirSync(join(dir,'good.json.tmp'))
              good.summary='unpublished'
              assert.throws(()=>queue.save(good))
              assert.equal(readFileSync(join(dir,'good.json'),'utf8'),before)
              report.failedWritePreservedPublishedRecord=true
              assert.equal(good.writeSeq,JSON.parse(before).writeSeq)
              assert.equal(good.summary,'579')
              report.failedWriteRestoredMemory=true
              const goals=new WorkerGoals(join(root,'goals'),queue.rows)
              goals.define('test','parent',{outcome:'check',criteria:['579']})
              writeFileSync(goals.path,'{partial')
              const restored=new WorkerGoals(join(root,'goals'),queue.rows)
              assert.equal(restored.view('test').state,'recovery')
              assert.equal(restored.view('test').recovery.usable,true)
              report.corruptGoalRecoveredForReview=true
              console.log('WHALE_DISASTER_REPORT '+JSON.stringify(report))
            } finally {rmSync(root,{recursive:true,force:true})}
        """.trimIndent()
        val result=ProcessProbe.run(resolver.launch(listOf("/opt/node/bin/node","--input-type=module","-e",script)),store.currentRoot,60L,outputLimit=4096)
        assertTrue(result.output.take(600),result.succeeded)
        val line=result.output.lineSequence().first{it.startsWith("WHALE_DISASTER_REPORT ")}.substringAfter(' ')
        val report=JSONObject(line)
        assertEquals(5,report.length())
        inst.sendStatus(0,android.os.Bundle().apply {putString("privateStorageFaults",report.toString())})
    }

    @Test fun privateCorruptSupervisionJournalIsDetectedWithoutChangingRealTasks() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val root=File(inst.targetContext.cacheDir,"watchdog-disaster-"+UUID.randomUUID()).apply {mkdirs()}
        val scoped=object:ContextWrapper(inst.targetContext) {override fun getNoBackupFilesDir():File=root}
        val row=JSONObject().put("id",UUID.randomUUID().toString()).put("revision",1)
            .put("health",JSONObject().put("runId","fault-probe").put("sequence",2))
        try {
            val now=System.currentTimeMillis()
            val journal=WorkerWatchdogJournal(scoped)
            assertTrue(journal.claim(row,now,model=true))
            journal.finish(row,"hold","等待暂停",now)
            assertFalse(WorkerWatchdogJournal(scoped).claim(row,now+300001,model=true))
            row.getJSONObject("health").put("sequence",3)
            assertTrue("旧暂停未生效且已有新进展时，后续监督应能重新检查",
                WorkerWatchdogJournal(scoped).claim(row,now+300001,model=true))
            File(root,"worker-watchdog.json").writeText("{partial")
            assertFalse("损坏监督日志恢复后应暂停模型额度",WorkerWatchdogJournal(scoped).due(row,System.currentTimeMillis()))
            assertFalse(WorkerWatchdogJournal(scoped).claim(row,System.currentTimeMillis(),model=true))
            val fresh=JSONObject(row.toString()).put("id",UUID.randomUUID().toString())
            assertTrue("基础运行状态检查仍应可用",WorkerWatchdogJournal(scoped).claim(fresh,System.currentTimeMillis(),model=false))
            assertEquals(1,File(root,"watchdog-recovery").listFiles()!!.size)
            inst.sendStatus(0,android.os.Bundle().apply {putBoolean("corruptJournalRecoveredWithModelCooldown",true);putBoolean("privateFixtureOnly",true)})
        } finally {root.deleteRecursively()}
    }
    @Test fun recoveryPreviewSurvivesRepeatedMirrorsWithoutOldResultNotification() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val root=File(inst.targetContext.cacheDir,"mirror-recovery-"+UUID.randomUUID()).apply{mkdirs()}
        val preferenceNames=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val scoped=object:ContextWrapper(inst.targetContext) {
            override fun getNoBackupFilesDir()=root
            override fun getApplicationContext()=this
            override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences =
                super.getSharedPreferences((root.name+"-"+name).also(preferenceNames::add),mode)
        }
        try {
            val store=WorkerDashboardStore(scoped)
            val id=UUID.randomUUID().toString()
            val original=JSONObject().put("id",id).put("title","原任务").put("owner","recovery-test")
                .put("sessionId","original").put("mode","read").put("state","completed").put("writeSeq",1).put("summary","579")
            store.put(original)
            val held=JSONObject(original.toString()).put("writeSeq",2).put("state","interrupted").put("summary","待核对")
                .put("recoveryRequired",true).put("recovery",JSONObject().put("incidentId","broken").put("usable",false))
                .put("reports",org.json.JSONArray().put(JSONObject().put("revision",1).put("state","completed")
                    .put("summary","579").put("finishedAt",System.currentTimeMillis()).put("recoveryHeld",true)))
            store.put(held)
            val second=JSONObject(held.toString()).put("writeSeq",3)
            second.getJSONObject("recovery").remove("localPreview")
            store.put(second)
            val saved=store.detail(id).getJSONObject("recovery")
            assertEquals("579",saved.getJSONObject("localPreview").getString("summary"))
            assertEquals(1,File(root,"worker-dashboard/recovery").listFiles()!!.size)
            assertTrue(WorkerReportInbox(scoped).unseen().isEmpty())
        } finally {preferenceNames.forEach(inst.targetContext::deleteSharedPreferences);root.deleteRecursively()}
    }

    @Test fun overlayRecoveryReviewShowsBudgetAndRequiresTheRealConfirmationButton() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val root=File(inst.targetContext.cacheDir,"review-overlay-"+UUID.randomUUID()).apply{mkdirs()}
        val preferenceNames=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val scoped=object:ContextWrapper(inst.targetContext) {
            override fun getNoBackupFilesDir()=root
            override fun getApplicationContext()=this
            override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences =
                super.getSharedPreferences((root.name+"-"+name).also(preferenceNames::add),mode)
        }
        fun views(v:android.view.View):List<android.view.View> = listOf(v)+
            if(v is android.view.ViewGroup) (0 until v.childCount).flatMap{views(v.getChildAt(it))} else emptyList()
        var dialog:android.app.AlertDialog?=null
        try {
            val id=UUID.randomUUID().toString()
            val store=WorkerDashboardStore(scoped)
            val row=JSONObject().put("id",id).put("title","恢复核对验收").put("owner","review-test")
                .put("sessionId","review-test").put("state","interrupted").put("writeSeq",1).put("recoveryRequired",true)
                .put("recovery",JSONObject().put("incidentId","review-test").put("usable",true).put("preview",
                    JSONObject().put("title","恢复核对验收").put("budgetUsage",JSONObject().put("tokens",777).put("calls",2).put("milliseconds",60000))))
            store.put(row)
            inst.runOnMainSync {
                val detail=WorkerDetailView(scoped,store.detail(id))
                dialog=android.app.AlertDialog.Builder(scoped).setView(detail).create().apply {
                    window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    show();PetUi(scoped).finishDialog(this)
                }
                assertTrue(views(detail).filterIsInstance<android.widget.TextView>().any{it.text.contains("777 tokens")})
                views(detail).filterIsInstance<android.widget.TextView>().single{it.text=="核对恢复副本后解锁"}.performClick()
            }
            assertEquals("未确认时不应排入恢复命令",0,store.pending().length())
            inst.runOnMainSync {
                val confirm=android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(::views)
                    .filterIsInstance<android.widget.Button>().single{it.isShown&&it.text=="已核对，解锁"}
                confirm.performClick()
            }
            val end=System.currentTimeMillis()+8000
            while(store.pending().length()==0) {check(System.currentTimeMillis()<end);Thread.sleep(40)}
            assertEquals("recover",store.pending().getJSONObject(0).getString("action"))
            assertTrue(store.pending().getJSONObject(0).getBoolean("recoveryAcknowledged"))
            assertEquals(1,store.pending().length())
            inst.sendStatus(0,android.os.Bundle().apply {putBoolean("overlayRecoveryConfirmation",true);putBoolean("budgetPreviewVisible",true)})
        } finally {inst.runOnMainSync{dialog?.dismiss()};preferenceNames.forEach(inst.targetContext::deleteSharedPreferences);root.deleteRecursively()}
    }

    @Test fun interruptedActionsAndOldEvidenceRemainHeldAfterRestart() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val store=RuntimeStore(inst.targetContext)
        val resolver=RuntimeLaunchResolver(inst.targetContext,store,includeCredentials=false)
        val script="""
            import assert from 'node:assert/strict'
            import {mkdtempSync,rmSync} from 'node:fs'
            import {WorkerQueue} from '/opt/dsh/plugins/dsh-mobile-shizuku/lib/workers.js'
            import {WorkerGoals} from '/opt/dsh/plugins/dsh-mobile-shizuku/lib/worker-goals.js'
            import {beginWorkerHealth,acceptsWatchdogHold} from '/opt/dsh/plugins/dsh-mobile-shizuku/lib/worker-health.js'
            const root=mkdtempSync('/tmp/whale-recovery-cases-')
            try {
              const queue=new WorkerQueue(root)
              for(const mode of ['read','device','create']) queue.save({id:mode,owner:'parent',sessionId:'session-'+mode,
                mode,state:'running',revision:1,input:'probe',inputs:[{id:mode+':input:1',kind:'request',revision:1,delivery:'sending'}],
                budgetUsage:{tokens:777,calls:2,milliseconds:500},budgetStartedAt:Date.now()-1000})
              queue.save({id:'cancel',owner:'parent',sessionId:'session-cancel',mode:'device',state:'cancelling',revision:1,authorizationRevoked:true})
              const restored=new WorkerQueue(root)
              assert.equal(restored.rows.get('read').state,'queued')
              assert.equal(restored.rows.get('read').inputs[0].delivery,'unknown')
              assert.equal(restored.rows.get('device').state,'interrupted')
              assert.equal(restored.rows.get('create').state,'interrupted')
              assert.equal(restored.rows.get('cancel').state,'cancelled')
              assert.equal(restored.rows.get('cancel').authorizationRevoked,true)
              for(const row of restored.rows.values()) restored.save(row)
              const again=new WorkerQueue(root)
              assert.equal(again.rows.get('read').inputs.filter(i=>i.kind==='recovery').length,1)
              assert.equal(again.rows.get('read').budgetUsage.tokens,777)
              const row=again.rows.get('read');row.state='running';beginWorkerHealth(row)
              const command={revision:row.revision,runId:row.health.runId,sequence:0,reason:'stalled',explanation:'probe'}
              assert.equal(acceptsWatchdogHold(row,command),true)
              row.inputs.push({id:'later',kind:'update',delivery:'pending',text:'corrected input'})
              assert.equal(acceptsWatchdogHold(row,command),false)
              row.inputs=[];beginWorkerHealth(row)
              assert.equal(acceptsWatchdogHold(row,command),false)
              console.log('WHALE_RECOVERY_CASES_OK')
            } finally {rmSync(root,{recursive:true,force:true})}
        """.trimIndent()
        val result=ProcessProbe.run(resolver.launch(listOf("/opt/node/bin/node","--input-type=module","-e",script)),store.currentRoot,60L,outputLimit=4096)
        assertTrue(result.output.take(600),result.succeeded && result.output.contains("WHALE_RECOVERY_CASES_OK"))
        inst.sendStatus(0,android.os.Bundle().apply {
            putBoolean("unknownDeliveryRetained",true);putBoolean("deviceAndCreationHeld",true)
            putBoolean("cancelStayedRevoked",true);putBoolean("oneRecoveryInputAcrossTwoLoads",true)
            putBoolean("budgetRetained",true);putBoolean("staleAndUpdatedCommandsRejected",true)
        })
    }

}
