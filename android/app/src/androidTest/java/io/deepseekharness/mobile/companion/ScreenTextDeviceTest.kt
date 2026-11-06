package io.deepseekharness.mobile.companion

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.DeviceBridgeServer
import io.deepseekharness.mobile.DeviceControlState
import io.deepseekharness.mobile.runtime.RuntimeHost
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.UUID

class ScreenTextDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val app=inst.targetContext
    private fun command(name:String,request:JSONObject):JSONObject=command(name,request.toString())
    private fun command(name:String,param:String):JSONObject {
        val bridge=RuntimeHost.deviceBridgeOrNull() as DeviceBridgeServer
        val token=PetControlDeviceUi.field(bridge,"token") as String
        return Socket("127.0.0.1",bridge.localPort).use { socket ->
            socket.soTimeout=40000
            val body=JSONObject().put("command",name).put("param",param).toString().toByteArray()
            socket.getOutputStream().apply {write(("POST /device-command HTTP/1.0\r\nAuthorization: Bearer $token\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray());write(body);flush()}
            JSONObject(socket.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n"))
        }
    }
    private fun value(name:String,request:JSONObject=JSONObject()):JSONObject {
        val response=command(name,request);assertTrue(response.toString(),response.getBoolean("ok"));return JSONObject(response.getString("text"))
    }
    private fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null) emptyList() else listOf(n)+(0 until n.childCount).flatMap{nodes(n.getChild(it))}
    @Test fun exactPasteIntoRealChromeUnderChineseImeAndWrongAppIsRejected() {
        val wake=WorkerRuntimeWake(app)
        try {
            PetControlDeviceUi.await("runtime") {
                try {wake.wake();true} catch(e:io.deepseekharness.mobile.runtime.RuntimeFailure){if(e.code!="RUNTIME_CLOSING") throw e;false}
            }
            PetControlDeviceUi.await("bridge"){RuntimeHost.deviceBridgeOrNull()!=null}
            DeviceControlState.resume()
            for(target in listOf("bin.mt.plus","com.google.android.apps.nbu.files","com.android.chrome")) {
                val launch=command("appLaunch",target)
                assertTrue("真实 App 启动未核实：$target $launch",launch.getBoolean("ok"))
                PetControlDeviceUi.await("actual foreground $target") {inst.uiAutomation.rootInActiveWindow?.packageName?.toString()==target}
            }
            var url:AccessibilityNodeInfo?=null
            PetControlDeviceUi.await("Chrome address field") {
                url=nodes(inst.uiAutomation.rootInActiveWindow).firstOrNull{it.viewIdResourceName=="com.android.chrome:id/url_bar"};url!=null
            }
            val rect=android.graphics.Rect();url!!.getBoundsInScreen(rect)
            val frame=value("screenObserve")
            val tapped=value("screenAct",JSONObject().put("observationId",frame.getString("observationId")).put("operationId",UUID.randomUUID().toString())
                .put("action","tap").put("x",rect.centerX()).put("y",rect.centerY()))
            assertTrue(tapped.has("observation"))
            val exact="https://developer.android.com/training/monitoring-device-state/doze-standby"
            fun request(target:String)=JSONObject().put("observationId",value("screenObserve").getString("observationId"))
                .put("operationId",UUID.randomUUID().toString()).put("action","text").put("text",exact).put("replace",true).put("packageName",target)
            val wrong=command("screenAct",request("com.example.wrong"))
            assertEquals("SCREEN_APP_CHANGED",wrong.getString("errorCode"))
            val pasted=value("screenAct",request("com.android.chrome"))
            assertTrue(pasted.getJSONObject("action").getBoolean("clipboardReplaced"))
            PetControlDeviceUi.await("URL exact, no IME rewriting") {
                nodes(inst.uiAutomation.rootInActiveWindow).any{it.viewIdResourceName=="com.android.chrome:id/url_bar" && it.text?.toString()==exact}
            }
            inst.sendStatus(0,android.os.Bundle().apply{putBoolean("chromeExactPaste",true);putBoolean("wrongAppRejected",true);putBoolean("threeAppsLaunchVerified",true)})
        } finally {wake.close()}
    }
}
