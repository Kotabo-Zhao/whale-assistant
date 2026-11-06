package io.deepseekharness.mobile.companion

import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.DeviceBridgeServer
import io.deepseekharness.mobile.DeviceControlState
import io.deepseekharness.mobile.runtime.RuntimeHost
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class WhaleScreenControlDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val app=inst.targetContext
    private fun field(value:Any,key:String)=value.javaClass.getDeclaredField(key).apply{isAccessible=true}.get(value)
    private fun main(block:()->Unit)=inst.runOnMainSync(block)
    private fun await(label:String,timeout:Long=20000,check:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+timeout
        while(!check()){check(SystemClock.elapsedRealtime()<until){label};Thread.sleep(70)}
    }
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))}else emptyList()

    @Test fun actualObservationTapPauseAndStopUseTheVisibleIndicator() {
        val wake=WorkerRuntimeWake(app)
        val wm=app.getSystemService(WindowManager::class.java)
        var fixture:LinearLayout?=null
        val count=java.util.concurrent.atomic.AtomicInteger()
        try {
            val ui=inst.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            app.startActivity(android.content.Intent(app,io.deepseekharness.mobile.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            await("own app foreground"){ui.rootInActiveWindow?.packageName==app.packageName}
            wake.wake()
            await("device bridge ready"){RuntimeHost.deviceBridgeOrNull() is DeviceBridgeServer}
            val bridge=RuntimeHost.deviceBridgeOrNull() as DeviceBridgeServer
            val token=field(bridge,"token") as String
            fun command(name:String,param:String=""):JSONObject {
                val bytes=JSONObject().put("command",name).put("param",param).toString().toByteArray()
                return Socket("127.0.0.1",bridge.localPort).use { socket ->
                    socket.soTimeout=70000
                    socket.getOutputStream().apply {
                        write(("POST /device-command HTTP/1.0\r\nAuthorization: Bearer "+token+"\r\nContent-Length: "+bytes.size+"\r\n\r\n").toByteArray());write(bytes);flush()
                    }
                    JSONObject(socket.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n"))
                }
            }
            if(InstrumentationRegistry.getArguments().getString("requireNativeTree")=="true") {
                inst.sendStatus(0,android.os.Bundle().apply { putBoolean("awaitingNativeRebind",true) })
                await("authorized native service rebound",60000) { io.deepseekharness.mobile.accessibility.DeepSeekAccessibilityService.current()!=null && ui.rootInActiveWindow?.packageName==app.packageName }
            }
            lateinit var button:TextView
            main {
                fixture=LinearLayout(app).apply {orientation=LinearLayout.VERTICAL;setPadding(24,16,24,16);setBackgroundColor(Color.WHITE)}
                fixture!!.addView(TextView(app).apply{text="Whale 4729";textSize=24f;setTextColor(Color.BLACK)})
                button=TextView(app).apply {text="Safe tap";textSize=22f;setTextColor(Color.BLUE);minHeight=140;gravity=Gravity.CENTER
                    setOnClickListener{count.incrementAndGet();text="Tapped "+count.get()}}
                fixture!!.addView(button)
                wm.addView(fixture,WindowManager.LayoutParams(minOf(1000,app.resources.displayMetrics.widthPixels-200),320,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,android.graphics.PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.LEFT;x=100;y=300})
            }
            Thread.sleep(400)
            val treeStart=SystemClock.elapsedRealtime()
            val tree=command("screenObserve","{\"ocr\":false}")
            assertTrue("observation response",tree.getBoolean("ok"))
            val native=JSONObject(tree.getString("text")).getBoolean("treeAvailable")
            if(InstrumentationRegistry.getArguments().getString("requireNativeTree")=="true") assertTrue("authorized native tree required: "+JSONObject(tree.getString("text")).optString("treeReason"),native)
            val treeMs=SystemClock.elapsedRealtime()-treeStart
            val timings=mutableListOf<Long>()
            var observedButton:org.json.JSONArray?=null
            repeat(2) {
                val start=SystemClock.elapsedRealtime()
                val response=command("screenObserve",JSONObject().put("ocr",true).put("region",org.json.JSONArray(listOf(100,300,minOf(1000,app.resources.displayMetrics.widthPixels-200),320))).toString())
                assertTrue("OCR through production bridge: "+response.optString("errorCode"),response.getBoolean("ok"))
                val image=JSONObject(response.getString("text")).getJSONObject("image")
                assertTrue("actual fixture text must be recognized",image.getString("ocrText").contains("4729"))
                val lines=image.getJSONArray("lines")
                observedButton=(0 until lines.length()).map(lines::getJSONObject).firstOrNull{it.getString("text").contains("Safe",ignoreCase=true)}?.getJSONArray("bounds")
                assertNotNull("OCR must locate the fixture button",observedButton)
                assertEquals(64,image.getString("frameId").length)
                assertTrue(image.getLong("capturedAtMs")<=image.getLong("observedAtMs"))
                timings.add(SystemClock.elapsedRealtime()-start)
            }
            var x=0;var y=0
            val target=requireNotNull(observedButton)
            x=(target.getInt(0)+target.getInt(2))/2;y=(target.getInt(1)+target.getInt(3))/2
            main {val pos=IntArray(2);button.getLocationOnScreen(pos);assertTrue(x in pos[0]..pos[0]+button.width && y in pos[1]..pos[1]+button.height)}
            assertTrue(command("shell","input tap $x $y").getBoolean("ok"))
            await("real shell tap"){count.get()==1}
            fun indicator():View?=field(DeviceControlState,"indicator")?.let{field(it,"root") as? View}
            fun click(label:String) {
                await("visible indicator $label") {var ready=false;main {ready=indicator()?.let{root->root.isShown&&views(root).filterIsInstance<TextView>().any{it.text.toString()==label}}==true};ready}
                main {views(requireNotNull(indicator())).filterIsInstance<TextView>().single{it.text.toString()==label}.performClick()}
            }
            click("暂停")
            val paused=command("shell","input tap $x $y")
            assertEquals("DEVICE_CONTROL_PAUSED",paused.optString("errorCode"));assertEquals(1,count.get())
            click("继续")
            assertTrue(command("shell","input tap $x $y").getBoolean("ok"))
            await("tap after resume"){count.get()==2}
            val result=AtomicReference<JSONObject>()
            val longStep=Thread {result.set(command("shell","sleep 8; input tap $x $y"))}.apply{start()}
            await("actual operating state"){DeviceControlState.current.phase=="operating"}
            click("停止")
            longStep.join(5000);assertFalse("stop must release in-flight command",longStep.isAlive)
            assertFalse(result.get().getBoolean("ok"))
            Thread.sleep(8500);assertEquals("stopped command must not tap later",2,count.get())
            assertEquals("stopped",DeviceControlState.current.phase)
            click("继续")
            inst.sendStatus(0,android.os.Bundle().apply {putBoolean("nativeTree",native);putLong("treeMs",treeMs);putLong("ocrColdMs",timings[0]);putLong("ocrWarmMs",timings[1]);putInt("verifiedTaps",count.get())})
        } finally {main {fixture?.let{runCatching{wm.removeView(it)}}};DeviceControlState.resume();wake.close()}
    }
}
