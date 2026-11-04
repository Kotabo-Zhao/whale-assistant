package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PetChatLayoutDeviceTest {
    @Test fun shortcutsLeaveTheComposerAnchoredInBothThemes() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val key = "chat-layout-" + UUID.randomUUID()
        val dir = File(app.cacheDir,key).apply { check(mkdirs()) }
        val ctx = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = dir
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(key+name,mode)
        }
        fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
        try {
            for (theme in listOf("simple","cute")) {
                ctx.getSharedPreferences("whale-interface",0).edit().putString("style",theme).commit()
                lateinit var window: CompanionBubbleWindow
                lateinit var panel: LinearLayout
                inst.runOnMainSync {
                    window = CompanionBubbleWindow(ctx,app.getSystemService(WindowManager::class.java),autoOpenOnDelivery=false) { intArrayOf(36,140,120,120) }
                    window.show("今天想一起做点什么？")
                    panel = CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible=true }.get(window) as LinearLayout
                }
                try {
                    inst.waitForIdleSync()
                    inst.runOnMainSync {
                        val nodes = all(panel).filterIsInstance<TextView>()
                        val input = nodes.filterIsInstance<EditText>().single()
                        val send = nodes.single { it.text == "发送" }
                        val options = nodes.single { it.contentDescription == "会话选项" }
                        val footer = input.parent as LinearLayout
                        assertSame(footer,send.parent)
                        assertSame(footer,panel.getChildAt(panel.childCount-1))
                        assertEquals(2,footer.childCount)
                        input.setText("帮我整理这份清单")
                        assertTrue(options.performClick())
                        assertTrue(nodes.single { it.text == "新话题" }.isShown)
                        assertTrue(nodes.single { it.text == "我的工具" }.performClick())
                        assertFalse(nodes.single { it.text == "新话题" }.isShown)
                        assertTrue(all(panel).any { it is WhalePetToolShelf && it.isShown })
                        nodes.single { it.text == "我的工具" }.performClick()
                        assertEquals("帮我整理这份清单",input.text.toString())
                    }
                    inst.waitForIdleSync()
                    inst.runOnMainSync {
                        val input = all(panel).filterIsInstance<EditText>().single()
                        val composer = input.parent as View
                        assertTrue(input.width > panel.width * 0.65)
                        assertTrue(composer.bottom <= panel.height)
                        if (InstrumentationRegistry.getArguments().getString("captureLayout") == "true") {
                            val bmp = Bitmap.createBitmap(panel.width,panel.height,Bitmap.Config.ARGB_8888)
                            panel.draw(Canvas(bmp))
                            File(app.getExternalFilesDir(null),"pet-chat-$theme.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }
                            bmp.recycle()
                        }
                    }
                    val ime = app.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    inst.runOnMainSync {
                        val input = all(panel).filterIsInstance<EditText>().single()
                        input.requestFocus()
                        ime.showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                    val deadline = android.os.SystemClock.elapsedRealtime()+5000
                    var visible = false
                    while (!visible && android.os.SystemClock.elapsedRealtime()<deadline) {
                        inst.runOnMainSync {
                            val inset = panel.rootWindowInsets
                            if (android.os.Build.VERSION.SDK_INT >= 30 && inset?.isVisible(android.view.WindowInsets.Type.ime()) == true) {
                                val composer = all(panel).filterIsInstance<EditText>().single().parent as View
                                val pos = IntArray(2).also(composer::getLocationOnScreen)
                                val frame = android.graphics.Rect().also(panel::getWindowVisibleDisplayFrame)
                                visible = pos[1] >= frame.top && pos[1] + composer.height <= frame.bottom
                            }
                        }
                        if (!visible) Thread.sleep(80)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 30) assertTrue("键盘展开后输入区未完整可见",visible)
                    inst.runOnMainSync { ime.hideSoftInputFromWindow(panel.windowToken,0) }
                } finally { inst.runOnMainSync { window.destroy() } }
            }
        } finally { dir.deleteRecursively() }
    }
}
