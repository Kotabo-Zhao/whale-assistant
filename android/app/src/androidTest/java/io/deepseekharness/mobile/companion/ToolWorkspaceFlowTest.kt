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

class ToolWorkspaceFlowTest {
    @Test fun realEnabledToolOpensFromThemedWorkspace() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val store=io.deepseekharness.mobile.features.WhaleFeatureStore(app)
        val id="ui-tool-"+java.util.UUID.randomUUID().toString().take(8)
        val revision=store.draft(org.json.JSONObject().put("id",id).put("title","界面验收：海星清单")
            .put("html","<h1 id='tool-proof'>工具界面连接成功</h1>").put("baseRevision","")).getString("latest")
        store.activate(id,revision,"")
        val main = inst.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun js(script: String, view: android.webkit.WebView = main.bridge.webView): String {
            val ready = CountDownLatch(1); var result = ""
            inst.runOnMainSync { view.evaluateJavascript(script) { result=it; ready.countDown() } }
            check(ready.await(5,TimeUnit.SECONDS)); return result
        }
        fun until(check: () -> Boolean) {
            val end = SystemClock.elapsedRealtime()+15000
            while (!check()) { assertTrue("工具界面等待超时",SystemClock.elapsedRealtime()<end); Thread.sleep(80) }
        }
        var originalStyle = "null"; var originalTheme = "null"
        try {
            until {
                inst.runOnMainSync { main.findViewById<android.view.View>(io.deepseekharness.mobile.R.id.harness_manage)?.takeIf { it.isShown }?.performClick() }
                js("!!document.querySelector('button[aria-label=工具]')") == "true"
            }
            originalStyle=js("document.documentElement.dataset.style"); originalTheme=js("document.documentElement.dataset.theme")
            js("document.querySelector('button[aria-label=工具]').click()")
            until { js("Array.from(document.querySelectorAll('.authored-tool-card')).some(b=>b.innerText.includes('界面验收：海星清单'))") == "true" }
            for (style in listOf("simple","cute")) for (mode in listOf("light","dark")) {
                js("document.documentElement.dataset.style='$style';document.documentElement.dataset.theme='$mode';document.querySelector('.tool-creation-entry').scrollIntoView({block:'start'})")
                inst.waitForIdleSync(); Thread.sleep(200)
                assertEquals("true",js("document.documentElement.scrollWidth<=innerWidth"))
                val bitmap=requireNotNull(inst.uiAutomation.takeScreenshot())
                try { File(app.getExternalFilesDir(null),"tools-flow-$style-$mode.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
                finally { bitmap.recycle() }
            }
            val monitor=inst.addMonitor(io.deepseekharness.mobile.features.WhaleFeatureActivity::class.java.name,null,false)
            try {
                js("Array.from(document.querySelectorAll('.authored-tool-card')).find(b=>b.innerText.includes('界面验收：海星清单')).click()")
                val opened=inst.waitForMonitorWithTimeout(monitor,10000)
                assertNotNull("点击真实已启用工具必须打开原生功能页",opened)
                try {
                    assertEquals(id,opened.intent.getStringExtra("featureId"))
                    fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
                        if(view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
                    until {
                        var page: android.webkit.WebView? = null
                        inst.runOnMainSync { page=descendants(opened.window.decorView).filterIsInstance<android.webkit.WebView>().firstOrNull() }
                        page?.let { js("document.getElementById('tool-proof')?.textContent==='工具界面连接成功'",it) } == "true"
                    }
                } finally { inst.runOnMainSync { opened.finish() } }
            } finally { inst.removeMonitor(monitor) }
            inst.sendStatus(0,android.os.Bundle().apply { putString("toolFlow","real draft/activation/list/open path; four web themes; no model call") })
        } finally {
            js("document.documentElement.dataset.style=$originalStyle;document.documentElement.dataset.theme=$originalTheme")
            inst.runOnMainSync { main.finish() }
            store.activate(id,"",revision)
            android.util.AtomicFile(File(app.noBackupFilesDir,"whale-features-v1/$id.json")).delete()
        }
    }
}
