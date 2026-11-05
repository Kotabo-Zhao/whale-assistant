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

class CapabilityPollingTest {
    @Test fun backgroundCapabilitiesStopAndResume() {
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
            while (!check()) { assertTrue("能力页面验收等待超时",SystemClock.elapsedRealtime()<end); Thread.sleep(80) }
        }
        fun click(text: String) {
            val expression = "Array.from(document.querySelectorAll('button')).find(b=>b.textContent.includes("+org.json.JSONObject.quote(text)+"))"
            until { js("!!($expression)") == "true" }
            js("($expression).click()")
        }
        try {
            until {
                inst.runOnMainSync { main.findViewById<android.view.View>(io.deepseekharness.mobile.R.id.harness_manage)?.takeIf { it.isShown }?.performClick() }
                js("!!document.querySelector('button[aria-label=打开应用设置]')") == "true"
            }
            js("document.querySelector('button[aria-label=打开应用设置]').click()")
            click("设备与诊断"); click("诊断与日志"); click("设备能力")
            until { js("!!document.getElementById('root-engine')") == "true" }
            until { js("!!document.querySelector('.capability-technical')") == "true" }
            assertEquals("false",js("document.querySelector('.capability-technical').open"))
            assertEquals("true",js("document.getElementById('root-engine').closest('.settings-subsection').innerText.includes('可能改动系统')"))
            js("document.getElementById('root-engine').scrollIntoView({block:'start'})")
            Thread.sleep(250)
            fun capture(name: String) {
                val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot())
                try { File(app.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
                finally { bitmap.recycle() }
            }
            capture("capability-collapsed.png")
            js("document.querySelector('.capability-technical summary').click()")
            assertEquals("true",js("document.querySelector('.capability-technical').open"))
            assertEquals("true",js("document.querySelector('.capability-technical').innerText.includes('UID 0 的命令能改动系统分区')"))
            Thread.sleep(250)
            capture("capability-expanded.png")
            js("document.querySelector('.capability-technical summary').click()")
            js("""window.capabilityReads=0;window.capabilityOriginal=window.Capacitor.nativePromise;
                window.Capacitor.nativePromise=function(plugin,method,options){
                    if(method==='getDeviceCapabilities' || method==='rootEngineState') window.capabilityReads++;
                    return window.capabilityOriginal.apply(this,arguments);
                };""")
            Thread.sleep(11000)
            assertTrue("可见时应有低频刷新",js("window.capabilityReads").toInt()>=2)
            inst.runOnMainSync { main.moveTaskToBack(true) }
            until { js("document.visibilityState==='hidden'") == "true" }
            val hidden = js("window.capabilityReads").toInt()
            Thread.sleep(12000)
            assertEquals("后台仍读取能力",hidden,js("window.capabilityReads").toInt())
            app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            until { js("document.visibilityState==='visible'") == "true" }
            until { js("window.capabilityReads").toInt()>=hidden+2 }
            inst.sendStatus(0,android.os.Bundle().apply { putString("capabilityPolling","visible polling; 12s background zero reads; return refresh verified") })
        } finally {
            js("if(window.capabilityOriginal)window.Capacitor.nativePromise=window.capabilityOriginal")
            inst.runOnMainSync { main.finish() }
        }
    }
}
