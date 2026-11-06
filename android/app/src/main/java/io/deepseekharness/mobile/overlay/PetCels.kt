package io.deepseekharness.mobile.overlay

internal data class PetCelHold(val frame: Int, val millis: Int)
internal data class PetCelSample(val clip: String, val frame: Int, val nextMs: Long)

internal class PetCelClip(
    val name: String, val holds: List<PetCelHold>, val still: Int = 0, val loopFrom: Int = 0,
    val cells: Int = 8,
) {
    val rows get() = cells / PetCels.COLUMNS
    val duration = holds.sumOf { it.millis }.toLong()
    private val loopDuration = holds.drop(loopFrom).sumOf { it.millis }.toLong()
    init {
        require(cells in listOf(8, 16))
        require(holds.isNotEmpty() && holds.all { it.frame in 0 until cells && it.millis > 0 })
        require(still in 0 until cells && loopFrom in holds.indices)
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

/** Each name owns complete illustrated cels. Held poses rest; gestures use dense, short-spaced drawings. */
internal object PetCels {
    const val CELL = 320
    const val COLUMNS = 4
    const val IDLE_CYCLE = 24_000L
    const val DRINK_AT = 16_000L
    const val DRINK_END = 20_500L
    private fun clip(name: String, vararg millis: Int, still: Int = 0, loopFrom: Int = 0, cells: Int = 8) =
        PetCelClip(name, millis.mapIndexed { i, ms -> PetCelHold(i, ms) }, still, loopFrom, cells)

    val clips = listOf(
        clip("idle", 1600, 70, 80, 110, 1800, 450, 90, 1000),
        clip("drink", 600, 80, 80, 80, 80, 80, 80, 200, 400, 80, 80, 80, 80, 80, 80, 2340, cells = 16),
        clip("reading", 1200, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 100, 1800, cells = 16),
        clip("writing", 300, 80, 80, 80, 80, 80, 80, 80, 80, 80, 400, 90, 80, 80, 80, 900, cells = 16),
        clip("control", 300, 80, 80, 80, 80, 80, 80, 180, 80, 80, 80, 80, 80, 100, 80, 300, cells = 16),
        clip("accept", 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 500, still = 10, cells = 16),
        clip("ask", 400, 80, 80, 80, 80, 80, 1600, 80, 80, 80, 600, 80, 80, 80, 80, 940, still = 6, cells = 16),
        clip("celebrate", 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 1000, still = 15, cells = 16),
        clip("setback", 300, 80, 80, 80, 80, 80, 80, 80, 300, 80, 80, 80, 80, 80, 80, 1060, still = 15, cells = 16),
        clip("touch", 60, 60, 60, 60, 60, 60, 60, 60, 60, 60, 60, 60, 60, 60, 60, 150, still = 6, cells = 16),
        clip("observe", 1600, 120, 1600, 100, 1800, 160, 120, 1800),
        clip("wait", 2000, 90, 100, 2000, 140, 180, 140, 1800),
        clip("paused", 80, 90, 100, 1800, 100, 1600, 120, 1800, still = 3, loopFrom = 3),
        clip("stopped", 100, 100, 120, 180, 120, 1200, 90, 1800, still = 7, loopFrom = 5),
        clip("error", 300, 100, 120, 500, 120, 90, 1400, 1800, still = 6, loopFrom = 6),
        clip("reply", 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 1200, still = 6, cells = 16),
        clip("notice", 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 2200, still = 13, cells = 16),
        clip("drag", 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, still = 0, cells = 16),
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
