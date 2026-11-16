package io.deepseekharness.mobile.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 缺键时的对话访问默认值。已经写入的 false 仍由 SharedPreferences 原样读出。 */
class CompanionAccessDefaultsTest {
    @Test fun openedMemoryAndLibraryStayReachableUntilTheUserTurnsThemOff() {
        assertTrue(CompanionAccessDefaults.BUBBLE_MEMORY)
        assertTrue(CompanionAccessDefaults.KNOWLEDGE_READ)
        assertFalse(CompanionAccessDefaults.KNOWLEDGE_WRITE)
        assertFalse(CompanionAccessDefaults.NOTIFICATION_MODEL)
        assertFalse(CompanionAccessDefaults.CALENDAR_MODEL)
    }
}
