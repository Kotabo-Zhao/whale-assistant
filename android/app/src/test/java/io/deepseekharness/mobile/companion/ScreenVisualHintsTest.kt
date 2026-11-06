package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test

class ScreenVisualHintsTest {
    @Test fun identifiesOnlyAThinLocalUnderlineBelowTheLabel() {
        val label=ScreenVisualHints.Box(420,30,460,52)
        val line=ScreenVisualHints.underline(600,400,label){x,y->if(x in 370..520 && y in 69..72) 0xffffff else 0x111111}
        assertEquals(ScreenVisualHints.Box(370,69,521,73),line)
        assertNull(ScreenVisualHints.underline(600,400,label){_,y->if(y in 69..72) 0xffffff else 0x111111})
        assertNull(ScreenVisualHints.underline(600,400,label){x,y->if(x in 370..520 && y in 69..100) 0xffffff else 0x111111})
    }
    @Test fun aDifferentColumnOrOrdinaryPageTextIsNotReportedAsSelected() {
        val label=ScreenVisualHints.Box(80,30,120,52)
        assertNull(ScreenVisualHints.underline(600,400,label){x,y->if(x in 370..520 && y in 69..72) 0xffffff else 0x111111})
        assertNull(ScreenVisualHints.underline(600,400,ScreenVisualHints.Box(80,180,120,202)){_,_->0xffffff})
    }
}
