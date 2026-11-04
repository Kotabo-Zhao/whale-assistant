package io.deepseekharness.mobile.overlay

import org.junit.Assert.*
import org.junit.Test

class CompanionHintLayoutTest {
    @Test fun tallChatUsesFreeSideWithoutCoveringPet() {
        val right = CompanionHintLayout.place(2560, 1380, 2260, 552, 264, 264, 960, 1110, 6, 24)
        assertTrue(right.x + 960 + 24 <= 2260)
        assertTrue(right.y >= 0 && right.y + 1110 <= 1380)
        val left = CompanionHintLayout.place(2560, 1380, 12, 552, 264, 264, 960, 1110, 6, 24)
        assertTrue(left.x >= 12 + 264 + 24)
    }
    @Test fun choosesAboveOrBelowAndClampsMeasuredBubble() {
        assertEquals(CompanionHintLayout.Position(0, 108), CompanionHintLayout.place(360, 800, 0, 180, 56, 56, 220, 64, 24, 8))
        assertEquals(CompanionHintLayout.Position(140, 88), CompanionHintLayout.place(360, 800, 304, 24, 56, 56, 220, 100, 24, 8))
        assertEquals(CompanionHintLayout.Position(140, 520), CompanionHintLayout.place(360, 800, 304, 760, 56, 56, 220, 232, 24, 8))
        val tight = CompanionHintLayout.place(100, 120, 0, 45, 20, 20, 100, 110, 24, 8)
        assertEquals(0, tight.x)
        assertTrue(tight.y >= 0 && tight.y + 110 <= 120)
        assertThrows(IllegalArgumentException::class.java) { CompanionHintLayout.place(0, 120, 0, 0, 20, 20, 100, 100, 0, 0) }
    }
}
