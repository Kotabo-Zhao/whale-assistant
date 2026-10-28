package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.shizuku.ShizukuState
import org.junit.Assert.*
import org.junit.Test

class CompanionReadinessTest {
    @Test fun distinguishesAuthorizationConnectionAndUnknown() {
        val facts = CompanionReadinessFacts(true, false, true, false, "enabled_disconnected", false,
            ShizukuState(true, true, "granted", false, "13", "13.6"))
        val text = CompanionReadiness.describe(facts)
        assertTrue(text.contains("悬浮服务未运行"))
        assertTrue(text.contains("监听连接可能受系统限制"))
        assertTrue(text.contains("应用通知未允许"))
        assertTrue(text.contains("系统已启用，但服务未连接"))
        assertTrue(text.contains("已授权，设备服务尚未连接"))
        assertTrue(text.contains("受系统电池优化限制"))
        val unavailable = CompanionReadiness.describe(CompanionReadinessFacts(null, false, null, null, null, null, null))
        assertTrue(unavailable.contains("状态未获取"))
        assertTrue(unavailable.contains("状态不可用"))
    }
    @Test fun reportsRunningButUnresponsiveAsNotDetectedRatherThanCertainFailure() {
        val state = ShizukuState(true, false, "undetermined", false, "", "")
        val text = CompanionReadiness.describe(CompanionReadinessFacts(true, true, false, true, "connected", true, state))
        assertTrue(text.contains("可能未启动或状态读取超时"))
        assertTrue(text.contains("厂商仍可能限制后台"))
        assertTrue(text.contains("服务运行中，是否显示受系统限制"))
    }
}
