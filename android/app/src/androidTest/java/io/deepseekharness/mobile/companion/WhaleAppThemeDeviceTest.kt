package io.deepseekharness.mobile.companion

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.MainActivity
import io.deepseekharness.mobile.WhaleDesign
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WhaleAppThemeDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val app=inst.targetContext
    private fun waitFor(label:String,check:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+20000
        while(!check()) { check(SystemClock.elapsedRealtime()<end){label}; Thread.sleep(100) }
    }
    private fun js(page:WebView,code:String):String {
        val done=CountDownLatch(1);var value=""
        inst.runOnMainSync { page.evaluateJavascript(code){value=it;done.countDown()} }
        check(done.await(10,TimeUnit.SECONDS));return value
    }
    private fun capture(view:View,name:String) {
      val draw = {
        check(view.width>0 && view.height>0)
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image));File(app.getExternalFilesDir(null),"p61-$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
      }
      if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) draw() else inst.runOnMainSync(draw)
    }

    @Test fun fourFamiliesAlsoReachTheRealHarnessSurface() {
        val nativeMode=io.deepseekharness.mobile.AppThemePreference.current(app)
        val style=app.getSharedPreferences("whale-interface",0)
        val originalStyle=style.getString("style",null)
        val prefs=app.getSharedPreferences("harness_theme",0)
        val original=prefs.all.toMap()
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        val wake=WorkerRuntimeWake(app)
        lateinit var activity:MainActivity
        scenario.onActivity {activity=it}
        var savedShellAppearance:String?=null
        try {
            wake.wake()
            val shell=activity.bridge.webView
            val shellAppearance=js(shell,"JSON.stringify({mode:localStorage.getItem('dsh-mobile-theme-v1'),style:localStorage.getItem('whale-interface-style')})")
            savedShellAppearance=shellAppearance
            waitFor("shell ready"){js(shell,"!!document.querySelector('[aria-label=\"我的\"]')")=="true"}
            js(shell,"window.dispatchEvent(new CustomEvent('dsh-native-tab',{detail:'harness'}));true")
            var page:WebView?=null
            val attached=io.deepseekharness.mobile.HarnessThemeBridge::class.java.getDeclaredField("attached").apply{isAccessible=true}
            waitFor("real Harness page attached") {
                inst.runOnMainSync {page=(attached.get(io.deepseekharness.mobile.HarnessThemeBridge) as? java.lang.ref.WeakReference<*>)?.get() as? WebView}
                page!=null
            }
            waitFor("real Harness document loaded"){js(requireNotNull(page),"document.documentElement.dataset.dshThemeSource==='shell'")=="true"}
            for(family in listOf("cute","simple")) for(mode in listOf("light","dark")) {
                fun nav(tab:String) {js(shell,"window.dispatchEvent(new CustomEvent('dsh-native-tab',{detail:"+JSONObject.quote(tab)+"}));true")}
                fun click(label:String) {
                    val target="[...document.querySelectorAll('button')].find(b=>b.textContent.trim()==="+JSONObject.quote(label)+")"
                    waitFor("theme control "+label){js(shell,"!!("+target+")")=="true"}
                    js(shell,"(()=>{const b="+target+";if(b.classList.contains(\'account-group-toggle\')&&b.getAttribute(\'aria-expanded\')===\'true\')return;b.click()})();true")
                }
                nav("settings");click("外观与语言")
                js(shell,"[...document.querySelectorAll('.management-row')].find(b=>b.textContent.includes('字号配色与背景')).click();true")
                click(if(family=="cute") "月汐·陪伴" else "深海·专注")
                js(shell,"document.querySelector('input[name=\"app-theme\"][value=\"$mode\"]').click();true")
                waitFor("App theme saved"){WhaleDesign.of(app).let{it.sea==(family=="simple")&&it.dark==(mode=="dark")}}
                val navSignature="JSON.stringify([...document.querySelectorAll('.bottom-navigation-item,.sidebar-nav .sidebar-item')].filter(e=>e.getBoundingClientRect().width>0).map(e=>{const r=e.getBoundingClientRect();return[e.getAttribute('aria-label'),r.x,r.y,r.width,r.height,getComputedStyle(e).fontSize]}))"
                val before=js(shell,navSignature)
                nav("harness")
                waitFor("conversation painted") {var shown=false;inst.runOnMainSync{shown=requireNotNull(page).isShown && requireNotNull(page).alpha>.99f};shown}
                val design=WhaleDesign.of(app)
                waitFor("Harness palette $family/$mode") {
                    js(requireNotNull(page),"getComputedStyle(document.body).getPropertyValue('--dsw-alias-label-primary').trim()") == JSONObject.quote(design.hex(design.ink))
                }
                assertEquals("navigation geometry must not change",before,js(shell,navSignature))
                assertEquals(js(shell,"getComputedStyle(document.documentElement).getPropertyValue('--bg').trim()"),
                    js(requireNotNull(page),"document.documentElement.style.getPropertyValue('--dsh-theme-background').trim()"))
                assertEquals(JSONObject.quote(mode),js(requireNotNull(page),"document.documentElement.dataset.dshTheme"))
                assertTrue(js(requireNotNull(page),"document.getElementById('whale-family-design').textContent").contains("border-radius:"+design.radius+"px"))
                assertEquals("composer surface must follow family",JSONObject.quote(design.hex(design.onAccent)),
                    js(requireNotNull(page),"getComputedStyle(document.body).getPropertyValue('--dsw-alias-label-primary-foreground').trim()"))
                assertEquals("conversation borders must follow family",JSONObject.quote(design.hex(design.line)),
                    js(requireNotNull(page),"getComputedStyle(document.body).getPropertyValue('--dsw-alias-border-l2').trim()"))
                assertTrue("conversation motion stylesheet missing",js(requireNotNull(page),"document.getElementById('whale-family-design').textContent").contains("whale-surface-in"))
                assertNull("separate native navigation must not exist",activity.findViewById<View>(io.deepseekharness.mobile.R.id.harness_tab_active))
                capture(activity.window.decorView,"harness-$family-$mode")
                inst.sendStatus(0,android.os.Bundle().apply{putString("harnessFamily","$family/$mode")})
            }
        } finally {
            savedShellAppearance?.let { saved ->
                if(!activity.isDestroyed) js(activity.bridge.webView,"(()=>{const v=JSON.parse("+saved+");for(const[k,x]of [['dsh-mobile-theme-v1',v.mode],['whale-interface-style',v.style]]){if(x===null)localStorage.removeItem(k);else localStorage.setItem(k,x)}})()")
            }
            io.deepseekharness.mobile.AppThemePreference.save(app,nativeMode)
            style.edit().apply{if(originalStyle==null)remove("style")else putString("style",originalStyle)}.commit()
            prefs.edit().apply {
                clear()
                original.forEach{(key,value)->when(value){is String->putString(key,value);is Boolean->putBoolean(key,value);is Int->putInt(key,value);is Long->putLong(key,value);is Float->putFloat(key,value)}}
            }.commit()
            inst.runOnMainSync{io.deepseekharness.mobile.HarnessThemeBridge.refresh()}
            scenario.close();wake.close()
        }
    }

    @Test fun fourFamiliesCoverManagementAndEveryNativePreferencesPage() {
        val nativeMode=io.deepseekharness.mobile.AppThemePreference.current(app)
        val nativeStyle=app.getSharedPreferences("whale-interface",0).getString("style",null)
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var activity:MainActivity
        scenario.onActivity{activity=it}
        val page=activity.bridge.webView
        waitFor("shell ready"){js(page,"!!document.querySelector('[aria-label=\"我的\"]')")=="true"}
        val saved=js(page,"JSON.stringify({mode:localStorage.getItem('dsh-mobile-theme-v1'),style:localStorage.getItem('whale-interface-style')})")
        fun nav(tab:String) {
            js(page,"window.dispatchEvent(new CustomEvent('dsh-native-tab',{detail:"+JSONObject.quote(tab)+"}));true")
            Thread.sleep(220)
        }
        fun button(name:String) {
            val expression="[...document.querySelectorAll('button')].find(b=>b.textContent.trim()==="+JSONObject.quote(name)+")"
            waitFor("button $name"){js(page,"!!("+expression+")")=="true"}
            js(page,"(()=>{const b="+expression+";if(b.classList.contains(\'account-group-toggle\')&&b.getAttribute(\'aria-expanded\')===\'true\')return;b.click()})();true")
        }
        try {
            for(style in listOf("cute","simple")) for(mode in listOf("light","dark")) {
                nav("settings");button("外观与语言")
                js(page,"[...document.querySelectorAll('.management-row')].find(b=>b.textContent.includes('字号配色与背景')).click();true")
                waitFor("theme choices"){js(page,"!!document.querySelector('[aria-label=\"界面风格\"]')")=="true"}
                button(if(style=="cute")"月汐·陪伴" else "深海·专注")
                js(page,"document.querySelector('input[name=\"app-theme\"][value=\"$mode\"]').click();true")
                waitFor("web theme"){js(page,"document.documentElement.dataset.style+'|'+document.documentElement.dataset.theme")==JSONObject.quote("$style|$mode")}
                waitFor("native theme saved"){WhaleDesign.of(app).let{it.sea==(style=="simple")&&it.dark==(mode=="dark")}}
                capture(activity.window.decorView,"app-$style-$mode-appearance")
                for(tab in listOf("assistant-memory","assistant-tasks","tools","settings")) {
                    nav(tab)
                    val design=WhaleDesign.of(app)
                    val expected=design.hex(design.ink)
                    val actual=js(page,"getComputedStyle(document.documentElement).getPropertyValue('--ink').trim()")
                    assertEquals("$tab family",JSONObject.quote(expected),actual)
                    assertTrue("App motion missing: $tab",js(page,"getComputedStyle(document.querySelector('.app-main > *')).animationName").contains("whale-page-enter") || !io.deepseekharness.mobile.WhaleMotion.enabled())
                    assertEquals("page overflow: $tab", "true",js(page,"document.documentElement.scrollWidth<=innerWidth+2"))
                    capture(activity.window.decorView,"app-$style-$mode-$tab")
                }
                for(group in listOf("模型连接","桌宠与主动跟进","通知与免打扰","外观与语言","设备控制","运行与数据")) {
                    nav("settings")
                    val expression="[...document.querySelectorAll('.account-group-toggle')].find(b=>b.textContent.includes("+JSONObject.quote(group)+"))"
                    js(page,"(()=>{const b="+expression+";if(b.getAttribute('aria-expanded')!=='true')b.click()})();true")
                    assertEquals("expanded $group","true",js(page,"("+expression+").getAttribute('aria-expanded')==='true'"))
                    capture(activity.window.decorView,"app-$style-$mode-group-"+listOf("模型连接","桌宠与主动跟进","通知与免打扰","外观与语言","设备控制","运行与数据").indexOf(group))
                }
                inst.sendStatus(0,android.os.Bundle().apply {putString("webFamily","$style/$mode")})
            }
            if(!activity.isDestroyed) js(page,"(()=>{const v=JSON.parse("+saved+");for(const [k,x]of [['dsh-mobile-theme-v1',v.mode],['whale-interface-style',v.style]]){if(x===null)localStorage.removeItem(k);else localStorage.setItem(k,x)}})()")
            scenario.close()
            for(style in listOf("cute","simple")) for(mode in listOf("light","dark")) {
                app.getSharedPreferences("whale-interface",0).edit().putString("style",style).commit()
                io.deepseekharness.mobile.AppThemePreference.save(app,mode)
                for(pref in CompanionPreferencePage.entries) {
                    ActivityScenario.launch<CompanionActivity>(Intent(app,CompanionActivity::class.java).putExtra(CompanionActivity.OPEN_PAGE,pref.name)).use { native ->
                        inst.waitForIdleSync();Thread.sleep(320)
                        native.onActivity { screen ->
                            val design=WhaleDesign.of(app)
                            assertEquals("native family "+pref.name,design.ink,PetUi(screen).ink)
                            val accent=android.util.TypedValue()
                            assertTrue(screen.theme.resolveAttribute(android.R.attr.colorAccent,accent,true))
                            assertEquals("native controls "+pref.name,design.accent,accent.data)
                            val motion=android.util.TypedValue()
                            assertTrue(screen.theme.resolveAttribute(android.R.attr.windowAnimationStyle,motion,true))
                            assertEquals("native motion "+pref.name,io.deepseekharness.mobile.R.style.WhaleWindowMotion,motion.resourceId)
                            capture(screen.window.decorView,"native-$style-$mode-"+pref.name.lowercase())
                        }
                    }
                }
                inst.sendStatus(0,android.os.Bundle().apply {putString("nativeFamily","$style/$mode");putInt("nativePages",CompanionPreferencePage.entries.size)})
            }
        } finally {
            // Restore appearance only. Credentials, permissions, user materials and task data are untouched.
            if(!activity.isDestroyed) js(page,"(()=>{const v=JSON.parse("+saved+");for(const [k,x]of [['dsh-mobile-theme-v1',v.mode],['whale-interface-style',v.style]]){if(x===null)localStorage.removeItem(k);else localStorage.setItem(k,x)}location.reload()})()")
            io.deepseekharness.mobile.AppThemePreference.save(app,nativeMode)
            app.getSharedPreferences("whale-interface",0).edit().apply { if(nativeStyle==null) remove("style") else putString("style",nativeStyle) }.commit()
            scenario.close()
        }
    }
}
