package io.deepseekharness.mobile.overlay

import org.junit.Assert.*
import org.junit.Test

class PetPlaybackTest {
    private val exits = PetCels.exits
    private val returns = PetCels.clips.filterKeys { it in exits.values }
    private fun player() = PetPlayback(PetCels.clips,exits)

    @Test fun eventEndsByPlayingItsOwnReleaseBeforeTheExactIdleStart() {
        val player=player();val reply=PetCels.clips.getValue("reply")
        player.sample(PetPose(PetAction.REPLY,reply.duration-1),1000)
        val first=player.sample(PetPose(elapsedMs=0),1001)
        assertEquals("reply_return",first.clip);assertEquals(0,first.frame)
        val duration=returns.getValue(first.clip).duration
        val end=player.sample(PetPose(elapsedMs=duration-1),1000+duration)
        assertEquals("reply_return",end.clip);assertEquals(30,end.frame)
        val idle=player.sample(PetPose(elapsedMs=duration),1001+duration)
        assertEquals("idle",idle.clip);assertEquals(0,idle.frame)
        assertEquals(PetCels.clips.getValue("idle").holds[0].millis.toLong(),idle.nextMs)
    }

    @Test fun interruptedPageTurnFinishesInOrderWithoutWaitingOutLongReadingHolds() {
        val player=player()
        val first=player.sample(PetPose(PetAction.THINK,1300),1000)
        val sequence=mutableListOf<Int>()
        var now=1001L;var current=player.sample(PetPose(elapsedMs=0),now)
        while(current.clip=="reading") {
            sequence.add(current.frame);assertTrue(current.nextMs in 1..80)
            now+=current.nextMs;current=player.sample(PetPose(elapsedMs=now-1001),now)
            assertTrue("Return should not get stuck in a reading loop",now<4000)
        }
        assertEquals(first.frame,sequence.first())
        assertEquals(sequence.sorted(),sequence)
        assertEquals("reading_return",current.clip)
    }

    @Test fun controlAndNewMessagesInterruptTheReleaseImmediately() {
        val player=player();val clip=PetCels.clips.getValue("touch")
        player.sample(PetPose(PetAction.TOUCH,clip.duration-1),0)
        assertEquals("touch_return",player.sample(PetPose(),1).clip)
        assertEquals("control",player.sample(PetPose(PetAction.CONTROL),30).clip)
        assertEquals("reading",player.sample(PetPose(PetAction.THINK),40).clip)
    }

    @Test fun powerSavingFinishesTheRealGestureThenStopsIdleScheduling() {
        val player=player();val clip=PetCels.clips.getValue("reply")
        player.sample(PetPose(PetAction.REPLY,clip.duration-1),0,quietIdle=true)
        val release=player.sample(PetPose(),1,quietIdle=true)
        assertEquals("reply_return",release.clip);assertTrue(release.nextMs<Long.MAX_VALUE)
        val resting=player.sample(PetPose(elapsedMs=2000),2001,quietIdle=true)
        assertEquals("idle",resting.clip);assertEquals(Long.MAX_VALUE,resting.nextMs)
        assertEquals("idle",player.sample(PetPose(elapsedMs=17000),17001,quietIdle=true).clip)
    }

    @Test fun reducedMotionAndHiddenResetDoNotReplayOldReturnClips() {
        val player=player();val clip=PetCels.clips.getValue("reply")
        player.sample(PetPose(PetAction.REPLY,clip.duration-1),0)
        assertEquals("reply_return",player.sample(PetPose(),1).clip)
        val reduced=player.sample(PetPose(reduced=true),40)
        assertEquals("idle",reduced.clip);assertEquals(Long.MAX_VALUE,reduced.nextMs)
        player.reset()
        assertEquals("idle",player.sample(PetPose(),8000).clip)
    }

    @Test fun theAmbientDrinkAlsoPutsAwayItsMugBeforeIdle() {
        val player=player()
        player.sample(PetPose(elapsedMs=PetCels.DRINK_END-1),0)
        assertEquals("drink_return",player.sample(PetPose(elapsedMs=PetCels.DRINK_END),1).clip)
        assertEquals("idle",player.sample(PetPose(elapsedMs=PetCels.DRINK_END+2000),2001).clip)
        assertEquals("drink",player.sample(PetPose(elapsedMs=PetCels.IDLE_CYCLE+PetCels.DRINK_AT),20000).clip)
    }
}
