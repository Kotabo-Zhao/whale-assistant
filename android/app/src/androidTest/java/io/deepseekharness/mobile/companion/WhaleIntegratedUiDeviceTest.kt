package io.deepseekharness.mobile.companion

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.AppThemePreference
import io.deepseekharness.mobile.WhaleDesign
import io.deepseekharness.mobile.features.WhaleFeatureStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercise production overlay windows and the actual IME/WebView on the device. */
@RunWith(AndroidJUnit4::class)
class WhaleIntegratedUiDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext
    private fun main(block: () -> Unit) = inst.runOnMainSync(block)
    private fun field(target: Any, key: String) = target.javaClass.getDeclaredField(key).apply { isAccessible = true }.get(target)
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun await(message: String, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 12000
        while (!check()) { check(SystemClock.elapsedRealtime() < end) { message }; Thread.sleep(60) }
    }
    private fun js(page: WebView, code: String): String {
        val latch = CountDownLatch(1); var result = ""
        main { page.evaluateJavascript(code) { result = it; latch.countDown() } }
        check(latch.await(8, TimeUnit.SECONDS)); return result
    }

    @Test fun allPetFamiliesKeepToolsAndRestoreAfterRealKeyboard() {
        val family = app.getSharedPreferences("whale-interface",0)
        val oldFamily = family.getString("style",null)
        val oldMode = AppThemePreference.current(app)
        val portrait=InstrumentationRegistry.getArguments().getString("portrait")=="true"
        val host=if(portrait) androidx.test.core.app.ActivityScenario.launch(io.deepseekharness.mobile.MainActivity::class.java) else null
        host?.onActivity { it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        if(portrait) await("physical portrait display") { app.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.let { it.width()<it.height() } }
        val data = File(app.cacheDir,"integrated-ui-" + UUID.randomUUID()).apply { mkdirs() }
        val names=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val prefix="integrated-"+UUID.randomUUID()
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = data
            override fun getApplicationContext() = this
            override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences {
                if(name in setOf("whale-interface","app_theme")) return app.getSharedPreferences(name,mode)
                return app.getSharedPreferences((prefix+name).also(names::add),mode)
            }
        }
        val historyContext = object : ContextWrapper(context) { override fun getApplicationContext() = this }
        val store = WhaleFeatureStore(context)
        val id="inline-"+UUID.randomUUID().toString().take(8)
        val revision = store.draft(JSONObject().put("id",id).put("title","同窗计算")
            .put("html","""<label>分钟<input id="minutes" type="number" value="90"></label>
                <button id="calculate" onclick="document.querySelector('output').value=document.querySelector('input').value/60+' 小时'">换算</button>
                <output data-whale-result></output>""")).getString("latest")
        store.activate(id,revision,"")
        val tool = store.activeTools().single()
        val wm = app.getSystemService(WindowManager::class.java)
        var bubble: CompanionBubbleWindow? = null
        try {
            for (style in listOf("cute","simple")) for (mode in listOf("light","dark")) for (height in listOf("small","auto","large")) {
                CompanionStore(context).chatHeightMode=height
                family.edit().putString("style",style).commit(); AppThemePreference.save(app,mode)
                main { bubble = CompanionBubbleWindow(context,wm,autoOpenOnDelivery=false,historyStore=CompanionBubbleHistory(historyContext),petBounds={ intArrayOf(650,700,84,84) }).also { it.show() } }
                val panel = field(requireNotNull(bubble),"root") as ViewGroup
                await("pet laid out") { var ready=false; main { ready=panel.width>0 && panel.height>0 }; ready }
                val input = views(panel).filterIsInstance<EditText>().first()
                main {
                    val ui=PetUi(context); val design=WhaleDesign.of(context)
                    assertEquals(design.ink,ui.ink); assertEquals(design.canvas,ui.canvas)
                    input.setText("保留的聊天草稿")
                }
                main { app.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken,0) }
                await("initial keyboard hidden") { var hidden=false; main { hidden=panel.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == false }; hidden }
                Thread.sleep(250)
                var originalHeight=0
                main { originalHeight=panel.height }
                main { input.requestFocus(); input.performClick(); app.getSystemService(InputMethodManager::class.java).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT) }
                await("real keyboard must become visible") { var shown=false; main { shown=panel.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }; shown }
                main { app.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken,0) }
                await("keyboard must hide") { var hidden=false; main { hidden=panel.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == false }; hidden }
                await("original height restored: $style/$mode") { var restored=false; main { restored=kotlin.math.abs(panel.height-originalHeight)<=2 }; restored }
                main { requireNotNull(bubble).openTool(tool) }
                var page: WebView?=null
                await("tool shares pet root") { main { page=views(field(requireNotNull(bubble),"inlineTool") as View).filterIsInstance<WebView>().singleOrNull() }; page!=null }
                try { await("tool ready") { js(requireNotNull(page),"document.readyState === 'complete' && !!document.querySelector('#calculate')") == "true" } }
                catch (error: Throwable) {
                    inst.sendStatus(0,android.os.Bundle().apply { putString("toolDiagnostic",js(requireNotNull(page),"JSON.stringify({html:document.documentElement.outerHTML,href:location.href,width:innerWidth,height:innerHeight})")) })
                    throw error
                }
                assertEquals("\"1.5 小时\"",js(requireNotNull(page),"document.querySelector('#calculate').click();document.querySelector('output').value"))
                main { assertTrue("tool viewport must remain usable in "+height,requireNotNull(page).height>=100) }
                assertTrue(js(requireNotNull(page),"getComputedStyle(document.body).color").contains(if(mode=="dark") (if(style=="cute") "244, 239, 248" else "238, 245, 250") else (if(style=="cute") "51, 45, 61" else "23, 43, 64")))
                fun click(text: String) = main { views(panel).filterIsInstance<TextView>().single { it.text.toString()==text }.performClick() }
                if(style=="cute" && mode=="light" && height=="auto") {
                    click("保存结果")
                    await("manual tool result saved") { CompanionKnowledgeStore(context).count()==1L }
                }
                click("返回对话")
                main { assertEquals("保留的聊天草稿",input.text.toString()); requireNotNull(bubble).openTool(tool) }
                assertEquals("\"1.5 小时\"",js(requireNotNull(page),"document.querySelector('output').value"))
                if(height=="auto") main {
                    val bitmap=Bitmap.createBitmap(panel.width,panel.height,Bitmap.Config.ARGB_8888)
                    panel.draw(Canvas(bitmap)); File(app.getExternalFilesDir(null),"p61-pet-$style-$mode.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                }
                click("带回对话")
                await("tool result returned to composer") { var ready=false; main { ready=input.text.toString()=="1.5 小时" }; ready }
                store.activate(id,"",revision)
                await("disabled tool renderer released") { var released=false; main { released=views(field(requireNotNull(bubble),"inlineTool") as View).none{it is WebView} }; released }
                main { requireNotNull(bubble).destroy() }
                store.activate(id,revision,"")
                bubble=null
                inst.sendStatus(0,android.os.Bundle().apply { putString("theme","$style/$mode/$height"); putInt("restoredHeight",originalHeight) })
            }
        } finally {
            main { bubble?.destroy() }
            family.edit().apply { if(oldFamily==null) remove("style") else putString("style",oldFamily) }.commit()
            AppThemePreference.save(app,oldMode); names.forEach(app::deleteSharedPreferences); data.deleteRecursively(); host?.close()
        }
    }
}
