package io.deepseekharness.mobile.overlay

/** Visual release is separate from business state: finish the gesture, put props away, then idle. */
internal class PetPlayback(
    private val clips: Map<String, PetCelClip>,
    private val exits: Map<String, String>,
) {
    private var last: PetCelSample? = null
    private var release: Release? = null
    private var idleOffset = 0L

    fun reset() { last = null; release = null; idleOffset = 0 }

    fun sample(pose: PetPose, now: Long, quietIdle: Boolean = false): PetCelSample {
        if (pose.action != PetAction.IDLE) idleOffset = 0
        val idle = clips.getValue("idle")
        val raw = PetCels.sample(pose)
        val desired = when {
            quietIdle && pose.action == PetAction.IDLE -> PetCelSample("idle", idle.still, Long.MAX_VALUE)
            pose.action == PetAction.IDLE && raw.clip == "idle" && idleOffset > 0 && !pose.reduced -> {
                // Restart only the resting drawing phase; retain the 24-second ambient schedule.
                val phase=pose.elapsedMs.coerceAtLeast(0)%PetCels.IDLE_CYCLE
                val boundary=if(phase<PetCels.DRINK_AT) PetCels.DRINK_AT-phase else PetCels.IDLE_CYCLE-phase
                idle.sample((pose.elapsedMs-idleOffset).coerceAtLeast(0)).let { it.copy(nextMs=minOf(it.nextMs,boundary)) }
            }
            else -> raw
        }
        fun remember(cel: PetCelSample): PetCelSample { last = cel; return cel }
        if (pose.reduced) { reset(); return remember(desired) }
        // Real device control, a new reply or a drag always interrupts a visual release immediately.
        if (desired.clip != "idle") { release = null; return remember(desired) }
        if (release == null) {
            val previous = last
            val exitName = previous?.let { exits[it.clip] }
            if (previous != null && exitName != null) {
                release = Release(clips.getValue(previous.clip), previous, clips.getValue(exitName), now)
            }
        }
        release?.let { active ->
            active.sample(now)?.let { return remember(it) }
            release = null
            idleOffset = pose.elapsedMs
            return remember(if (quietIdle) PetCelSample("idle", idle.still, Long.MAX_VALUE) else idle.sample(0))
        }
        return remember(desired)
    }

    private class Release(source: PetCelClip, previous: PetCelSample, private val exit: PetCelClip, private val started: Long) {
        private val sourceName = source.name
        private val finish = mutableListOf<PetCelHold>()
        private val finishDuration: Long
        init {
            var index = source.holds.indexOfLast { it.frame == previous.frame }.coerceAtLeast(0)
            var first = true
            // Do not skip through moving drawings or wait out seconds-long reading holds.
            // The exit's first cel is an exact copy of source.exitFrame.
            for (step in source.holds.indices) {
                if (source.holds[index].frame == source.exitFrame) break
                val hold = source.holds[index]
                val remaining = if (first) minOf(hold.millis.toLong(),previous.nextMs) else hold.millis.toLong()
                finish.add(PetCelHold(hold.frame,remaining.coerceIn(1,80).toInt()))
                first = false
                index = if (index+1 < source.holds.size) index+1 else source.loopFrom
            }
            finishDuration = finish.sumOf { it.millis }.toLong()
        }
        fun sample(now: Long): PetCelSample? {
            var elapsed = (now-started).coerceAtLeast(0)
            if (elapsed < finishDuration) {
                for (hold in finish) {
                    if (elapsed < hold.millis) return PetCelSample(sourceName,hold.frame,hold.millis-elapsed)
                    elapsed -= hold.millis
                }
            }
            val exitElapsed = (now-started-finishDuration).coerceAtLeast(0)
            return if (exitElapsed < exit.duration) exit.sample(exitElapsed) else null
        }
    }
}
