package io.deepseekharness.mobile.overlay

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PetInteractionTest {
    private fun contact(p:PetPose,right:Boolean,x:Float,y:Float) {
        val rig=PetSkeleton(1222f,1287f);rig.set(p)
        val hand=rig.bones[if(right) PetJoint.WRIST_R.ordinal else PetJoint.WRIST_L.ordinal]
        val chest=rig.bones[PetJoint.CHEST.ordinal]
        val hx=(if(right) .513f else .478f)*1222f;val hy=(if(right) .702f else .705f)*1287f
        val tx=PetInteraction.pointX(p,x,y)*1222f;val ty=PetInteraction.pointY(p,x,y)*1287f
        assertEquals("grip x right=$right at $p",chest.x(tx,ty),hand.x(hx,hy),2f)
        assertEquals("grip y right=$right at $p",chest.y(tx,ty),hand.y(hx,hy),2f)
    }
    @Test fun cupHasTwoFixedGripsAndReachesMouthBeforeReturningToLap() {
        for(t in 0L..24000 step 100) {
            val p=PetActing.pose(PetAction.IDLE,t)
            contact(p,false,-.075f,-.04f);contact(p,true,.035f,.01f)
        }
        val rest=PetActing.pose(PetAction.IDLE,0);val drink=PetActing.pose(PetAction.IDLE,9000)
        assertTrue(rest.holdY-drink.holdY>.14f)
        assertTrue(abs(rest.leftShoulder-drink.leftShoulder)>25f)
        val rig=PetSkeleton(1222f,1287f);rig.set(drink)
        val chest=rig.bones[PetJoint.CHEST.ordinal];val head=rig.bones[PetJoint.HEAD.ordinal]
        val x=PetInteraction.pointX(drink,0f,-.106f)*1222f
        val y=PetInteraction.pointY(drink,0f,-.106f)*1287f
        assertEquals(head.x(.484f*1222,.443f*1287),chest.x(x,y),.1f)
        assertEquals(head.y(.484f*1222,.443f*1287),chest.y(x,y),.1f)
        assertEquals(rest.holdY,PetActing.pose(PetAction.IDLE,14000).holdY,0f)
    }
    @Test fun readingHandFollowsTurningPageAndPhoneFingerReachesScreen() {
        for(t in 0L..9000 step 75) {
            val p=PetActing.pose(PetAction.THINK,t)
            contact(p,false,-.025f,.01f)
            contact(p,true,PetInteraction.pageHandX(p.pageTurn),PetInteraction.pageHandY(p.pageTurn))
        }
        for(t in 0L..1900 step 25) {
            val p=PetActing.pose(PetAction.CONTROL,t)
            contact(p,false,-.035f,.003f)
            contact(p,true,.015f*.88f,-.058f*.88f-.028f*(1f-p.phoneTap))
        }
        assertEquals(1f,PetActing.pose(PetAction.CONTROL,355).phoneTap,.001f)
    }
    @Test fun interruptionBlendsDoNotDetachHandsFromBook() {
        val actor=PetActing();actor.sample(0);actor.sample(2000)
        actor.chatThinking=true
        for(t in 2100L..3000 step 20) {
            val p=actor.sample(t)
            if(p.prop==PetProp.BOOK && p.propAmount>.01f) contact(p,false,-.025f,.01f)
        }
        actor.control("operating",3100)
        for(t in 3100L..4000 step 20) {
            val p=actor.sample(t)
            assertFalse("two objects cannot occupy one hand",p.phone>.01f && p.propAmount>.01f)
            if(p.phone>.01f) contact(p,false,-.035f,.003f)
        }
    }
    @Test fun pendingQuestionWinsOverOtherRunningWorkAndResumeDiscardsOldReactions() {
        val actor=PetActing();actor.runningTasks=3;actor.waitingTasks=1;actor.sample(10)
        assertEquals(PetAction.ASK,actor.action)
        actor.react(PetAction.CELEBRATE,100);val frame=actor.sample(800)
        actor.resume(60000,frame);val first=actor.sample(60000)
        assertEquals(frame,first);assertEquals(PetAction.ASK,actor.action)
        actor.sample(61000);assertEquals(PetAction.ASK,actor.action)
    }
    @Test fun blinkHasFastCloseHoldSlowOpenAndDoesNotFightIntentionalLids() {
        assertTrue(CompanionPetMotion.blink(60)>.85f)
        assertEquals(1f,CompanionPetMotion.blink(90),0f)
        assertTrue(CompanionPetMotion.blink(180) in .1f.. .6f)
        assertEquals(0f,CompanionPetMotion.blink(240),0f)
        val blink=PetBlinkClock();blink.reset(0)
        assertEquals(0f,blink.sample(2400,true),0f)
        assertEquals(0f,blink.sample(2500,false),0f)
        assertFalse(blink.blinking(2500))
        assertEquals(0f,blink.sample(90000,false),0f)
    }
    @Test fun quietRefreshBudgetAndFastFeedbackAreIndependent() {
        assertEquals(200L,PetFramePolicy.delay(PetAction.IDLE,15000,false,false,false))
        assertEquals(33L,PetFramePolicy.delay(PetAction.IDLE,8500,false,false,false))
        assertEquals(33L,PetFramePolicy.delay(PetAction.IDLE,15000,false,true,false))
        assertEquals(33L,PetFramePolicy.delay(PetAction.PAUSED,0,true,false,false))
        assertEquals(200L,PetFramePolicy.delay(PetAction.PAUSED,5000,false,false,false))
        assertEquals(250L,PetFramePolicy.delay(PetAction.IDLE,15000,false,false,true))
    }
    @Test fun aNewTurnOrDragCannotReplayAnOldReply() {
        val actor=PetActing();actor.sample(0);actor.react(PetAction.REPLY,100);actor.sample(700)
        actor.chatThinking=true;actor.sample(710);assertEquals(PetAction.THINK,actor.action)
        actor.chatThinking=false;actor.react(PetAction.NOTICE,800);actor.sample(900)
        actor.dragging=true;actor.sample(1000);assertEquals(PetAction.DRAG,actor.action)
        actor.dragging=false;actor.sample(1200);assertEquals(PetAction.IDLE,actor.action)
    }
    @Test fun otherHeldObjectsStayInTheGraspAndWritingLoopClosesWithoutATeleport() {
        for(action in listOf(PetAction.ACCEPT,PetAction.ASK,PetAction.REPLY,PetAction.CELEBRATE,PetAction.SETBACK,PetAction.NOTICE,PetAction.STOPPED)) {
            for(t in 0L..2200 step 100) {
                val p=PetActing.pose(action,t)
                contact(p,action==PetAction.ACCEPT || action==PetAction.STOPPED,0f,0f)
            }
        }
        val a=PetActing.pose(PetAction.WORK,3599);val b=PetActing.pose(PetAction.WORK,3600)
        assertEquals(a.rightShoulder,b.rightShoulder,.2f)
        assertEquals(a.rightElbow,b.rightElbow,.2f)
    }
    @Test fun changingObjectsCannotTeleportHandsAtTheHalfwaySwitch() {
        val rig=PetSkeleton(1222f,1287f)
        for(a in PetAction.entries) for(b in PetAction.entries) {
            val from=PetActing.pose(a,if(a==PetAction.IDLE) 9000 else 900)
            var last:FloatArray?=null
            for(tick in 0..30) {
                val t=tick/30f
                val p=PetInteraction.constrain(from.mix(PetActing.pose(b,tick*20L),t*t*(3-2*t)))
                rig.set(p)
                val l=rig.bones[PetJoint.WRIST_L.ordinal];val r=rig.bones[PetJoint.WRIST_R.ordinal]
                val now=floatArrayOf(l.x(.478f*1222,.705f*1287),l.y(.478f*1222,.705f*1287),r.x(.513f*1222,.702f*1287),r.y(.513f*1222,.702f*1287))
                last?.let{old -> assertTrue("hand jumped $a -> $b at $tick: ${now.indices.maxOf{abs(now[it]-old[it])}}",now.indices.all{abs(now[it]-old[it])<52f}) }
                last=now
            }
        }
    }
}
