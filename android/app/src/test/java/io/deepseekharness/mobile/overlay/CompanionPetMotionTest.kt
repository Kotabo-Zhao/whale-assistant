package io.deepseekharness.mobile.overlay

import org.junit.Assert.*
import org.junit.Test

class CompanionPetMotionTest {
    @Test fun waitingForUserWinsOverBackgroundWorkButRealControlWinsOverBoth() {
        val actor = PetActing()
        actor.runningTasks = 3; actor.waitingTasks = 1
        assertEquals(PetAction.ASK, actor.sample(10).action)
        actor.chatThinking = true
        assertEquals(PetAction.THINK, actor.sample(20).action)
        actor.control("operating", 30)
        actor.react(PetAction.CELEBRATE, 40)
        assertEquals(PetAction.CONTROL, actor.sample(50).action)
        actor.control("paused", 60)
        assertEquals(PetAction.PAUSED, actor.sample(60).action)
    }
    @Test fun operationGraceEndsEvenDuringALongHeldCelAndPauseCancelsIt() {
        val actor = PetActing()
        actor.control("operating", 100); actor.sample(100)
        actor.control("observing", 120)
        assertEquals(PetAction.CONTROL, actor.sample(120).action)
        assertEquals(430L, actor.untilStateChange(120))
        assertEquals(PetAction.OBSERVE, actor.sample(550).action)
        actor.control("operating", 600); actor.control("paused", 620); actor.control("observing", 630)
        assertEquals(PetAction.OBSERVE, actor.sample(630).action)
    }
    @Test fun acknowledgementFinishesThenLatestReplyPlaysAndReturnsToWork() {
        val actor = PetActing(); actor.runningTasks = 1; actor.sample(0)
        actor.react(PetAction.ACCEPT, 100)
        actor.react(PetAction.SETBACK, 200); actor.react(PetAction.REPLY, 300)
        assertEquals(PetAction.ACCEPT, actor.sample(1000).action)
        assertEquals(1700L, actor.untilStateChange(1000))
        assertEquals(PetAction.REPLY, actor.sample(2700).action)
        assertEquals(PetAction.WORK, actor.sample(6100).action)
    }
    @Test fun newThinkingAndDragClearStaleReplies() {
        val actor = PetActing(); actor.sample(0); actor.react(PetAction.REPLY, 10)
        actor.chatThinking = true
        assertEquals(PetAction.THINK, actor.sample(20).action)
        actor.chatThinking = false; actor.react(PetAction.REPLY, 30); actor.dragging = true
        assertEquals(PetAction.DRAG, actor.sample(40).action)
        actor.dragging = false
        assertEquals(PetAction.IDLE, actor.sample(50).action)
    }
    @Test fun hiddenReactionDoesNotReplayButStableClipCanResumeAtItsHeldFrame() {
        val actor = PetActing(); val visible = actor.sample(0); actor.sample(1500)
        actor.react(PetAction.CELEBRATE, 2000)
        actor.resume(9000, visible.copy(elapsedMs = 1500))
        assertEquals(PetPose(PetAction.IDLE, 1500), actor.sample(9000))
        actor.control("paused", 10000); actor.resume(11000, visible)
        assertEquals(PetPose(PetAction.PAUSED, 0), actor.sample(11000))
    }
    @Test fun transitionsOnlySelectWholeIndependentCelsAndNeverBlendBodyParts() {
        for (a in PetAction.entries) for (b in PetAction.entries) {
            val first = PetActing.pose(a, 900)
            val next = PetActing.pose(b, 0)
            assertEquals(PetCels.actionClips.getValue(a), PetCels.sample(first).clip)
            assertEquals(PetCels.actionClips.getValue(b), PetCels.sample(next).clip)
        }
    }
    @Test fun reducedMotionUsesTheCurrentSemanticActionWithNoTimedFrames() {
        val actor = PetActing(); actor.control("failed", 0)
        val still = actor.still()
        assertTrue(still.reduced)
        assertEquals(PetAction.ERROR, still.action)
        assertEquals(Long.MAX_VALUE, PetCels.sample(still).nextMs)
        actor.control("idle"); actor.runningTasks = 1
        assertEquals("writing", PetCels.sample(actor.still()).clip)
    }
}
