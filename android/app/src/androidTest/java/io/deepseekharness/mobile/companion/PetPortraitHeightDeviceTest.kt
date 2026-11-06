package io.deepseekharness.mobile.companion

import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.MainActivity
import io.deepseekharness.mobile.AppThemePreference
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PetPortraitHeightDeviceTest {
    @Test fun phonePortraitContentAndKeyboardKeepTheComposerVisibleAtThreePetPositions() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=inst.targetContext
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        fun main(block:()->Unit)=inst.runOnMainSync(block)
        fun await(label:String,check:()->Boolean) {
            val end=SystemClock.elapsedRealtime()+12000
            while(!check()){check(SystemClock.elapsedRealtime()<end){label};Thread.sleep(40)}
        }
        val wm=app.getSystemService(WindowManager::class.java)
        await("竖屏手机区域没有生效"){wm.maximumWindowMetrics.bounds.let{it.height()>it.width()}}
        val root=File(app.cacheDir,"portrait-height-"+UUID.randomUUID()).apply{mkdirs()}
        val names=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val appearance=app.getSharedPreferences("whale-interface",0)
        val originalStyle=appearance.getString("style",null)
        val originalMode=AppThemePreference.current(app)
        val context=object:ContextWrapper(app) {
            override fun getApplicationContext()=this
            override fun getNoBackupFilesDir()=root
            override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences {
                if(name in setOf("whale-interface","app_theme")) return app.getSharedPreferences(name,mode)
                return super.getSharedPreferences((root.name+name).also(names::add),mode)
            }
        }
        fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))}else emptyList()
        fun bounds(view:View):Rect {val xy=IntArray(2);view.getLocationOnScreen(xy);return Rect(xy[0],xy[1],xy[0]+view.width,xy[1]+view.height)}
        val d=app.resources.displayMetrics.density
        val font=app.resources.configuration.fontScale
        val display=wm.maximumWindowMetrics.bounds
        assertTrue("验收宽度必须是手机宽度",display.width()/d in 350f..440f)
        val ime=app.getSystemService(InputMethodManager::class.java)
        var window:CompanionBubbleWindow?=null
        fun screenshot(name:String) {
            val bitmap=requireNotNull(inst.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot())
            File(app.getExternalFilesDir(null),"p73-portrait-$name-font$font.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
        try {
            app.startActivity(android.content.Intent().setComponent(android.content.ComponentName(inst.context.packageName,ForeignDropSource::class.java.name))
                .putExtra("note","portrait-probe").putExtra("portrait",true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("竖屏测试背景未就绪"){inst.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).rootInActiveWindow?.packageName==inst.context.packageName}
            for(style in listOf("cute","simple")) for(mode in listOf("light","dark")) for(anchor in listOf(.12f,.50f,.88f)) {
                context.getSharedPreferences("whale-interface",0).edit().putString("style",style).commit()
                AppThemePreference.save(context,mode)
                val design=io.deepseekharness.mobile.WhaleDesign.of(app)
                assertEquals("实际明暗主题没有应用",mode=="dark",design.dark)
                assertEquals("桌宠配色没有应用",design.canvas,PetUi(context).canvas)
                main {window=CompanionBubbleWindow(context,wm,autoOpenOnDelivery=false,petBounds={
                    intArrayOf((display.width()*.06f).toInt(),(display.height()*anchor).toInt(),(56*d).toInt(),(56*d).toInt())
                }).also{it.show("今天想聊些什么？")}}
                val panel=CompanionBubbleWindow::class.java.getDeclaredField("root").apply{isAccessible=true}.get(window) as ViewGroup
                val message=views(panel).filterIsInstance<TextView>().single{it.text.toString()=="今天想聊些什么？"}
                val input=views(panel).filterIsInstance<EditText>().single()
                fun settled():Int {
                    var previous="";var since=0L;var height=0
                    await("窗口没有收敛") {
                        var signature="";main{height=panel.height;signature=bounds(panel).toShortString()}
                        val now=SystemClock.elapsedRealtime()
                        if(signature!=previous){previous=signature;since=now}
                        height>0&&now-since>=180
                    }
                    return height
                }
                fun inside(label:String,view:View) {
                    main {
                        val rect=bounds(view);val frame=Rect().also(panel::getWindowVisibleDisplayFrame)
                        assertTrue("$label frame=$frame rect=$rect params="+panel.layoutParams,
                            rect.top>=frame.top && rect.bottom<=frame.bottom && rect.left>=0 && rect.right<=display.width())
                    }
                }
                val compact=settled()
                inside("短消息窗口",panel)
                if(font>=1.4f) main {
                    for(label in listOf("存资料","我的工具","会话选项")) {
                        val button=views(panel).filterIsInstance<TextView>().single{it.isShown && it.text.toString()==label}
                        assertEquals("大字号快捷按钮发生挤压：$label",1,button.layout.lineCount)
                    }
                }
                if(anchor==.88f)screenshot("$style-$mode-short")
                main {message.text=List(42){"这是用于竖屏长回复验收的一行内容。"}.joinToString("\n")}
                val expanded=settled()
                assertTrue("长消息没有展开",expanded>compact)
                assertTrue("自动高度在竖屏占用过多",(expanded/d)<=(display.height()/d)*.61f)
                inside("长回复窗口",panel)
                if(anchor==.88f)screenshot("$style-$mode-long")
                main {message.text="今天想聊些什么？"}
                assertEquals("短消息没有收回",compact,settled())
                main {
                    input.setText("这是一条竖屏输入草稿")
                    input.requestFocus();ime.showSoftInput(input,InputMethodManager.SHOW_IMPLICIT)
                }
                await("真实键盘未显示"){var shown=false;main{shown=panel.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true};shown}
                settled()
                inside("键盘上方输入栏",input.parent as View)
                if(anchor==.88f)screenshot("$style-$mode-keyboard")
                main {views(panel).filterIsInstance<TextView>().single{it.contentDescription=="会话选项"}.performClick()}
                settled();inside("展开菜单后输入栏",input.parent as View)
                if(anchor==.88f)screenshot("$style-$mode-keyboard-options")
                main {
                    ime.hideSoftInputFromWindow(input.windowToken,0)
                    views(panel).filterIsInstance<TextView>().single{it.contentDescription=="会话选项"}.performClick()
                    input.setText("")
                }
                await("键盘未收起"){var hidden=false;main{hidden=panel.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==false};hidden}
                assertEquals("键盘收起后高度未恢复",compact,settled())
                inside("恢复后窗口",panel)
                main{window?.destroy()};window=null
                inst.sendStatus(0,android.os.Bundle().apply{putString("portraitCase","$style/$mode/$anchor");putInt("compactPx",compact);putInt("longPx",expanded)})
            }
        } finally {
            main{window?.destroy()}
            AppThemePreference.save(app,originalMode)
            appearance.edit().apply{if(originalStyle==null)remove("style")else putString("style",originalStyle)}.commit()
            names.forEach(app::deleteSharedPreferences);root.deleteRecursively();scenario.close()
        }
    }
}
