package io.deepseekharness.mobile.companion

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.DeviceBridgeServer
import io.deepseekharness.mobile.DeviceControlState
import io.deepseekharness.mobile.runtime.RuntimeHost
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class ScreenSequenceDeviceTest {
    private fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null) emptyList() else listOf(n)+(0 until n.childCount).flatMap{nodes(n.getChild(it))}
    @Test fun realClockPlanStopsOnMissingTargetCancellationAndPause() {
        val inst=InstrumentationRegistry.getInstrumentation();val app=inst.targetContext
        val wake=WorkerRuntimeWake(app);wake.wake()
        fun await(check:()->Boolean){val end=SystemClock.elapsedRealtime()+60000;while(!check()){check(SystemClock.elapsedRealtime()<end);Thread.sleep(30)}}
        try {
            await{RuntimeHost.deviceBridgeOrNull()!=null}
            val bridge=RuntimeHost.deviceBridgeOrNull() as DeviceBridgeServer
            val token=DeviceBridgeServer::class.java.getDeclaredField("token").apply{isAccessible=true}.get(bridge) as String
            fun command(name:String,param:String):JSONObject=Socket("127.0.0.1",bridge.localPort).use{s->
                s.soTimeout=30000
                val bytes=JSONObject().put("command",name).put("param",param).toString().toByteArray()
                s.getOutputStream().apply{write(("POST /device-command HTTP/1.0\r\nAuthorization: Bearer $token\r\nContent-Length: ${bytes.size}\r\n\r\n").toByteArray());write(bytes);flush()}
                JSONObject(s.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n"))
            }
            app.startActivity(checkNotNull(app.packageManager.getLaunchIntentForPackage("com.android.deskclock")))
            var lastDismissedAt=0L
            var stableSince=0L
            await {
                val root=inst.uiAutomation.rootInActiveWindow
                if(root?.packageName?.toString()=="com.android.permissioncontroller" &&
                    nodes(root).any{it.text?.toString()?.contains("允许时钟向您发送通知")==true} && SystemClock.elapsedRealtime()-lastDismissedAt>500) {
                    inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);lastDismissedAt=SystemClock.elapsedRealtime()
                }
                val clock=root?.packageName?.toString()=="com.android.deskclock" && nodes(root).any{it.contentDescription?.toString()=="闹钟"}
                if(!clock) stableSince=0 else if(stableSince==0L) stableSince=SystemClock.elapsedRealtime()
                clock && SystemClock.elapsedRealtime()-stableSince>=800
            }
            fun plan(vararg labels:String)=JSONObject().put("operationId",java.util.UUID.randomUUID().toString()).put("packageName","com.android.deskclock")
                .put("sessionId","device-test-screen-sequence")
                .put("steps",JSONArray(labels.map{JSONObject().put("text",it)}))
            if(InstrumentationRegistry.getArguments().getString("recoverPreviousTest")=="true") {
                val rows=JSONObject(command("screenRecover","{}").getString("text")).getJSONArray("items")
                for(i in 0 until rows.length()) {
                    val row=rows.getJSONObject(i)
                    if(row.optString("state")=="unknown" && row.optLong("reconciledAt")==0L &&
                        row.optString("packageName")=="com.android.deskclock" && row.optString("sessionId") in setOf("","device-test-screen-sequence"))
                        assertTrue(command("screenRecover",JSONObject().put("operationId",row.getString("operationId")).toString()).getBoolean("ok"))
                }
            }
            fun selected(text:String)=nodes(inst.uiAutomation.rootInActiveWindow).any{it.contentDescription?.toString()==text && it.isSelected}
            val full=command("screenSequence",plan("时钟","定时器","秒表").put("includeImage",true).toString())
            assertTrue(full.toString(),full.getBoolean("ok"))
            val result=JSONObject(full.getString("text"))
            inst.sendStatus(0,android.os.Bundle().apply{putString("sequence",JSONObject().put("status",result.optString("status"))
                .put("error",result.optString("errorCode")).put("completed",result.optInt("completed")).put("elapsedMs",result.optLong("elapsedMs")).toString())})
            assertEquals(result.toString(),"completed",result.getString("status"));assertEquals(3,result.getInt("completed"));await{selected("秒表")}
            assertTrue(result.toString(),result.getBoolean("visibleTextStable"))
            assertEquals("image/jpeg",result.getJSONObject("snapshot").getString("mediaType"))
            assertTrue(result.getJSONObject("snapshot").getString("imageBase64").length>100)
            assertFalse(result.getJSONObject("observation").has("observationId"))
            val image=result.getJSONObject("observation").getJSONObject("image")
            val overlay=image.getJSONObject("excludedControlOverlay").getJSONArray("regions").getJSONArray(0)
            val excluded=android.graphics.Rect(overlay.getInt(0),overlay.getInt(1),overlay.getInt(2),overlay.getInt(3))
            val lines=image.getJSONArray("lines")
            assertTrue("应看到秒表标签下的真实指示线",(0 until lines.length()).any {
                lines.getJSONObject(it).let{row->row.optString("text").contains("秒表") && row.optString("visualSelectionHint")=="underline"}
            })
            for(i in 0 until lines.length()) {
                val b=lines.getJSONObject(i).getJSONArray("bounds")
                assertFalse("桌宠不属于目标 App 观察",excluded.contains((b.getInt(0)+b.getInt(2))/2,(b.getInt(1)+b.getInt(3))/2))
            }
            val missing=JSONObject(command("screenSequence",plan("时钟","WhaleMissingTarget7319","秒表").toString()).getString("text"))
            assertEquals("SCREEN_TARGET_MISSING",missing.getString("errorCode"));assertEquals(1,missing.getInt("completed"));assertTrue(selected("时钟"))
            val wrong=JSONObject(command("screenSequence",plan("秒表").put("packageName","com.android.settings").toString()).getString("text"))
            assertEquals("SCREEN_APP_CHANGED",wrong.getString("errorCode"));assertEquals(0,wrong.getInt("completed"))
            val cancelled=plan("秒表")
            assertTrue(command("screenSequenceCancel",cancelled.getString("operationId")).getBoolean("ok"))
            assertEquals("SCREEN_SEQUENCE_CANCELLED",command("screenSequence",cancelled.toString()).getString("errorCode"));assertTrue(selected("时钟"))
            for(pause in listOf(false,true)) {
                val sequence=plan("定时器","WhaleMissingTarget7319","秒表")
                val reply=java.util.concurrent.atomic.AtomicReference<JSONObject>()
                val runner=Thread{reply.set(command("screenSequence",sequence.toString()))}.apply{start()}
                await{selected("定时器")}
                if(pause) DeviceControlState.pause() else command("screenSequenceCancel",sequence.getString("operationId"))
                runner.join(10000);assertFalse(runner.isAlive)
                assertEquals(if(pause) "DEVICE_CONTROL_PAUSED" else "SCREEN_SEQUENCE_CANCELLED",reply.get().getString("errorCode"))
                Thread.sleep(200);assertTrue(selected("定时器"));DeviceControlState.resume()
                val recovery=command("screenRecover",JSONObject().put("operationId",sequence.getString("operationId")).toString())
                assertTrue(recovery.toString(),recovery.getBoolean("ok"))
                assertTrue(JSONObject(recovery.getString("text")).getBoolean("doNotReplay"))
                assertTrue(command("screenSequence",plan("时钟").toString()).getBoolean("ok"))
                await{selected("时钟")}
            }
        } finally {DeviceControlState.resume();wake.close()}
    }
}
