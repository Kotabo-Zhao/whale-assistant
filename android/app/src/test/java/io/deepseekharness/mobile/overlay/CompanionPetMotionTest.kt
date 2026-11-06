package io.deepseekharness.mobile.overlay

import org.junit.Assert.*
import org.junit.Test

class CompanionPetMotionTest {
    @Test fun deviceTruthOverridesChatAndReducedMotionKeepsTheProp() {
        val actor=PetActing()
        actor.runningTasks=1
        actor.react(PetAction.CELEBRATE,0)
        actor.control("operating",100)
        actor.sample(100); val working=actor.sample(900)
        assertEquals(PetAction.CONTROL,actor.action)
        assertEquals(1f,working.phone,0f)
        assertEquals(2,actor.still().phoneMode)
        actor.react(PetAction.CELEBRATE,1000)
        actor.control("paused",1000);actor.sample(1000);actor.sample(1500)
        assertEquals(PetAction.PAUSED,actor.action)
        assertEquals(4,actor.still().phoneMode)
        actor.control("stopped",1600);actor.sample(1600);actor.sample(2100)
        assertEquals(0f,actor.still().phone,0f)
        assertEquals(PetAction.STOPPED,actor.action)
        actor.control("idle",2200);actor.sample(2200)
        assertEquals(PetAction.WORK,actor.action)
    }
    @Test fun briefTapRemainsReadableButNeverDelaysPause() {
        val actor=PetActing()
        actor.control("operating",100);actor.sample(100)
        actor.control("observing",130);actor.sample(200)
        assertEquals(PetAction.CONTROL,actor.action)
        actor.control("paused",210);actor.sample(210)
        assertEquals(PetAction.PAUSED,actor.action)
        actor.control("observing",800);actor.sample(800)
        assertEquals(PetAction.OBSERVE,actor.action)
    }
    @Test fun blinkOpensAndCloses() {
        assertEquals(0f, CompanionPetMotion.blink(0), .001f)
        assertEquals(1f, CompanionPetMotion.blink(140), .001f)
        assertEquals(0f, CompanionPetMotion.blink(280), .001f)
    }
    @Test fun jointsCreateDistinctSilhouettes() {
        val idle = PetActing.pose(PetAction.IDLE, 850)
        val accept = PetActing.pose(PetAction.ACCEPT, 850)
        val work = PetActing.pose(PetAction.WORK, 850)
        val celebrate = PetActing.pose(PetAction.CELEBRATE, 850)
        assertTrue(accept.leftElbow - idle.leftElbow > 60)
        assertTrue(celebrate.rightElbow < -65 && celebrate.leftElbow > 65)
        assertTrue(work.rightElbow != PetActing.pose(PetAction.WORK, 1100).rightElbow)
        assertTrue(idle.tailRoot != idle.tailTip && idle.leftFin != idle.rightFin)
        val skeleton = PetSkeleton(100f, 100f)
        skeleton.set(PetPose(leftElbow = 90f))
        val wrist = skeleton.bones[PetJoint.WRIST_L.ordinal]
        val forearm = skeleton.bones[PetJoint.ELBOW_L.ordinal]
        assertEquals(forearm.x(46f, 67f), wrist.x(46f, 67f), .001f)
        assertTrue(wrist.x(46f, 67f) < 40f)
        assertEquals(52f, skeleton.bones[PetJoint.WRIST_R.ordinal].x(52f, 67f), .001f)
    }
    @Test fun dispatchHoldsAcknowledgementThenReflectsLiveWorkerState() {
        val actor = PetActing()
        actor.runningTasks = 1
        actor.react(PetAction.ACCEPT, 100)
        actor.sample(800)
        actor.react(PetAction.CELEBRATE, 900)
        actor.react(PetAction.TOUCH, 950)
        actor.sample(1750); assertEquals(PetAction.ACCEPT, actor.action)
        actor.sample(1850); assertEquals(PetAction.CELEBRATE, actor.action)
        actor.sample(4500); assertEquals(PetAction.WORK, actor.action)
        actor.runningTasks = 0; actor.waitingTasks = 1
        actor.sample(12000); assertEquals(PetAction.ASK, actor.action)
        actor.waitingTasks = 0; actor.sample(13000); assertEquals(PetAction.IDLE, actor.action)
    }
}
