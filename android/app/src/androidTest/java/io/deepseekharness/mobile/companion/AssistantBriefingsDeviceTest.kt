package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.MainActivity
import io.deepseekharness.mobile.R
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AssistantBriefingsDeviceTest {
    @Test fun installedTaskPageRefreshesOnlyForCommittedBriefings() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val main = inst.startActivitySync(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val prefix = "briefing-probe-${UUID.randomUUID()}-"
        // Exercise the real store's process-wide notification using private fixture data.
        // The installed page continues to read the user's normal inbox.
        val fixture = WorkerReportInbox(object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences(prefix + name, mode)
        })
        fun js(script: String): String {
            val done = CountDownLatch(1)
            var result = ""
            inst.runOnMainSync {
                main.bridge.webView.evaluateJavascript(script) { result = it; done.countDown() }
            }
            check(done.await(5, TimeUnit.SECONDS)) { "WebView inspection timed out" }
            return result
        }
        fun clickTab(labels: String) = js("""
            (() => { const b = [...document.querySelectorAll('nav button')].find(x =>
              x.offsetHeight && ($labels).includes(x.getAttribute('aria-label')));
              if (!b) return false; b.click(); return true; })()
        """.trimIndent()) == "true"
        fun reads() = js("window.whaleBriefingProbe.reads").toInt()
        val selector = ".assistant-task-list[aria-label='小鲸近况']"
        try {
            PetControlDeviceUi.await("open the tools tab in the installed workspace") {
                inst.runOnMainSync {
                    main.findViewById<android.view.View>(R.id.harness_manage)?.takeIf { it.isShown }?.performClick()
                }
                clickTab("['工具','Tools']")
            }
            js("""
                (() => { const cap = window.Capacitor;
                  const probe = window.whaleBriefingProbe = { original: cap.nativePromise, reads: 0 };
                  cap.nativePromise = function(plugin, method, options) {
                    if (method === 'companionDashboard' && options?.request?.operation === 'assistantBriefings') probe.reads++;
                    return probe.original.apply(this, arguments);
                  }; })()
            """.trimIndent())
            assertTrue(clickTab("['任务','Tasks']"))
            PetControlDeviceUi.await("first native inbox read and rendered task page") {
                reads() >= 1 && js("!!document.querySelector(\"$selector\")") == "true"
            }
            val expected = WorkerReportInbox(app).briefings().takeLast(20).reversed().map { it.getString("text") }
            PetControlDeviceUi.await("rendered briefings must match the real inbox") {
                val actual = JSONArray(js("[...document.querySelectorAll(\"$selector article p\")].map(p => p.textContent)"))
                (0 until actual.length()).map(actual::getString) == expected
            }
            val initial = reads()
            SystemClock.sleep(16_000)
            assertEquals("An idle page must not poll the bridge", initial, reads())
            val owner = "session-briefing-probe"
            fixture.add(JSONObject().put("id", "probe").put("owner", owner),
                JSONObject().put("revision", 1).put("state", "completed").put("summary", "内部回执"))
            SystemClock.sleep(450)
            assertEquals("A raw receipt must not refresh the visible inbox", initial, reads())
            val summary = JSONObject().put("owner", owner).put("fingerprint", "a".repeat(64))
                .put("summary", "事件验证").put("next", "")
            fixture.addCoordination(summary)
            PetControlDeviceUi.await("committed briefing must refresh the installed page") { reads() > initial }
            fixture.addCoordination(summary)
            SystemClock.sleep(450)
            assertEquals("A duplicate briefing must not trigger a second read", initial + 1, reads())
            assertTrue(clickTab("['工具','Tools']"))
            PetControlDeviceUi.await("the task page should unmount") { js("!!document.querySelector(\"$selector\")") == "false" }
            fixture.addCoordination(summary.put("fingerprint", "b".repeat(64)))
            SystemClock.sleep(450)
            assertEquals("A closed page must not read the inbox", initial + 1, reads())
            assertTrue(clickTab("['任务','Tasks']"))
            PetControlDeviceUi.await("reopening the task page should read the latest inbox") { reads() > initial + 1 }
            inst.sendStatus(0, Bundle().apply {
                putInt("idleSeconds", 16); putInt("idleReads", 0); putInt("uniqueBriefingReads", 1)
                putInt("rawReceiptReads", 0); putInt("duplicateReads", 0); putInt("unmountedReads", 0)
            })
        } finally {
            js("if (window.whaleBriefingProbe) { window.Capacitor.nativePromise = window.whaleBriefingProbe.original; delete window.whaleBriefingProbe; }")
            app.deleteSharedPreferences(prefix + "worker_report_inbox")
            inst.runOnMainSync { main.finish() }
        }
    }
}
