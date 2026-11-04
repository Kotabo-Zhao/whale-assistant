package io.deepseekharness.mobile.companion

import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.AppThemePreference
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhalePetSurfaceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext
    private fun children(view: View): Sequence<View> = sequence {
        yield(view); if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(children(view.getChildAt(i)))
    }
    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    @Test fun chatComposerAndCaptureShareWorkingDraftAndTheme() {
        val args = InstrumentationRegistry.getArguments()
        val stage = args.getString("stage") ?: "final"
        val mode = args.getString("theme") ?: "dark"
        val token = UUID.randomUUID().toString()
        val folder = File(app.cacheDir, "surface-proof-$token").apply { mkdirs() }
        val prefNames = mutableSetOf<String>()
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name !in setOf("worker_report_inbox","companion_delivery_displayed","app_theme")) return super.getSharedPreferences(name,mode)
                val unique = "surface-$token-$name"; synchronized(prefNames) { prefNames.add(unique) }
                return app.getSharedPreferences(unique,mode)
            }
        }
        AppThemePreference.save(isolated, mode)
        val request = "把旅行笔记里的地点整理成清单。"
        val reply = "可以。把笔记存进资料库，然后把整理任务交给我。"
        val draft = "按城市整理"
        CompanionBubbleHistory(isolated).save(CompanionBubbleHistory.Snapshot("proof-$token", "0".repeat(64),
            listOf(CompanionBubbleChat.Message("user",request),CompanionBubbleChat.Message("assistant",reply))))
        val fixtureTask = "整理旅行地点"
        if (stage == "cute") {
            val id = UUID.randomUUID().toString()
            val row = org.json.JSONObject().put("id",id).put("sessionId","proof-worker-$id")
                .put("owner","demo").put("mode","read").put("title",fixtureTask).put("text",fixtureTask)
                .put("state","waiting").put("question","按城市还是按日期整理？").put("scope","只读取这份旅行笔记")
                .put("createdAt",1).put("updatedAt",1).put("writeSeq",1)
            WorkerDashboardStore(isolated).put(row)
            val guest = File(folder,"dsh-runtime/current/root/.dsh/mobile-workers").apply { mkdirs() }
            File(guest,"$id.json").writeText(row.toString())
        }
        lateinit var bubble: CompanionBubbleWindow
        fun root() = requireNotNull(field(bubble,"root") as? View)
        fun proof(name: String, view: View) {
            assertTrue(name, view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(app.getExternalFilesDir(null),"surface-$stage-$mode-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
        fun waitForPortrait(view: () -> View) {
            if (stage != "cute") return
            val end = SystemClock.elapsedRealtime() + 5000
            var loaded = false
            while (!loaded && SystemClock.elapsedRealtime() < end) {
                inst.runOnMainSync {
                    val portrait = children(view()).firstOrNull { it.javaClass.simpleName == "PetPortrait" }
                    loaded = portrait != null && field(portrait, "face") is Bitmap
                }
                if (!loaded) Thread.sleep(50)
            }
            assertTrue("portrait from the original whale artwork", loaded)
        }
        fun click(description: String) = inst.runOnMainSync {
            assertTrue(children(root()).single { it.contentDescription == description && it.isShown }.performClick())
        }
        try {
            inst.runOnMainSync {
                bubble = CompanionBubbleWindow(isolated, app.getSystemService(WindowManager::class.java), autoOpenOnDelivery=false) { intArrayOf(52,260,128,128) }
                bubble.show()
            }
            inst.waitForIdleSync()
            inst.runOnMainSync {
                val panel = root()
                assertTrue(children(panel).filterIsInstance<TextView>().any { it.text.toString() == reply })
                children(panel).filterIsInstance<EditText>().single().setText(draft)
            }
            inst.waitForIdleSync()
            waitForPortrait(::root)
            inst.runOnMainSync { proof("chat",root()) }
            if (stage != "before") {
                lateinit var input: EditText
                inst.runOnMainSync {
                    input = children(root()).filterIsInstance<EditText>().single()
                    input.requestFocus(); input.performClick()
                    app.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(input, 0)
                }
                var ime = false
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (!ime && SystemClock.elapsedRealtime() < deadline) {
                    inst.runOnMainSync { ime = androidx.core.view.ViewCompat.getRootWindowInsets(input)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
                    if (!ime) Thread.sleep(80)
                }
                assertTrue("system keyboard did not open above the pet", ime)
                inst.runOnMainSync {
                    val frame = android.graphics.Rect(); root().getWindowVisibleDisplayFrame(frame)
                    val xy = IntArray(2); input.getLocationOnScreen(xy)
                    val send = children(root()).filterIsInstance<TextView>().single { it.text == "发送" }
                    val at = IntArray(2); send.getLocationOnScreen(at)
                    assertTrue("input overlaps keyboard", xy[1]+input.height <= frame.bottom + 2)
                    assertTrue("send overlaps keyboard", at[1]+send.height <= frame.bottom + 2)
                    inst.sendStatus(0, android.os.Bundle().apply { putInt("inputBottom",xy[1]+input.height); putInt("visibleBottom",frame.bottom) })
                    app.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken,0)
                }
                inst.waitForIdleSync()
            }
            if (stage != "before") click("会话选项")
            click("交办独立后台任务")
            inst.waitForIdleSync()
            waitForPortrait { (field(field(bubble,"workerPanel")!!,"editDialog") as AlertDialog).window!!.decorView }
            inst.runOnMainSync {
                val dialog = field(field(bubble,"workerPanel")!!,"editDialog") as AlertDialog
                val view = dialog.window!!.decorView
                assertEquals(3, children(view).filterIsInstance<android.widget.RadioButton>().count())
                children(view).filterIsInstance<EditText>().single().setText("从旅行笔记整理地点")
                proof("task",view); dialog.dismiss()
                assertEquals(draft,children(root()).filterIsInstance<EditText>().single().text.toString())
            }
            click("存入本地资料库")
            inst.waitForIdleSync()
            waitForPortrait { (field(bubble,"captureDialog") as AlertDialog).window!!.decorView }
            inst.runOnMainSync {
                val dialog = field(bubble,"captureDialog") as AlertDialog
                val view = dialog.window!!.decorView
                val editor = children(view).filterIsInstance<EditText>().single()
                assertEquals(draft,editor.text.toString()); editor.setText("京都站北口，周六上午十点集合。")
                proof("save",view); dialog.dismiss()
                assertEquals(draft,children(root()).filterIsInstance<EditText>().single().text.toString())
            }
            if (stage == "cute") {
                inst.runOnMainSync { bubble.openTaskInbox() }
                val panel = field(bubble,"workerPanel")!!
                fun inbox() = (field(panel, "dialog") as AlertDialog).window!!.decorView
                fun demoCard() = children(inbox()).firstOrNull { it.contentDescription == "任务管理：$fixtureTask" }
                val until = SystemClock.elapsedRealtime()+10000
                var ready = false
                while (!ready && SystemClock.elapsedRealtime()<until) {
                    inst.runOnMainSync { ready = demoCard()?.width?.let { it > 0 } == true }
                    if (!ready) Thread.sleep(80)
                }
                assertTrue("waiting task visible", ready)
                waitForPortrait { inbox() }
                inst.runOnMainSync {
                    val card = requireNotNull(demoCard())
                    assertTrue(children(card).filterIsInstance<TextView>().any { it.text=="回答" })
                    assertFalse(children(card).filterIsInstance<TextView>().any { it.text=="撤销" })
                    proof("inbox",inbox())
                    children(card).filterIsInstance<TextView>().single { it.text=="管理" }.performClick()
                    assertTrue(children(requireNotNull(demoCard())).filterIsInstance<TextView>().any { it.text=="撤销" })
                }
            }
        } finally {
            inst.runOnMainSync { bubble.destroy() }
            synchronized(prefNames) { prefNames.forEach(app::deleteSharedPreferences) }
            check(folder.parentFile == app.cacheDir && folder.name == "surface-proof-$token")
            folder.walkBottomUp().forEach { it.delete() }
        }
    }
}
