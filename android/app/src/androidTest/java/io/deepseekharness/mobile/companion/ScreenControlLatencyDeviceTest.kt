package io.deepseekharness.mobile.companion

import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.DeviceBridgeServer
import io.deepseekharness.mobile.DeviceControlState
import io.deepseekharness.mobile.runtime.RuntimeHost
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

/** The same visible fixture and production HTTP bridge are used before and after optimization. */
class ScreenControlLatencyDeviceTest {
    @Test fun productionCaptureOcrAndInputHaveSeparateMeasuredStages() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=inst.targetContext
        val wake=WorkerRuntimeWake(app)
        val wm=app.getSystemService(WindowManager::class.java)
        var fixture:LinearLayout?=null
        val count=java.util.concurrent.atomic.AtomicInteger()
        val downs=java.util.concurrent.atomic.AtomicInteger()
        val ups=java.util.concurrent.atomic.AtomicInteger()
        val cancels=java.util.concurrent.atomic.AtomicInteger()
        fun main(block:()->Unit)=inst.runOnMainSync(block)
        fun await(label:String,check:()->Boolean) {
            val end=SystemClock.elapsedRealtime()+20000
            while(!check()){check(SystemClock.elapsedRealtime()<end){label};Thread.sleep(10)}
        }
        try {
            app.startActivity(android.content.Intent(app,io.deepseekharness.mobile.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            wake.wake()
            await("设备桥未就绪"){RuntimeHost.deviceBridgeOrNull() is DeviceBridgeServer}
            val bridge=RuntimeHost.deviceBridgeOrNull() as DeviceBridgeServer
            val token=DeviceBridgeServer::class.java.getDeclaredField("token").apply{isAccessible=true}.get(bridge) as String
            fun command(name:String,param:String=""):JSONObject {
                val bytes=JSONObject().put("command",name).put("param",param).toString().toByteArray()
                try {return Socket("127.0.0.1",bridge.localPort).use{socket->
                    socket.soTimeout=70000
                    socket.getOutputStream().apply{
                        write(("POST /device-command HTTP/1.0\r\nAuthorization: Bearer "+token+"\r\nContent-Length: "+bytes.size+"\r\n\r\n").toByteArray())
                        write(bytes);flush()
                    }
                    JSONObject(socket.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n"))
                }
                } catch(error:Exception) {
                    val pool=DeviceBridgeServer::class.java.getDeclaredField("executor").apply{isAccessible=true}.get(bridge) as java.util.concurrent.ThreadPoolExecutor
                    val running=DeviceBridgeServer::class.java.getDeclaredField("running").apply{isAccessible=true}.get(bridge) as java.util.concurrent.atomic.AtomicBoolean
                    inst.sendStatus(0,android.os.Bundle().apply {
                        putString("failedBridgeCommand",name);putBoolean("sameBridge",RuntimeHost.deviceBridgeOrNull()===bridge)
                        putBoolean("bridgeRunning",running.get());putInt("bridgeActive",pool.activeCount);putInt("bridgeQueued",pool.queue.size)
                    })
                    throw error
                }
            }
            lateinit var button:TextView
            main {
                fixture=LinearLayout(app).apply{orientation=LinearLayout.VERTICAL;setPadding(24,16,24,16);setBackgroundColor(Color.WHITE)}
                fixture!!.addView(TextView(app).apply{text="Whale 7319";textSize=26f;setTextColor(Color.BLACK)})
                button=TextView(app).apply{text="Safe tap 0";textSize=24f;setTextColor(Color.BLUE);minHeight=140;gravity=Gravity.CENTER
                    setOnTouchListener {_,event->
                        when(event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN->downs.incrementAndGet()
                            android.view.MotionEvent.ACTION_UP->ups.incrementAndGet()
                            android.view.MotionEvent.ACTION_CANCEL->cancels.incrementAndGet()
                        };false
                    }
                    setOnClickListener{text="Safe tap "+count.incrementAndGet()}}
                fixture!!.addView(button)
                wm.addView(fixture,WindowManager.LayoutParams(minOf(1000,app.resources.displayMetrics.widthPixels-200),340,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,android.graphics.PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.LEFT;x=100;y=300})
            }
            await("验收窗口未布局"){var laidOut=false;main{laidOut=button.width>0};laidOut}
            var x=0;var y=0;var region=JSONArray()
            main {
                val xy=IntArray(2);fixture!!.getLocationOnScreen(xy)
                region=JSONArray(listOf(xy[0],xy[1],fixture!!.width,fixture!!.height))
                button.getLocationOnScreen(xy);x=xy[0]+button.width/2;y=xy[1]+button.height/2
            }
            val request=JSONObject().put("ocr",true).put("region",region)
            // Real HTTP clients may keep spare connections open before writing their first byte.
            val idleConnections=ArrayList<Socket>()
            val preconnectMs:Long
            try {
                repeat(24) {idleConnections.add(Socket("127.0.0.1",bridge.localPort))}
                val started=SystemClock.elapsedRealtime()
                val responsive=command("screenObserve",request.toString())
                preconnectMs=SystemClock.elapsedRealtime()-started
                assertTrue(responsive.toString(),responsive.getBoolean("ok"))
                assertTrue("空闲预连接阻塞了观察",preconnectMs<4000)
            } finally {idleConnections.forEach{runCatching{it.close()}}}
            val mirror=DeviceBridgeServer::class.java.getDeclaredField("mirrorExecutor").apply{isAccessible=true}.get(bridge) as java.util.concurrent.ThreadPoolExecutor
            val mirrorEntered=java.util.concurrent.CountDownLatch(1)
            val mirrorRelease=java.util.concurrent.CountDownLatch(1)
            var mirrorControlMs=0L
            try {
                mirror.execute {mirrorEntered.countDown();mirrorRelease.await(15,java.util.concurrent.TimeUnit.SECONDS)}
                assertTrue("后台通道未就绪",mirrorEntered.await(10,java.util.concurrent.TimeUnit.SECONDS))
                while(mirror.queue.remainingCapacity()>0) runCatching {mirror.execute {}}
                val busy=command("workerRecord","{}")
                assertEquals("DEVICE_BRIDGE_BUSY",busy.getString("errorCode"))
                val started=SystemClock.elapsedRealtime()
                val responsive=command("screenObserve",request.toString())
                mirrorControlMs=SystemClock.elapsedRealtime()-started
                assertTrue(responsive.toString(),responsive.getBoolean("ok"))
                assertTrue("后台回写阻塞了前台观察",mirrorControlMs<5000)
            } finally {mirrorRelease.countDown()}
            val engine=DeviceBridgeServer::class.java.getDeclaredField("shizuku").apply{isAccessible=true}.get(bridge) as io.deepseekharness.mobile.shizuku.ShizukuRuntime
            val mismatch=runCatching {DeviceControlState.execute(app,false,"隔离尺寸校验") {
                engine.mainScreenAction(JSONObject().put("action","tap").put("width",1).put("height",1).put("x",0).put("y",0))
                io.deepseekharness.mobile.shizuku.DeviceCommandResult(true,0,"{}",false,null)
            }}.exceptionOrNull()
            assertEquals("SCREEN_GEOMETRY_CHANGED",(mismatch as? io.deepseekharness.mobile.runtime.RuntimeFailure)?.code)
            assertEquals(0,count.get())
            val samples=JSONArray()
            repeat(if(InstrumentationRegistry.getArguments().getString("skipBaseline")=="true") 0 else 5) {
                val sample=JSONObject()
                var started=SystemClock.elapsedRealtime()
                val screenshot=command("screenshot")
                assertTrue(screenshot.optString("errorCode"),screenshot.getBoolean("ok"))
                sample.put("pngCaptureMs",SystemClock.elapsedRealtime()-started)
                started=SystemClock.elapsedRealtime()
                val visual=command("screenCapture")
                assertTrue(visual.toString(),visual.getBoolean("ok"))
                val envelope=JSONObject(visual.getString("text"))
                assertEquals("image/jpeg",envelope.getString("mediaType"))
                val visualBytes=android.util.Base64.decode(envelope.getString("imageBase64"),android.util.Base64.DEFAULT)
                val thumbnail=android.graphics.BitmapFactory.decodeByteArray(visualBytes,0,visualBytes.size)
                assertTrue(maxOf(thumbnail.width,thumbnail.height)<=1600)
                thumbnail.recycle()
                sample.put("fastVisualMs",SystemClock.elapsedRealtime()-started).put("fastVisualBytes",visualBytes.size)
                    .put("legacyVisualBytes",android.util.Base64.decode(screenshot.getString("text"),android.util.Base64.DEFAULT).size)
                if(it==0) java.io.File(app.externalCacheDir,"p74-screen-fast.jpg").writeBytes(visualBytes)
                started=SystemClock.elapsedRealtime()
                val recognized=ScreenObservation.text(screenshot.getString("text"),request)
                assertTrue(recognized.getString("ocrText").contains("7319"))
                sample.put("ocrOnlyMs",SystemClock.elapsedRealtime()-started)
                started=SystemClock.elapsedRealtime()
                val observed=command("screenObserve",request.toString())
                assertTrue(observed.optString("errorCode"),observed.getBoolean("ok"))
                val observation=JSONObject(observed.getString("text")).getJSONObject("image")
                assertTrue(observation.getString("ocrText").contains("7319"))
                sample.put("captureSource",observation.optString("captureSource"))
                    .put("captureMs",observation.optLong("captureMs",-1)).put("ocrMs",observation.optLong("ocrMs",-1))
                if(InstrumentationRegistry.getArguments().getString("expectRaw")=="true")
                    assertEquals("shizuku_raw_pipe",observation.getString("captureSource"))
                sample.put("observeMs",SystemClock.elapsedRealtime()-started)
                started=SystemClock.elapsedRealtime()
                val result=command("shell","input tap $x $y")
                assertTrue(result.optString("errorCode"),result.getBoolean("ok"))
                await("真实点击未到达"){count.get()==it+1}
                sample.put("shellTapMs",SystemClock.elapsedRealtime()-started)
                samples.put(sample)
            }
            val full=command("screenObserve","{\"ocr\":true}")
            assertTrue(full.getBoolean("ok"))
            val fullImage=JSONObject(full.getString("text")).getJSONObject("image")
            if(InstrumentationRegistry.getArguments().getString("expectPortrait")=="true")
                assertTrue("没有进入真实竖屏像素布局",fullImage.getInt("width")<fullImage.getInt("height"))
            val lines=fullImage.getJSONArray("lines")
            val target=(0 until lines.length()).map(lines::getJSONObject).first{it.getString("text").contains("Safe",true)}.getJSONArray("bounds")
            main {
                val xy=IntArray(2);button.getLocationOnScreen(xy)
                val tx=(target.getInt(0)+target.getInt(2))/2;val ty=(target.getInt(1)+target.getInt(3))/2
                assertTrue("全屏采样坐标映射错误",tx in xy[0] until xy[0]+button.width && ty in xy[1] until xy[1]+button.height)
            }
            val beforeFast=count.get()
            val fastSamples=JSONArray()
            var next=JSONObject(full.getString("text"))
            repeat(5) {round->
                val id=next.getString("observationId")
                val actionRequest=JSONObject().put("observationId",id).put("action","tap").put("x",x).put("y",y)
                    .put("ocr",true).put("region",region)
                val started=SystemClock.elapsedRealtime()
                val result=command("screenAct",actionRequest.toString())
                assertTrue(result.toString(),result.getBoolean("ok"))
                val value=JSONObject(result.getString("text"))
                val receipt=value.getJSONObject("action")
                assertEquals("shizuku_in_process",receipt.getString("channel"))
                next=value.getJSONObject("observation")
                await("常驻通道点击未到达"){count.get()==beforeFast+round+1}
                val ocrText=next.getJSONObject("image").getString("ocrText")
                inst.sendStatus(0,android.os.Bundle().apply{putString("postActionOcr",ocrText);putString("receipt",receipt.toString())})
                assertTrue("新观察没有包含动作结果: $ocrText",ocrText.replace(Regex("\\s+"),"").contains("Safetap"+(beforeFast+round+1)))
                fastSamples.put(JSONObject().put("injectionMs",receipt.getLong("injectionMs"))
                    .put("settleMs",value.getLong("settleMs")).put("actionAndObserveMs",SystemClock.elapsedRealtime()-started))
                if(round==4) {
                    val replay=command("screenAct",actionRequest.toString())
                    assertTrue(replay.toString(),replay.getBoolean("ok"))
                    val prior=JSONObject(replay.getString("text"))
                    assertEquals("recorded",prior.getString("status"));assertTrue(prior.getBoolean("doNotReplay"))
                    assertEquals(beforeFast+5,count.get())
                }
            }
            // Pause invalidates an observation, including after the user resumes.
            val beforePause=JSONObject(command("screenObserve",request.toString()).getString("text"))
            DeviceControlState.pause()
            val paused=command("screenAct",JSONObject().put("observationId",beforePause.getString("observationId"))
                .put("action","tap").put("x",x).put("y",y).toString())
            assertEquals("DEVICE_CONTROL_PAUSED",paused.getString("errorCode"))
            DeviceControlState.resume()
            val stale=command("screenAct",JSONObject().put("observationId",beforePause.getString("observationId"))
                .put("action","tap").put("x",x).put("y",y).toString())
            assertEquals("SCREEN_OBSERVATION_STALE",stale.getString("errorCode"))
            assertEquals(beforeFast+5,count.get())
            // Mid-gesture pause/stop must send CANCEL, never a delayed UP that clicks.
            for(stop in listOf(false,true)) {
                val fresh=JSONObject(command("screenObserve",request.toString()).getString("text"))
                val beforeDown=downs.get();val beforeUp=ups.get();val beforeCancel=cancels.get()
                val result=java.util.concurrent.atomic.AtomicReference<JSONObject>()
                val running=Thread {
                    result.set(command("screenAct",JSONObject().put("observationId",fresh.getString("observationId"))
                        .put("action","swipe").put("x",x).put("y",y).put("endX",x+20).put("endY",y)
                        .put("durationMs",1000).put("region",region).toString()))
                }.apply{start()}
                await("手势没有真实按下"){downs.get()>beforeDown}
                if(stop) DeviceControlState.stop() else DeviceControlState.pause()
                running.join(5000)
                assertFalse("中断未释放手势请求",running.isAlive)
                assertFalse(result.get().getBoolean("ok"))
                await("按下状态未用 CANCEL 清理"){cancels.get()>beforeCancel}
                Thread.sleep(1100)
                assertEquals("中断后发生抬起",beforeUp,ups.get())
                assertEquals("中断后发生延迟点击",beforeFast+5,count.get())
                DeviceControlState.resume()
                val recovery=command("screenRecover",JSONObject().put("operationId",fresh.getString("observationId")).toString())
                assertTrue(recovery.toString(),recovery.getBoolean("ok"))
            }
            // The pet and its control menu cannot be tapped by this coordinate channel.
            val protectedObservation=JSONObject(command("screenObserve","{\"ocr\":true}").getString("text"))
            val service=checkNotNull(PetControlDeviceUi.service())
            val panel=PetControlDeviceUi.field(service,"ballView") as View
            var controlX=0;var controlY=0
            main {val pos=IntArray(2);panel.getLocationOnScreen(pos);controlX=pos[0]+panel.width/2;controlY=pos[1]+panel.height/2}
            val protectedResult=command("screenAct",JSONObject().put("observationId",protectedObservation.getString("observationId"))
                .put("action","tap").put("x",controlX).put("y",controlY).toString())
            assertEquals("SCREEN_CONTROL_PROTECTED",protectedResult.getString("errorCode"))
            if(InstrumentationRegistry.getArguments().getString("realAgent")=="true") {
                val host=checkNotNull(RuntimeHost.controllerOrNull())
                val client=HarnessBubbleClient(host.openHarnessAccess())
                val session=client.call("session/create",JSONObject()).getString("sessionId")
                client.command(session,"/permission read-only")
                val task=java.util.UUID.randomUUID().toString()
                val beforeAgent=count.get();val started=SystemClock.elapsedRealtime()
                val reply=PetReplyOperation(150000).run {
                    client.followReplyWithImages(session,task) {
                        client.call("session/prompt",JSONObject().put("sessionId",session).put("requestId",task).put("mode","queue")
                            .put("content",JSONArray().put(JSONObject().put("type","text").put("text",
                                "这是用户授权的本机屏控响应实测。请立即用 mobile_screen_observe 观察当前屏幕，找到白色验收浮层 Whale 7319 中 Safe tap 按钮；用 mobile_screen_act 点击两次，每次都使用最新 observationId 和返回的 OCR 坐标；核对第二次返回的文字数字。只操作这个验收按钮，不碰其他内容，不调用 Shell、截图或其他工具。完成后回复屏控完成及最终数字。"))))
                    }
                }
                assertEquals("真实模型未完成两次实际点击: "+reply.text,beforeAgent+2,count.get())
                assertTrue("模型未使用快速观察",reply.tools.contains("mobile_screen_observe"))
                assertTrue("模型未使用合并动作",reply.tools.contains("mobile_screen_act"))
                inst.sendStatus(0,android.os.Bundle().apply {
                    putBoolean("realAgentFastTools",true);putInt("realAgentTaps",count.get()-beforeAgent)
                    putLong("realAgentElapsedMs",SystemClock.elapsedRealtime()-started)
                    putString("realAgentTools",reply.tools.joinToString(","))
                })
            }
            inst.sendStatus(0,android.os.Bundle().apply{
                putBoolean("inFlightPauseAndStopCancel",true)
                putLong("saturatedMirrorControlMs",mirrorControlMs)
                putLong("idlePreconnectControlMs",preconnectMs)
                putBoolean("structuredMirrorBusy",true)
                putBoolean("geometryMismatchRefused",true)
                putBoolean("controlStripProtected",true)
                putString("fastControlSamples",fastSamples.toString())
                putBoolean("replayAndPauseRejected",true)

                putInt("screenWidth",fullImage.getInt("width"));putInt("screenHeight",fullImage.getInt("height"))
                putString("installedVersion",app.packageManager.getPackageInfo(app.packageName,0).versionName)
                putString("screenLatencySamples",samples.toString());putInt("actualTaps",count.get())
            })
        } finally {main{fixture?.let{runCatching{wm.removeView(it)}}};DeviceControlState.resume();wake.close()}
    }
}
