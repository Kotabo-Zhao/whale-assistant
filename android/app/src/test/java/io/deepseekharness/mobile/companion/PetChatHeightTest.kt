package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetChatHeightTest {
    @Test fun portraitShortMessagesStayCompactWhileLongRepliesExpandGenerously() {
        val short = PetChatHeight.total(800, 220, 160, 36, "auto", true, false, false, 1f)
        val long = PetChatHeight.total(800, 220, 160, 3000, "auto", true, false, false, 1f)
        // Compact when empty/short (160 + 88 = 248), expands to 68% (544) ignoring cramped nearPet=220
        assertEquals(248, short)
        assertEquals(544, long)
        assertTrue(long > short)
    }

    @Test fun smallPortraitKeyboardKeepsComposerAndReadableMessageArea() {
        val controls = PetChatHeight.controls(300, 180, 1f)
        assertTrue(controls <= 48)
        val height = PetChatHeight.total(300, 130, 180 + controls, 2000, "auto", true, true, false, 1f)
        assertTrue(height <= 300)
        assertTrue(height - (180 + controls) >= 64)
    }

    @Test fun explicitLargeModeAndToolsHaveSpaciousHeight() {
        val auto = PetChatHeight.total(780, 200, 160, 2000, "auto", true, false, false, 1f)
        val large = PetChatHeight.total(780, 200, 160, 2000, "large", true, false, false, 1f)
        assertTrue(large > auto)
        assertTrue(PetChatHeight.total(780, 200, 68, 390, "auto", true, false, true, 1f) - 68 >= 180)
        assertTrue(PetChatHeight.total(720, 200, 160, 2000, "auto", false, false, false, 1f) > auto)
    }
}
