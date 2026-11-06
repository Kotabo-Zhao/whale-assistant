package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test

class PetChatHeightTest {
    @Test fun portraitShortMessagesStayCompactWhileLongRepliesStopAtSixtyPercent() {
        val short=PetChatHeight.total(800,700,160,36,"auto",true,false,false,1f)
        val long=PetChatHeight.total(800,700,160,3000,"auto",true,false,false,1f)
        assertEquals(224,short)
        assertEquals(480,long)
        assertTrue(long>short)
        assertEquals(short,PetChatHeight.total(800,700,160,36,"auto",true,false,false,1f))
    }
    @Test fun smallPortraitKeyboardKeepsComposerAndAtLeastOneMessageLine() {
        val controls=PetChatHeight.controls(300,180,1f)
        assertTrue(controls<=56)
        val height=PetChatHeight.total(300,130,180+controls,2000,"auto",true,true,false,1f)
        assertTrue(height<=300)
        assertTrue(height-(180+controls)>=44)
    }
    @Test fun explicitLargeModeAndToolsStillHaveUsableHeight() {
        val auto=PetChatHeight.total(780,700,160,2000,"auto",true,false,false,1f)
        val large=PetChatHeight.total(780,700,160,2000,"large",true,false,false,1f)
        assertTrue(large>auto)
        assertTrue(PetChatHeight.total(780,700,68,390,"auto",true,false,true,1f)-68>=160)
        assertTrue(PetChatHeight.total(720,660,160,2000,"auto",false,false,false,1f)>auto)
    }
}
