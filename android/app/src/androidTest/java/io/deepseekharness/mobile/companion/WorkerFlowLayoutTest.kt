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

class WorkerFlowLayoutTest {
    @Test fun taskOverviewFiltersAndThemes() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val main = inst.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun js(script: String): String {
            val ready = CountDownLatch(1); var result = ""
            inst.runOnMainSync { main.bridge.webView.evaluateJavascript(script) { result=it; ready.countDown() } }
            check(ready.await(5,TimeUnit.SECONDS)); return result
        }
        fun until(check: () -> Boolean) {
            val end = SystemClock.elapsedRealtime()+15000
            while (!check()) { assertTrue("任务界面等待超时",SystemClock.elapsedRealtime()<end); Thread.sleep(80) }
        }
        var originalStyle = "null"; var originalTheme = "null"
        try {
            until {
                inst.runOnMainSync { main.findViewById<android.view.View>(io.deepseekharness.mobile.R.id.harness_manage)?.takeIf { it.isShown }?.performClick() }
                js("!!document.querySelector('button[aria-label=事项]')") == "true"
            }
            originalStyle=js("document.documentElement.dataset.style"); originalTheme=js("document.documentElement.dataset.theme")
            js("""window.workerFlowOriginal=window.Capacitor.nativePromise;
                window.Capacitor.nativePromise=function(plugin,method,options){
                  if(method==='companionDashboard' && options?.request?.operation==='workerPage') {
                    window.workerFlowQuery=options.request;
                    const rows=[{id:'layout-waiting',text:'布局验收：旅行清单需要补充目的地',state:'waiting',mode:'read',question:'这次想去哪个城市？'},
                      {id:'layout-complete',text:'布局验收：已整理出行准备事项',state:'completed',mode:'read',summary:'证件、交通和住宿已分类整理。',sessionId:'session-layout-only'}];
                    const items=options.request.filter==='attention'?rows.slice(0,1):rows;
                    return Promise.resolve({items,total:items.length,running:0,attention:1});
                  }
                  return window.workerFlowOriginal.apply(this,arguments);
                };document.querySelector('button[aria-label=事项]').click();""")
            until { js("!!document.querySelector('.worker-overview')") == "true" }
            until { js("document.querySelector('.worker-overview').innerText.includes('0')") == "true" }
            assertEquals("true",js("!!document.querySelector('.worker-card textarea')"))
            for (style in listOf("simple","cute")) for (mode in listOf("light","dark")) {
                js("document.documentElement.dataset.style='$style';document.documentElement.dataset.theme='$mode';document.querySelector('.worker-flow-heading').scrollIntoView({block:'start'})")
                Thread.sleep(150)
                assertEquals("true",js("document.documentElement.scrollWidth<=innerWidth"))
                val bitmap=requireNotNull(inst.uiAutomation.takeScreenshot())
                try { File(app.getExternalFilesDir(null),"task-flow-$style-$mode.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
                finally { bitmap.recycle() }
            }
            js("document.querySelector('.worker-overview-item.attention').click()")
            until { js("window.workerFlowQuery.filter==='attention' && document.querySelectorAll('.assistant-task-list article').length===1") == "true" }
            assertEquals("true",js("window.workerFlowQuery.group==='' && window.workerFlowQuery.offset===0"))
            inst.sendStatus(0,android.os.Bundle().apply { putString("taskFlow","four theme layouts and global attention navigation; synthetic read responses only") })
        } finally {
            js("if(window.workerFlowOriginal)window.Capacitor.nativePromise=window.workerFlowOriginal;document.documentElement.dataset.style=$originalStyle;document.documentElement.dataset.theme=$originalTheme")
            inst.runOnMainSync { main.finish() }
        }
    }
}
