package io.deepseekharness.mobile.overlay

import org.junit.Assert.*
import org.junit.Test

class PetCelsTest {
    @Test fun everyStateHasItsOwnDrawingAndThereIsASeparateOccasionalDrink() {
        assertEquals(PetAction.entries.toSet(), PetCels.actionClips.keys)
        assertEquals(17, PetCels.actionClips.values.toSet().size)
        assertEquals(18, PetCels.primaryClips.size)
        assertEquals(52, PetCels.clips.size)
        assertEquals(PetCels.exits.keys, PetCels.entries.keys)
        assertEquals(PetCels.primaryClips.keys - "idle", PetCels.exits.keys)
        assertTrue("drink" in PetCels.clips)
    }
    @Test fun everyFrameBoundaryHasAnExactPositiveWakeupAndValidAtlasCell() {
        for (clip in PetCels.clips.values) {
            var t = 0L
            while (t < clip.duration * 4) {
                val sample = clip.sample(t)
                assertTrue(sample.frame in 0 until clip.cells)
                assertTrue(sample.nextMs > 0)
                val held = clip.sample(t + sample.nextMs - 1)
                assertEquals(sample.frame, held.frame)
                assertEquals(1L, held.nextMs)
                t += sample.nextMs
            }
        }
    }
    @Test fun idleSleepsDuringHoldsAndDrinksOnlyInItsAuthoredWindow() {
        assertEquals("idle", PetCels.sample(PetPose(elapsedMs = 15999)).clip)
        assertEquals(1L, PetCels.sample(PetPose(elapsedMs = 15999)).nextMs)
        assertEquals("drink", PetCels.sample(PetPose(elapsedMs = 16000)).clip)
        assertEquals("drink", PetCels.sample(PetPose(elapsedMs = 20499)).clip)
        assertEquals("idle", PetCels.sample(PetPose(elapsedMs = 20500)).clip)
        assertEquals("idle", PetCels.sample(PetPose(elapsedMs = 24000)).clip)
        assertTrue(PetCels.sample(PetPose()).nextMs >= 1000)
        var t = 0L; var updates = 0
        while (t < 24000) { t += PetCels.sample(PetPose(elapsedMs = t)).nextMs; updates++ }
        assertTrue("Idle should not retain a fixed 5/30/60 FPS tick: $updates", updates < 110)
    }
    @Test fun pauseAndStopDoNotRepeatedlyPerformTheirEntryGesture() {
        for (name in listOf("paused", "stopped", "error")) {
            val clip = PetCels.clips.getValue(name)
            val allowed = clip.holds.drop(clip.loopFrom).map { it.frame }.toSet()
            for (t in clip.duration..clip.duration * 5 step 37)
                assertTrue(clip.sample(t).frame in allowed)
        }
    }
    @Test fun allReactionCelsFinishBeforeDirectorEndsTheirEvent() {
        for (action in listOf(PetAction.ACCEPT, PetAction.CELEBRATE, PetAction.SETBACK,
            PetAction.TOUCH, PetAction.REPLY, PetAction.NOTICE)) {
            val clip = PetCels.clips.getValue(PetCels.actionClips.getValue(action))
            assertEquals(action.name, PetActing.duration(action), clip.duration)
        }
    }
    @Test fun invalidTimingsCannotMakeAnInfiniteSchedulingLoop() {
        for (holds in listOf(emptyList(), listOf(PetCelHold(8,100)), listOf(PetCelHold(0,0)))) {
            assertThrows(IllegalArgumentException::class.java) { PetCelClip("bad", holds) }
        }
    }
}
