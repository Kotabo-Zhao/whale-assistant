package io.deepseekharness.mobile.overlay

import kotlin.math.PI
import kotlin.math.sin

internal enum class PetAction { IDLE, ACCEPT, WORK, ASK, CELEBRATE, SETBACK, TOUCH,
    OBSERVE, CONTROL, WAIT, PAUSED, STOPPED, ERROR }

/** Rotations are local to the named joint. Lift is measured in fractions of character height. */
internal data class PetPose(
    val chest: Float = 0f, val head: Float = 0f, val leftFin: Float = 0f, val rightFin: Float = 0f,
    val hair: Float = 0f, val tailRoot: Float = 0f, val tailTip: Float = 0f,
    val leftShoulder: Float = 0f, val rightShoulder: Float = 0f,
    val leftElbow: Float = 0f, val rightElbow: Float = 0f, val leftWrist: Float = 0f, val rightWrist: Float = 0f,
    val feet: Float = 0f, val lift: Float = 0f, val gaze: Float = 0f, val mouth: Float = 0f, val sheet: Float = 0f,
    val phone: Float = 0f, val phoneTap: Float = 0f, val phoneMode: Int = 0,
) {
    fun mix(b: PetPose, t: Float): PetPose {
        fun blend(a: Float, end: Float) = a + (end - a) * t
        return PetPose(blend(chest,b.chest), blend(head,b.head), blend(leftFin,b.leftFin), blend(rightFin,b.rightFin),
            blend(hair,b.hair), blend(tailRoot,b.tailRoot), blend(tailTip,b.tailTip),
            blend(leftShoulder,b.leftShoulder), blend(rightShoulder,b.rightShoulder), blend(leftElbow,b.leftElbow), blend(rightElbow,b.rightElbow),
            blend(leftWrist,b.leftWrist), blend(rightWrist,b.rightWrist), blend(feet,b.feet), blend(lift,b.lift), blend(gaze,b.gaze), blend(mouth,b.mouth), blend(sheet,b.sheet),
            blend(phone,b.phone), blend(phoneTap,b.phoneTap), b.phoneMode)
    }
}

