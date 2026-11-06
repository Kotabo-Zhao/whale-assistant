package io.deepseekharness.mobile.overlay

import org.junit.Assert.*
import org.junit.Test

class CompanionPetMotionTest {
    @Test fun conversationDraggingAndControlHaveDifferentPrioritiesAndReturnToLiveState() {
        val actor=PetActing();actor.runningTasks=1;actor.chatThinking=true
        actor.sample(100);assertEquals(PetAction.THINK,actor.action)
        assertEquals(PetAccent.THOUGHT,actor.still().accent)
        actor.react(PetAction.REPLY,200);actor.chatThinking=false
        actor.sample(800);assertEquals(PetAction.REPLY,actor.action)
        actor.sample(2800);assertEquals(PetAction.WORK,actor.action)
        assertEquals(PetAccent.INK,actor.still().accent)
        actor.dragging=true;actor.sample(3000);assertEquals(PetAction.DRAG,actor.action)
        actor.control("paused",3100);actor.sample(3100);assertEquals(PetAction.PAUSED,actor.action)
        assertEquals(4,actor.still().phoneMode)
        actor.dragging=false;actor.control("idle",3200);actor.sample(3300);assertEquals(PetAction.WORK,actor.action)
        actor.runningTasks=0;actor.react(PetAction.NOTICE,3400);actor.sample(4000)
        assertEquals(PetAction.NOTICE,actor.action)
        actor.sample(7000);assertEquals(PetAction.IDLE,actor.action)
    }
    @Test fun stateAccentsAndFacialLayersStayFiniteAndDoNotLeakAcrossTransitions() {
        for(action in PetAction.entries) for(time in 0L..15000L step 75) {
            val p=PetActing.pose(action,time,.8f)
            assertTrue("$action $time",p.accentAlpha in 0f..1f && p.accentPhase in 0f..1f)
            assertTrue(p.eyeClose in 0f..1f && p.wink in 0f..1f && p.phoneTap in 0f..1f)
            assertTrue(p.lift in -.04f.. .10f)
            val blend=p.mix(PetPose(),.6f)
            assertTrue(blend.accentAlpha in 0f..1f)
        }
        val touch=PetActing.pose(PetAction.TOUCH,410)
        val celebrate=PetActing.pose(PetAction.CELEBRATE,410)
        assertTrue(touch.wink>0f);assertEquals(PetAccent.HEART,touch.accent)
        assertEquals(PetAccent.STARS,celebrate.accent)
        assertTrue(touch.rightElbow>celebrate.rightElbow+40)
        val actor=PetActing();actor.control("failed",0)
        val still=actor.still();repeat(10){assertEquals(still,actor.still())}
        assertEquals(PetAccent.ALERT,still.accent)
    }
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
