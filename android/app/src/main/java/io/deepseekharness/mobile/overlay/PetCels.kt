package io.deepseekharness.mobile.overlay

internal data class PetCelHold(val frame: Int, val millis: Int)
internal data class PetCelSample(val clip: String, val frame: Int, val nextMs: Long)

internal class PetCelClip(
    val name: String, val holds: List<PetCelHold>, val still: Int = 0, val loopFrom: Int = 0,
) {
    val duration = holds.sumOf { it.millis }.toLong()
    private val loopDuration = holds.drop(loopFrom).sumOf { it.millis }.toLong()
    init {
        require(holds.isNotEmpty() && holds.all { it.frame in 0..7 && it.millis > 0 })
        require(still in 0..7 && loopFrom in holds.indices)
    }
    fun sample(elapsed: Long): PetCelSample {
        var t = elapsed.coerceAtLeast(0)
        var start = 0
        if (t >= duration) { t = (t - duration) % loopDuration; start = loopFrom }
        for (i in start until holds.size) {
            val hold = holds[i]
            if (t < hold.millis) return PetCelSample(name, hold.frame, hold.millis - t)
            t -= hold.millis
        }
        error("Invalid cel timing")
    }
}

/** Each name is an independently drawn, complete eight-cel asset. No shared skeleton or eye overlay. */
internal object PetCels {
    const val CELL = 320
    const val COLUMNS = 4
    const val ROWS = 2
    const val IDLE_CYCLE = 24_000L
    const val DRINK_AT = 16_000L
    const val DRINK_END = 20_500L
    private fun clip(name: String, vararg millis: Int, still: Int = 0, loopFrom: Int = 0) =
        PetCelClip(name, millis.mapIndexed { i, ms -> PetCelHold(i, ms) }, still, loopFrom)

    val clips = listOf(
        clip("idle", 1600, 70, 80, 110, 1800, 450, 90, 1000),
        clip("drink", 1000, 250, 250, 300, 650, 350, 400, 1300),
        clip("reading", 3000, 180, 200, 240, 240, 240, 350, 2200),
        clip("writing", 600, 180, 200, 220, 700, 140, 200, 900),
        clip("control", 400, 120, 120, 160, 120, 300, 100, 600),
        clip("accept", 120, 160, 200, 220, 200, 220, 200, 380, still = 2),
        PetCelClip("ask", listOf(PetCelHold(2,900), PetCelHold(3,450), PetCelHold(4,120),
            PetCelHold(5,1700), PetCelHold(6,1330)), still = 3),
        clip("celebrate", 180, 180, 220, 400, 220, 220, 300, 480, still = 7),
        clip("setback", 300, 250, 400, 300, 300, 350, 300, 500, still = 7),
        clip("touch", 90, 110, 140, 180, 170, 140, 100, 120, still = 6),
        clip("observe", 1600, 350, 1400, 100, 1600, 600, 350, 1300),
        clip("wait", 2000, 500, 110, 1800, 700, 600, 450, 1600),
        clip("paused", 160, 180, 200, 1800, 110, 1300, 450, 1600, still = 3, loopFrom = 3),
        clip("stopped", 200, 220, 300, 250, 300, 1000, 1200, 1800, still = 7, loopFrom = 5),
        clip("error", 400, 250, 350, 500, 400, 110, 1200, 1800, still = 6, loopFrom = 6),
        clip("reply", 150, 230, 330, 260, 350, 120, 360, 600, still = 7),
        clip("notice", 300, 280, 400, 160, 350, 700, 450, 760, still = 5),
        clip("drag", 220, 220, 300, 110, 300, 300, 300, 300, still = 0),
    ).associateBy { it.name }
    val actionClips = mapOf(
        PetAction.IDLE to "idle", PetAction.THINK to "reading", PetAction.WORK to "writing",
        PetAction.CONTROL to "control", PetAction.ACCEPT to "accept", PetAction.ASK to "ask",
        PetAction.CELEBRATE to "celebrate", PetAction.SETBACK to "setback", PetAction.TOUCH to "touch",
        PetAction.OBSERVE to "observe", PetAction.WAIT to "wait", PetAction.PAUSED to "paused",
        PetAction.STOPPED to "stopped", PetAction.ERROR to "error", PetAction.REPLY to "reply",
        PetAction.NOTICE to "notice", PetAction.DRAG to "drag",
    )
    fun sample(pose: PetPose): PetCelSample {
        val clip = clips.getValue(actionClips.getValue(pose.action))
        if (pose.reduced) return PetCelSample(clip.name, clip.still, Long.MAX_VALUE)
        if (pose.action != PetAction.IDLE) return clip.sample(pose.elapsedMs)
        val phase = pose.elapsedMs.coerceAtLeast(0) % IDLE_CYCLE
        return when {
            phase < DRINK_AT -> clip.sample(phase).let { it.copy(nextMs = minOf(it.nextMs, DRINK_AT - phase)) }
            phase < DRINK_END -> clips.getValue("drink").sample(phase - DRINK_AT)
                .let { it.copy(nextMs = minOf(it.nextMs, DRINK_END - phase)) }
            else -> clip.sample(phase - DRINK_END).let { it.copy(nextMs = minOf(it.nextMs, IDLE_CYCLE - phase)) }
        }
    }
}
