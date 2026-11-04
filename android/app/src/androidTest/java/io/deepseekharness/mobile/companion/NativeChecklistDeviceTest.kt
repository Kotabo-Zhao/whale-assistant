package io.deepseekharness.mobile.companion

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class NativeChecklistDeviceTest {
    @Test fun reviewedCandidatePreservesOrder() {
        val pkg = InstrumentationRegistry.getArguments().getString("candidatePackage").orEmpty()
        assumeTrue(pkg.matches(Regex("io\\.deepseekharness\\.candidate\\.f[a-f0-9]{24}")))
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val component = "$pkg/io.deepseekharness.candidate.PreviewActivity"
        ui.executeShellCommand("am start -W -n $component").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        fun lookup(label: String): AccessibilityNodeInfo? {
            val root = ui.rootInActiveWindow ?: return null
            if (root.packageName?.toString() != pkg) return null
            return root.findAccessibilityNodeInfosByText(label).firstOrNull {
                it.contentDescription?.toString() == label || it.text?.toString() == label
            }
        }
        fun awaitCondition(test: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 5000
            while (!test()) {
                check(SystemClock.elapsedRealtime() < until) { "候选功能控件或结果未就绪" }
                SystemClock.sleep(100)
            }
        }
        awaitCondition { lookup("原始清单") != null }
        val input = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, " 海星 \n鲸鱼\n海星\n\n 海豚 ")
        }
        assertTrue(lookup("原始清单")!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input))
        assertTrue(lookup("整理清单")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitCondition { lookup("整理结果")?.text?.toString() == "海星\n鲸鱼\n海豚" }
    }
}
