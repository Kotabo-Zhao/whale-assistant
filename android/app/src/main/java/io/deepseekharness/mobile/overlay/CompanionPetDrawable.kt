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
    private val source = Rect()
    private val destination = RectF()
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
        pose = next
        currentCel = cel
        wanted = currentCel.clip
        val loaded = cache[wanted]
        if (loaded != null) show(loaded, currentCel)
        else request(wanted)
    }

    fun setAnimatedPose(next: PetPose, playback: PetPlayback, now: Long, quietIdle: Boolean) {
        if (closed) return
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
            if (entry.value === displayed) continue
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
        canvas.drawBitmap(retainedCel ?: displayed, displayedSource(), destination, paint)
    }

    override fun getIntrinsicWidth() = PetCels.CELL
    override fun getIntrinsicHeight() = PetCels.CELL
    override fun setAlpha(alpha: Int) { if (paint.alpha != alpha) { paint.alpha = alpha; invalidateSelf() } }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun close() {
        closed = true
        onAtlasReady = null
        retainedCel?.recycle()
        retainedCel = null
        cache.values.forEach { if (!it.isRecycled) it.recycle() }
        cache.clear()
    }
    companion object {
        const val MAX_ATLASES = 3
        const val MAX_BITMAP_BYTES = 32L * 1024 * 1024
    }
}
