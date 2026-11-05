package io.deepseekharness.mobile.companion

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.MainActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PetExecutionEventsTest {
    @Test fun installedPageRefreshesOnChangesWithoutIdlePolling() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val id = java.util.UUID.randomUUID().toString()
        val title = "事件刷新验收 " + id.take(8)
        val store = PetExecutionStore(app)
        store.update(id,"session-ui-test",title,"running")
        val main = inst.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun js(script: String): String {
            val ready = CountDownLatch(1); var result = ""
            inst.runOnMainSync { main.bridge.webView.evaluateJavascript(script) { result=it; ready.countDown() } }
            check(ready.await(5,TimeUnit.SECONDS)); return result
        }
        fun until(check: () -> Boolean) {
            val end = SystemClock.elapsedRealtime()+15000
            while (!check()) { assertTrue("执行记录未更新",SystemClock.elapsedRealtime()<end); Thread.sleep(80) }
        }
        val row = "Array.from(document.querySelectorAll('section[aria-label=\"Agent 执行任务\"] article')).find(a=>a.querySelector('strong')?.textContent===${org.json.JSONObject.quote(title)})"
        try {
            until {
                inst.runOnMainSync { main.findViewById<android.view.View>(io.deepseekharness.mobile.R.id.harness_manage)?.takeIf { it.isShown }?.performClick() }
                js("!!document.querySelector('button[aria-label=事项]')") == "true"
            }
            js("document.querySelector('button[aria-label=事项]').click()")
            until { js("!!Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='执行记录')") == "true" }
            js("""window.executionReadCount=0;window.executionOriginal=window.Capacitor.nativePromise;
                window.Capacitor.nativePromise=function(plugin,method,options){
                    if(method==='companionDashboard' && options?.request?.operation==='executionList') window.executionReadCount++;
                    return window.executionOriginal.apply(this,arguments);
                };Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='执行记录').click();""")
            until { js("($row)?.innerText.includes('进行中')") == "true" }
            val before = js("window.executionReadCount").toInt()
            Thread.sleep(11000)
            assertEquals("静置时仍在读取执行记录",before,js("window.executionReadCount").toInt())
            store.update(id,"session-ui-test",title,"waiting")
            until { js("($row)?.innerText.includes('等你回答')") == "true" }
            assertTrue(js("window.executionReadCount").toInt()>before)
            js("document.querySelector('button[aria-label=工具]').click()")
            until { js("!document.querySelector('section[aria-label=\"Agent 执行任务\"]')") == "true" }
            val hidden = js("window.executionReadCount").toInt()
            store.update(id,"session-ui-test",title,"completed")
            Thread.sleep(500)
            assertEquals(hidden,js("window.executionReadCount").toInt())
            js("document.querySelector('button[aria-label=事项]').click()")
            until { js("!!Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='执行记录')") == "true" }
            js("Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='执行记录').click()")
            until { js("($row)?.innerText.includes('已完成')") == "true" }
            inst.sendStatus(0,android.os.Bundle().apply { putString("executionEvents","11s idle: zero reads; waiting event and completed return verified") })
        } finally {
            js("if(window.executionOriginal)window.Capacitor.nativePromise=window.executionOriginal")
            inst.runOnMainSync { main.finish() }
            android.util.AtomicFile(File(app.noBackupFilesDir,"pet-executions/$id.json")).delete()
        }
    }
}
