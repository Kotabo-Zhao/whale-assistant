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

    @Test fun aNewRequestWhilePuttingSomethingAwayKeepsTheVisibleFrameAndUsesOnlyTheLatestEntry() {
        val player=player();val clip=PetCels.clips.getValue("touch")
        player.sample(PetPose(PetAction.TOUCH,clip.duration-1),0)
        assertEquals("touch_return",player.sample(PetPose(),1).clip)
        assertEquals("touch_return",player.sample(PetPose(PetAction.CONTROL),30).clip)
        assertEquals("touch_return",player.sample(PetPose(PetAction.THINK),40).clip)
        val seen = (41L..1000).map { t -> player.sample(PetPose(PetAction.THINK,t-40),t).clip }.distinct()
        assertEquals(listOf("touch_return","reading_enter","reading"),seen)
    }

    @Test fun allPrimaryStatesCanReachEachOtherWithoutReplacingTheFirstVisiblePose() {
        fun pose(name: String, elapsed: Long) = if (name == "drink") PetPose(elapsedMs=PetCels.DRINK_AT+elapsed)
            else PetPose(PetCels.actionClips.entries.single { it.value == name }.key,elapsed)
        for (source in PetCels.primaryClips.keys) for (dest in PetCels.primaryClips.keys) {
            if (source == dest) continue
            val p = player()
            val before = p.sample(pose(source,720), 1000)
            val first = p.sample(pose(dest,0), 1001)
            assertEquals("$source -> $dest starts on another sheet", source, first.clip)
            assertEquals("$source -> $dest starts on another cel", before.frame, first.frame)
            var now = 1001L
            var cel = first
            val seen = mutableSetOf<String>()
            while (cel.clip != dest && now < 5000) {
                seen.add(cel.clip)
                now += cel.nextMs
                cel = p.sample(pose(dest,now - 1001), now)
            }
            assertEquals("$source -> $dest",dest,cel.clip)
            assertEquals("target starts at its own first drawing",0,cel.frame)
            if (dest != "idle") {
                assertTrue(PetCels.entries.getValue(dest) in seen)
                assertTrue("handoff took ${now-1001}ms",now-1001 <= 1250)
            }
        }
    }

    @Test fun repeatingAReactionOrParkingAMovingIdleDoesNotResetItsDrawing() {
        val p = player()
        val old = p.sample(PetPose(PetAction.REPLY,720),720)
        assertTrue(old.frame > 0)
        val restart = p.sample(PetPose(PetAction.REPLY,0),721)
        assertEquals(old.clip,restart.clip)
        assertEquals(old.frame,restart.frame)
        val repeated = p.sample(PetPose(PetAction.REPLY,420),1141)
        assertTrue(repeated.clip == "reply")
        p.reset()
        val blink = p.sample(PetPose(elapsedMs=1640),2000)
        val parking = p.sample(PetPose(elapsedMs=1641),2001,quietIdle=true)
        assertEquals(blink.frame,parking.frame)
        assertEquals(Long.MAX_VALUE,p.sample(PetPose(elapsedMs=2400),2760,quietIdle=true).nextMs)
    }

    @Test fun aColdOrSupersededAtlasNeverAdvancesTheVisualClockInTheBackground() {
        val p = player()
        val idle = p.sample(PetPose(), 0)
        var cel = p.sample(PetPose(PetAction.WORK), 10, displayed=idle) { it == "idle" }
        cel = p.sample(PetPose(PetAction.WORK,28),38, displayed=cel) { it == "idle" }
        assertEquals("idle",cel.clip)
        assertEquals(Long.MAX_VALUE,cel.nextMs)
        cel = p.sample(PetPose(PetAction.WORK,850),860, displayed=cel)
        assertEquals("writing_enter",cel.clip); assertEquals(0,cel.frame)
        val interrupted = p.sample(PetPose(PetAction.CONTROL),861, displayed=cel)
        assertEquals(cel.clip,interrupted.clip); assertEquals(cel.frame,interrupted.frame)
        val seen=(862L..1800).map { p.sample(PetPose(PetAction.CONTROL,it-861),it).clip }.distinct()
        assertEquals(listOf("writing_enter","control_enter","control"),seen)
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
        val at = PetCels.IDLE_CYCLE + PetCels.DRINK_AT
        var t = 20000L
        var cel = player.sample(PetPose(elapsedMs=at),t)
        val seen = mutableSetOf<String>()
        while (cel.clip != "drink" && t < 21000) {
            seen.add(cel.clip)
            t += cel.nextMs
            cel = player.sample(PetPose(elapsedMs=at+t-20000),t)
        }
        assertTrue("drink_enter" in seen)
        assertEquals("drink",cel.clip)
        assertEquals(0,cel.frame)
    }
}
