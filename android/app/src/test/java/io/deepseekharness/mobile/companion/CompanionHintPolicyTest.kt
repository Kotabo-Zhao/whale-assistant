package io.deepseekharness.mobile.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionHintPolicyTest {
    @Test fun dueRemindersBypassRoutineCooldownButDoNotReplaceAVisibleReminder() {
        assertTrue(CompanionHintPolicy.mayShow(reminder = true, reminderVisible = false, now = 1_001, last = 1_000))
        assertFalse(CompanionHintPolicy.mayShow(reminder = false, reminderVisible = false, now = 1_001, last = 1_000))
        assertFalse(CompanionHintPolicy.mayShow(reminder = true, reminderVisible = true, now = 200_000, last = 1_000))
        assertFalse(CompanionHintPolicy.mayShow(reminder = false, reminderVisible = true, now = 200_000, last = 1_000))
        assertTrue(CompanionHintPolicy.mayShow(reminder = false, reminderVisible = false, now = 121_000, last = 1_000))
        assertFalse(CompanionHintPolicy.mayShow(reminder = false, reminderVisible = false, now = 999, last = 1_000))
    }
    @Test fun pendingRemindersAreBoundedFifoAndExpire() {
        val queue = CompanionHintQueue<String>()
        for (index in 1..5) assertTrue(queue.offer("r$index", 1_000L))
        assertFalse(queue.offer("overflow", 1_001L))
        for (index in 1..5) org.junit.Assert.assertEquals("r$index", queue.take(2_000L))
        org.junit.Assert.assertNull(queue.take(2_000L))
        assertTrue(queue.offer("expired", 1_000L)); assertTrue(queue.offer("fresh", 300_000L))
        org.junit.Assert.assertEquals("fresh", queue.take(301_001L))
        assertTrue(queue.offer("clock changed", 500_000L))
        org.junit.Assert.assertNull(queue.take(499_999L))
        assertTrue(queue.offer("cleared", 500_000L)); queue.clear()
        assertFalse(queue.isNotEmpty())
    }
}
