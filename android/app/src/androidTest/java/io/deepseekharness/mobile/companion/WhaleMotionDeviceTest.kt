package io.deepseekharness.mobile.companion

import android.provider.Settings
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.MainActivity
import io.deepseekharness.mobile.WhaleMotion
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WhaleMotionDeviceTest {
    @Test fun nativeMotionPreservesGeometryAndReducedMotionDisablesBothSurfaces() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=inst.targetContext
        val automation=inst.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val keys=listOf("animator_duration_scale","transition_animation_scale","window_animation_scale")
        val original=keys.associateWith{Settings.Global.getString(app.contentResolver,it)}
        fun scales(value:String?) {
            for(key in keys) {
                val setting=value ?: original[key]
                val command=if(setting==null) "settings delete global $key" else "settings put global $key $setting"
                automation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
            }
        }
        fun waitFor(message:String,condition:()->Boolean) {
            val until=System.currentTimeMillis()+15000
            while(!condition()) {check(System.currentTimeMillis()<until){message};Thread.sleep(40)}
        }
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var host:MainActivity
        lateinit var probe:TextView
        fun js(code:String):String {
            val done=CountDownLatch(1);var result=""
            inst.runOnMainSync{host.bridge.webView.evaluateJavascript(code){result=it;done.countDown()}}
            check(done.await(10,TimeUnit.SECONDS));return result
        }
        scenario.onActivity {host=it}
        try {
            scales("1");waitFor("系统动画未启用"){WhaleMotion.enabled()}
            waitFor("App 页面未加载"){js("!!document.querySelector('.app-main')")=="true"}
            js("window.dispatchEvent(new CustomEvent('dsh-native-tab',{detail:'settings'}));true")
            waitFor("设置页未显示"){js("!!document.querySelector('.account-sections')")=="true"}
            var layoutSignature=""
            var stableSince=0L
            waitFor("设置页异步内容尚未稳定") {
                val next=js("(()=>{const b=document.querySelector('.app-main > *').getBoundingClientRect();return JSON.stringify([b.width,b.height,document.fonts.status])})()")
                val now=android.os.SystemClock.elapsedRealtime()
                if(next!=layoutSignature){layoutSignature=next;stableSince=now}
                now-stableSince>=1000
            }
            js("""(()=>{
                const element=document.querySelector('.app-main > *');
                element.style.animation='none';void element.offsetWidth;element.style.animation='';
                window.whaleTestMotionFrames=[];window.whaleTestMotionDone=false;
                const start=performance.now();
                const sample=()=>{
                    const box=element.getBoundingClientRect(),parent=element.parentElement.getBoundingClientRect();
                    window.whaleTestMotionFrames.push({alpha:Number(getComputedStyle(element).opacity),width:box.width,height:box.height,
                        parent:[parent.x,parent.y,parent.width,parent.height]});
                    if(performance.now()-start<400)requestAnimationFrame(sample);else window.whaleTestMotionDone=true;
                };
                requestAnimationFrame(sample);return true;
            })()""")
            waitFor("网页动效没有绘制完毕"){js("window.whaleTestMotionDone")=="true"}
            val webFrames=org.json.JSONArray(js("JSON.stringify(window.whaleTestMotionFrames)").let {
                org.json.JSONArray("["+it+"]").getString(0)
            })
            assertTrue("App 缺少实际中间帧",(0 until webFrames.length()).any{webFrames.getJSONObject(it).getDouble("alpha") in .001.. .999})
            val firstWeb=webFrames.getJSONObject(0)
            for(index in 1 until webFrames.length()) {
                val sample=webFrames.getJSONObject(index)
                assertEquals(firstWeb.getDouble("width"),sample.getDouble("width"),.5)
                assertEquals(firstWeb.getDouble("height"),sample.getDouble("height"),.5)
                assertEquals("导航定位容器发生移动",firstWeb.getJSONArray("parent").toString(),sample.getJSONArray("parent").toString())
            }
            inst.runOnMainSync {
                probe=TextView(host).apply {text="动效验收";setBackgroundColor(0xff317c89.toInt())}
                host.addContentView(probe,ViewGroup.LayoutParams(320,140))
            }
            inst.waitForIdleSync()
            val width=probe.width;val height=probe.height
            val frames=java.util.Collections.synchronizedList(mutableListOf<Float>())
            val observer=android.view.ViewTreeObserver.OnPreDrawListener {
                frames.add(probe.alpha)
                assertEquals("动画改变宽度",width,probe.width)
                assertEquals("动画改变高度",height,probe.height)
                true
            }
            inst.runOnMainSync {
                probe.viewTreeObserver.addOnPreDrawListener(observer)
                WhaleMotion.enter(probe)
                assertTrue("进入动画缺少起始帧",probe.alpha<1f)
            }
            waitFor("进入动画没有结束") {
                var ended=false;inst.runOnMainSync{ended=probe.alpha==1f};ended
            }
            inst.runOnMainSync{probe.viewTreeObserver.removeOnPreDrawListener(observer)}
            assertTrue("缺少实际绘制的过渡帧",synchronized(frames){frames.any{it>0f&&it<1f}})
            inst.runOnMainSync {
                assertEquals(1f,probe.alpha,.001f);assertEquals(0f,probe.translationY,.001f)
                assertEquals(width,probe.width);assertEquals(height,probe.height)
            }
            scales("0");waitFor("系统减少动态效果未生效"){!WhaleMotion.enabled()}
            scenario.moveToState(Lifecycle.State.STARTED);scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor("App 未同步减少动态效果"){js("document.documentElement.dataset.motionReduced")=="\"true\""}
            assertEquals("\"none\"",js("getComputedStyle(document.querySelector('.app-main > *')).animationName"))
            inst.runOnMainSync {
                WhaleMotion.enter(probe)
                assertEquals(1f,probe.alpha,0f);assertEquals(0f,probe.translationY,0f)
                val params=android.view.WindowManager.LayoutParams()
                WhaleMotion.overlay(params)
                assertEquals(0,params.windowAnimations)
            }
            inst.sendStatus(0,android.os.Bundle().apply {
                putBoolean("webIntermediateFrames",true);putInt("webFrames",webFrames.length())
                putBoolean("nativeIntermediateFrames",true);putBoolean("geometryPreserved",true)
                putBoolean("webReducedMotion",true);putBoolean("nativeReducedMotion",true)
            })
        } finally {
            scales(null)
            scenario.close()
        }
    }
}
