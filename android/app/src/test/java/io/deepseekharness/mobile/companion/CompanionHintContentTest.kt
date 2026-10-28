package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionHintContentTest {
    @Test fun onlyShowsExplicitlyEnabledBoundedNonSensitiveDetails() {
        assertEquals(CompanionHintContent.GENERIC, CompanionHintContent.recurring("喝水", false))
        assertEquals("提醒：喝水 点击打开对话。", CompanionHintContent.recurring("喝水", true))
        assertEquals("提醒：起来 活动 点击打开对话。", CompanionHintContent.recurring("起来\n活动", true))
        assertEquals(CompanionHintContent.GENERIC, CompanionHintContent.recurring("验证码 123456", true))
        assertEquals(CompanionHintContent.GENERIC, CompanionHintContent.recurring("   ", true))
        assertTrue(CompanionHintContent.recurring("鲸".repeat(65), true).contains("鲸".repeat(64) + "…"))
        assertTrue(CompanionHintContent.recurring("鲸".repeat(63) + "🐋", true).contains("鲸".repeat(63) + "…"))
    }
}
