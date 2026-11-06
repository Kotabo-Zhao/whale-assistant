package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class OwnerAssistantDeviceTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(run: (Context)->Unit) {
        val dir=File(app.cacheDir,"owner-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val prefs=mutableSetOf<String>()
        val ctx=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir()=dir
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("${dir.name}-$name".also(prefs::add),mode)
        }
        try { run(ctx) } finally {
            runCatching { CompanionReminder(ctx).list().let { rows -> (0 until rows.length()).map(rows::getJSONObject).forEach { CompanionReminder(ctx).remove(it.getString("id")) } } }
            prefs.forEach(app::deleteSharedPreferences);dir.deleteRecursively()
        }
    }
    private fun field(memory:CompanionMemoryStore,key:String="preferred_name")=memory.profile(true).getJSONArray("fields").let { rows ->
        (0 until rows.length()).map(rows::getJSONObject).single { it.getString("field")==key }
    }
    private fun set(memory:CompanionMemoryStore,value:String)=memory.changeProfile(JSONObject().put("action","set").put("field","preferred_name")
        .put("value",value).put("expectedRevision",field(memory).getString("revision")).put("operationId",UUID.randomUUID().toString()),forUser=true)
    private fun request(action:String)=JSONObject().put("action",action).put("operationId",UUID.randomUUID().toString())
    private fun update(item:JSONObject,state:String?=null)=request("update").put("id",item.getString("id")).put("expectedRevision",item.getString("revision")).apply { if(state!=null)put("state",state) }

    @Test fun profileIsSharedIndexedAndFreshAfterReopenCorrectionAndRevocation()=fixture { ctx ->
        val memory=CompanionMemoryStore(ctx);set(memory,"测试主人甲")
        val settings=CompanionStore(ctx);settings.bubbleMemoryAccess=true;settings.notificationModelAccess=true
        val owner=OwnerContext(ctx);val old=owner.read()
        assertTrue(old.toString().contains("测试主人甲"))
        assertEquals("测试主人甲",field(CompanionMemoryStore(ctx)).getString("value"))
        MemoryMigrationDatabase(UnifiedUnderstandingStore.path(ctx)).use { assertEquals(1,it.profileRows().size) }
        set(memory,"测试主人乙");assertFalse(owner.isCurrent(old));assertFalse(owner.read().toString().contains("测试主人甲"))
        val current=field(memory)
        memory.changeProfile(JSONObject().put("action","forget").put("field","preferred_name").put("expectedRevision",current.getString("revision")),forUser=true)
        assertEquals("empty",field(CompanionMemoryStore(ctx)).getString("state"))
        assertFalse(owner.read().toString().contains("测试主人乙"))
        settings.bubbleMemoryAccess=false;assertFalse(owner.read().has("profile"))
    }
    @Test fun replayRescheduleCompleteCancelAndDeleteInvalidateOldAlarms()=fixture { ctx ->
        val path=UnifiedUnderstandingStore.path(ctx);val now=System.currentTimeMillis()
        val create=request("create").put("title","测试交办").put("nextAction","核对结果").put("waitingFor","测试回执").put("dueAtMs",now+7200000).put("reminderAtMs",now+3600000)
        val created=SecretaryActions.execute(ctx,create,"session-test");assertTrue(created.getBoolean("scheduled"))
        val id=created.getString("id");var item=created.getJSONObject("item");val revision=item.getInt("reminderRevision")
        assertTrue(SecretaryActions.execute(ctx,create,"session-test").getBoolean("idempotent"))
        UnderstandingQueue(path).use { assertEquals(1,it.secretary.list().getJSONArray("items").length()) }
        val stale=update(item,"done")
        item=SecretaryActions.execute(ctx,update(item).put("dueAtMs",now+14400000),"session-test").getJSONObject("item")
        UnderstandingQueue(path).use { assertFalse(it.itemReminderCurrent(id,now+3600000,revision)) }
        assertEquals("none",item.getString("reminderStatus"))
        assertThrows(Exception::class.java) { SecretaryActions.execute(ctx,stale,"session-test") }
        item=SecretaryActions.execute(ctx,update(item).put("reminderAtMs",now+5400000),"session-test").getJSONObject("item")
        val replacement=item.getInt("reminderRevision")
        item=SecretaryActions.execute(ctx,update(item,"done"),"session-test").getJSONObject("item")
        UnderstandingQueue(path).use { assertFalse(it.itemReminderCurrent(id,now+5400000,replacement));assertEquals(0,it.secretary.list(activeOnly=true).getJSONArray("items").length()) }
        item=SecretaryActions.execute(ctx,update(item,"open"),"session-test").getJSONObject("item")
        assertEquals("none",item.getString("reminderStatus"))
        item=SecretaryActions.execute(ctx,update(item,"cancelled"),"session-test").getJSONObject("item")
        SecretaryActions.execute(ctx,request("delete").put("id",id).put("expectedRevision",item.getString("revision")),"session-test")
        assertTrue(SecretaryActions.execute(ctx,create,"session-test").getBoolean("removed"))
        UnderstandingQueue(path).use { assertNull(it.item(id));assertTrue(it.desiredItemReminders().isEmpty()) }
    }
    @Test fun backupsKeepTaskMetadataButCannotResurrectDeletedItemsOrOldReminders()=fixture { ctx ->
        val path=UnifiedUnderstandingStore.path(ctx);val now=System.currentTimeMillis()
        val create=request("create").put("title","备份交办").put("nextAction","读取测试报告").put("dueAtMs",now+7200000).put("reminderAtMs",now+3600000)
        var item:JSONObject;val backup:ByteArray
        UnderstandingQueue(path).use { q -> item=q.secretary.execute(create,"session-test").getJSONObject("item");backup=q.exportArchive() }
        val restore=File(ctx.noBackupFilesDir,"restored.db")
        UnderstandingQueue(restore).use { q ->
            q.importArchive(backup)
            val recovered=q.secretary.public(item.getString("id"));assertEquals("读取测试报告",recovered.getString("nextAction"));assertEquals("needs_review",recovered.getString("reminderStatus"))
            assertTrue(q.secretary.execute(create,"session-test").getBoolean("idempotent"))
        }
        UnderstandingQueue(path).use { q ->
            item=q.secretary.execute(update(item,"done"),"session-test").getJSONObject("item")
            q.importArchive(backup);assertEquals("done",q.item(item.getString("id"))!!.state)
            q.secretary.execute(request("delete").put("id",item.getString("id")).put("expectedRevision",item.getString("revision")),"session-test")
            q.importArchive(backup);assertNull(q.item(item.getString("id")))
            assertEquals(0,JSONObject(String(q.exportArchive(),Charsets.UTF_8)).getJSONObject("secretary").getJSONArray("items").length())
        }
    }
    @Test fun transactionFailureRollsBackAndSoonestDeadlineAppearsFirst()=fixture { ctx ->
        UnderstandingQueue(UnifiedUnderstandingStore.path(ctx)).use { q ->
            val now=System.currentTimeMillis()
            val invalid=request("create").put("title","不应保存").put("reminderAtMs",now-1)
            assertThrows(Exception::class.java) { q.secretary.execute(invalid,"session-test") }
            assertEquals(0,q.secretary.list().getJSONArray("items").length())
            q.secretary.execute(request("create").put("title","最早").put("dueAtMs",now+60000),"session-test")
            q.secretary.execute(request("create").put("title","较晚").put("dueAtMs",now+90000),"session-test")
            q.secretary.execute(request("create").put("title","未定"),"session-test")
            assertEquals("最早",q.secretary.list(activeOnly=true).getJSONArray("items").getJSONObject(0).getString("title"))
            q.clearCollectedData();assertEquals(0,q.secretary.exportMetadata().getJSONArray("items").length())
        }
    }
    @Test fun reminderReceiverDeliversOnceAndCompletionClearsItsNotification()=fixture { ctx ->
        val manager=ctx.getSystemService(android.app.NotificationManager::class.java)
        org.junit.Assume.assumeTrue(manager.areNotificationsEnabled())
        val now=System.currentTimeMillis();val at=now-1000
        val item=UnderstandingQueue(UnifiedUnderstandingStore.path(ctx)).use { q ->
            q.secretary.execute(request("create").put("title","鲸鱼助手提醒验收").put("reminderAtMs",at),"session-test",now-30000).getJSONObject("item")
        }
        val id=item.getString("id")
        val reminder=JSONObject().put("id",id).put("text","鲸鱼助手提醒验收").put("at",at).put("linkedItem",true)
            .put("linkedItemRevision",item.getInt("reminderRevision")).put("state","scheduled")
        ctx.getSharedPreferences("companion_reminders_v1",0).edit().putString("items",org.json.JSONArray().put(reminder).toString()).commit()
        val intent=android.content.Intent().setData(android.net.Uri.parse("dsh-reminder://local/$id"))
        try {
            CompanionReminderReceiver().onReceive(ctx,intent)
            assertEquals("delivered",CompanionReminder(ctx).list().getJSONObject(0).getString("state"))
            val posted=manager.activeNotifications.single { it.id==id.hashCode() }.postTime
            CompanionReminderReceiver().onReceive(ctx,intent)
            assertEquals(posted,manager.activeNotifications.single { it.id==id.hashCode() }.postTime)
            SecretaryActions.execute(ctx,update(item,"done"),"session-test")
            assertFalse(manager.activeNotifications.any { it.id==id.hashCode() })
            CompanionReminderReceiver().onReceive(ctx,intent)
            assertFalse(manager.activeNotifications.any { it.id==id.hashCode() })
        } finally { manager.cancel(id.hashCode()) }
    }
    @Test fun ownerDataNeedsNoSeparateSettingsPage() {
        val inst=InstrumentationRegistry.getInstrumentation()
        fun views(view:android.view.View):List<android.view.View> = listOf(view)+if(view is android.view.ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        for(page in listOf("DATA","DAILY")) {
            val activity=inst.startActivitySync(android.content.Intent(app,CompanionActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(CompanionActivity.OPEN_PAGE,page))
            try {
                inst.runOnMainSync {
                    assertFalse(views(activity.window.decorView).filterIsInstance<android.widget.TextView>().any {
                        it.text.toString().contains("主人档案") || it.text.toString().contains("秘书事项")
                    })
                }
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }
}
