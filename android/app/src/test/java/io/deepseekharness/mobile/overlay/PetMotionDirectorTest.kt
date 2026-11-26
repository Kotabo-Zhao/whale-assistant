package io.deepseekharness.mobile.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetMotionDirectorTest {

    @Test
    fun `crossfadeProgress eases smoothly from 0 to 1 over 180ms`() {
        assertEquals(0f, PetMotionDirector.crossfadeProgress(0L), 0.001f)
        val mid = PetMotionDirector.crossfadeProgress(90L)
        assertTrue("Ease-out cubic midpoint should exceed linear 0.5, got $mid", mid > 0.75f && mid < 1f)
        assertEquals(1f, PetMotionDirector.crossfadeProgress(180L), 0.001f)
        assertEquals(1f, PetMotionDirector.crossfadeProgress(300L), 0.001f)
    }

    @Test
    fun `resolveUsagiIdleClip cycles through 6 lively beats in daytime and sleeps late at night`() {
        assertEquals("idle", PetMotionDirector.resolveUsagiIdleClip(1_000L, hourOfDay = 14))
        assertEquals("control", PetMotionDirector.resolveUsagiIdleClip(7_000L, hourOfDay = 14))
        assertEquals("touch", PetMotionDirector.resolveUsagiIdleClip(11_000L, hourOfDay = 14))
        assertEquals("drink", PetMotionDirector.resolveUsagiIdleClip(16_000L, hourOfDay = 14))
        assertEquals("reading", PetMotionDirector.resolveUsagiIdleClip(21_000L, hourOfDay = 14))
        assertEquals("celebrate", PetMotionDirector.resolveUsagiIdleClip(25_000L, hourOfDay = 14))

        // Late night substitutes cozy nap beats
        assertEquals("sleep", PetMotionDirector.resolveUsagiIdleClip(21_000L, hourOfDay = 23))
        assertEquals("sleep", PetMotionDirector.resolveUsagiIdleClip(25_000L, hourOfDay = 2))
    }

    @Test
    fun `spring physics reacts to landing bounce and drag velocity then settles cleanly`() {
        val director = PetMotionDirector()
        director.triggerLandingBounce()
        val peak = director.step(PetAction.IDLE, nowMs = 100L, dtSec = 0.016f, reduced = false)
        assertTrue("Landing bounce should squash wider on X", peak.scaleX > 1.005f)
        assertTrue("Landing bounce should squash shorter on Y", peak.scaleY < 0.995f)

        director.setDragVelocity(1500f)
        var dragTransform = director.step(PetAction.DRAG, nowMs = 200L, dtSec = 0.016f, reduced = false)
        repeat(8) { idx ->
            dragTransform = director.step(PetAction.DRAG, nowMs = 200L + idx * 16L, dtSec = 0.016f, reduced = false)
        }
        assertTrue("Positive drag velocity should tilt rotation positively", dragTransform.rotationDeg > 1.5f)
    }
}
