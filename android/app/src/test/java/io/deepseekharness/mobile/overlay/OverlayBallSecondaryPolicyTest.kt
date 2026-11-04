package io.deepseekharness.mobile.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayBallSecondaryPolicyTest {
    private fun band(x: Int, y: Int, screen: Int = 900, menu: Int = 200) =
        OverlayBallSecondaryPolicy.menuBand(x, y, 60, 60, menu, screen, top = 24, bottom = 456, gap = 8)

    @Test fun leftAndRightEdgesKeepMenuBesidePet() {
        assertEquals(68, band(0, 90).x)
        assertEquals(632, band(840, 90).x)
        assertEquals(90, band(840, 90).yFor(200))
    }

    @Test fun verticalEdgesStayInsideSystemBars() {
        assertEquals(24, band(0, 0).yFor(200))
        assertEquals(256, band(0, 430).yFor(200))
        assertEquals(432, band(840, 430).maxHeight)
    }

    @Test fun narrowScreenUsesSpaceAboveAndScrolls() {
        val area = band(100, 320, screen = 264, menu = 248)
        assertEquals(8, area.x)
        assertEquals(288, area.maxHeight)
        assertEquals(24, area.yFor(288))
        assertEquals(212, area.yFor(100))
    }

    @Test fun narrowScreenUsesSpaceBelowAndScrolls() {
        val area = band(100, 36, screen = 264, menu = 248)
        assertEquals(104, area.yFor(250))
        assertEquals(352, area.maxHeight)
    }

    @Test fun petTallerThanSafeBandStillLeavesMenuReachable() {
        val area = OverlayBallSecondaryPolicy.menuBand(0, 0, 60, 60, 60, 64, 10, 40, 8)
        assertEquals(2, area.x)
        assertEquals(10, area.yFor(30))
        assertEquals(30, area.maxHeight)
    }

    @Test fun accessibilityCanOnlyDisableAnActiveService() {
        assertEquals(OverlayBallSecondaryPolicy.AccessibilityAction.ALREADY_OFF, OverlayBallSecondaryPolicy.accessibilityState(false))
        assertEquals(OverlayBallSecondaryPolicy.AccessibilityAction.DISABLE_SELF, OverlayBallSecondaryPolicy.accessibilityState(true))
    }
}
