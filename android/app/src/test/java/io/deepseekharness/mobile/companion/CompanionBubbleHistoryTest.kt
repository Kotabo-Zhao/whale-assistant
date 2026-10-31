package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionBubbleHistoryTest {
    @Test fun roundTripAndRejectMalformedSnapshot() {
        val snapshot = CompanionBubbleHistory.Snapshot("session-1", "a".repeat(64), listOf(
            CompanionBubbleChat.Message("user", "你好"), CompanionBubbleChat.Message("assistant", "欢迎回来")))
        assertEquals(snapshot, CompanionBubbleHistory.decode(CompanionBubbleHistory.encode(snapshot)))
        assertThrows(IllegalArgumentException::class.java) { CompanionBubbleHistory.encode(snapshot.copy(scope = "invalid")) }
        assertThrows(IllegalArgumentException::class.java) { CompanionBubbleHistory.encode(snapshot.copy(messages = snapshot.messages.reversed())) }
    }
}
