package io.deepseekharness.mobile.overlay

import kotlin.math.abs
import kotlin.math.roundToInt

/** The director owns business deadlines. This clock owns only the drawings that actually reach the window. */
internal class PetPlayback(
    private val clips: Map<String, PetCelClip>,
    private val exits: Map<String, String>,
) {
    private var last: PetCelSample? = null
    private var target: String? = null
    private var previousElapsed = 0L
    private var timeline = 0L
    private var sampledAt = 0L
    private var waitingForAtlas = false
    private var wasQuiet = false
    private var travel: List<PetCelSample> = emptyList()
    private var travelDuration = 0L
    var nextAtlas: String? = null
        private set

    fun reset() {
        last = null
        target = null
        timeline = 0
        waitingForAtlas = false
        wasQuiet = false
        travel = emptyList()
        travelDuration = 0
        nextAtlas = null
    }

    fun sample(
        pose: PetPose, now: Long, quietIdle: Boolean = false, displayed: PetCelSample? = null,
        available: (String) -> Boolean = { true },
    ): PetCelSample {
        val raw = PetCels.sample(pose)
        val quiet = quietIdle && pose.action == PetAction.IDLE
        val name = if (quiet) "idle" else raw.clip
        if (pose.reduced) {
            reset()
            return raw
        }
        val current = displayed ?: last
        nextAtlas = null
        val restarted = pose.action != PetAction.IDLE && target == name && pose.elapsedMs < previousElapsed
        val parkIdle = quiet && !wasQuiet && current?.clip == "idle" && current.frame != clips.getValue("idle").still
        wasQuiet = quiet
        previousElapsed = pose.elapsedMs
        if (current == null) {
            // A sampler without an attached drawable may start in the middle of a recording.
            target = name
            timeline = when {
                quiet -> 0
                name == "drink" -> pose.elapsedMs % PetCels.IDLE_CYCLE - PetCels.DRINK_AT
                else -> pose.elapsedMs
            }
        } else if (target == null && current.clip == name && !parkIdle) {
            target = name
            timeline = clips.getValue(name).holds.takeWhile { it.frame != current.frame }.sumOf { it.millis.toLong() }
        } else if (target != name || restarted || parkIdle) {
            val path = buildPath(current, name)
            // A newer request during an exit must not repeatedly extend the current drawing.
            val remaining = if (travel.isNotEmpty()) last?.nextMs?.minus((now - sampledAt).coerceAtLeast(0)) else null
            if (remaining != null && path.firstOrNull()?.let { it.clip == current.clip && it.frame == current.frame } == true) {
                path[0] = path[0].copy(nextMs = minOf(path[0].nextMs, remaining.coerceAtLeast(1)))
            }
            target = name
            travel = path
            travelDuration = path.sumOf { it.nextMs }
            timeline = 0
        } else if (!waitingForAtlas) {
            timeline += (now - sampledAt).coerceAtLeast(0)
        }
        sampledAt = now

        if (travel.isNotEmpty() && timeline >= travelDuration) {
            timeline -= travelDuration
            travel = emptyList()
            travelDuration = 0
        }
        var offset = 0L
        var clipStart = 0L
        var preceding: String? = null
        var next: PetCelSample? = null
        for (step in travel) {
            if (step.clip != preceding) { clipStart = offset; preceding = step.clip }
            offset += step.nextMs
            if (timeline < offset) {
                next = step.copy(nextMs = offset - timeline)
                break
            }
        }
        val cel = next ?: if (quiet) PetCelSample("idle", clips.getValue("idle").still, Long.MAX_VALUE)
            else clips.getValue(name).sample(timeline)

        // A cold atlas can take much longer than a motion frame. Freeze at its entrance;
        // on decoder completion resample from here, never from an elapsed wall-clock deadline.
        if (!available(cel.clip)) {
            timeline = if (next == null) 0 else clipStart
            waitingForAtlas = true
            return retain((current ?: clips.getValue("idle").sample(0)).copy(nextMs = ambientDeadline(pose, quiet)))
        }
        waitingForAtlas = false
        if (next != null) {
            // Warm only the next distinct atlas, near its first visible frame. Long holds,
            // hidden pets and normal primary loops do not run a preload queue.
            var boundary = 0L
            for (step in travel) {
                if (boundary >= offset && step.clip != cel.clip) {
                    if (boundary - timeline <= 200) nextAtlas = step.clip
                    break
                }
                boundary += step.nextMs
            }
            if (nextAtlas == null && travelDuration - timeline <= 200 && cel.clip != name &&
                travel.last().clip == cel.clip) nextAtlas = name
        }
        return retain(cel.copy(nextMs = minOf(cel.nextMs, ambientDeadline(pose, quiet))))
    }

    private fun retain(cel: PetCelSample): PetCelSample {
        last = cel
        return cel
    }

    private fun ambientDeadline(pose: PetPose, quiet: Boolean): Long {
        if (pose.action != PetAction.IDLE || quiet) return Long.MAX_VALUE
        val phase = pose.elapsedMs % PetCels.IDLE_CYCLE
        return when {
            phase < PetCels.DRINK_AT -> PetCels.DRINK_AT - phase
            phase < PetCels.DRINK_END -> PetCels.DRINK_END - phase
            else -> PetCels.IDLE_CYCLE - phase
        }
    }

    private fun buildPath(current: PetCelSample, destination: String): MutableList<PetCelSample> {
        val path = mutableListOf<PetCelSample>()
        val source = clips.getValue(current.clip)
        val entry = PetCels.entries[destination]
        when {
            current.clip == destination -> {
                // Repeating a reaction while its hands are up returns along that action's own frames.
                path.travel(source, current.frame, 0, 6)
                return path
            }
            current.clip == entry -> {
                path.travel(source, current.frame, source.exitFrame)
                return path
            }
            current.clip == "idle" -> {
                val end = if (current.frame <= source.cells / 2) 0 else source.cells
                path.travel(source, current.frame, end, 5)
            }
            current.clip in PetCels.entries.values -> path.travel(source, current.frame, 0)
            current.clip in exits.values -> path.travel(source, current.frame, source.exitFrame)
            destination == "idle" -> {
                // A gesture ending normally has time to finish and put its own prop away.
                val start = source.holds.indexOfFirst { it.frame == current.frame }
                var i = start.coerceAtLeast(0)
                for (step in source.holds.indices) {
                    val hold = source.holds[i]
                    if (hold.frame == source.exitFrame) break
                    val ms = if (step == 0) minOf(current.nextMs, hold.millis.toLong()) else hold.millis.toLong()
                    path.add(PetCelSample(source.name, hold.frame, ms.coerceIn(1, 80)))
                    i = if (i + 1 < source.holds.size) i + 1 else source.loopFrom
                }
                val exit = clips.getValue(exits.getValue(source.name))
                path.addAll(exit.holds.map { PetCelSample(exit.name, it.frame, it.millis.toLong()) })
            }
            current.frame < abs(source.exitFrame - current.frame) -> {
                path.travel(source, current.frame, 0, 5)
                val approach = clips.getValue(PetCels.entries.getValue(source.name))
                path.travel(approach, approach.exitFrame, 0)
            }
            else -> {
                path.travel(source, current.frame, source.exitFrame, 5)
                val exit = clips.getValue(exits.getValue(source.name))
                path.travel(exit, 0, exit.exitFrame)
            }
        }
        if (entry != null) {
            val incoming = clips.getValue(entry)
            path.travel(incoming, 0, incoming.exitFrame)
        }
        return path
    }

    /** Sample a finite strip in either direction. Its first and last drawings are always included. */
    private fun MutableList<PetCelSample>.travel(clip: PetCelClip, from: Int, to: Int, maxPrimarySteps: Int = 5) {
        val distances = petTravelDistance[clip.name]
        val direction = if (to < from) -1 else 1
        val span = distances?.let { abs(it[to] - it[from]) } ?: 0
        // A bow can settle sooner than a seated character can stand up. Space samples
        // by actual drawing change so the fastest part gets the most distinct poses.
        val steps = if (distances == null) minOf(abs(to - from), maxPrimarySteps)
            else minOf(abs(to - from), ((span + 35) / 36).coerceIn(1, 18))
        var cursor = from
        for (i in 0..steps) {
            val frame = when {
                i == 0 -> from
                i == steps -> to
                distances == null -> (from + (to - from) * i.toDouble() / steps).roundToInt()
                else -> {
                    val goal = distances[from] + direction * span * i.toDouble() / steps
                    while (cursor != to && abs(distances[cursor + direction] - goal) < abs(distances[cursor] - goal)) cursor += direction
                    cursor
                }
            }
            if (lastOrNull()?.let { it.clip == clip.name && it.frame == frame % clip.cells } != true)
                add(PetCelSample(clip.name, frame % clip.cells, 28L))
        }
    }
}
