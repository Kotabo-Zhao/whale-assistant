package io.deepseekharness.mobile.companion

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WhaleShellUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private lateinit var host: MainActivity
    private val app get() = inst.targetContext
    private fun js(expression: String): String {
        val done = CountDownLatch(1); var answer = ""
        inst.runOnMainSync { host.bridge.webView.evaluateJavascript(expression) { answer = it; done.countDown() } }
        check(done.await(5, TimeUnit.SECONDS))
        return answer
    }
    private fun until(hint: String, test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 25000
        while (!test()) { check(SystemClock.elapsedRealtime() < end) { hint }; Thread.sleep(80) }
    }
    private fun native(name: String): View? {
        var out: View? = null
        val id = app.resources.getIdentifier(name, "id", app.packageName)
        if (id != 0) inst.runOnMainSync { out = host.findViewById<View>(id)?.takeIf { it.isShown && it.alpha>.99f } }
        return out
    }
    private fun bounds(view: View): Rect {
        val box = Rect()
        inst.runOnMainSync { val p = IntArray(2); view.getLocationOnScreen(p); box.set(p[0],p[1],p[0]+view.width,p[1]+view.height) }
        return box
    }
    private fun touch(x: Int, y: Int) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x.toFloat(),y.toFloat(),0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(inst.uiAutomation.injectInputEvent(event,true)) } finally { event.recycle() }
        }
    }
    private fun sharedNav(label:String)="Array.from(document.querySelectorAll('button[aria-label]')).find(e=>e.getAttribute('aria-label')==="+JSONObject.quote(label)+" && e.getBoundingClientRect().width)"
    private fun webRect(element:String):Rect {
        val values=JSONArray(js("(()=>{const r=("+element+").getBoundingClientRect();return [r.x,r.y,r.width,r.height,innerWidth]})()"))
        val hostBox=bounds(host.bridge.webView);val scale=hostBox.width()/values.getDouble(4)
        val left=hostBox.left+(values.getDouble(0)*scale).toInt();val top=hostBox.top+(values.getDouble(1)*scale).toInt()
        return Rect(left,top,left+(values.getDouble(2)*scale).toInt(),top+(values.getDouble(3)*scale).toInt())
    }
    private fun itemsVisible()=js("Array.from(document.querySelectorAll('.bottom-navigation-item,.sidebar-nav .sidebar-item')).filter(e=>e.getBoundingClientRect().width>0).length").toInt()
    private fun tap(view: View) = bounds(view).let { touch(it.centerX(),it.centerY()) }
    private fun dismissKeyboardIfVisible() {
        fun shown(): Boolean {
            var result = false
            inst.runOnMainSync { result = host.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true }
            return result
        }
        if (shown()) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")).use { it.readBytes() }
            until("键盘仍占据前台") { !shown() }
        }
    }
    private fun webTap(element: String) {
        js("($element).scrollIntoView({block:'nearest'})")
        try {
            until("触摸目标未就绪") {
                js("(()=>{const e=($element);if(!e || e.disabled)return false;const r=e.getBoundingClientRect();return e.contains(document.elementFromPoint(r.x+r.width/2,r.y+r.height/2))})()")=="true"
            }
        } catch(error:Throwable) {
            inst.sendStatus(0,Bundle().apply { putString("touchGeometry",js("(()=>{const e=($element);const r=e?.getBoundingClientRect();const hit=r?document.elementFromPoint(r.x+r.width/2,r.y+r.height/2):null;return JSON.stringify({hash:location.hash,target:r?[r.x,r.y,r.width,r.height]:null,hit:hit?.className,viewport:[innerWidth,innerHeight]})})()")) })
            shot(host.window.decorView,"ui-touch-failure.png")
            throw error
        }
        inst.uiAutomation.waitForIdle(150,1500)
        val p = JSONArray(js("(()=>{const r=($element).getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2,innerWidth]})()"))
        val box = bounds(host.bridge.webView); val scale = box.width()/p.getDouble(2)
        touch(box.left+(p.getDouble(0)*scale).toInt(),box.top+(p.getDouble(1)*scale).toInt())
    }
    private fun shot(view: View, name: String, bottomFraction: Double = 1.0) {
        inst.waitForIdleSync(); Thread.sleep(250)
        val box = Rect()
        inst.runOnMainSync { val p=IntArray(2);view.getLocationInWindow(p);box.set(p[0],p[1],p[0]+view.width,p[1]+(view.height*bottomFraction).toInt()) }
        val bitmap=Bitmap.createBitmap(box.width(),box.height(),Bitmap.Config.ARGB_8888)
        val done=CountDownLatch(1);var result=-1
        PixelCopy.request(host.window,box,bitmap,{ result=it;done.countDown() },Handler(Looper.getMainLooper()))
        check(done.await(5,TimeUnit.SECONDS) && result==PixelCopy.SUCCESS)
        File(app.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }

    @Test fun nativeChatAndWorkspace() {
        val baseline = InstrumentationRegistry.getArguments().getString("baseline") == "true"
        host=inst.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val portrait = InstrumentationRegistry.getArguments().getString("portrait") == "true"
        val orientation = host.requestedOrientation
        val suffix = if (portrait) "-portrait" else "-landscape"
        val ref = "蓝鲸观察手记 " + UUID.randomUUID().toString().take(8)
        val note = ref + "。鲸浮出水面时，呼吸孔会短暂张开。"
        val store = CompanionKnowledgeStore(app)
        val saved = store.saveText(note,"shared_text",null)
        try {
            if (portrait) {
                inst.runOnMainSync { host.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                until("未能进入竖屏") { val size = bounds(host.bridge.webView); size.width() < size.height() }
            }
            until("没有进入原生对话") {
                if (native("harness_web_view") != null) true else {
                    js("Array.from(document.querySelectorAll('button[aria-label=对话]')).find(e=>e.getBoundingClientRect().width)?.click()")
                    false
                }
            }
            val chat = requireNotNull(native("harness_web_view"))
            val geometry=if(baseline) bounds(requireNotNull(native("harness_manage"))) else webRect(sharedNav("对话"))
            shot(host.bridge.webView,if(baseline) "ui-native-before.png" else "ui-native$suffix.png")
            if(!baseline) {
                check(geometry.width()>=44*app.resources.displayMetrics.density && geometry.height()>=44*app.resources.displayMetrics.density)
                check(bounds(chat).bottom<=geometry.top+2 || bounds(chat).left>=geometry.right-2) { "对话内容覆盖了 App 导航" }
                check(bounds(chat).bottom<=bounds(host.bridge.webView).bottom+2) { "对话内容超出可用窗口" }
                check(itemsVisible()==5)
                check(native("harness_tab_active")==null) { "不应再创建另一套原生标签" }
            }
            inst.sendStatus(0,Bundle().apply {putString("sharedNavigation",geometry.toShortString());putString("chatBounds",bounds(chat).toShortString())})
            if(baseline) tap(requireNotNull(native("harness_manage"))) else webTap(sharedNav("资料"))
            if (baseline) {
                until("工作台未显示") { native("harness_web_view")==null && js("!!document.querySelector('button[aria-label=记忆]')")=="true" }
                webTap("Array.from(document.querySelectorAll('button[aria-label=记忆]')).find(e=>e.getBoundingClientRect().width)")
            }
            until("记忆工作区未显示") { js("!!document.querySelector('#companion-workspace-tab-knowledge')")=="true" }
            val firstPanel=js("document.querySelector('.workspace-tabs [aria-selected=true]')?.textContent")
            inst.sendStatus(0,Bundle().apply { putString("memoryLanding",firstPanel) })
            if (baseline) webTap("document.querySelector('#companion-workspace-tab-knowledge')")
            else check(firstPanel=="\"资料\"") { firstPanel }
            until("资料搜索未加载") { js("!!document.querySelector('input[type=search]')")=="true" }
            val layout=js("(()=>{const q=document.querySelector('.companion-workspace input[type=search]').getBoundingClientRect();return [innerWidth,innerHeight,q.top,q.bottom]})()")
            inst.sendStatus(0,Bundle().apply { putString("searchLayout",layout) })
            if (!baseline) {
                val fit = js("[innerWidth,innerHeight,document.documentElement.scrollWidth,document.querySelector('.bottom-navigation').getBoundingClientRect().bottom]")
                val screen = JSONArray(fit)
                check(screen.getInt(2) <= screen.getInt(0)+1) { "主界面横向溢出 $fit" }
                if (portrait) check(kotlin.math.abs(screen.getDouble(1)-screen.getDouble(3)) < 2) { "竖屏底栏未贴底 $fit" }
                inst.sendStatus(0,Bundle().apply { putString("viewportFit",fit) })
                webTap("document.querySelector('button[aria-label=记一笔]')")
                until("记一笔未聚焦到输入框") { js("document.activeElement===document.querySelector('.library-editor textarea') && !!document.querySelector('.library-editor textarea')")=="true" }
                Thread.sleep(450)
                dismissKeyboardIfVisible()
                webTap("Array.from(document.querySelectorAll('.library-editor button')).find(b=>b.textContent==='取消编辑')")
                until("取消后未收起编辑器") { js("!document.querySelector('.library-editor')")=="true" }

            }
            val token=JSONObject.quote(ref)
            js("(()=>{const i=document.querySelector('.companion-workspace input[type=search]');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(i,$token);i.dispatchEvent(new Event('input',{bubbles:true}))})()")
            until("隔离样例未显示") { js("!document.querySelector('.capture-shelf') && [...document.querySelectorAll('.knowledge-card-copy')].length===1 && document.querySelector('.knowledge-card-copy')?.innerText.includes($token)")=="true" }
            js("document.querySelector('.app-main').scrollTop=0;window.scrollTo(0,0);void 0")
            // Only the unique synthetic match may appear in the collection screenshot.
            shot(host.bridge.webView,if(baseline) "ui-library-before.png" else "ui-library$suffix.png")
            if (!baseline) {
                fun nav(label:String) = sharedNav(label)
                webTap("document.querySelector('.library-result')")
                until("没有进入单项资料详情") { js("!!document.querySelector('#knowledge-detail-title')") == "true" }
                inst.uiAutomation.executeShellCommand("input keyevent 4").close()
                until("返回时搜索位置丢失") { js("!document.querySelector('#knowledge-detail-title') && document.querySelector('.library-search input')?.value===$token")=="true" }
                webTap(nav("任务"))
                val manual = "document.querySelector('.worker-manual-actions button')"
                until("后台任务的手动入口未出现") { js("!!($manual) && !document.querySelector('.worker-dispatch')")=="true" }
                webTap(manual)
                until("没有打开手动交办") { js("!!document.querySelector('.worker-dispatch textarea')")=="true" }
                webTap(manual)
                until("没有收起手动交办") { js("!document.querySelector('.worker-dispatch')")=="true" }
                js("document.querySelector('.app-main').scrollTop=0;window.scrollTo(0,0);void 0")
                val cut = js("Math.min(document.querySelector('.worker-manual-actions').getBoundingClientRect().bottom,innerHeight)/innerHeight").toDouble()
                shot(host.bridge.webView,"ui-tasks$suffix.png",cut)
                webTap(nav("工具")); until("工具中心未出现") { js("!!document.querySelector('.tools-workspace')")=="true" }
                webTap("document.querySelector('.tools-plugin-link')")
                until("插件管理未出现") { js("!!document.querySelector('.plugin-screen')") == "true" }
                webTap("document.querySelector('button[aria-label=返回工具]')")
                until("插件返回没有回到工具 " + js("[location.hash,history.state?.dshView,history.state?.toolsParent]")) { js("!!document.querySelector('.tools-workspace')") == "true" }
                webTap(nav("我的"))
                until("设置搜索未显示") { js("!!document.querySelector('.account-workspace .settings-search input')") == "true" }
                check(js("document.querySelectorAll('.account-group-toggle').length")=="6")
                check(js("document.querySelectorAll('.account-group-toggle[aria-expanded=true]').length")=="1")
                shot(host.bridge.webView,"ui-settings$suffix.png")
                webTap("Array.from(document.querySelectorAll('.account-group-toggle')).find(b=>b.getAttribute('aria-label')==='运行与数据')")
                until("没有显示诊断入口") { js("document.querySelector('.settings-directory').innerText.includes('诊断与日志')")=="true" }
                val search = "document.querySelector('.settings-search input')"
                js("(()=>{const i=($search);Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(i,'记忆');i.dispatchEvent(new Event('input',{bubbles:true}))})()")
                webTap("document.querySelector('.settings-search-go')")
                val preference = "Array.from(document.querySelectorAll('.account-preference > button')).find(b=>b.innerText.includes('助手偏好'))"
                until("跨组搜索没有找到助手偏好") { js("!!($preference)")=="true" }
                webTap(preference)
                val preferencesMonitor = inst.addMonitor(CompanionActivity::class.java.name, null, false)
                try {
                    webTap("document.querySelector('.companion-settings-advanced > summary')")
                    webTap("Array.from(document.querySelectorAll('.companion-settings button')).find(b=>b.textContent.trim()==='全部助手偏好')")
                    val prefs = requireNotNull(inst.waitForMonitorWithTimeout(preferencesMonitor, 10000)) as CompanionActivity
                    fun descendant(view: View): Sequence<View> = sequence {
                        yield(view)
                        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) yieldAll(descendant(view.getChildAt(i)))
                    }
                    inst.runOnMainSync {
                        val card = descendant(prefs.window.decorView).first { it.contentDescription?.toString()?.startsWith("记忆与资料，")==true }
                        check(card.performClick())
                    }
                    until("原生目录未完成布局") {
                        var present = false
                        inst.runOnMainSync { present = descendant(prefs.window.decorView).any { it.isShown && it.contentDescription?.toString()?.startsWith("本地资料，")==true } }
                        present
                    }
                    inst.runOnMainSync {
                        prefs.onBackPressedDispatcher.onBackPressed()
                        prefs.onBackPressedDispatcher.onBackPressed()
                    }
                    until("离开原生助手偏好后没有返回设置") { host.hasWindowFocus() && js("!!document.querySelector('.companion-settings')")=="true" }
                } finally { inst.removeMonitor(preferencesMonitor) }
                webTap("Array.from(document.querySelectorAll('.companion-settings button')).find(b=>b.textContent==='打开记忆')")
                until("设置入口未回到统一资料库") { js("!!document.querySelector('.memory-workspace .library-search')")=="true" }
                webTap(nav("对话")); until("未恢复原对话") { native("harness_web_view")===chat }
                webTap(sharedNav("任务"))
                until("对话未直达事项") { js("location.hash==='#assistant-tasks'")=="true" && native("harness_web_view")==null }
                webTap(nav("对话")); until("返回工作台前没有恢复对话") { native("harness_web_view")===chat }
                webTap(sharedNav("工作台"))
                until("工作台启动区未出现") { js("!!document.querySelector('.conversation-gate .launch-panel')")=="true" && native("harness_web_view")==null }
                js("document.querySelector('.app-main').scrollTop=0;window.scrollTo(0,0);void 0")
                val homeEnd = js("Math.min(document.querySelector('.desk-shortcuts').getBoundingClientRect().bottom,innerHeight)/innerHeight").toDouble()
                shot(host.bridge.webView,"ui-home$suffix.png",homeEnd)
            }
        } finally { saved.recordIds.forEach { store.remove(it,note) };inst.runOnMainSync { if (portrait) host.requestedOrientation = orientation; host.finish() } }
    }
    @Test fun firstRunCanOpenLocalLibraryWithoutChoosingLanguage() {
        host=inst.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var saved: JSONArray? = null
        try {
            until("管理页未就绪") {
                native("harness_manage")?.let { tap(it) }
                js("!!document.querySelector('.app-shell')")=="true" && native("harness_web_view")==null
            }
            val keys = listOf("dsh-mobile-language-v1","dsh-mobile-onboarding-v1")
            saved = JSONArray(js("[localStorage.getItem('dsh-mobile-language-v1'),localStorage.getItem('dsh-mobile-onboarding-v1')]"))
            for (key in keys) js("localStorage.removeItem(" + JSONObject.quote(key) + ")")
            inst.runOnMainSync { host.bridge.webView.reload() }
            val pet = InstrumentationRegistry.getArguments().getString("firstPath") == "pet"
            val entry = if (pet) ".onboarding-paths button:nth-child(2)" else ".onboarding-paths button:first-child"
            val selector = "document.querySelector('$entry')"
            until("首次使用入口未就绪") { js("!!($selector)")=="true" }
            check(js("document.querySelectorAll('.onboarding-paths button').length") == "3")
            if (pet) {
                shot(host.bridge.webView,"whale-first-use.png")
                webTap("document.querySelector('.onboarding-paths button:last-child')")
                until("模型路径没有打开密钥框") { js("!!document.querySelector('.onboarding-fields input')")=="true" }
                webTap("document.querySelector('.onboarding-nav button')")
                until("返回开始页失败") { js("!!($selector)")=="true" }
            }
            check(js("!document.querySelector('.language-screen') && !localStorage.getItem('dsh-mobile-language-v1')")=="true")
            webTap(selector)
            if (pet) {
                until("没有到达桌宠设置") { js("!!document.querySelector('#pet-start-settings') && !!document.querySelector('[aria-labelledby=pet-start-settings] input[role=switch]')")=="true" }
                check(js("localStorage.getItem('dsh-mobile-onboarding-v1')")=="\"1\"")
                if (!android.provider.Settings.canDrawOverlays(app)) {
                    webTap("document.querySelector('[aria-labelledby=pet-start-settings] button.button-secondary')")
                    until("系统悬浮窗授权页未打开") {
                        inst.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.settings"
                    }
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")).use { it.readBytes() }
                    until("从系统设置返回后没有保留桌宠入口") {
                        inst.uiAutomation.rootInActiveWindow?.packageName?.toString() == app.packageName &&
                            js("!!document.querySelector('#pet-start-settings') && !document.querySelector('.onboarding-dialog')")=="true"
                    }
                    check(!android.provider.Settings.canDrawOverlays(app))
                    inst.sendStatus(0,Bundle().apply { putString("overlayReturn","system settings returned to pet controls; permission remained off") })
                }
            } else until("没有模型配置操作也能进入本地资料库") { js("!!document.querySelector('.memory-workspace .library-search')")=="true" }
            check(js("!localStorage.getItem('dsh-mobile-language-v1')")=="true")
            inst.sendStatus(0,Bundle().apply { putString("firstRun",if (pet) "model path returned to welcome; pet settings reached" else "local library reached without credentials") })
        } finally {
            saved?.let {
                for ((i,key) in listOf("dsh-mobile-language-v1","dsh-mobile-onboarding-v1").withIndex()) {
                    val expression = if (it.isNull(i)) "localStorage.removeItem("+JSONObject.quote(key)+")"
                        else "localStorage.setItem("+JSONObject.quote(key)+","+JSONObject.quote(it.getString(i))+")"
                    js(expression)
                }
                check(JSONArray(js("[localStorage.getItem('dsh-mobile-language-v1'),localStorage.getItem('dsh-mobile-onboarding-v1')]")).toString()==it.toString())
            }
            inst.runOnMainSync { host.finish() }
        }
    }

}
