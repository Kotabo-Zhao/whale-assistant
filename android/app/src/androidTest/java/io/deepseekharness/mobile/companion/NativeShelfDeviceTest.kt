package io.deepseekharness.mobile.companion

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.features.NativeFeatureRegistry
import io.deepseekharness.mobile.features.WhaleFeatureActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NativeShelfDeviceTest {
    @Test fun importedScreenCanBeOpenedFromPet() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val fixture = File(app.getExternalFilesDir(null),"native-checklist-test.json")
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativeShelf") == "true")
        val raw = fixture.readText()
        val registry = NativeFeatureRegistry(app)
        val entry = registry.parse(raw)
        val tampered = org.json.JSONObject(raw).put("apkSha256","0".repeat(64))
        assertTrue(runCatching { registry.import(tampered.toString()) }.isFailure)
        fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
            (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        fun until(label: String, check: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime()+8000
            while (!check()) {
                assertTrue(label,SystemClock.elapsedRealtime()<deadline)
                SystemClock.sleep(80)
            }
        }
        val studio = inst.startActivitySync(Intent(app, WhaleFeatureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var pet: CompanionBubbleWindow? = null
        fun clickStudio(title: String) {
            until(title) {
                var clicked = false
                inst.runOnMainSync {
                    val card = views(studio.window.decorView).filterIsInstance<TextView>()
                        .firstOrNull { it.text == entry.title }?.parent as? ViewGroup
                    clicked = card?.let(::views)?.filterIsInstance<TextView>()?.firstOrNull { it.text == title }?.performClick() == true
                }
                clicked
            }
        }
        try {
            val shared = File(app.cacheDir,"share/native-checklist-proof.json")
            shared.parentFile!!.mkdirs()
            shared.writeText(raw)
            val uri = FileProvider.getUriForFile(app,app.packageName+".diagnostics",shared)
            val result = WhaleFeatureActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java)
            inst.runOnMainSync { result.apply { isAccessible = true }.invoke(studio,441,Activity.RESULT_OK,Intent().setData(uri)) }
            until("清单未导入") { registry.entries().contains(entry) }
            if (!registry.active(entry)) clickStudio("启用到桌宠")
            until("未启用") { registry.active(entry) }
            clickStudio("移出桌宠")
            until("未停用") { !registry.active(entry) }
            assertTrue(runCatching { registry.openFromPet(entry) }.isFailure)
            clickStudio("启用到桌宠")
            until("未重新启用") { registry.active(entry) }
            inst.runOnMainSync {
                pet = CompanionBubbleWindow(app,app.getSystemService(WindowManager::class.java), autoOpenOnDelivery = false) { intArrayOf(36,140,120,120) }
                pet!!.show()
            }
            fun petViews(): List<View> {
                val root = CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible = true }.get(pet) as View
                return views(root)
            }
            inst.runOnMainSync {
                petViews().filterIsInstance<TextView>().single { it.text == app.getString(io.deepseekharness.mobile.R.string.pet_menu_tools) }.performClick()
            }
            until("桌宠工具入口缺失") {
                var clicked = false
                inst.runOnMainSync { clicked = petViews().firstOrNull { it.isShown && it.contentDescription == "打开原生工具 · " + entry.title }?.performClick() == true }
                clicked
            }
            until("原生候选没有打开") {
                inst.uiAutomation.rootInActiveWindow?.packageName?.toString() == entry.pkg
            }
        } finally {
            inst.runOnMainSync { pet?.destroy(); studio.finish() }
        }
    }
}
