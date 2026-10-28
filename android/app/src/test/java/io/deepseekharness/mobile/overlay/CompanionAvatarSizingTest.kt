package io.deepseekharness.mobile.overlay

import org.junit.Assert.*
import org.junit.Test

class CompanionAvatarSizingTest {
    @Test fun samplesPortraitForActualFitCenterBounds() {
        assertEquals(4, CompanionAvatarSizing.sample(1024, 1536, 264))
        assertEquals(2, CompanionAvatarSizing.sample(1024, 1536, 450))
        assertEquals(1, CompanionAvatarSizing.sample(1024, 1536, 1600))
        assertEquals(4, CompanionAvatarSizing.sample(1536, 1024, 264))
    }
    @Test fun rejectsInvalidBoundsAndAvoidsIntegerOverflow() {
        assertThrows(IllegalArgumentException::class.java) { CompanionAvatarSizing.sample(0, 100, 10) }
        assertThrows(IllegalArgumentException::class.java) { CompanionAvatarSizing.sample(100, 100, 0) }
        assertEquals(1, CompanionAvatarSizing.sample(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
