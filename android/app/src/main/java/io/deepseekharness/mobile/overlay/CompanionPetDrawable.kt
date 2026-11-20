package io.deepseekharness.mobile.overlay

import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/** Complete illustrated cels. Never meshes, bone interpolation, detached hands or face stickers. */
internal class CompanionPetDrawable(
    private val resources: Resources,
    private val targetSize: Int,
    private val decoder: Executor,
    private val main: Handler = Handler(Looper.getMainLooper()),
) : Drawable(), AutoCloseable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; textAlign = Paint.Align.CENTER }
    private val source = Rect()
    private val fadeSource = Rect()
    private val destination = RectF()
    private var fadeFrom: Bitmap? = null
    private var fadeStartMs = 0L
    private var lastAction: PetAction = PetAction.IDLE
    private var impulseStartMs = 0L
    private var curSx = 1f
    private var curSy = 1f
    private var curDx = 0f
    private var curDy = 0f
    private var curRot = 0f
    private var curPivotFrac = 0.94f
    private val liveFrameTick = Runnable { if (!closed && !pose.reduced) invalidateSelf() }
    private val cache = LinkedHashMap<String, Bitmap>(4, .75f, true)
    private var decoding = false
    private val failed = mutableSetOf<String>()
    @Volatile private var closed = false
    @Volatile private var wanted = "idle"
    private var pose = PetPose()
    val currentPose get() = pose
    var currentCel = PetCels.sample(pose)
        private set
    val nextFrameDelayMs get() = currentCel.nextMs
    private var displayedClip = "idle"
    private var displayedFrame = 0
    private var displayed: Bitmap
    private var retainedCel: Bitmap? = null
    var onAtlasReady: (() -> Unit)? = null
    val displayedClipName get() = displayedClip
    val cachedAtlasCount get() = cache.size
    val cachedBitmapBytes get() = cache.values.sumOf { it.allocationByteCount.toLong() }
    // Reserve one idle plus two large clips (action + return), scaled to the actual avatar.
    val cacheLimitBytes = minOf(MAX_BITMAP_BYTES, targetSize.coerceIn(96, PetCels.CELL).toLong().let { side ->
        side * side * 4 * (PetCels.clips.getValue("idle").cells + 2 * PetCels.clips.values.maxOf { it.cells })
    })

    init {
        // Constructor is called on the avatar executor. One idle atlas is enough to attach.
        displayed = decode("idle")
        cache["idle"] = displayed
    }

    private fun decode(name: String): Bitmap {
        val cellSize = targetSize.coerceIn(96, PetCels.CELL)
        val bitmap = resources.assets.open("pet-cels/$name.webp").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                // Bake density scaling during the off-main decode. A 264px avatar should
                // not keep every 320px source cel resident after doubling its frame count.
                inScaled = true; inDensity = PetCels.CELL; inTargetDensity = cellSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: error("Missing pet animation: $name")
        val rows = PetCels.clips.getValue(name).rows
        require(bitmap.width == cellSize * PetCels.COLUMNS && bitmap.height == cellSize * rows)
        return bitmap
    }

    fun setPose(next: PetPose, cel: PetCelSample = PetCels.sample(next)) {
        if (closed) return
        if (next.action != lastAction) { lastAction = next.action; impulseStartMs = android.os.SystemClock.uptimeMillis() }
        pose = next
        currentCel = cel
        wanted = currentCel.clip
        val loaded = cache[wanted]
        if (loaded != null) show(loaded, currentCel)
        else request(wanted)
    }

    fun setAnimatedPose(next: PetPose, playback: PetPlayback, now: Long, quietIdle: Boolean) {
        if (closed) return
        if (next.action != lastAction) { lastAction = next.action; impulseStartMs = android.os.SystemClock.uptimeMillis() }
        pose = next
        val visible = PetCelSample(displayedClip, displayedFrame, currentCel.nextMs)
        currentCel = playback.sample(next, now, quietIdle, visible) { name ->
            wanted = name
            if (name in cache) true else { request(name); false }
        }
        cache[currentCel.clip]?.let { show(it, currentCel) }
        playback.nextAtlas?.let { name ->
            if (name !in cache) { wanted = name; request(name) }
        }
    }

    /** A resized window keeps the actual previous cel until its new-size atlas is ready. */
    fun retainVisibleFrom(other: CompanionPetDrawable) {
        val rect = other.displayedSource()
        val pixels = other.retainedCel ?: other.displayed
        val cel = Bitmap.createBitmap(pixels, rect.left, rect.top, rect.width(), rect.height())
        retainedCel = if (cel === pixels) pixels.copy(Bitmap.Config.ARGB_8888, false) else cel
        displayedClip = other.displayedClip
        displayedFrame = other.displayedFrame
        currentCel = other.currentCel.copy(clip = displayedClip, frame = displayedFrame)
        pose = other.pose
    }

    private fun show(bitmap: Bitmap, cel: PetCelSample) {
        if (retainedCel == null && displayed === bitmap && displayedClip == cel.clip && displayedFrame == cel.frame) return
        retainedCel?.recycle()
        retainedCel = null
        displayed = bitmap; displayedClip = cel.clip; displayedFrame = cel.frame
        invalidateSelf()
    }

    private fun request(name: String) {
        if (closed || decoding || name in failed) return
        decoding = true
        decoder.execute {
            // Rapid business-state changes must not decode every obsolete state in the queue.
            val result = if (!closed && wanted == name) runCatching { decode(name) } else null
            val bitmap = result?.getOrNull()
            val posted = main.post {
                decoding = false
                if (closed) bitmap?.recycle()
                else {
                    if (bitmap != null) {
                        if (wanted == name) {
                            cache[name] = bitmap
                            if (currentCel.clip == name) show(bitmap, currentCel)
                            trim()
                            onAtlasReady?.invoke()
                        } else bitmap.recycle()
                    } else if (result?.isFailure == true) {
                        failed.add(name)
                        android.util.Log.w("WhalePet", "Could not decode animation $name", result.exceptionOrNull())
                    }
                    // Only one decode/result may be outstanding. Coalesce all intervening states.
                    if (wanted !in cache) request(wanted)
                }
            }
            if (!posted) bitmap?.recycle()
        }
    }

    private fun trim() {
        val entries = cache.entries.iterator()
        while ((cache.size > MAX_ATLASES || cachedBitmapBytes > cacheLimitBytes) && entries.hasNext()) {
            val entry = entries.next()
            if (entry.value === displayed || entry.value === fadeFrom) continue
            entry.value.recycle()
            entries.remove()
        }
    }

    /** Test/export preparation only; never called by draw() or the live overlay's UI thread. */
    fun prepareForExport(action: PetAction, elapsed: Long = 0) {
        val name = PetCels.sample(PetPose(action, elapsed)).clip
        if (name !in cache) cache[name] = decode(name)
        setPose(PetPose(action, elapsed))
        trim()
    }

    private fun displayedSource(): Rect {
        retainedCel?.let { source.set(0, 0, it.width, it.height); return source }
        val cellW = displayed.width / PetCels.COLUMNS
        val cellH = displayed.height / PetCels.clips.getValue(displayedClip).rows
        val col = displayedFrame % PetCels.COLUMNS
        val row = displayedFrame / PetCels.COLUMNS
        source.set(col * cellW, row * cellH, (col + 1) * cellW, (row + 1) * cellH)
        return source
    }

    override fun draw(canvas: Canvas) {
        if (closed) return
        val side = minOf(bounds.width(), bounds.height()).toFloat()
        val x = bounds.exactCenterX() - side / 2
        val y = bounds.exactCenterY() - side / 2
        destination.set(x, y, x + side, y + side)

        val now = android.os.SystemClock.uptimeMillis()
        var targetSx = 1f
        var targetSy = 1f
        var targetDx = 0f
        var targetDy = 0f
        var targetRot = 0f
        var targetPivotFrac = 0.94f

        val isTransitionClip = displayedClip.endsWith("_enter") || displayedClip.endsWith("_return")
        if (!pose.reduced && !isTransitionClip) {
            val sec = (now % 60_000L) / 1000.0
            when (pose.action) {
                PetAction.IDLE, PetAction.WAIT -> {
                    val s = kotlin.math.sin(sec * 2.0 * Math.PI / 3.0).toFloat()
                    targetSx = 1f - 0.015f * s
                    targetSy = 1f + 0.022f * s
                    targetDy = -side * 0.014f * s
                    targetRot = 1.1f * kotlin.math.sin(sec * 2.0 * Math.PI / 6.0).toFloat()
                }
                PetAction.PAUSED, PetAction.STOPPED -> {
                    val s = kotlin.math.sin(sec * 2.0 * Math.PI / 3.8).toFloat()
                    targetSx = 1f + 0.018f * s
                    targetSy = 1f - 0.015f * s
                    targetRot = 1.4f * s
                }
                PetAction.THINK, PetAction.OBSERVE, PetAction.ASK -> {
                    val s = kotlin.math.sin(sec * 2.0 * Math.PI / 2.2).toFloat()
                    targetRot = 3.2f * s
                    targetDy = -side * 0.018f * kotlin.math.abs(s)
                    targetSx = 1f - 0.010f * kotlin.math.abs(s)
                    targetSy = 1f + 0.014f * kotlin.math.abs(s)
                }
                PetAction.WORK, PetAction.CONTROL -> {
                    val s = kotlin.math.sin(sec * 2.0 * Math.PI / 0.95).toFloat()
                    targetSx = 1f + 0.018f * s
                    targetSy = 1f - 0.018f * s
                    targetDy = -side * 0.020f * kotlin.math.abs(s)
                    targetRot = 2.4f * kotlin.math.sin(sec * 2.0 * Math.PI / 1.9).toFloat()
                }
                PetAction.CELEBRATE, PetAction.TOUCH, PetAction.ACCEPT, PetAction.REPLY, PetAction.NOTICE -> {
                    val hop = kotlin.math.abs(kotlin.math.sin(sec * 2.0 * Math.PI / 0.78)).toFloat()
                    targetSx = 1f - 0.032f * hop
                    targetSy = 1f + 0.042f * hop
                    targetDy = -side * 0.052f * hop
                    targetRot = 4.0f * kotlin.math.sin(sec * 2.0 * Math.PI / 1.56).toFloat()
                }
                PetAction.DRAG -> {
                    val swing = kotlin.math.sin(sec * 2.0 * Math.PI / 1.05).toFloat()
                    targetPivotFrac = 0.14f
                    targetRot = 10.5f * swing
                    targetDx = side * 0.022f * swing
                }
                PetAction.SETBACK, PetAction.ERROR -> {
                    val shiver = kotlin.math.sin(sec * 2.0 * Math.PI / 0.42).toFloat()
                    targetDx = side * 0.016f * shiver
                    targetRot = 2.2f * shiver
                }
            }

            // Spring-damped state-change pop impulse (classic Live2D settle)
            val impElapsed = (now - impulseStartMs).coerceAtLeast(0L)
            if (impulseStartMs > 0L && impElapsed < 320L) {
                val p = impElapsed / 320f
                val wave = kotlin.math.sin(p * Math.PI * 2.0).toFloat() * (1f - p) * (1f - p)
                targetSx += 0.065f * wave
                targetSy -= 0.065f * wave
                targetDy -= side * 0.025f * kotlin.math.abs(wave)
            }
        }

        // Smooth exponential damping across state transitions (zero discontinuity)
        val damp = if (pose.reduced) 1f else 0.16f
        curSx += (targetSx - curSx) * damp
        curSy += (targetSy - curSy) * damp
        curDx += (targetDx - curDx) * damp
        curDy += (targetDy - curDy) * damp
        curRot += (targetRot - curRot) * damp
        curPivotFrac += (targetPivotFrac - curPivotFrac) * damp
        val pivotY = y + side * curPivotFrac

        canvas.save()
        canvas.translate(curDx, curDy)
        canvas.rotate(curRot, x + side * 0.5f, pivotY)
        canvas.scale(curSx, curSy, x + side * 0.5f, pivotY)

        canvas.drawBitmap(retainedCel ?: displayed, displayedSource(), destination, paint)
        canvas.restore()

        if (!pose.reduced) {
            drawStateParticles(canvas, x, y, side, now)
            main.removeCallbacks(liveFrameTick)
            main.postDelayed(liveFrameTick, 16L)
        }
    }

    private fun drawStateParticles(canvas: Canvas, x: Float, y: Float, side: Float, now: Long) {
        val sec = (now % 60_000L) / 1000.0
        when (pose.action) {
            PetAction.CELEBRATE, PetAction.TOUCH, PetAction.ACCEPT -> {
                val colors = intArrayOf(0xFFFFD166.toInt(), 0xFFFF8FAB.toInt(), 0xFF6BC3FF.toInt())
                for (i in 0 until 3) {
                    val phase = ((sec * 0.7 + i * 0.33) % 1.0).toFloat()
                    val alpha = (kotlin.math.sin(phase * Math.PI).toFloat() * 220f).toInt().coerceIn(0, 220)
                    val px = x + side * (0.22f + i * 0.28f) + kotlin.math.sin(sec * 3.0 + i).toFloat() * side * 0.03f
                    val py = y + side * (0.34f - phase * 0.24f)
                    val r = side * (0.028f + 0.014f * kotlin.math.sin(phase * Math.PI).toFloat())
                    fxPaint.color = (colors[i] and 0x00FFFFFF) or (alpha shl 24)
                    canvas.drawCircle(px, py, r, fxPaint)
                }
            }
            PetAction.PAUSED, PetAction.STOPPED -> {
                fxPaint.textSize = side * 0.11f
                for (i in 0 until 2) {
                    val phase = ((sec * 0.35 + i * 0.5) % 1.0).toFloat()
                    val alpha = (kotlin.math.sin(phase * Math.PI).toFloat() * 190f).toInt().coerceIn(0, 190)
                    val px = x + side * (0.72f + phase * 0.14f)
                    val py = y + side * (0.32f - phase * 0.22f)
                    fxPaint.color = 0x006BC3FF or (alpha shl 24)
                    canvas.drawText(if (i == 0) "z" else "Z", px, py, fxPaint)
                }
            }
            PetAction.THINK, PetAction.OBSERVE, PetAction.ASK -> {
                for (i in 0 until 3) {
                    val phase = ((sec * 0.55 + i * 0.33) % 1.0).toFloat()
                    val alpha = (kotlin.math.sin(phase * Math.PI).toFloat() * 170f).toInt().coerceIn(0, 170)
                    val px = x + side * (0.74f + i * 0.06f)
                    val py = y + side * (0.26f - phase * 0.14f)
                    fxPaint.color = 0x005CA4FF or (alpha shl 24)
                    canvas.drawCircle(px, py, side * (0.018f + i * 0.005f), fxPaint)
                }
            }
            PetAction.CONTROL, PetAction.WORK -> {
                val pulse = ((sec * 1.1) % 1.0).toFloat()
                val alpha = ((1f - pulse) * 140f).toInt().coerceIn(0, 140)
                fxPaint.color = 0x00257AF4 or (alpha shl 24)
                fxPaint.style = Paint.Style.STROKE
                fxPaint.strokeWidth = maxOf(1.5f, side * 0.015f)
                canvas.drawCircle(x + side * 0.5f, y + side * 0.52f, side * (0.32f + pulse * 0.16f), fxPaint)
                fxPaint.style = Paint.Style.FILL
            }
            else -> Unit
        }
    }

    override fun getIntrinsicWidth() = PetCels.CELL
    override fun getIntrinsicHeight() = PetCels.CELL
    override fun setAlpha(alpha: Int) { if (paint.alpha != alpha) { paint.alpha = alpha; invalidateSelf() } }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun close() {
        closed = true
        onAtlasReady = null
        main.removeCallbacks(liveFrameTick)
        fadeFrom = null
        fadeStartMs = 0L
        retainedCel?.recycle()
        retainedCel = null
        cache.values.forEach { if (!it.isRecycled) it.recycle() }
        cache.clear()
    }
    companion object {
        const val FADE_DURATION_MS = 360L
        const val MAX_ATLASES = 3
        const val MAX_BITMAP_BYTES = 32L * 1024 * 1024
    }
}
