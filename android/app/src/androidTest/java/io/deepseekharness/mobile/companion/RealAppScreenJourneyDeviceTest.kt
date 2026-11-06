package io.deepseekharness.mobile.companion

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.runtime.RuntimeHost
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Cloud model + production tools; assertions come independently from the real Clock app. */
class RealAppScreenJourneyDeviceTest {
    private fun nodes(root:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> {
        if(root==null) return emptyList()
        return listOf(root)+(0 until root.childCount).flatMap {nodes(root.getChild(it))}
    }
    @Test fun clockNavigationWithRealModel() = journey(true)
    @Test fun settingsNavigationWithRealModel() = journey(false)
    private fun journey(clock:Boolean) {
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=inst.targetContext
        val args=InstrumentationRegistry.getArguments()
        val wake=WorkerRuntimeWake(app)
        wake.wake()
        val readyBy=SystemClock.elapsedRealtime()+60000
        while(runCatching{checkNotNull(RuntimeHost.controllerOrNull()).openHarnessAccess()}.isFailure) {
            check(SystemClock.elapsedRealtime()<readyBy){"运行环境未就绪"};Thread.sleep(100)
        }
        val client=HarnessBubbleClient(checkNotNull(RuntimeHost.controllerOrNull()).openHarnessAccess())
        val session=client.call("session/create",JSONObject()).getString("sessionId")
        client.command(session,"/permission read-only")
        val caseName=if(clock) "clock" else "settings"
        app.startActivity(if(clock) checkNotNull(app.packageManager.getLaunchIntentForPackage("com.android.deskclock"))
            else android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        if(clock) {
        val deadline=SystemClock.elapsedRealtime()+10000
        var alarm:AccessibilityNodeInfo?=null
        var lastDismissedAt=0L
        var stableSince=0L
        while((alarm==null || SystemClock.elapsedRealtime()-stableSince<800) && SystemClock.elapsedRealtime()<deadline) {
            val root=inst.uiAutomation.rootInActiveWindow
            if(root?.packageName?.toString()=="com.android.permissioncontroller" &&
                nodes(root).any{it.text?.toString()?.contains("允许时钟向您发送通知")==true} &&
                SystemClock.elapsedRealtime()-lastDismissedAt>500) {
                inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                lastDismissedAt=SystemClock.elapsedRealtime()
            }
            alarm=nodes(root).firstOrNull{it.contentDescription?.toString()=="闹钟"}
            if(alarm==null || root?.packageName?.toString()!="com.android.deskclock") stableSince=0
            else if(stableSince==0L) stableSince=SystemClock.elapsedRealtime()
            Thread.sleep(50)
        }
        assertNotNull("真实时钟 App 未打开",alarm)
        if(alarm?.isSelected!=true) assertTrue(alarm!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        } else {
            val ready=SystemClock.elapsedRealtime()+8000
            var stableSince=0L
            while(stableSince==0L || SystemClock.elapsedRealtime()-stableSince<800) {
                check(SystemClock.elapsedRealtime()<ready){"设置首页未打开"}
                val root=inst.uiAutomation.rootInActiveWindow
                val home=root?.packageName?.toString()=="com.android.settings" && nodes(root).any{it.text?.toString()=="搜索设置"}
                if(!home) stableSince=0 else if(stableSince==0L) stableSince=SystemClock.elapsedRealtime()
                Thread.sleep(50)
            }
        }
        Thread.sleep(250) // Setup only; excluded from task timing.
        val started=SystemClock.elapsedRealtime()
        val events=JSONArray()
        val pages=JSONArray()
        val running=java.util.concurrent.atomic.AtomicBoolean(true)
        val watcher=Thread {
            var last=if(clock) "闹钟" else "设置"
            while(running.get()) {
                val tab=nodes(inst.uiAutomation.rootInActiveWindow).firstOrNull {
                    if(!clock) it.packageName?.toString()=="com.android.settings" && it.contentDescription?.toString() in setOf("网络和互联网","互联网") else
                    it.packageName?.toString()=="com.android.deskclock" && it.isSelected &&
                        it.contentDescription?.toString() in setOf("时钟","定时器","秒表","闹钟")
                }?.contentDescription?.toString()
                if(tab!=null && tab!=last) {
                    synchronized(pages){pages.put(JSONObject().put("tab",tab).put("ms",SystemClock.elapsedRealtime()-started))}
                    last=tab
                }
                Thread.sleep(35)
            }
        }.apply {start()}
        val task=java.util.UUID.randomUUID().toString()
        var firstChunk=true
        val prompt=(if(!clock) "现在前台是系统设置 App com.android.settings 首页。请依次进入“网络和互联网”、再进入“互联网”，停在互联网页面。只查看页面，不切换 WLAN、飞行模式或任何设置，不连接或断开网络。完成后简短回复。" else "现在前台是系统时钟 App com.android.deskclock。请按顺序切换到“时钟”、再“定时器”、最后“秒表”，停在秒表页。只切换标签，不创建闹钟、不启动计时或秒表、不改设置。完成后简短回复。")+
            if(args.getString("mode")=="baseline") "本次对照只使用 mobile_screen_observe 和 mobile_screen_act，每次动作后查看返回观察再继续。" else ""
        try {
            val reply=PetReplyOperation(150000).run {
                client.followReplyWithImages(session,task,onTrace={kind,value->
                    val type=value.optString("type")
                    val accepted=if(kind=="stream") type=="start" || type=="end" || type=="chunk" && firstChunk
                        else type in setOf("user/message","tool/call","tool/result","assistant/message","llm/retry","turn/end")
                    if(kind=="stream" && type=="start") firstChunk=true
                    if(kind=="stream" && type=="chunk") firstChunk=false
                    if(accepted) synchronized(events) {
                        val row=JSONObject().put("kind",kind).put("type",type).put("ms",SystemClock.elapsedRealtime()-started)
                        if(kind=="stream") row.put("step",value.optInt("step",-1))
                        val data=value.optJSONObject("data")
                        if(type=="user/message") row.put("source",data?.optJSONObject("source")?.optString("kind"))
                        if(type=="tool/call") row.put("tool",data?.optString("name")).put("callId",data?.optString("callId"))
                        if(type=="tool/result") {
                            val message=data?.optJSONObject("message")
                            row.put("callId",message?.optString("toolCallId")).put("error",message?.optBoolean("isError"))
                            val body=message?.optJSONArray("content")?.let{parts->(0 until parts.length()).map{parts.getJSONObject(it).optString("text")}.joinToString("\n")}.orEmpty()
                            row.put("codes",JSONArray(Regex("\\b[A-Z][A-Z_]{5,60}\\b").findAll(body).map{it.value}.distinct().take(6).toList()))
                        }
                        if(type=="assistant/message") row.put("usage",data?.optJSONObject("usage"))
                        events.put(row)
                    }
                }) {
                    client.call("session/prompt",JSONObject().put("sessionId",session).put("requestId",task).put("mode","queue")
                        .put("content",JSONArray().put(JSONObject().put("type","text").put("text",prompt))))
                }
            }
            val total=SystemClock.elapsedRealtime()-started
            running.set(false);watcher.join(3000)
            val report=JSONObject().put("mode",args.getString("mode","optimized")).put("elapsedMs",total)
                .put("events",events).put("pages",pages).put("tools",JSONArray(reply.tools.toList()))
            java.io.File(app.externalCacheDir,"p75-$caseName-${args.getString("mode","optimized")}.json").writeText(report.toString(2))
            inst.sendStatus(0,android.os.Bundle().apply{putString("journey",report.toString())})
            assertEquals(if(clock) listOf("时钟","定时器","秒表") else listOf("网络和互联网","互联网"),(0 until pages.length()).map{pages.getJSONObject(it).getString("tab")})
            val screen=nodes(inst.uiAutomation.rootInActiveWindow)
            if(clock) {
            assertTrue(screen.any{it.contentDescription?.toString()=="秒表" && it.isSelected})
            assertTrue("秒表应尚未启动",screen.any{it.contentDescription?.toString() in setOf("开始","启动")})
            } else assertTrue(screen.any{it.contentDescription?.toString()=="互联网"})
        } finally {running.set(false);watcher.join(3000);wake.close()}
    }
}
