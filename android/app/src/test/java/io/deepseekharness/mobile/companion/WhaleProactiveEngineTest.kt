package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleProactiveEngineTest {

    @Test
    fun `extractCommitment captures time-anchored commitments and ignores casual chat`() {
        val now = 1_700_000_000_000L
        val item = WhaleProactiveEngine.extractCommitment("明天上午要参加芯片架构面试，有点紧张", now)
        assertNotNull(item)
        assertEquals("project", item!!.category)
        assertEquals(now + 16L * 3_600_000L, item.dueAtMs)
        assertTrue(item.actionHint.contains("准备清单"))

        assertNull(WhaleProactiveEngine.extractCommitment("今天天气真不错呀", now))
    }

    @Test
    fun `shouldIntervene enforces busy, cooldown, quiet hours, and dismissed streak gates`() {
        // High-value reminder when user is free & rested -> allow
        assertTrue(WhaleProactiveEngine.shouldIntervene(eventValue = 75, isUserBusy = false, minutesSinceLastPrompt = 120, dismissedStreak = 0))
        // Casual greeting when user is busy -> block
        assertFalse(WhaleProactiveEngine.shouldIntervene(eventValue = 45, isUserBusy = true, minutesSinceLastPrompt = 120, dismissedStreak = 0))
        // Repeatedly dismissed -> raise bar & block moderate nudge
        assertFalse(WhaleProactiveEngine.shouldIntervene(eventValue = 65, isUserBusy = false, minutesSinceLastPrompt = 120, dismissedStreak = 2))
        // Quiet hours block non-critical nudges
        assertFalse(WhaleProactiveEngine.shouldIntervene(eventValue = 80, isUserBusy = false, minutesSinceLastPrompt = 240, dismissedStreak = 0, isQuietHours = true))
        assertTrue(WhaleProactiveEngine.shouldIntervene(eventValue = 95, isUserBusy = false, minutesSinceLastPrompt = 240, dismissedStreak = 0, isQuietHours = true))
    }

    @Test
    fun `reconsiderItems silently retracts commitments already finished in recent dialogue`() {
        val now = 1_700_000_000_000L
        val item = WhaleProactiveEngine.extractCommitment("明天上午要参加芯片架构面试", now)!!
        val (updated, retracted) = WhaleProactiveEngine.reconsiderItems(
            listOf(item),
            listOf("刚才芯片架构面试已经面完了，感觉很稳！"),
        )
        assertEquals(listOf(item.id), retracted)
        assertEquals("done", updated.single().status)
    }
}
