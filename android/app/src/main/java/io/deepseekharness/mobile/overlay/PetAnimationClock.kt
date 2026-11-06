package io.deepseekharness.mobile.overlay

/** One clock for blinks and acting. No independent timer can reopen an intentional squint. */
internal class PetBlinkClock {
    private var nextAt=Long.MAX_VALUE
    private var startedAt=-10000L
    private var index=0
    fun reset(now:Long) { nextAt=now+2400;startedAt=now-10000 }
    fun sample(now:Long,eyesClosed:Boolean):Float {
        if(eyesClosed) { startedAt=now-10000;nextAt=now+1800;return 0f }
        if(now>=nextAt) {
            startedAt=now
            // Repeatable irregular timing, without allocations or a separate scheduled job.
            nextAt=now+when(index++%5){0->4300;1->5700;2->3800;3->6100;else->4900}
        }
        return CompanionPetMotion.blink(now-startedAt)
    }
    fun blinking(now:Long)=now-startedAt in 0 until CompanionPetMotion.BLINK_MS
    fun untilNext(now:Long)=(nextAt-now).coerceAtLeast(1)
}

internal object PetFramePolicy {
    fun delay(action:PetAction,elapsed:Long,transitioning:Boolean,blink:Boolean,powerSave:Boolean):Long {
        val active=transitioning || blink || action==PetAction.DRAG ||
            action==PetAction.ACCEPT || action==PetAction.CELEBRATE || action==PetAction.TOUCH ||
            action==PetAction.REPLY || action==PetAction.NOTICE || action==PetAction.SETBACK ||
            (action==PetAction.IDLE && PetInteraction.sip(elapsed)>.001f) ||
            (action==PetAction.THINK && PetInteraction.page(elapsed)>.001f)
        return when {
            active -> if(powerSave) 66L else 33L
            powerSave -> 250L
            action==PetAction.CONTROL || action==PetAction.WORK -> 50L
            action==PetAction.OBSERVE || action==PetAction.THINK || action==PetAction.ASK -> 83L
            action==PetAction.IDLE -> 200L
            else -> 200L
        }
    }
}
