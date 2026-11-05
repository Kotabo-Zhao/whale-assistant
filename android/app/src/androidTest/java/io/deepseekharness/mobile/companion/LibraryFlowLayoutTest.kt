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

class LibraryFlowLayoutTest {
    @Test fun captureSearchReadAndReturnAcrossThemes() {
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
            while (!check()) { assertTrue("资料界面等待超时",SystemClock.elapsedRealtime()<end); Thread.sleep(80) }
        }
        val originalWidth=main.bridge.webView.layoutParams.width
        var originalStyle = "null"; var originalTheme = "null"
        try {
            until {
                inst.runOnMainSync { main.findViewById<android.view.View>(io.deepseekharness.mobile.R.id.harness_manage)?.takeIf { it.isShown }?.performClick() }
                js("!!document.querySelector('button[aria-label=记忆]')") == "true"
            }
            originalStyle=js("document.documentElement.dataset.style"); originalTheme=js("document.documentElement.dataset.theme")
            js("""window.libraryFlowOriginal=window.Capacitor.nativePromise;
                window.Capacitor.nativePromise=function(plugin,method,options){
                  const request=options?.request;
                  if(method==='companionDashboard') {
                    const audio={id:'layout-audio',createdAt:1,generation:'layout-only',text:'[原件]',source:'original',mime:'audio/wav',displayName:'布局验收：周末出行录音.wav',parseState:'complete',coverage:'extracted_text',shareOrigin:'本地文件'};
                    const note={id:'layout-note',createdAt:2,documentId:'layout-document',text:'周末出行计划：旅行清单包括证件、车票和充电器。到达后先办理入住。',source:'text_file',displayName:'布局验收：出行清单.txt',coverage:'extracted_text',shareOrigin:'系统分享'};
                    if(request.operation==='knowledgeList') {
                      window.libraryQuery=request;
                      return Promise.resolve({items:request.source?[ ]:request.query?[note]:[audio,note],nextOffset:null});
                    }
                    if(request.operation==='knowledgeRecentCaptures') return Promise.resolve({items:[audio,note]});
                    if(request.operation==='knowledgeCaptureRead') return Promise.resolve({row:audio,items:[{id:'layout-part',text:'[00:12] 旅行清单包括证件、车票和充电器。\n[00:27] 到达后先办理入住。',mediaKind:'audio_transcript'}],nextOffset:null});
                    if(request.operation==='knowledgePickFiles') {window.libraryPicked=true;return Promise.resolve({});}
                  }
                  return window.libraryFlowOriginal.apply(this,arguments);
                };document.querySelector('button[aria-label=记忆]').click();""")
            until { js("!!document.querySelector('.library-intake') && document.querySelectorAll('.library-result').length===2") == "true" }
            for (style in listOf("simple","cute")) for (mode in listOf("light","dark")) {
                js("document.documentElement.dataset.style='$style';document.documentElement.dataset.theme='$mode';document.querySelector('.workspace-heading').scrollIntoView({block:'start'})")
                Thread.sleep(150)
                assertEquals("true",js("document.documentElement.scrollWidth<=innerWidth"))
                val bitmap=requireNotNull(inst.uiAutomation.takeScreenshot())
                try { File(app.getExternalFilesDir(null),"library-flow-$style-$mode.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
                finally { bitmap.recycle() }
                js("document.querySelector('.capture-card').click()")
                until { js("!!document.querySelector('.capture-cue')") == "true" }
                assertEquals("true",js("document.body.style.overflow==='hidden' && document.querySelector('[role=dialog]').scrollWidth<=document.querySelector('[role=dialog]').clientWidth"))
                inst.waitForIdleSync(); Thread.sleep(200)
                val reader=requireNotNull(inst.uiAutomation.takeScreenshot())
                try { File(app.getExternalFilesDir(null),"library-reader-$style-$mode.png").outputStream().use { reader.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
                finally { reader.recycle() }
                js("document.querySelector('[role=dialog] header button').click()")
                until { js("!document.querySelector('[role=dialog]') && document.body.style.overflow!=='hidden'") == "true" }
            }
            js("document.querySelector('button[aria-label=存入本地文件]').click()")
            until { js("window.libraryPicked===true") == "true" }
            js("Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(document.querySelector('input[type=search]'),'旅行清单');document.querySelector('input[type=search]').dispatchEvent(new Event('input',{bubbles:true}))")
            until { js("window.libraryQuery.query==='旅行清单' && document.querySelectorAll('.library-result').length===1 && !!document.querySelector('.result-excerpt mark')") == "true" }
            inst.runOnMainSync { main.bridge.webView.layoutParams=main.bridge.webView.layoutParams.apply { width=(360 * app.resources.displayMetrics.density).toInt() } }
            until { js("innerWidth<=361") == "true" }
            js("document.querySelector('.library-results-heading').scrollIntoView({block:'start'})")
            inst.waitForIdleSync(); Thread.sleep(200)
            assertEquals("true",js("document.documentElement.scrollWidth<=innerWidth"))
            val narrow=requireNotNull(inst.uiAutomation.takeScreenshot())
            try { File(app.getExternalFilesDir(null),"library-search-narrow.png").outputStream().use { narrow.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
            finally { narrow.recycle() }
            inst.runOnMainSync { main.bridge.webView.layoutParams=main.bridge.webView.layoutParams.apply { width=originalWidth } }
            js("Array.from(document.querySelectorAll('button')).find(b=>b.innerText==='清除搜索').click()")
            until { js("window.libraryQuery.query==='' && document.querySelectorAll('.library-result').length===2") == "true" }
            js("document.querySelector('.library-capture-guide summary').click()")
            assertEquals("true",js("document.querySelector('.library-capture-guide').open && document.documentElement.scrollWidth<=innerWidth"))
            inst.sendStatus(0,android.os.Bundle().apply { putString("libraryFlow","four themes, read/return, search/reset and capture entry; synthetic bridge responses only") })
        } finally {
            js("if(window.libraryFlowOriginal)window.Capacitor.nativePromise=window.libraryFlowOriginal;document.documentElement.dataset.style=$originalStyle;document.documentElement.dataset.theme=$originalTheme")
            inst.runOnMainSync { main.bridge.webView.layoutParams=main.bridge.webView.layoutParams.apply { width=originalWidth }; main.finish() }
        }
    }
}
