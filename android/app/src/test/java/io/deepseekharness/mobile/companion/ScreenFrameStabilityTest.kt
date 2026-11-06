package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test

class ScreenFrameStabilityTest {
    @Test fun cursorNoiseIsToleratedButMenuOrKeyboardTransitionIsNot() {
        val before=IntArray(1000){0xff202020.toInt()}
        val cursor=before.copyOf().apply{this[40]=0xffffffff.toInt()}
        assertTrue(ScreenFrameStability.similar(before,cursor))
        val menu=before.copyOf().apply{for(i in 30..100) this[i]=0xffe0e0e0.toInt()}
        assertFalse(ScreenFrameStability.similar(before,menu))
        assertFalse(ScreenFrameStability.similar(before,IntArray(4)))
        assertTrue(ScreenFrameStability.similar(menu,menu.copyOf()))
        val sample=ScreenFrameStability.sample(2,3){x,y->assertTrue(x in 0..1 && y in 0..2);42}
        assertTrue(sample.all{it==42})
    }
}