/** Transient reactions interrupt a loop, then return to the real foreground/Worker state. */
internal class PetActing {
    var runningTasks = 0
    var waitingTasks = 0
    var chatThinking = false
    private var controlAction: PetAction? = null
    private var operationUntil = 0L
    fun control(phase: String, now: Long = 0) {
        if(phase=="operating") operationUntil=now+450
        controlAction = when (phase) {
            "observing" -> PetAction.OBSERVE
            "operating" -> PetAction.CONTROL
            "waiting" -> PetAction.WAIT
            "paused" -> PetAction.PAUSED
            "stopped" -> PetAction.STOPPED
            "failed" -> PetAction.ERROR
            else -> null
        }
        // Never celebrate an unrelated chat reply while the phone is controlled or paused.
        if (controlAction != null) { cue = null; afterAccept = null }
    }
    fun still(): PetPose = pose(controlAction ?: when {
        runningTasks > 0 || chatThinking -> PetAction.WORK
        waitingTasks > 0 -> PetAction.ASK
        else -> PetAction.IDLE
    }, 900)
    var action = PetAction.IDLE
        private set
    private var cue: PetAction? = null
    private var afterAccept: PetAction? = null
    private var cueAt = 0L
    private var entered = 0L
    private var from = PetPose()
    private var last = PetPose()
    fun react(value: PetAction, now: Long) {
        if (controlAction != null) return
        if (value == PetAction.IDLE || value == PetAction.WORK) return
        if (value == PetAction.TOUCH && cue != null && now - cueAt < duration(cue)) return
        if (cue == PetAction.ACCEPT && value != PetAction.ACCEPT && now - cueAt < duration(cue)) {
            afterAccept = value
            return
        }
        afterAccept = null
        cue = value; cueAt = now
        // Repeated submissions still receive a fresh acknowledgement gesture.
        from = last; entered = now; action = value
    }
    private fun duration(state: PetAction?) = when (state) {
        PetAction.ACCEPT -> 1700
        PetAction.ASK -> 4500
        PetAction.SETBACK -> 2700
        PetAction.CELEBRATE -> 2200
        PetAction.TOUCH -> 1050
        else -> 0
    }
    fun sample(now: Long, drag: Float = 0f): PetPose {
        if (cue != null && now - cueAt >= duration(cue)) {
            val pending = afterAccept
            val ended = cueAt + duration(cue)
            cue = null
            afterAccept = null
            if (pending != null && now - ended < duration(pending)) react(pending, ended)
        }
        val device = if(controlAction==PetAction.OBSERVE && now<operationUntil) PetAction.CONTROL else controlAction
        val next = device ?: cue ?: when {
            runningTasks > 0 || chatThinking -> PetAction.WORK
            waitingTasks > 0 -> PetAction.ASK
            else -> PetAction.IDLE
        }
        if (next != action) { from = last; entered = now; action = next }
        val elapsed = (now - entered).coerceAtLeast(0)
        val target = pose(action, elapsed, drag)
        val t = (elapsed / if (from.phone != target.phone) 420f else 190f).coerceIn(0f, 1f)
        last = if (t < 1f) from.mix(target, t * t * (3f - 2f * t)) else target
        return last
    }
    companion object {
        private fun wave(ms: Long, period: Double, phase: Double = 0.0) = sin(ms * 2 * PI / period + phase).toFloat()
        private fun pulse(ms: Long, start: Long, width: Long): Float {
            if (ms < start || ms > start + width) return 0f
            return sin((ms - start) * PI / width).toFloat()
        }
        fun pose(action: PetAction, ms: Long, drag: Float = 0f): PetPose {
            val sway = wave(ms, 3350.0)
            val tail = wave(ms, 2250.0)
            val lag = wave(ms, 2250.0, -.85)
            val d = drag.coerceIn(-1f, 1f)
            return when (action) {
                PetAction.IDLE -> PetPose(chest = 4f*sway, head = 7f*wave(ms,4200.0,.35) + d*10,
                    leftFin = 15f*wave(ms,2700.0,-.45), rightFin = -13f*wave(ms,2700.0,-1.1), hair = -8f*sway - d*13,
                    tailRoot = 19f*tail - d*16, tailTip = 27f*lag, leftShoulder = 6f*sway, rightShoulder = -5f*sway,
                    leftElbow = 12f + 9f*wave(ms,3350.0,.8), rightElbow = -9f + 8f*wave(ms,3350.0,-.6),
                    leftWrist = 10f*sway, rightWrist = -8f*lag, feet = 4f*sway,
                    lift = .009f*(1f+sway), gaze = wave(ms,5100.0))
                PetAction.ACCEPT -> {
                    val dip = pulse(ms,0,330); val up = pulse(ms,170,970); val salute = pulse(ms,100,1600)
                    PetPose(chest = -9f*salute + 4f*dip, head = -16f*salute + 9f*dip,
                        leftFin = 32f*up, rightFin = -36f*up, hair = 14f*up,
                        tailRoot = -26f*up, tailTip = -32f*pulse(ms,310,1050),
                        leftShoulder = 33f*salute, leftElbow = 122f*salute, leftWrist = 22f*wave(ms,430.0)*salute,
                        rightShoulder = -10f*up, rightElbow = -22f*up, rightWrist = -14f*up, feet = 15f*up,
                        lift = .062f*up - .018f*dip, gaze = -.85f*salute, mouth = .8f*up)
                }
                PetAction.WORK -> {
                    val beat = wave(ms,820.0); val tilt = wave(ms,3000.0,-.65)
                    PetPose(chest = 7f + 3f*beat, head = 10f + 5f*tilt,
                        leftFin = -9f + 8f*beat, rightFin = 17f + 6f*tilt, hair = -8f - 5f*beat,
                        tailRoot = 10f + 15f*wave(ms,1650.0), tailTip = 20f*wave(ms,1650.0,-1.05),
                        leftShoulder = 15f, leftElbow = 54f + 12f*tilt, leftWrist = -24f + 7f*beat,
                        rightShoulder = -13f + 5f*beat, rightElbow = -35f - 23f*beat, rightWrist = -19f*wave(ms,820.0,-.8),
                        feet = 6f*beat, lift = .007f*(1f+beat), gaze = .6f + .35f*tilt, sheet = 1f)
                }
                PetAction.ASK -> PetPose(chest = -6f + 2f*sway, head = -20f + 5f*sway, leftFin = -17f, rightFin = -28f,
                    hair = 11f, tailRoot = -14f + 12f*tail, tailTip = 13f*lag,
                    leftShoulder = 24f, leftElbow = 72f + 12f*sway, leftWrist = 24f*tail,
                    rightShoulder = -5f, rightElbow = -12f, rightWrist = 7f*sway, lift = .007f*sway, gaze = -.9f)
                PetAction.CELEBRATE, PetAction.TOUCH -> {
                    val hop = pulse(ms,80,650) + pulse(ms,810,600)
                    val liftArms = pulse(ms,0, if (action == PetAction.TOUCH) 1030 else 2100)
                    PetPose(chest = -6f*liftArms, head = 11f*wave(ms,710.0)*liftArms, leftFin = 33f*hop, rightFin = -32f*hop,
                        hair = 10f*wave(ms,710.0), tailRoot = 29f*wave(ms,840.0), tailTip = 34f*wave(ms,840.0,-1.0),
                        leftShoulder = 32f*liftArms, rightShoulder = -32f*liftArms, leftElbow = 118f*liftArms, rightElbow = -116f*liftArms,
                        leftWrist = 22f*lag, rightWrist = -22f*lag, feet = 18f*hop, lift = .07f*hop, mouth = hop)
                }
                PetAction.SETBACK -> PetPose(chest = 10f, head = 13f + 3f*sway, leftFin = -20f, rightFin = 20f, hair = -7f,
                    tailRoot = 18f, tailTip = 12f*lag, leftElbow = 25f, rightElbow = -38f + 4f*sway, lift = -.01f, gaze = -.6f)
                PetAction.OBSERVE, PetAction.CONTROL, PetAction.WAIT, PetAction.PAUSED, PetAction.ERROR -> {
                    val tapping = if (action == PetAction.CONTROL) (1f + wave(ms, 680.0)) * .5f else 0f
                    val paused = action == PetAction.PAUSED
                    val looking = action == PetAction.OBSERVE
                    val problem = action == PetAction.ERROR
                    PetPose(chest = if (paused) -4f else 3f + sway, head = when {
                        paused -> -12f; problem -> -18f + 3f*sway; looking -> 9f + 4f*wave(ms, 2400.0); else -> 6f + 2f*sway
                    }, leftFin = 5f*sway, rightFin = -6f*sway, hair = -3f*sway,
                        tailRoot = 8f*tail, tailTip = 12f*lag,
                        leftShoulder = -5f, leftElbow = if (paused) -22f else -53f, leftWrist = 6f,
                        rightShoulder = if (paused) -26f else 7f,
                        rightElbow = if (paused) -108f else 43f + 10f*tapping,
                        rightWrist = if (paused) -20f else -10f*tapping,
                        lift = .003f*sway, gaze = if (looking) .45f*wave(ms, 2400.0) else if (paused || problem) -.7f else .35f,
                        phone = 1f, phoneTap = tapping, phoneMode = when (action) {
                            PetAction.OBSERVE -> 1; PetAction.CONTROL -> 2; PetAction.WAIT -> 3
                            PetAction.PAUSED -> 4; else -> 6
                        })
                }
                PetAction.STOPPED -> PetPose(chest = -3f, head = -8f, leftFin = -8f, rightFin = 8f,
                    tailRoot = 5f*tail, tailTip = 6f*lag, leftElbow = 12f,
                    rightShoulder = -26f, rightElbow = -108f, rightWrist = -20f, phoneMode = 5)
            }
        }
    }
}
