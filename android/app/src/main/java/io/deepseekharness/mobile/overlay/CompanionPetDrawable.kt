package io.deepseekharness.mobile.overlay

import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import java.util.Calendar
import java.util.concurrent.Executor

/**
 * 桌宠 60fps 渲染核心（支持鲸鱼娘 & 乌萨奇）：
 * - Vsync 同步 `Choreographer` 60fps 帧循环（无 Handler 抖动）；
 * - 180ms 缓出交叉淡化溶图（Crossfade）+ 阻尼弹簧微挤压弹性过渡，彻底消除状态硬切；
 * - 每段动作独立本地时钟（`clipStartedAtMs`），切动作始终从第 0 帧完整起播；
 * - 乌萨奇 6 幕自主待机剧场（28 秒循环 + 深夜自动切换趴睡）与全 9 套图集后台预热；
 * - 实时拖动速度物理摆动 + 落地 Q 弹挤压回弹。
 */
internal class CompanionPetDrawable(
    private val resources: Resources,
    private val targetSize: Int,
    private val decoder: Executor,
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val usagiTheme: Boolean = false,
    private val pixelTheme: Boolean = false,
) : Drawable(), AutoCloseable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val prevPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val prevSource = Rect()
    private val destination = RectF()
    private val cache = LinkedHashMap<String, Bitmap>(12, .75f, true)
    private val motion = PetMotionDirector()

    private var lastDrawMs = 0L
    private var clipStartedAtMs = SystemClock.uptimeMillis()
    private var idleReelStartedAtMs = SystemClock.uptimeMillis()
    private var activeUsagiIdleClip = "idle"

    // 180ms 平滑溶图过渡状态（禁止强行回收正处于淡出中的 prevBitmap）
    private var prevBitmap: Bitmap? = null
    private var transitionStartMs = 0L
    private var drawAlpha = 255

    @Volatile var ambientTickAllowed = true
    private var vsyncPosted = false
    private val choreographerCallback = Choreographer.FrameCallback {
        vsyncPosted = false
        if (!closed && !pose.reduced && ambientTickAllowed) {
            invalidateSelf()
        }
    }
    private val fallbackTick = Runnable {
        if (!closed && !pose.reduced && ambientTickAllowed) invalidateSelf()
    }

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
    val isUsagiTheme get() = usagiTheme
    val isPixelTheme get() = pixelTheme
    val displayedClipName get() = displayedClip
    val cachedAtlasCount get() = cache.size
    val cachedBitmapBytes get() = cache.values.sumOf { it.allocationByteCount.toLong() }
    val cacheLimitBytes = minOf(MAX_BITMAP_BYTES, targetSize.coerceIn(96, PetCels.CELL).toLong().let { side ->
        side * side * 4 * (PetCels.clips.getValue("idle").cells + 5 * PetCels.clips.values.maxOf { it.cells })
    })

    fun setDragVelocity(vxPxPerSec: Float) {
        motion.setDragVelocity(vxPxPerSec)
    }

    fun clearDragVelocity() {
        motion.clearDragVelocity()
    }

    fun triggerLandingBounce() {
        motion.triggerLandingBounce()
        resumeLiveTick()
    }

    fun resumeLiveTick() {
        if (closed || pose.reduced || !ambientTickAllowed) return
        main.removeCallbacks(fallbackTick)
        scheduleNextFrame(immediate = true)
    }

    private fun isHighRateActive(): Boolean {
        if (prevBitmap != null) return true
        return pose.action != PetAction.IDLE && pose.action != PetAction.WAIT &&
            pose.action != PetAction.PAUSED && pose.action != PetAction.STOPPED
    }

    private fun scheduleNextFrame(immediate: Boolean = false) {
        if (closed || pose.reduced || !ambientTickAllowed) return
        val highRate = immediate || isHighRateActive()
        if (highRate && Looper.myLooper() == Looper.getMainLooper()) {
            main.removeCallbacks(fallbackTick)
            if (!vsyncPosted) {
                vsyncPosted = true
                runCatching {
                    Choreographer.getInstance().postFrameCallback(choreographerCallback)
                }.onFailure {
                    vsyncPosted = false
                    main.postDelayed(fallbackTick, if (immediate) 0L else 16L)
                }
            }
        } else {
            main.removeCallbacks(fallbackTick)
            val delayMs = if (immediate) 0L else if (highRate) 16L else 33L
            main.postDelayed(fallbackTick, delayMs)
        }
    }

    private fun assetKey(name: String): String {
        val rows = PetCels.clips.getValue(name).rows
        return when {
            pixelTheme -> pixelAtlasPath(name)
            usagiTheme -> usagiAtlasPath(name, rows)
            else -> "pet-cels/$name.webp"
        }
    }

    init {
        displayed = decode("idle")
        cache[assetKey("idle")] = displayed
        prewarmHotAtlases()
    }

    private fun pixelAtlasPath(name: String): String {
        val base = name.removeSuffix("_enter").removeSuffix("_return")
        return when (base) {
            "drag" -> "pet-cels-pixel/drag_16.webp"
            "touch" -> "pet-cels-pixel/touch_16.webp"
            "paused", "stopped", "sleep" -> "pet-cels-pixel/sleep_16.webp"
            "writing", "control" -> "pet-cels-pixel/writing_16.webp"
            "reading", "observe", "wait", "think" -> "pet-cels-pixel/reading_16.webp"
            "ask", "notice" -> "pet-cels-pixel/ask_16.webp"
            "celebrate", "accept", "reply" -> "pet-cels-pixel/celebrate_16.webp"
            "error", "setback" -> "pet-cels-pixel/error_16.webp"
            else -> "pet-cels-pixel/idle_16.webp"
        }
    }

    private fun usagiAtlasPath(name: String, @Suppress("UNUSED_PARAMETER") rows: Int): String {
        val base = name.removeSuffix("_enter").removeSuffix("_return")
        return when (base) {
            "drag" -> "pet-cels-usagi/drag_32.webp"
            "touch" -> "pet-cels-usagi/touch_32.webp"
            "paused", "stopped", "sleep" -> "pet-cels-usagi/sleep_32.webp"
            "drink" -> "pet-cels-usagi/drink_32.webp"
            "writing" -> "pet-cels-usagi/writing_32.webp"
            "control" -> "pet-cels-usagi/control_32.webp"
            "celebrate", "accept", "reply", "notice" -> "pet-cels-usagi/celebrate_32.webp"
            "reading" -> "pet-cels-usagi/reading_32.webp"
            else -> "pet-cels-usagi/idle_32.webp"
        }
    }

    private fun decode(name: String): Bitmap {
        val maxSide = if (usagiTheme) 192 else PetCels.CELL
        val cellSize = targetSize.coerceIn(96, maxSide)
        val clipRows = PetCels.clips.getValue(name).rows
        val assetPath = when {
            pixelTheme -> pixelAtlasPath(name)
            usagiTheme -> usagiAtlasPath(name, clipRows)
            else -> "pet-cels/$name.webp"
        }
        val rows = when {
            pixelTheme && assetPath.endsWith("_16.webp") -> 4
            usagiTheme && assetPath.endsWith("_32.webp") -> 8
            else -> clipRows
        }
        val raw = resources.assets.open(assetPath).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inScaled = true; inDensity = PetCels.CELL; inTargetDensity = cellSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: error("Missing pet animation: $name")
        val expectedW = cellSize * PetCels.COLUMNS
        val expectedH = cellSize * rows
        if (raw.width == expectedW && raw.height == expectedH) return raw
        if (raw.width >= PetCels.COLUMNS && raw.height >= rows) {
            return if (raw.width > expectedW || raw.height > expectedH) {
                Bitmap.createScaledBitmap(raw, expectedW, expectedH, true).also {
                    if (it !== raw) raw.recycle()
                }
            } else raw
        }
        raw.recycle()
        error("Invalid pet atlas dimensions for $name: ${raw.width}x${raw.height}")
    }

    fun setPose(next: PetPose, cel: PetCelSample = PetCels.sample(next)) {
        if (closed) return
        pose = next
        currentCel = cel
        wanted = currentCel.clip
        val loaded = cache[assetKey(wanted)]
        if (loaded != null) show(loaded, currentCel)
        else request(wanted)
    }

    fun setAnimatedPose(next: PetPose, playback: PetPlayback, now: Long, quietIdle: Boolean) {
        if (closed) return
        val actionChanged = next.action != pose.action
        pose = next
        if (actionChanged && next.action == PetAction.IDLE) {
            idleReelStartedAtMs = now
        }

        // 乌萨奇待机 6 幕自主剧场：28 秒自然串联不同招牌动作，不再死板重复同一段
        if (usagiTheme && next.action == PetAction.IDLE && !quietIdle && !next.reduced) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val reelClip = PetMotionDirector.resolveUsagiIdleClip(now - idleReelStartedAtMs, hour)
            if (reelClip != activeUsagiIdleClip) {
                activeUsagiIdleClip = reelClip
            }
            val reelSample = PetCelSample(reelClip, 0, 120L)
            currentCel = reelSample
            wanted = reelClip
            val loaded = cache[assetKey(reelClip)]
            if (loaded != null) {
                show(loaded, reelSample)
            } else {
                request(reelClip)
            }
            if (ambientTickAllowed) scheduleNextFrame(immediate = true)
            return
        }

        val visible = PetCelSample(displayedClip, displayedFrame, currentCel.nextMs)
        currentCel = playback.sample(next, now, quietIdle, visible) { name ->
            wanted = name
            if (assetKey(name) in cache) true else { request(name); false }
        }
        cache[assetKey(currentCel.clip)]?.let { show(it, currentCel) }
        if ((usagiTheme || pixelTheme) && !pose.reduced && ambientTickAllowed) {
            scheduleNextFrame(immediate = true)
        }
        playback.nextAtlas?.let { name ->
            if (assetKey(name) !in cache) { wanted = name; request(name) }
        }
    }

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
        val atlasChanged = displayed !== bitmap
        if (!atlasChanged && retainedCel == null && displayedClip == cel.clip && displayedFrame == cel.frame) return

        // 不同图集/状态切换时：捕获当前帧做 180ms 缓出溶图 + 弹性微挤压脉冲，彻底消除生硬跳切
        if (atlasChanged && !pose.reduced && retainedCel == null && !displayed.isRecycled) {
            prevSource.set(displayedSource())
            prevBitmap = displayed
            transitionStartMs = SystemClock.uptimeMillis()
            clipStartedAtMs = transitionStartMs
            motion.triggerTransitionPop(if (pose.action == PetAction.TOUCH || pose.action == PetAction.CELEBRATE) 1.15f else 0.85f)
        } else if (atlasChanged) {
            clipStartedAtMs = SystemClock.uptimeMillis()
            prevBitmap = null
        }

        retainedCel?.recycle()
        retainedCel = null
        displayed = bitmap
        displayedClip = cel.clip
        displayedFrame = cel.frame
        invalidateSelf()
    }

    private fun request(name: String) {
        val key = assetKey(name)
        if (closed || decoding || key in failed || key in cache) return
        decoding = true
        decoder.execute {
            val result = if (!closed && assetKey(wanted) == key) runCatching { decode(name) } else null
            val bitmap = result?.getOrNull()
            val posted = main.post {
                decoding = false
                if (closed) bitmap?.recycle()
                else {
                    if (bitmap != null) {
                        if (key !in cache) {
                            cache[key] = bitmap
                            if (assetKey(currentCel.clip) == key) show(bitmap, currentCel)
                            trim()
                            onAtlasReady?.invoke()
                        } else bitmap.recycle()
                    } else if (result?.isFailure == true) {
                        failed.add(key)
                        android.util.Log.w("WhalePet", "Could not decode animation $name", result.exceptionOrNull())
                    }
                    if (assetKey(wanted) !in cache) request(wanted)
                }
            }
            if (!posted) {
                decoding = false
                bitmap?.recycle()
            }
        }
    }

    private fun prewarmHotAtlases() {
        val hot = if (usagiTheme) {
            listOf("drag", "touch", "celebrate", "control", "drink", "reading", "writing", "paused")
        } else {
            listOf("drag_enter", "drag", "touch_enter", "touch", "reading", "celebrate")
        }
        decoder.execute {
            val seenKeys = HashSet<String>()
            seenKeys.add(assetKey("idle"))
            for (name in hot) {
                if (closed) break
                val key = assetKey(name)
                if (!seenKeys.add(key)) continue
                val bmp = runCatching { decode(name) }.getOrNull() ?: continue
                val posted = main.post {
                    if (closed) bmp.recycle()
                    else if (key !in cache) { cache[key] = bmp; trim() }
                    else bmp.recycle()
                }
                if (!posted) bmp.recycle()
            }
        }
    }

    private fun trim() {
        val dragKey = assetKey("drag")
        val idleKey = assetKey("idle")
        val entries = cache.entries.iterator()
        while ((cache.size > MAX_ATLASES || cachedBitmapBytes > cacheLimitBytes) && entries.hasNext()) {
            val entry = entries.next()
            if (entry.value === displayed || entry.value === prevBitmap) continue
            if (entry.key == dragKey || entry.key == idleKey) continue
            // 仅从缓存移除交由 NativeAllocationRegistry/GC 回收，避免 RenderThread 正在渲染 DisplayList 时触发 recycled bitmap 闪退
            entries.remove()
        }
    }

    fun prepareForExport(action: PetAction, elapsed: Long = 0) {
        val name = PetCels.sample(PetPose(action, elapsed)).clip
        val key = assetKey(name)
        if (key !in cache) cache[key] = decode(name)
        setPose(PetPose(action, elapsed))
        trim()
    }

    private fun displayedSource(): Rect {
        retainedCel?.let { source.set(0, 0, it.width, it.height); return source }
        val cellW = (displayed.width / PetCels.COLUMNS).coerceAtLeast(1)
        val rows = (displayed.height / cellW).coerceAtLeast(1)
        val totalCells = (PetCels.COLUMNS * rows).coerceAtLeast(1)
        val effectiveFrame = if ((usagiTheme || pixelTheme) && !pose.reduced) {
            val stepMs = when {
                pixelTheme -> 72L
                displayedClip in setOf("paused", "stopped", "sleep") -> 46L
                pose.action == PetAction.IDLE && displayedClip == "idle" -> 34L
                else -> 22L
            }
            // 使用每段动作独立本地时钟，切状态始终从第 0 帧完整起播
            val localElapsed = (SystemClock.uptimeMillis() - clipStartedAtMs).coerceAtLeast(0L)
            ((localElapsed / stepMs) % totalCells).toInt()
        } else {
            displayedFrame.coerceIn(0, totalCells - 1)
        }
        val cellH = (displayed.height / rows).coerceAtLeast(1)
        val col = effectiveFrame % PetCels.COLUMNS
        val row = effectiveFrame / PetCels.COLUMNS
        source.set(col * cellW, row * cellH, (col + 1) * cellW, (row + 1) * cellH)
        return source
    }

    override fun draw(canvas: Canvas) {
        if (closed) return
        val side = minOf(bounds.width(), bounds.height()).toFloat()
        val x = bounds.exactCenterX() - side / 2
        val y = bounds.exactCenterY() - side / 2
        destination.set(x, y, x + side, y + side)

        val now = SystemClock.uptimeMillis()
        val dt = if (lastDrawMs == 0L) 0.016f else ((now - lastDrawMs).coerceIn(4L, 45L) / 1000f)
        lastDrawMs = now

        val tr = motion.step(pose.action, now, dt, pose.reduced)
        val pivotY = y + side * tr.pivotYFrac

        val outBmp = prevBitmap
        val fadeProgress = if (outBmp != null && !outBmp.isRecycled) {
            PetMotionDirector.crossfadeProgress(now - transitionStartMs)
        } else {
            1f
        }
        if (fadeProgress >= 1f && prevBitmap != null) {
            prevBitmap = null
        }

        canvas.save()
        canvas.translate(0f, tr.offsetYFrac * side)
        canvas.rotate(tr.rotationDeg, x + side * 0.5f, pivotY)
        canvas.scale(tr.scaleX, tr.scaleY, x + side * 0.5f, pivotY)

        val activeBmp = retainedCel?.takeIf { !it.isRecycled } ?: displayed.takeIf { !it.isRecycled }
        if (activeBmp != null) {
            if (outBmp != null && !outBmp.isRecycled && fadeProgress < 1f) {
                prevPaint.alpha = ((1f - fadeProgress) * drawAlpha).toInt().coerceIn(0, 255)
                canvas.drawBitmap(outBmp, prevSource, destination, prevPaint)
                paint.alpha = (fadeProgress * drawAlpha).toInt().coerceIn(0, 255)
                canvas.drawBitmap(activeBmp, displayedSource(), destination, paint)
                paint.alpha = drawAlpha
            } else {
                paint.alpha = drawAlpha
                canvas.drawBitmap(activeBmp, displayedSource(), destination, paint)
            }
        }
        canvas.restore()

        if (!pose.reduced && ambientTickAllowed) {
            scheduleNextFrame(immediate = false)
        }
    }

    override fun getIntrinsicWidth() = PetCels.CELL
    override fun getIntrinsicHeight() = PetCels.CELL
    override fun setAlpha(alpha: Int) {
        if (drawAlpha != alpha) {
            drawAlpha = alpha
            paint.alpha = alpha
            invalidateSelf()
        }
    }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        prevPaint.colorFilter = colorFilter
        invalidateSelf()
    }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun close() {
        closed = true
        onAtlasReady = null
        vsyncPosted = false
        runCatching { Choreographer.getInstance().removeFrameCallback(choreographerCallback) }
        main.removeCallbacks(fallbackTick)
        prevBitmap = null
        retainedCel = null
        cache.clear()
    }
    companion object {
        const val MAX_ATLASES = 12
        const val MAX_BITMAP_BYTES = 56L * 1024 * 1024
    }
}
