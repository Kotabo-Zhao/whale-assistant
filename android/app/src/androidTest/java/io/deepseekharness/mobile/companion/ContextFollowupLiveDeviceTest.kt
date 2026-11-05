package io.deepseekharness.mobile.companion

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.OverlayBallService
import io.deepseekharness.mobile.SessionCatalog
import io.deepseekharness.mobile.runtime.RuntimeStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class ContextFollowupLiveDeviceTest {
    @Test fun recentMainChatAndNewMaterialTriggerAnActualIdlePetHint() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveContextOverlay") == "yes")
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val settings = CompanionStore(app)
        check(settings.knowledgeModelAccess && !settings.inQuietHours())
        val prefs = app.getSharedPreferences("companion_proactive_chat_v1", Context.MODE_PRIVATE)
        val before = prefs.all
        val hadFollowup = settings.contextFollowup
        val note = CompanionKnowledgeStore(app)
        val label = "银帆纸艺展" + UUID.randomUUID().toString().take(6).map { ('甲'.code + it.code % 9).toChar() }.joinToString("")
        val text = "展务最新确认：${label}的会场已定为蓝桨馆。本周六上午可以布展。"
        var fixtureId: String? = null
        fun field(obj: Any, name: String): Any? = obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)
        fun contains(view: View?, question: String): Boolean = when (view) {
            is TextView -> view.text.toString() == question
            is ViewGroup -> (0 until view.childCount).any { contains(view.getChildAt(it), question) }
            else -> false
        }
        val active = OverlayBallService::class.java.getDeclaredField("activeService").apply { isAccessible = true }
        try {
            settings.contextFollowup = false
            withLivePetRuntime(app) { host ->
                val session = host.call("session/create", JSONObject()).getString("sessionId")
                val requestId = UUID.randomUUID().toString()
                val prompt = "我正在筹备${label}，目前会场还没有确定。等展务确认场地后我想一起定布展时间。请先简单回应，暂时不用查找或执行。"
                host.followReplyWithImages(session, requestId) {
                    host.call("session/prompt", JSONObject().put("sessionId",session).put("requestId",requestId).put("mode","queue")
                        .put("content",JSONArray().put(JSONObject().put("type","text").put("text",prompt))))
                }
                val chats = SessionCatalog(app, RuntimeStore(app)).initiativeChats()
                assertTrue("刚才真实的主对话没有被正确读出", (0 until chats.length()).map(chats::getJSONObject)
                    .any { it.optString("id") == session && it.getJSONArray("turns").toString().contains(label) })
                fixtureId = note.saveText(text, "text_file", null).recordIds.single()
                OverlayBallService.start(app)
                val readyUntil = SystemClock.elapsedRealtime() + 10_000
                while (active.get(null) == null && SystemClock.elapsedRealtime() < readyUntil) SystemClock.sleep(80)
                val service = requireNotNull(active.get(null))
                settings.contextFollowup = true
                val evidence = requireNotNull(PetContextFollowup.find(app))
                assertEquals(fixtureId, evidence.id)
                assertTrue(evidence.chat.turns.any { it.text.contains(label) })
                val source = java.io.File(RuntimeStore(app).currentRoot, "root/.dsh/sessions/" + evidence.chat.file)
                assertTrue("核对来源失败：时间 ${source.lastModified()}/${evidence.chat.at}，长度 ${source.length()}/${evidence.chat.size}，规范路径一致=${source.canonicalFile == source.absoluteFile}", PetContextFollowup.current(app, evidence.reference()))
                val ready = service.javaClass.getDeclaredMethod("initiativeScreenReady").apply { isAccessible = true }
                var idle = false
                val idleUntil = SystemClock.elapsedRealtime() + 12_000
                while (!idle && SystemClock.elapsedRealtime() < idleUntil) {
                    inst.runOnMainSync { idle = ready.invoke(service) == true }
                    if (!idle) SystemClock.sleep(100)
                }
                val pet = (field(service, "bubbleChat\$delegate") as Lazy<*>).value as CompanionBubbleWindow
                assertTrue("浮窗未空闲；窗口可接收=${pet.canReceiveInitiative}，已附着=${field(service, "ballAttached")}，已有提示=${field(service,"hintView") != null}", idle)
                OverlayBallService.checkInitiativeAfterOptIn()
                val deadline = SystemClock.elapsedRealtime() + 100_000
                var question: String? = null
                while (question == null && SystemClock.elapsedRealtime() < deadline) {
                    inst.runOnMainSync {
                        val hint = field(service, "hintView") as? TextView
                        if (hint?.isShown == true && (hint.text.contains("蓝桨馆") || hint.text.contains("周六"))) {
                            question = hint.text.toString()
                            hint.performClick()
                        }
                    }
                    if (question == null) SystemClock.sleep(80)
                }
                val shown = requireNotNull(question) {
                    val window = (field(service, "bubbleChat\$delegate") as Lazy<*>).value as CompanionBubbleWindow
                    "未看到跟进气泡；可接收=${window.canReceiveInitiative}，桌宠已就绪=${field(service, "ballAttached")}，扫描中=${field(service, "proactiveMemoryRunning")}，状态=${PetInitiative.lastStatus(app)}"
                }
                val window = (field(service, "bubbleChat\$delegate") as Lazy<*>).value as CompanionBubbleWindow
                val openUntil = SystemClock.elapsedRealtime() + 8000
                var inChat = false
                while (!inChat && SystemClock.elapsedRealtime() < openUntil) {
                    inst.runOnMainSync { inChat = contains(field(window, "root") as? View, shown) }
                    if (!inChat) SystemClock.sleep(60)
                }
                assertTrue("点击气泡后原问题应进入桌宠对话", inChat)
                inst.sendStatus(0, Bundle().apply { putString("syntheticFollowup", shown) })
                inst.runOnMainSync { window.hide() }
            }
        } finally {
            // Only roll back quota/status written by this opt-in synthetic check.
            val id = fixtureId
            if (id != null) {
                val opening = prefs.getString("opening", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (opening?.optJSONObject("context")?.optString("id") == id ||
                    prefs.getString("last_considered", null) != before["last_considered"]) {
                    val editor = prefs.edit()
                    for (key in listOf("attempt_daily","attempt_weekly","last_considered","considered_contexts","key","last","status","status_at","opening")) {
                        when (val old = before[key]) {
                            is String -> editor.putString(key, old)
                            is Long -> editor.putLong(key, old)
                            else -> editor.remove(key)
                        }
                    }
                    check(editor.commit())
                }
                note.remove(id, text)
            }
            settings.contextFollowup = hadFollowup
        }
    }
}
