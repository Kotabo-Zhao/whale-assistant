package io.deepseekharness.mobile.overlay

internal enum class PetAction { IDLE, ACCEPT, WORK, ASK, CELEBRATE, SETBACK, TOUCH,
    OBSERVE, CONTROL, WAIT, PAUSED, STOPPED, ERROR, THINK, REPLY, NOTICE, DRAG }

/** A complete authored cel, never a set of shared bone angles or detached facial layers. */
internal data class PetPose(val action: PetAction = PetAction.IDLE, val elapsedMs: Long = 0, val reduced: Boolean = false)

/** Business state chooses a clip. The illustrator owns every body, hand, face and prop in it. */
internal class PetActing {
    var runningTasks = 0
    var waitingTasks = 0
    var chatThinking = false
        set(value) {
            if (value && !field && cue != PetAction.ACCEPT) { cue = null; afterAccept = null }
            field = value
        }
    var dragging = false
        set(value) {
            if (value && !field) { cue = null; afterAccept = null }
            field = value
        }
    var action = PetAction.IDLE
        private set
    private var controlAction: PetAction? = null
    private var operationUntil = 0L
    private var cue: PetAction? = null
    private var afterAccept: PetAction? = null
    private var cueAt = 0L
    private var entered = 0L
    private var initialized = false
    val actionElapsed: Long get() = last.elapsedMs
    private var last = PetPose()

    fun control(phase: String, now: Long = 0) {
        if (phase == "operating") operationUntil = now + 450
        else if (phase != "observing") operationUntil = 0L
        controlAction = when (phase) {
            "observing" -> PetAction.OBSERVE
            "operating" -> PetAction.CONTROL
            "waiting" -> PetAction.WAIT
            "paused" -> PetAction.PAUSED
            "stopped" -> PetAction.STOPPED
            "failed" -> PetAction.ERROR
            else -> null
        }
        if (controlAction != null) { cue = null; afterAccept = null }
    }

    private fun restingAction() = when {
        dragging -> PetAction.DRAG
        chatThinking -> PetAction.THINK
        waitingTasks > 0 -> PetAction.ASK
        runningTasks > 0 -> PetAction.WORK
        else -> PetAction.IDLE
    }

    fun still() = PetPose(controlAction ?: restingAction(), reduced = true)

    fun resume(now: Long, visible: PetPose) {
        cue = null; afterAccept = null
        action = controlAction ?: restingAction()
        entered = now - if (visible.action == action && !visible.reduced) visible.elapsedMs else 0
        initialized = true
    }

    fun react(value: PetAction, now: Long) {
        if (controlAction != null || duration(value) == 0L) return
        if ((value == PetAction.TOUCH || value == PetAction.NOTICE) && cue != null && now - cueAt < duration(cue)) return
        if (cue == PetAction.ACCEPT && value != PetAction.ACCEPT && now - cueAt < duration(cue)) {
            afterAccept = value
            return
        }
        afterAccept = null
        cue = value; cueAt = now
        entered = now; action = value; initialized = true
    }

    fun sample(now: Long): PetPose {
        if (!initialized) { entered = now; initialized = true }
        if (cue != null && now - cueAt >= duration(cue)) {
            val pending = afterAccept
            val ended = cueAt + duration(cue)
            cue = null; afterAccept = null
            if (pending != null && now - ended < duration(pending)) react(pending, ended)
        }
        val device = if (controlAction == PetAction.OBSERVE && now < operationUntil) PetAction.CONTROL else controlAction
        val next = device ?: if (dragging) PetAction.DRAG else cue ?: restingAction()
        if (next != action) { entered = now; action = next }
        last = PetPose(action, (now - entered).coerceAtLeast(0))
        return last
    }

    /** A long still cel must not postpone a completed reaction or a real control-state change. */
    fun untilStateChange(now: Long): Long {
        var delay = cue?.let { (cueAt + duration(it) - now).coerceAtLeast(1) } ?: Long.MAX_VALUE
        if (controlAction == PetAction.OBSERVE && now < operationUntil) delay = minOf(delay, operationUntil - now)
        return delay
    }

    companion object {
        fun pose(action: PetAction, elapsed: Long) = PetPose(action, elapsed.coerceAtLeast(0))
        fun duration(action: PetAction?): Long = when (action) {
            PetAction.ACCEPT -> 2600
            PetAction.ASK -> 4500
            PetAction.SETBACK -> 3200
            PetAction.CELEBRATE -> 3600
            PetAction.TOUCH -> 2400
            PetAction.REPLY -> 3400
            PetAction.NOTICE -> 4000
            else -> 0
        }
    }
}
