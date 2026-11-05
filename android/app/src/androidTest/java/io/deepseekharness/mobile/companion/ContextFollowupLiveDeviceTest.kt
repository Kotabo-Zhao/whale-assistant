package io.deepseekharness.mobile.companion

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.OverlayBallService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class ContextFollowupLiveDeviceTest {
    @Test fun libraryAndMainChatChangesReachTheIdleOverlayOnTheSameDay() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveContextOverlay") == "yes")
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val settings = CompanionStore(app)
        check(settings.contextFollowup && settings.knowledgeModelAccess && !settings.inQuietHours())
        val prefs = app.getSharedPreferences("companion_proactive_chat_v1", Context.MODE_PRIVATE)
        val library = CompanionKnowledgeStore(app)
        val tag = UUID.randomUUID().toString().take(6).map { ('甲'.code + it.code % 9).toChar() }.joinToString("")
        val expo = "紫鲸运动会" + tag
        val rehearsal = "松针合奏" + tag.reversed()
        val ids = mutableListOf<String>()
        fun note(text: String): String = library.saveText(text, "text_file", null).recordIds.single().also(ids::add)
        fun waitFor(label: String, timeout: Long = 160_000, until: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < end) {
                if (until()) return
                SystemClock.sleep(150)
            }
            fail("等待超时：$label；状态=${PetInitiative.lastStatus(app)}，排队=${ContextFollowupEvents.batch(app).size}，候选=${ContextFollowupEvents.proposed(app) != null}")
        }
        fun sent(): List<JSONObject> {
            val rows = JSONArray(prefs.getString("delivered_matters", "[]"))
            return (0 until rows.length()).map(rows::getJSONObject)
        }
        fun ask(host: HarnessBubbleClient, text: String) {
            val session = host.call("session/create", JSONObject()).getString("sessionId")
            val id = UUID.randomUUID().toString()
            host.followReplyWithImages(session, id) {
                host.call("session/prompt", JSONObject().put("sessionId",session).put("requestId",id).put("mode","queue")
                    .put("content",JSONArray().put(JSONObject().put("type","text").put("text",text))))
            }
        }
        val active = OverlayBallService::class.java.getDeclaredField("activeService").apply { isAccessible = true }
        fun actualHint(noteId: String): String {
            var text = ""
            waitFor("实际桌面提示") {
                inst.runOnMainSync {
                    val service = active.get(null) ?: return@runOnMainSync
                    val hint = service.javaClass.getDeclaredField("hintView").apply { isAccessible = true }.get(service) as? TextView
                    if (hint?.isShown == true) text = hint.text.toString()
                }
                text.isNotEmpty() && sent().any { it.getString("note") == noteId && it.getString("text") == text }
            }
            return text
        }
        val previousOpening = prefs.getString("opening", null)
        try {
            OverlayBallService.start(app)
            waitFor("浮窗服务", 15_000) { active.get(null) != null }
            withLivePetRuntime(app) { host ->
                // Each trigger goes through the ordinary writer/turn bridge. No scheduler kick or time travel.
                ask(host, "我在筹办${expo}，奖杯刻字还在等组委会批准配色。配色公布后我要选对应的丝带。请先简单回应，不用查找。")
                val text = "${expo}组委会刚刚批准奖杯采用珊瑚橙配色，丝带可以按这个颜色准备。"
                val first = note(text)
                val firstQuestion = actualHint(first)
                assertTrue(firstQuestion.contains("珊瑚橙"))
                inst.sendStatus(0, Bundle().apply { putString("noteTriggered", firstQuestion) })
                val revised = "${expo}奖杯配色已获批，颜色是珊瑚橙。请依该颜色备好丝带。"
                assertTrue(library.edit(first, text, revised))
                val copy = note("${expo}奖杯颜色确认采用珊瑚橙，丝带随奖杯配色准备。")
                waitFor("重新判断改写及转存的同一事项") {
                    ContextFollowupEvents.batch(app).keys.none { it == "note:$first" || it == "note:$copy" }
                }
                assertEquals(1, sent().count { it.optString("note") in listOf(first, copy) })
                inst.sendStatus(0, Bundle().apply { putString("sameMatter", "updated and copied note, one visible reminder") })

                val second = note("${rehearsal}的乐谱已由编曲师交付，最终选用降E大调，乐手可以按此版本开始分谱。")
                waitFor("新曲谱等待匹配对话") { "note:$second" !in ContextFollowupEvents.batch(app) }
                assertTrue(sent().none { it.optString("note") == second })
                ask(host, "我负责${rehearsal}的乐手分谱，还不清楚编曲师最后定的调性。等知道调性就想确认分谱安排。请先简单回应，不用查找或执行。")
                val secondQuestion = actualHint(second)
                assertTrue(secondQuestion.contains("降E大调"))
                assertEquals(2, sent().count { it.optString("note") in ids })
                inst.sendStatus(0, Bundle().apply { putString("chatTriggered", secondQuestion) })
            }
        } finally {
            ids.forEach { id -> library.findById(id)?.let { library.remove(id, it.getString("text")) } }
            // Remove only this run's synthetic matters/proposal. Leave unrelated signals and settings alone.
            val remaining = sent().filterNot { it.optString("note") in ids }
            val edit = prefs.edit().putString("delivered_matters", JSONArray(remaining).toString())
            fun synthetic(key: String) = prefs.getString(key, null)?.let { JSONObject(it).optJSONObject("context")?.optString("id") in ids } == true
            if (synthetic("opening")) edit.putString("opening", previousOpening)
            edit.putString("context_proposals", JSONArray(ContextFollowupEvents.staged(app).filterNot { it.optString("note") in ids }).toString())
            edit.commit()
        }
    }
}
