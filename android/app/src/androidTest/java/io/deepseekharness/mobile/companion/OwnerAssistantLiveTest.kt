package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real configured model + real App bridge + pet fast lane. Only temporary, named fixtures are written. */
class OwnerAssistantLiveTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val app=inst.targetContext
    private val evidence=JSONArray()
    private fun nameField()=CompanionMemoryStore(app).profile(true).getJSONArray("fields").let { rows ->
        (0 until rows.length()).map(rows::getJSONObject).single { it.getString("field")=="preferred_name" }
    }
    private fun send(host:HarnessBubbleClient,session:String,text:String,label:String):CompanionBubbleChat.Reply {
        val request=UUID.randomUUID().toString();val start=SystemClock.elapsedRealtime()
        val reply=PetReplyOperation(180_000).run { host.followReplyWithImages(session,request) {
            host.call("session/prompt",JSONObject().put("sessionId",session).put("requestId",request).put("mode","queue")
                .put("content",JSONArray().put(JSONObject().put("type","text").put("text",text))))
        } }
        evidence.put(JSONObject().put("step",label).put("ms",SystemClock.elapsedRealtime()-start).put("tools",JSONArray(reply.tools)).put("answer",reply.text))
        File(app.noBackupFilesDir,"owner-live-evidence.json").writeText(JSONObject().put("steps",evidence).toString(2))
        inst.sendStatus(0,android.os.Bundle().apply { putString("ownerStep",label);putLong("replyMs",SystemClock.elapsedRealtime()-start) })
        return reply
    }
    @Test fun realAppAndPetRecallCorrectionForgetAndSecretaryLifecycle() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveOwnerChecks")=="yes")
        assumeTrue("不覆盖已有主人称呼",nameField().getString("state")=="empty")
        val marker="验收"+UUID.randomUUID().toString().take(8)
        val first="小鲸$marker";val corrected="阿鲸$marker";val title="秘书闭环$marker"
        val prefs=app.getSharedPreferences("companion_private_v1",0)
        val keys=listOf("automatic_assistant_memory","automatic_memory_suppressed_all","memory_enabled")
        val original=keys.associateWith { if(prefs.contains(it)) prefs.getBoolean(it,false) else null }
        val settings=CompanionStore(app);val oldTasks=settings.notificationModelAccess;val oldMemory=settings.bubbleMemoryAccess
        val personaPrefs="owner-persona-test-$marker"
        val petHistoryDir=File(app.cacheDir,"owner-pet-history-$marker").apply { check(mkdirs()) }
        val historyContext=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir()=petHistoryDir
        }
        val petContext=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences(if(name=="companion_personas_v1")personaPrefs else name,mode)
        }
        fun tasks()=UnifiedUnderstandingStore.open(app).use { q -> q.queue.secretary.list(title).getJSONArray("items").let { rows -> (0 until rows.length()).map(rows::getJSONObject) } }
        try {
            prefs.edit().putBoolean("automatic_assistant_memory",true).putBoolean("automatic_memory_suppressed_all",false).putBoolean("memory_enabled",true).commit()
            settings.notificationModelAccess=false;settings.bubbleMemoryAccess=true
            withLivePetRuntime(app) { host ->
                val session=host.call("session/create",JSONObject()).getString("sessionId")
                val saved=send(host,session,"这是本机功能验收：请把我的称呼设为“$first”，保存到主人档案 preferred_name。这是我明确要求的测试数据，其他字段不改。不要交给 Worker。","App保存称呼")
                assertTrue("未用真实档案工具",saved.tools.contains("mobile_owner_profile"));assertEquals(first,nameField().getString("value"));assertEquals("confirmed",nameField().getString("state"))
                val fresh=host.call("session/create",JSONObject()).getString("sessionId")
                val recalled=send(host,fresh,"我希望你怎么称呼我？只说称呼，使用本轮自动加载的主人档案，不调用检索工具。","新会话自动加载")
                assertTrue(recalled.text.contains(first));assertFalse(recalled.tools.contains("mobile_owner_profile"))
                val persona=CompanionPersonaStore(petContext);val card=persona.save(JSONObject().put("name","验收秘书").put("personality","简洁严谨"));persona.activate(card.getString("id"))
                val begin=SystemClock.elapsedRealtime()
                val pet=PetDirectChat(petContext).readOnly(listOf(CompanionBubbleChat.Message("user","我希望你怎么称呼我？只说称呼。")))
                assertTrue(pet.contains(first));evidence.put(JSONObject().put("step","桌宠切换角色后新对话").put("ms",SystemClock.elapsedRealtime()-begin).put("answer",pet))
                send(host,session,"把我的称呼纠正为“$corrected”，使用主人档案更新，旧称呼不再用。","App纠正称呼")
                assertEquals(corrected,nameField().getString("value"))
                val reread=send(host,fresh,"我的称呼现在是什么？直接按当前档案只说称呼。","旧会话读取纠正结果")
                assertTrue(reread.text.contains(corrected));assertFalse(reread.text.contains(first))
                send(host,session,"小说中人物说‘我叫虚构演员’。这不是我的信息，也不是改称呼指令。只回复已读，不要保存。","第三方引文不覆盖")
                assertEquals(corrected,nameField().getString("value"))
                val now=System.currentTimeMillis();val at=now+3600000;val due=now+7200000
                val petChat=CompanionBubbleChat(app,historyStore=CompanionBubbleHistory(historyContext))
                val petStart=SystemClock.elapsedRealtime()
                val created=PetReplyOperation(180_000).run {
                    petChat.fusedReply(listOf(CompanionBubbleChat.Message("user",
                        "请创建秘书事项“$title”，下一步是核对验收报告，等待对象是测试回执；截止时间为 Unix 毫秒 $due，提醒时间为 Unix 毫秒 $at。这些是明确的未来测试时间，直接保存，不操作外部日历。")),
                        CompanionBubbleChatPolicy.toolScope(true,false,false,false),true,false) {}
                }
                evidence.put(JSONObject().put("step","桌宠实际交办与设提醒").put("ms",SystemClock.elapsedRealtime()-petStart).put("tools",JSONArray(created.tools)).put("answer",created.text))
                File(app.noBackupFilesDir,"owner-live-evidence.json").writeText(JSONObject().put("steps",evidence).toString(2))
                inst.sendStatus(0,android.os.Bundle().apply { putString("ownerStep","桌宠实际交办与设提醒");putLong("replyMs",SystemClock.elapsedRealtime()-petStart) })
                assertTrue(created.tools.contains("mobile_secretary"));assertEquals(1,tasks().size)
                var task=tasks().single();val id=task.getString("id");val rev=task.getInt("reminderRevision")
                assertEquals(at,task.getLong("reminderAtMs"));assertEquals("scheduled",task.getString("reminderStatus"))
                val newAt=at+3600000
                send(host,session,"把“$title”改期：截止时间改为 Unix 毫秒 ${due+7200000}，提醒改为 Unix 毫秒 $newAt。保留同一事项，更新下一步为核对新版报告。","实际改期")
                assertEquals(1,tasks().size);task=tasks().single();assertEquals(newAt,task.getLong("reminderAtMs"))
                UnifiedUnderstandingStore.open(app).use { assertFalse(it.queue.itemReminderCurrent(id,at,rev)) }
                send(host,session,"“$title”已经由我核对完成，请标记完成并停用提醒。","实际完成")
                assertEquals("done",tasks().single().getString("state"))
                assertFalse(UnifiedUnderstandingStore.open(app).use { q -> q.queue.desiredItemReminders().any { it.itemId==id } })
                val closed=PetDirectChat(petContext).readOnly(listOf(CompanionBubbleChat.Message("user","名称含“$marker”的秘书事项现在是什么状态？只按本轮事项快照回答，直接输出 state 的原文 open、done、cancelled 之一，不加解释。")))
                evidence.put(JSONObject().put("step","桌宠读取已完成事项").put("answer",closed))
                File(app.noBackupFilesDir,"owner-live-evidence.json").writeText(JSONObject().put("steps",evidence).toString(2))
                assertEquals("桌宠没有采用当前事项状态：$closed","done",closed.trim().trim('`','*','"','。','.',' '))
                send(host,session,"请忘记主人档案中的称呼，删除 preferred_name 字段及其历史。","实际遗忘")
                assertEquals("empty",nameField().getString("state"))
                val forgotten=send(host,host.call("session/create",JSONObject()).getString("sessionId"),"我的称呼是什么？档案没有就说未记录，不猜测。","遗忘后新会话")
                assertFalse(forgotten.text.contains(first));assertFalse(forgotten.text.contains(corrected))
                File(app.noBackupFilesDir,"owner-live-evidence.json").writeText(JSONObject().put("status","PASS").put("steps",evidence).toString(2))
            }
        } finally {
            runCatching { tasks().forEach { task ->
                SecretaryActions.execute(app,JSONObject().put("action","delete").put("id",task.getString("id")).put("expectedRevision",task.getString("revision"))
                    .put("operationId",UUID.randomUUID().toString()),"session-owner-test-cleanup")
                CompanionReminder(app).remove(task.getString("id"))
            } }
            runCatching { val field=nameField();if(field.optString("value") in setOf(first,corrected)) CompanionMemoryStore(app).changeProfile(JSONObject()
                .put("action","forget").put("field","preferred_name").put("expectedRevision",field.getString("revision")),forUser=true) }
            prefs.edit().apply { original.forEach { (key,value)->if(value==null)remove(key) else putBoolean(key,value) } }.commit()
            settings.notificationModelAccess=oldTasks;settings.bubbleMemoryAccess=oldMemory;app.deleteSharedPreferences(personaPrefs)
            petHistoryDir.deleteRecursively()
        }
    }
}
