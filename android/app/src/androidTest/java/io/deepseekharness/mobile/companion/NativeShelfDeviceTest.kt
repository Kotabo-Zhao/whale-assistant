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
    @Test fun importsManifestThroughAndroidDownloads() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativePicker") == "true")
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val ui = inst.uiAutomation
        val registry = NativeFeatureRegistry(app)
        val raw = File(app.getExternalFilesDir(null),"native-v2-proof.json").readText()
        val entry = registry.parse(raw)
        require(entry.id == "checklist-rollback" && !registry.active(entry))
        val key = "rev." + entry.revision
        val saved = registry.prefs.getString(key,null)
        require(saved == null || registry.parse(saved) == entry)
        check(registry.prefs.edit().remove(key).commit())
        fun until(label: String, check: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 8000
            while (!check()) {
                assertTrue(label, SystemClock.elapsedRealtime() < until)
                SystemClock.sleep(100)
            }
        }
        fun click(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
            var target = node
            while (target != null) {
                if (target.isClickable && target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true
                target = target.parent
            }
            return false
        }
        fun named(name: String) = ui.rootInActiveWindow?.findAccessibilityNodeInfosByText(name)
            ?.firstOrNull { it.text?.toString() == name || it.contentDescription?.toString() == name }
        val filename = "whale-rollback-proof.json"
        var cleaned = false
        val studio = inst.startActivitySync(Intent(app,WhaleFeatureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            until("没有打开文档选择器入口") { click(named("导入原生功能清单")) }
            until("系统文件选择器未打开") { ui.rootInActiveWindow?.packageName == "com.android.documentsui" }
            if (named(filename) == null) {
                until("找不到位置导航") {
                    click(named("Show roots") ?: named("显示根目录") ?: named("显示根路径")
                        ?: ui.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("com.android.documentsui:id/toolbar")?.firstOrNull()?.getChild(0))
                }
                until("找不到下载目录") { click(named("下载") ?: named("Downloads") ?: named("下载内容")) }
            }
            until("未选中测试清单") { click(named(filename)) }
            until("清单未通过正常入口登记") { registry.entries().contains(entry) }
            assertFalse(registry.active(entry))
            if (InstrumentationRegistry.getArguments().getString("cleanRollbackFixture") == "true") {
                val versions = registry.entries().filter { it.id == entry.id }
                val first = registry.parse(File(app.getExternalFilesDir(null),"native-v1-proof.json").readText())
                check(versions.toSet() == setOf(first,entry))
                val edit = registry.prefs.edit().remove("active." + entry.id).remove("previous." + entry.id)
                versions.forEach { edit.remove("rev." + it.revision).remove("order." + it.revision) }
                check(edit.commit())
                cleaned = true
            }
        } finally {
            inst.runOnMainSync { studio.finish() }
            if (!cleaned && saved != null && !registry.entries().contains(entry)) registry.import(saved)
        }
    }

    @Test fun importedScreenCanBeOpenedFromPet() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val rollback = InstrumentationRegistry.getArguments().getString("nativeRollback") == "true"
        val fixture = File(app.getExternalFilesDir(null),if (rollback) "native-v1-proof.json" else "native-checklist-test.json")
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
        fun importManifest(content: String) {
            val shared = File(app.cacheDir,"share/native-checklist-proof.json")
            shared.parentFile!!.mkdirs()
            shared.writeText(content)
            val uri = FileProvider.getUriForFile(app,app.packageName+".diagnostics",shared)
            val result = WhaleFeatureActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java)
            inst.runOnMainSync { result.apply { isAccessible = true }.invoke(studio,441,Activity.RESULT_OK,Intent().setData(uri)) }
            until("清单未导入") { registry.entries().contains(registry.parse(content)) }
        }
        fun launchFromShelf(target: NativeFeatureRegistry.Entry) {
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
                inst.runOnMainSync { clicked = petViews().singleOrNull { it.isShown && it.contentDescription == "打开原生工具 · " + target.title }?.performClick() == true }
                clicked
            }
            until("原生候选没有打开") {
                inst.uiAutomation.rootInActiveWindow?.packageName?.toString() == target.pkg
            }
            inst.runOnMainSync { pet?.destroy(); pet = null }
        }
        try {
            importManifest(raw)
            if (registry.entries().count { it.id == entry.id } > 1 && !registry.active(entry)) {
                clickStudio("版本记录（2）")
                clickStudio("启用 " + entry.revision.take(8))
            } else if (!registry.active(entry)) clickStudio("启用到桌宠")
            until("未启用") { registry.active(entry) }
            clickStudio("移出桌宠")
            until("未停用") { !registry.active(entry) }
            assertTrue(runCatching { registry.openFromPet(entry) }.isFailure)
            if (registry.entries().count { it.id == entry.id } > 1) {
                clickStudio("版本记录（2）"); clickStudio("启用 " + entry.revision.take(8))
            } else clickStudio("启用到桌宠")
            until("未重新启用") { registry.active(entry) }
            if (rollback) {
                val second = File(app.getExternalFilesDir(null),"native-v2-proof.json").readText()
                val next = registry.parse(second)
                assertEquals(entry.id,next.id)
                assertNotEquals(entry.pkg,next.pkg)
                importManifest(second)
                clickStudio("版本记录（2）")
                clickStudio("启用 " + next.revision.take(8))
                until("新版本未启用") { registry.active(next) }
                assertTrue(runCatching { registry.select(entry,null,true) }.isFailure)
                assertTrue(runCatching { registry.openFromPet(entry) }.isFailure)
                launchFromShelf(next)
                inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                until("工坊未返回") { inst.uiAutomation.rootInActiveWindow?.packageName?.toString() == app.packageName }
                clickStudio("恢复上一版")
                until("原版本未恢复") { registry.active(entry) }
                assertTrue(runCatching { registry.openFromPet(next) }.isFailure)
            }
            launchFromShelf(entry)
        } finally {
            inst.runOnMainSync { pet?.destroy(); studio.finish() }
        }
    }
}
