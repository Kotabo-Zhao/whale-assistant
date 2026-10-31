package io.deepseekharness.mobile.overlay

import org.junit.Assert.*
import org.junit.Test

class CompanionPetMotionTest {
    @Test fun blinkAndReplyAreSmoothAndReturnToRest() {
        assertEquals(0f, CompanionPetMotion.blink(0), 0.001f)
        assertEquals(1f, CompanionPetMotion.blink(140), 0.001f)
        assertEquals(0f, CompanionPetMotion.blink(280), 0.001f)
        val idle = CompanionPetMotion.pose(1200, 0f, -1)
        val thinking = CompanionPetMotion.pose(1200, 1f, -1)
        val reply = CompanionPetMotion.pose(1200, 0f, 450)
        assertNotEquals(idle.rotation, thinking.rotation)
        assertTrue(reply.lift > idle.lift)
        assertEquals(idle.lift, CompanionPetMotion.pose(1200, 0f, 1800).lift, 0.001f)
    }
    @Test fun armsMoveIndependentlyForThinkingAndReply() {
        val idle = CompanionPetMotion.arms(0, 0f, -1)
        val thinking = CompanionPetMotion.arms(0, 1f, -1)
        val reply = CompanionPetMotion.arms(0, 0f, 900)
        assertEquals(0f, idle.left, 0.001f)
        assertTrue(thinking.left < -8f && thinking.right > 1f)
        assertTrue(reply.left < -12f && reply.right > 12f)
        assertEquals(idle, CompanionPetMotion.arms(0, 0f, 1800))
    }
    @Test fun idleSwayLoopsAndTapReturnsToRest() {
        assertEquals(0f, CompanionPetMotion.sway(0f), 0.001f)
        assertEquals(0f, CompanionPetMotion.sway(1f), 0.001f)
        assertEquals(1f, CompanionPetMotion.sway(0.25f), 0.001f)
        assertEquals(0.9f, CompanionPetMotion.tapScale(150), 0.001f)
        assertEquals(1f, CompanionPetMotion.tapScale(300), 0.001f)
        assertEquals(1f, CompanionPetMotion.tapScale(-1), 0.001f)
    }
}
