package io.deepseekharness.mobile.companion

import android.content.Intent
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.AppAuthenticationState
import io.deepseekharness.mobile.HarnessActivity
import io.deepseekharness.mobile.R
import io.deepseekharness.mobile.runtime.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainQueueDeviceTest {
    @Test fun updatedMainConversationOpensAndSendsThroughItsComposer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveMainQueue")=="yes")
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=inst.targetContext
        val sink=object:RuntimeEventSink {
            override fun onProgress(snapshot:RuntimeStateSnapshot)=Unit
            override fun onTerminalOutput(sessionId:String,dataBase64:String,suppressPublicOutput:Boolean)=Unit
            override fun onTerminalExit(sessionId:String,exitCode:Int)=Unit
        }
        val controller=RuntimeHost.acquire(app,sink)
        try {
            if(controller.store.installedManifest()?.rootfs?.sha256!=controller.store.bundledManifestOrNull()?.rootfs?.sha256) {
                controller.stopRuntime()
                controller.install(RuntimeSource(null,null))
            }
            RuntimeHost.ensureDeviceBridge(controller)
            if(runCatching { controller.openHarnessAccess() }.isFailure) RuntimeHost.startHarness(controller)
            val access=controller.openHarnessAccess()
            val host=HarnessBubbleClient(access)
            fun mainCall(method:String,request:JSONObject):JSONObject {
                val uri=java.net.URI(access.url)
                check(uri.host=="127.0.0.1")
                val connection=java.net.URL("http",uri.host,uri.port,"/api/$method").openConnection() as java.net.HttpURLConnection
                try {
                    connection.requestMethod="POST";connection.doOutput=true
                    connection.connectTimeout=15000;connection.readTimeout=15000
                    connection.setRequestProperty("Content-Type","application/json")
                    val basic=java.util.Base64.getEncoder().encodeToString((access.username+":"+access.password).toByteArray())
                    connection.setRequestProperty("Authorization","Basic $basic")
                    val cookie=host.javaClass.getDeclaredMethod("getAuthCookie").apply {isAccessible=true}.invoke(host) as String
                    connection.setRequestProperty("Cookie",cookie)
                    val body=JSONObject().put("type","client-request").put("rpcId",java.util.UUID.randomUUID().toString())
                        .put("method",method).put("payload",JSONObject().put("args",JSONObject().put("request",request)))
                    connection.outputStream.use {it.write(body.toString().toByteArray())}
                    val result=JSONObject(connection.inputStream.bufferedReader().use {it.readText()}).getJSONObject("result")
                    check(result.getBoolean("ok")) {"主对话测试接口失败"}
                    return result.getJSONObject("value")
                } finally {connection.disconnect()}
            }
            val workspace=mainCall("workspace/create",JSONObject().put("path","/root"))
            val workspaceId=workspace.getJSONObject("workspace").getString("workspaceId")
            val id=host.call("session/create",JSONObject().put("workspaceId",workspaceId)).getString("sessionId")
            host.call("session/prompt",JSONObject().put("sessionId",id).put("requestId",java.util.UUID.randomUUID().toString())
                .put("mode","queue").put("content",org.json.JSONArray().put(JSONObject().put("type","text").put("text","请只回复数字1"))))
            AppAuthenticationState.authorizeHarnessLaunch(access)
            val intent=Intent(app,HarnessActivity::class.java).putExtra(HarnessActivity.EXTRA_SESSION_ID,id)
            ActivityScenario.launch<HarnessActivity>(intent).use { scenario ->
                fun evaluate(script:String):String {
                    var value="";val done=CountDownLatch(1)
                    scenario.onActivity {
                        it.findViewById<WebView>(R.id.harness_web_view).evaluateJavascript(script) { result->value=result;done.countDown() }
                    }
                    check(done.await(5,TimeUnit.SECONDS))
                    return value
                }
                fun until(timeout:Long=30000,condition:()->Boolean) {
                    val end=SystemClock.elapsedRealtime()+timeout
                    while(!condition()){
                        if(SystemClock.elapsedRealtime()>=end) {
                            error("主对话界面等待超时")
                        }
                        Thread.sleep(100)
                    }
                }
                until { evaluate("document.readyState==='complete'")=="true" }
                until {
                    evaluate("""(()=>{const d=Array.from(document.querySelectorAll('[role=dialog]')).find(d=>d.textContent.includes('预览版说明'));if(d){const b=Array.from(d.querySelectorAll('button')).find(b=>/知道|继续|开始|了解|确认/.test(b.textContent));if(b)b.click()}return true})()""")
                    evaluate("!!document.querySelector('[contenteditable=true]')")=="true"
                }
                assertEquals("true",evaluate("""(()=>{const input=document.querySelector('input[type=file][multiple]');if(!input)return false;const transfer=new DataTransfer();transfer.items.add(new File(['Synthetic attachment for queue retry'],'queue-retry.txt',{type:'text/plain'}));input.files=transfer.files;input.dispatchEvent(new Event('change',{bubbles:true}));return true})()"""))
                // Let the Host admit the attachment, then withhold its response until reload.
                evaluate("""(()=>{const original=window.fetch;window.__mainQueueIds=[];window.fetch=function(input,init){if(String(input).split('?')[0].endsWith('api/session/prompt')){const body=JSON.parse(init.body);window.__mainQueueIds.push(body.payload.args.request.requestId);if(window.__mainQueueIds.length===1){sessionStorage.setItem('whale.test.firstId',body.payload.args.request.requestId);sessionStorage.setItem('whale.test.payload',JSON.stringify(body.payload.args.request.content));return original.call(this,input,init).then(response=>{window.__firstAdmitted=response.ok;return new Promise(()=>{})})}}return original.call(this,input,init)};return true})()""")
                assertEquals("true",evaluate("""(()=>{const e=document.querySelector('[contenteditable=true]');e.focus();const r=document.createRange();r.selectNodeContents(e);r.collapse(false);const selection=window.getSelection();selection.removeAllRanges();selection.addRange(r);document.execCommand('insertText',false,'请只回复数字，37加19是多少？');return true})()"""))
                until { evaluate("document.querySelector('[contenteditable=true]')?.textContent.includes('37')")=="true" }
                until { evaluate("""Array.from(document.querySelectorAll('button')).some(b=>/^(发送|Send)/i.test(b.getAttribute('aria-label')||b.title||'')&&!b.disabled)""")=="true" }
                val sent=evaluate("""(()=>{const b=Array.from(document.querySelectorAll('button')).find(b=>/^(发送|Send)/i.test(b.getAttribute('aria-label')||b.title||''));if(!b||b.disabled)return false;b.click();return true})()""")
                assertEquals("主对话发送按钮不存在", "true",sent)
                until { evaluate("window.__mainQueueIds.length===1")=="true" }
                until { evaluate("window.__firstAdmitted===true")=="true" }
                scenario.onActivity { it.findViewById<WebView>(R.id.harness_web_view).reload() }
                until { evaluate("window.__mainQueueIds===undefined && !!document.querySelector('[contenteditable=true]')")=="true" }
                evaluate("""(()=>{const original=window.fetch;window.__mainQueueIds=[sessionStorage.getItem('whale.test.firstId')];window.fetch=function(input,init){if(String(input).endsWith('api/session/prompt')){const req=JSON.parse(init.body).payload.args.request;window.__mainQueueIds.push(req.requestId);window.__fileRetry=req.content.some(p=>p.type==='file')&&JSON.stringify(req.content)===sessionStorage.getItem('whale.test.payload')}return original.call(this,input,init)};return true})()""")
                until { evaluate("""(()=>{const b=document.querySelector('[data-whale-recover]');if(b)b.click();return !!b})()""")=="true" }
                until { evaluate("window.__mainQueueIds.length===2")=="true" }
                assertEquals("重试必须使用原文件回执","true",evaluate("window.__fileRetry"))
                assertEquals("重试必须沿用原请求编号", "true",evaluate("window.__mainQueueIds[0]===window.__mainQueueIds[1]"))
                until(150000) {
                    host.recentRecords(id).objects().any { row ->
                        val event=row.optJSONObject("event") ?: row
                        event.optString("type")=="assistant/message" &&
                            event.optJSONObject("data")?.optJSONObject("message")?.optJSONArray("content")?.toString()?.contains("56")==true
                    }
                }
                until { evaluate("document.body.textContent.includes('56')")=="true" }
                val admitted = host.recentRecords(id).objects().count { row ->
                    val event=row.optJSONObject("event") ?: row
                    event.optString("type")=="user/message" &&
                        event.optJSONObject("data")?.optJSONArray("content")?.toString()?.contains("37")==true
                }
                assertEquals("重试只能形成一条已接受消息",1,admitted)
                repeat(2) { index ->
                    evaluate("""(()=>{const e=document.querySelector('[contenteditable=true]');e.focus();const range=document.createRange();range.selectNodeContents(e);range.collapse(false);const sel=window.getSelection();sel.removeAllRanges();sel.addRange(range);document.execCommand('insertText',false,'请只回复数字，24加13是多少？');return true})()""")
                    until { evaluate("""(()=>{const e=document.querySelector('[contenteditable=true]');const b=Array.from(document.querySelectorAll('button')).find(b=>/^(发送消息|排队发送|Send|Queue)/i.test(b.getAttribute('aria-label')||b.title||''));if(!e?.textContent.includes('24')||!b||b.disabled)return false;b.click();return true})()""")=="true" }
                    until { evaluate("window.__mainQueueIds.length===${3+index}")=="true" }
                    until { evaluate("!document.querySelector('[contenteditable=true]')?.textContent.includes('24')")=="true" }
                }
                assertEquals("两条相同消息必须独立发送","true",evaluate("window.__mainQueueIds[2]!==window.__mainQueueIds[3]"))
                until(150000) {
                    host.recentRecords(id).objects().count { row ->
                        val event=row.optJSONObject("event") ?: row
                        event.optString("type")=="user/message" &&
                            event.optJSONObject("data")?.optJSONArray("content")?.toString()?.contains("24加13")==true
                    } == 2
                }
                inst.sendStatus(0,android.os.Bundle().apply {putString("mainQueueLive","actual composer: Host admitted before acknowledgement -> page reload -> same-ID retry -> one admission -> visible model answer")})
            }
        } finally { RuntimeHost.detachPluginSink(sink) }
    }
}
