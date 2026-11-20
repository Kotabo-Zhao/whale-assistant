package io.deepseekharness.mobile.overlay

import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * True 60 FPS Continuous Live2D-Style Skeletal Rig Renderer for Whale-Chan.
 *
 * - ZERO hard cuts, ZERO frame jumps, ZERO blurry alpha ghosting.
 * - Every single joint (head tilt, body lean/bounce/breath, independent left/right arms,
 *   independent left/right legs, continuous eyelid blink/droop/happy-arc, blush, mouth,
 *   and animated state props) interpolates smoothly at 60 FPS via exponential spring damping.
 * - Every frame is continuously computed in real time so all 17 states transition seamlessly.
 */
internal class CompanionPetDrawable(
    private val resources: Resources,
    private val targetSize: Int,
    private val decoder: Executor,
    private val main: Handler = Handler(Looper.getMainLooper()),
) : Drawable(), AutoCloseable {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rigRect = RectF(0f, 0f, RIG_BASE, RIG_BASE)
    private val ovalRect = RectF()
    private val path = Path()

    private val parts = LinkedHashMap<String, Bitmap>(6)
    @Volatile private var closed = false
    private var pose = PetPose()
    val currentPose get() = pose
    var currentCel = PetCels.sample(pose)
        private set
    val nextFrameDelayMs get() = if (pose.reduced) Long.MAX_VALUE else 16L
    private var displayedClip = "idle"
    var onAtlasReady: (() -> Unit)? = null
    val displayedClipName get() = displayedClip
    val cachedAtlasCount get() = parts.size
    val cachedBitmapBytes get() = parts.values.sumOf { if (it.isRecycled) 0L else it.allocationByteCount.toLong() }
    val cacheLimitBytes = MAX_BITMAP_BYTES

    // Continuous 60 FPS spring-damped skeletal state (13 DOF)
    private var curHeadRot = 0f
    private var curBodyRot = 0f
    private var curBodyDx = 0f
    private var curBodyDy = 0f
    private var curBodySx = 1f
    private var curBodySy = 1f
    private var curArmL = 0f
    private var curArmR = 0f
    private var curLegL = 0f
    private var curLegR = 0f
    private var curEyeClose = 0f
    private var curHappyEye = 0f
    private var curBlush = 0f
    private var curMouthOpen = 0f
    private var curPivotY = 472f
    private var curPropScale = 0f
    private var activeProp = PROP_NONE

    private var lastDrawMs = 0L
    private val frameTick = Runnable {
        if (!closed && !pose.reduced) invalidateSelf()
    }

    init {
        for (name in PART_NAMES) {
            parts[name] = decodePart(name)
        }
    }

    private fun decodePart(name: String): Bitmap {
        val side = targetSize.coerceIn(160, 512)
        return resources.assets.open("pet-rig/$name.webp").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inScaled = true
                inDensity = 512
                inTargetDensity = side
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: error("Missing pet rig part: $name")
    }

    fun setPose(next: PetPose, cel: PetCelSample = PetCels.sample(next)) {
        if (closed) return
        pose = next
        currentCel = cel
        displayedClip = cel.clip
        invalidateSelf()
    }

    fun setAnimatedPose(next: PetPose, playback: PetPlayback, now: Long, quietIdle: Boolean) {
        if (closed) return
        pose = next
        currentCel = PetCels.sample(next)
        displayedClip = if (quietIdle && next.action == PetAction.IDLE) "idle" else currentCel.clip
        invalidateSelf()
    }

    /** Preserves continuous skeletal state seamlessly across stepless window resizes. */
    fun retainVisibleFrom(other: CompanionPetDrawable) {
        pose = other.pose
        currentCel = other.currentCel
        displayedClip = other.displayedClip
        curHeadRot = other.curHeadRot
        curBodyRot = other.curBodyRot
        curBodyDx = other.curBodyDx
        curBodyDy = other.curBodyDy
        curBodySx = other.curBodySx
        curBodySy = other.curBodySy
        curArmL = other.curArmL
        curArmR = other.curArmR
        curLegL = other.curLegL
        curLegR = other.curLegR
        curEyeClose = other.curEyeClose
        curHappyEye = other.curHappyEye
        curBlush = other.curBlush
        curMouthOpen = other.curMouthOpen
        curPivotY = other.curPivotY
        curPropScale = other.curPropScale
        activeProp = other.activeProp
    }

    /** Test/export helper. */
    fun prepareForExport(action: PetAction, elapsed: Long = 0) {
        setPose(PetPose(action, elapsed))
    }

    override fun draw(canvas: Canvas) {
        if (closed) return
        val side = minOf(bounds.width(), bounds.height()).toFloat()
        if (side <= 0f) return
        val left = bounds.exactCenterX() - side / 2f
        val top = bounds.exactCenterY() - side / 2f

        val now = SystemClock.uptimeMillis()
        val dt = if (lastDrawMs == 0L) 0.016f else ((now - lastDrawMs).coerceIn(4L, 50L) / 1000f)
        lastDrawMs = now
        val t = (now % 3_600_000L) / 1000.0

        // Natural continuous blinking wave (~every 3.8s, smooth 200ms close & open)
        val blinkCycle = (t % 3.8)
        val autoBlink = when {
            blinkCycle < 0.09 -> (blinkCycle / 0.09).toFloat()
            blinkCycle < 0.13 -> 1f
            blinkCycle < 0.23 -> (1.0 - (blinkCycle - 0.13) / 0.10).toFloat()
            else -> 0f
        }

        // Compute continuous target rig pose for ALL 17 states (+ drink easter egg)
        var tHeadRot = 0f
        var tBodyRot = 0f
        var tBodyDx = 0f
        var tBodyDy = 0f
        var tBodySx = 1f
        var tBodySy = 1f
        var tArmL = 0f
        var tArmR = 0f
        var tLegL = 0f
        var tLegR = 0f
        var tEyeClose = autoBlink
        var tHappyEye = 0f
        var tBlush = 0.15f
        var tMouthOpen = 0f
        var tPivotY = 472f
        var wantProp = PROP_NONE

        if (!pose.reduced) {
            val isDrink = pose.action == PetAction.IDLE && displayedClip == "drink"
            when {
                isDrink -> {
                    val sip = sin(t * 2.0 * PI / 1.8).toFloat()
                    tHeadRot = 3.5f * sip
                    tBodySy = 1f + 0.018f * sip
                    tBodySx = 1f - 0.012f * sip
                    tArmL = -42f - 5f * sip
                    tArmR = 42f + 5f * sip
                    tBlush = 0.45f
                    wantProp = PROP_BOBA
                }
                pose.action == PetAction.IDLE -> {
                    val b = sin(t * 2.0 * PI / 3.0).toFloat()
                    val sway = sin(t * 2.0 * PI / 5.4).toFloat()
                    tHeadRot = 3.6f * sway
                    tBodyRot = 1.4f * sway
                    tBodyDy = -5f * b
                    tBodySx = 1f - 0.014f * b
                    tBodySy = 1f + 0.020f * b
                    tArmL = -4f + 5f * sin(t * 2.0 * PI / 3.0 - 0.5).toFloat()
                    tArmR = 4f - 5f * sin(t * 2.0 * PI / 3.0 - 0.5).toFloat()
                    tLegL = 1.5f * sway
                    tLegR = -1.5f * sway
                }
                pose.action == PetAction.THINK -> {
                    val s = sin(t * 2.0 * PI / 2.2).toFloat()
                    tHeadRot = -7f + 4f * s
                    tBodyRot = 2f * s
                    tBodyDy = -4f * abs(s)
                    tArmL = -48f - 4f * s
                    tArmR = 46f + 4f * s
                    tEyeClose = maxOf(autoBlink, 0.12f)
                    wantProp = PROP_BOOK
                }
                pose.action == PetAction.WORK -> {
                    val tap = sin(t * 2.0 * PI / 0.36).toFloat()
                    val sway = sin(t * 2.0 * PI / 1.6).toFloat()
                    tHeadRot = 4f * sway
                    tBodyDy = -3f * abs(tap)
                    tBodySy = 1f + 0.015f * tap
                    tBodySx = 1f - 0.012f * tap
                    tArmL = -46f + 12f * tap
                    tArmR = 46f + 12f * tap
                    tLegL = 3f * sway
                    tLegR = -3f * sway
                    wantProp = PROP_KEYBOARD
                }
                pose.action == PetAction.CONTROL -> {
                    val wave = sin(t * 2.0 * PI / 1.1).toFloat()
                    tHeadRot = -4f + 3f * wave
                    tBodyRot = -3f * wave
                    tBodyDy = -7f * abs(wave)
                    tArmL = -82f + 22f * wave
                    tArmR = 14f - 8f * wave
                    tLegL = -5f * wave
                    tLegR = 5f * wave
                    wantProp = PROP_WAND
                }
                pose.action == PetAction.OBSERVE -> {
                    val peer = sin(t * 2.0 * PI / 2.4).toFloat()
                    tHeadRot = 6f * peer
                    tBodyRot = 3.5f * peer
                    tBodyDx = 6f * peer
                    tArmL = -78f + 10f * peer
                    tArmR = 10f
                    wantProp = PROP_MAGNIFIER
                }
                pose.action == PetAction.ACCEPT -> {
                    val hop = abs(sin(t * 2.0 * PI / 0.9)).toFloat()
                    tHeadRot = -5f * hop
                    tBodyDy = -16f * hop
                    tBodySy = 1f + 0.035f * hop
                    tBodySx = 1f - 0.025f * hop
                    tArmL = -115f - 8f * hop
                    tArmR = 12f + 6f * hop
                    tLegL = -8f * hop
                    tLegR = 8f * hop
                    tHappyEye = 0.65f
                    tMouthOpen = 0.45f
                }
                pose.action == PetAction.ASK -> {
                    val tilt = sin(t * 2.0 * PI / 2.0).toFloat()
                    tHeadRot = 11f + 3f * tilt
                    tBodyRot = 2.5f * tilt
                    tArmL = -12f
                    tArmR = 72f + 6f * tilt
                    tMouthOpen = 0.25f
                }
                pose.action == PetAction.REPLY -> {
                    val talk = abs(sin(t * 2.0 * PI / 0.34)).toFloat()
                    val wave = sin(t * 2.0 * PI / 0.85).toFloat()
                    tHeadRot = 4.5f * wave
                    tBodyDy = -6f * abs(wave)
                    tArmL = -88f + 24f * wave
                    tArmR = 18f + 10f * wave
                    tMouthOpen = 0.2f + 0.7f * talk
                    tBlush = 0.35f
                }
                pose.action == PetAction.NOTICE -> {
                    val ring = sin(t * 2.0 * PI / 0.32).toFloat()
                    tHeadRot = 5f * ring
                    tBodyDy = -8f * abs(ring)
                    tArmL = -86f + 22f * ring
                    tArmR = 16f
                    tMouthOpen = 0.55f
                    wantProp = PROP_BELL
                }
                pose.action == PetAction.CELEBRATE -> {
                    val jump = abs(sin(t * 2.0 * PI / 0.68)).toFloat()
                    val wiggle = sin(t * 2.0 * PI / 1.36).toFloat()
                    tHeadRot = 6f * wiggle
                    tBodyDy = -28f * jump
                    tBodySy = 1f + 0.048f * jump
                    tBodySx = 1f - 0.036f * jump
                    tArmL = -112f - 14f * jump
                    tArmR = 112f + 14f * jump
                    tLegL = -16f * jump
                    tLegR = 16f * jump
                    tHappyEye = 1f
                    tEyeClose = 1f
                    tBlush = 0.75f
                    tMouthOpen = 0.85f
                }
                pose.action == PetAction.TOUCH -> {
                    val bounce = abs(sin(t * 2.0 * PI / 0.62)).toFloat()
                    val wig = sin(t * 2.0 * PI / 0.9).toFloat()
                    tHeadRot = 7f * wig
                    tBodyDy = -10f * bounce
                    tBodySx = 1f + 0.055f * (1f - bounce)
                    tBodySy = 1f - 0.050f * (1f - bounce)
                    tArmL = -68f - 10f * bounce
                    tArmR = 68f + 10f * bounce
                    tHappyEye = 1f
                    tEyeClose = 1f
                    tBlush = 0.95f
                    tMouthOpen = 0.4f
                }
                pose.action == PetAction.SETBACK -> {
                    val shiv = sin(t * 2.0 * PI / 0.38).toFloat()
                    tHeadRot = -8f + 2.5f * shiv
                    tBodyDx = 4f * shiv
                    tBodyDy = 6f
                    tBodySy = 0.96f
                    tArmL = -34f + 5f * shiv
                    tArmR = 34f - 5f * shiv
                    tBlush = 0.65f
                    tMouthOpen = 0.35f
                }
                pose.action == PetAction.ERROR -> {
                    val shiv = sin(t * 2.0 * PI / 0.30).toFloat()
                    tHeadRot = 4f * shiv
                    tBodyDx = 5f * shiv
                    tArmL = -52f
                    tArmR = 52f
                    tEyeClose = 0f
                    tMouthOpen = 0.55f
                    wantProp = PROP_WARN
                }
                pose.action == PetAction.DRAG -> {
                    val swing = sin(t * 2.0 * PI / 0.92).toFloat()
                    val kick = sin(t * 2.0 * PI / 0.46).toFloat()
                    tPivotY = 120f
                    tBodyRot = 12f * swing
                    tHeadRot = -6f * swing
                    tBodyDy = -12f + 4f * cos(t * 2.0 * PI / 0.92).toFloat()
                    tArmL = -58f + 16f * kick
                    tArmR = 58f + 16f * kick
                    tLegL = 22f * kick
                    tLegR = -22f * kick
                    tEyeClose = 0f
                    tBlush = 0.7f
                    tMouthOpen = 0.6f
                }
                pose.action == PetAction.WAIT -> {
                    val b = sin(t * 2.0 * PI / 3.4).toFloat()
                    tHeadRot = 2.5f * sin(t * 2.0 * PI / 5.0).toFloat()
                    tBodyDy = 14f - 3f * b
                    tBodySy = 0.97f + 0.015f * b
                    tArmL = -16f
                    tArmR = 16f
                    tLegL = -6f
                    tLegR = 6f
                }
                pose.action == PetAction.PAUSED -> {
                    val doze = sin(t * 2.0 * PI / 3.6).toFloat()
                    tHeadRot = 9f + 4f * doze
                    tBodyDy = 12f + 2f * doze
                    tBodySx = 1f + 0.016f * doze
                    tBodySy = 0.98f - 0.014f * doze
                    tArmL = -10f
                    tArmR = 56f + 4f * doze
                    tEyeClose = 0.62f + 0.14f * doze
                }
                pose.action == PetAction.STOPPED -> {
                    val sleep = sin(t * 2.0 * PI / 4.2).toFloat()
                    tHeadRot = 13f + 2.5f * sleep
                    tBodyDy = 18f + 2f * sleep
                    tBodySx = 1f + 0.022f * sleep
                    tBodySy = 0.96f - 0.018f * sleep
                    tArmL = -24f
                    tArmR = 24f
                    tEyeClose = 1f
                }
            }
        }

        // Smooth frame-rate-independent exponential spring damping (ZERO sudden jumps across ANY state change!)
        val rate = if (pose.reduced) 1f else (1f - kotlin.math.exp(-10.5f * dt))
        val fastRate = if (pose.reduced) 1f else (1f - kotlin.math.exp(-18.0f * dt))
        curHeadRot += (tHeadRot - curHeadRot) * rate
        curBodyRot += (tBodyRot - curBodyRot) * rate
        curBodyDx += (tBodyDx - curBodyDx) * rate
        curBodyDy += (tBodyDy - curBodyDy) * rate
        curBodySx += (tBodySx - curBodySx) * rate
        curBodySy += (tBodySy - curBodySy) * rate
        curArmL += (tArmL - curArmL) * rate
        curArmR += (tArmR - curArmR) * rate
        curLegL += (tLegL - curLegL) * rate
        curLegR += (tLegR - curLegR) * rate
        curEyeClose += (tEyeClose - curEyeClose) * fastRate
        curHappyEye += (tHappyEye - curHappyEye) * rate
        curBlush += (tBlush - curBlush) * rate
        curMouthOpen += (tMouthOpen - curMouthOpen) * fastRate
        curPivotY += (tPivotY - curPivotY) * rate

        // Smooth prop scale-in / scale-out (zero hard pop!)
        if (wantProp != activeProp) {
            curPropScale += (0f - curPropScale) * fastRate
            if (curPropScale < 0.06f) {
                activeProp = wantProp
            }
        } else {
            val targetPropScale = if (activeProp == PROP_NONE) 0f else 1f
            curPropScale += (targetPropScale - curPropScale) * rate
        }

        // Render in unified 512x512 rig coordinate space scaled cleanly to any stepless window size!
        val scale = side / RIG_BASE
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)

        // Whole-body continuous transform
        canvas.translate(curBodyDx, curBodyDy)
        canvas.rotate(curBodyRot, 256f, curPivotY)
        canvas.scale(curBodySx, curBodySy, 256f, curPivotY)

        // 1. Legs (back layer, swinging from hip sockets)
        drawPart(canvas, "leg_l", curLegL, 234f, 372f)
        drawPart(canvas, "leg_r", curLegR, 278f, 372f)

        // 2. Arms (rotating seamlessly from rounded shoulder sockets)
        drawPart(canvas, "arm_l", curArmL, 217f, 269f)
        drawPart(canvas, "arm_r", curArmR, 295f, 269f)

        // 3. Torso (sweater + navy pleated skirt)
        parts["torso"]?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, null, rigRect, bitmapPaint) }

        // 4. Head + live continuous facial animation (eyelids, happy arcs, blush, mouth)
        canvas.save()
        canvas.rotate(curHeadRot, 256f, 238f)
        parts["head"]?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, null, rigRect, bitmapPaint) }
        drawLiveFaceOverlays(canvas)
        canvas.restore()

        // 5. Smoothly scaled animated props
        if (activeProp != PROP_NONE && curPropScale > 0.03f) {
            drawAnimatedProp(canvas, activeProp, curPropScale, t)
        }

        // 6. Crisp 60 FPS state accent particles
        if (!pose.reduced) {
            drawStateParticles(canvas, t)
        }

        canvas.restore()

        if (!pose.reduced) {
            main.removeCallbacks(frameTick)
            main.postDelayed(frameTick, 16L)
        }
    }

    private fun drawPart(canvas: Canvas, name: String, rotDeg: Float, px: Float, py: Float) {
        val bmp = parts[name] ?: return
        if (bmp.isRecycled) return
        canvas.save()
        canvas.rotate(rotDeg, px, py)
        canvas.drawBitmap(bmp, null, rigRect, bitmapPaint)
        canvas.restore()
    }

    private fun drawLiveFaceOverlays(canvas: Canvas) {
        // Rosy blush glow
        if (curBlush > 0.05f) {
            val a = (curBlush * 130f).toInt().coerceIn(0, 140)
            fxPaint.style = Paint.Style.FILL
            fxPaint.color = (0x00FF8CA8) or (a shl 24)
            ovalRect.set(192f, 194f, 224f, 210f)
            canvas.drawOval(ovalRect, fxPaint)
            ovalRect.set(288f, 194f, 320f, 210f)
            canvas.drawOval(ovalRect, fxPaint)
        }

        // Continuous smooth eyelids (covers open eyes seamlessly from 0.0 -> 1.0!)
        if (curEyeClose > 0.03f) {
            fxPaint.style = Paint.Style.FILL
            fxPaint.color = SKIN_COLOR
            val lidH = 44f * curEyeClose.coerceIn(0f, 1f)
            // Left eye cover (rig coords: x=196..238, y=154..198)
            ovalRect.set(194f, 152f, 240f, 154f + lidH + 4f)
            canvas.drawRoundRect(ovalRect, 14f, 14f, fxPaint)
            // Right eye cover (rig coords: x=272..314, y=154..198)
            ovalRect.set(270f, 152f, 316f, 154f + lidH + 4f)
            canvas.drawRoundRect(ovalRect, 14f, 14f, fxPaint)

            // Crisp curved lash line tracking the eyelid edge
            strokePaint.color = LASH_COLOR
            strokePaint.strokeWidth = 3.8f
            val lashY = 156f + lidH * 0.85f
            val arch = if (curHappyEye > 0.4f) -9f * curHappyEye else 3.5f * curEyeClose
            // Left lash
            path.reset()
            path.moveTo(199f, lashY)
            path.quadTo(217f, lashY + arch, 235f, lashY)
            canvas.drawPath(path, strokePaint)
            // Right lash
            path.reset()
            path.moveTo(275f, lashY)
            path.quadTo(293f, lashY + arch, 311f, lashY)
            canvas.drawPath(path, strokePaint)
        }

        // Continuous animated mouth (rig center ~(256, 208))
        if (curMouthOpen > 0.06f) {
            val rx = 6.5f + 4.5f * curMouthOpen
            val ry = 2.5f + 7.5f * curMouthOpen
            fxPaint.style = Paint.Style.FILL
            fxPaint.color = MOUTH_FILL
            ovalRect.set(256f - rx, 206f - ry * 0.3f, 256f + rx, 206f + ry * 1.15f)
            canvas.drawOval(ovalRect, fxPaint)
            strokePaint.color = LASH_COLOR
            strokePaint.strokeWidth = 2.2f
            canvas.drawOval(ovalRect, strokePaint)
        }
    }

    private fun drawAnimatedProp(canvas: Canvas, prop: Int, scale: Float, t: Double) {
        val bob = sin(t * 2.0 * PI / 1.5).toFloat() * 4f
        canvas.save()
        when (prop) {
            PROP_BOBA -> {
                canvas.translate(256f, 326f + bob)
                canvas.scale(scale, scale)
                fxPaint.style = Paint.Style.FILL
                fxPaint.color = 0xFFD8F0FF.toInt()
                ovalRect.set(-22f, -24f, 22f, 28f)
                canvas.drawRoundRect(ovalRect, 10f, 10f, fxPaint)
                fxPaint.color = 0xFF7CC6FF.toInt()
                ovalRect.set(-18f, -6f, 18f, 24f)
                canvas.drawRoundRect(ovalRect, 8f, 8f, fxPaint)
                strokePaint.color = 0xFF3E5573.toInt(); strokePaint.strokeWidth = 3f
                ovalRect.set(-22f, -24f, 22f, 28f)
                canvas.drawRoundRect(ovalRect, 10f, 10f, strokePaint)
                canvas.drawLine(0f, -44f, 0f, -12f, strokePaint)
            }
            PROP_BOOK -> {
                canvas.translate(256f, 324f + bob)
                canvas.scale(scale, scale)
                fxPaint.style = Paint.Style.FILL
                fxPaint.color = 0xFF6BC3FF.toInt()
                ovalRect.set(-34f, -22f, 34f, 24f)
                canvas.drawRoundRect(ovalRect, 8f, 8f, fxPaint)
                fxPaint.color = 0xFFFFFFFF.toInt()
                ovalRect.set(-28f, -16f, 28f, 18f)
                canvas.drawRoundRect(ovalRect, 5f, 5f, fxPaint)
                strokePaint.color = 0xFF257AF4.toInt(); strokePaint.strokeWidth = 3f
                canvas.drawLine(0f, -16f, 0f, 18f, strokePaint)
            }
            PROP_KEYBOARD -> {
                canvas.translate(256f, 332f + bob * 0.6f)
                canvas.scale(scale, scale)
                fxPaint.style = Paint.Style.FILL
                fxPaint.color = 0xCC70C6FF.toInt()
                ovalRect.set(-54f, -16f, 54f, 18f)
                canvas.drawRoundRect(ovalRect, 9f, 9f, fxPaint)
                strokePaint.color = 0xFF257AF4.toInt(); strokePaint.strokeWidth = 2.6f
                canvas.drawRoundRect(ovalRect, 9f, 9f, strokePaint)
            }
            PROP_WAND -> {
                canvas.translate(154f, 242f + bob)
                canvas.rotate(-28f + 10f * sin(t * 5.0).toFloat())
                canvas.scale(scale, scale)
                strokePaint.color = 0xFF257AF4.toInt(); strokePaint.strokeWidth = 5f
                canvas.drawLine(0f, 24f, 0f, -26f, strokePaint)
                fxPaint.style = Paint.Style.FILL
                fxPaint.color = 0xFF8FD3FF.toInt()
                canvas.drawCircle(0f, -30f, 12f, fxPaint)
            }
            PROP_MAGNIFIER -> {
                canvas.translate(184f, 200f + bob)
                canvas.scale(scale, scale)
                fxPaint.style = Paint.Style.FILL
                fxPaint.color = 0x669BD6FF.toInt()
                canvas.drawCircle(0f, 0f, 26f, fxPaint)
                strokePaint.color = 0xFF257AF4.toInt(); strokePaint.strokeWidth = 5f
                canvas.drawCircle(0f, 0f, 26f, strokePaint)
                canvas.drawLine(18f, 18f, 38f, 38f, strokePaint)
            }
            PROP_BELL -> {
                canvas.translate(154f, 240f + bob)
                canvas.rotate(18f * sin(t * 16.0).toFloat())
                canvas.scale(scale, scale)
                fxPaint.style = Paint.Style.FILL
                fxPaint.color = 0xFFB8DCFF.toInt()
                ovalRect.set(-18f, -18f, 18f, 16f)
                canvas.drawArc(ovalRect, 180f, 180f, true, fxPaint)
                canvas.drawRect(-18f, -2f, 18f, 12f, fxPaint)
                strokePaint.color = 0xFF3E5573.toInt(); strokePaint.strokeWidth = 3f
                canvas.drawLine(-22f, 12f, 22f, 12f, strokePaint)
            }
            PROP_WARN -> {
                canvas.translate(256f, 322f + bob)
                canvas.scale(scale, scale)
                path.reset()
                path.moveTo(0f, -28f); path.lineTo(28f, 22f); path.lineTo(-28f, 22f); path.close()
                fxPaint.style = Paint.Style.FILL
                fxPaint.color = 0xFFFFD166.toInt()
                canvas.drawPath(path, fxPaint)
                strokePaint.color = 0xFF5A4020.toInt(); strokePaint.strokeWidth = 3.5f
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(0f, -10f, 0f, 6f, strokePaint)
                fxPaint.color = 0xFF5A4020.toInt()
                canvas.drawCircle(0f, 14f, 3f, fxPaint)
            }
        }
        canvas.restore()
    }

    private fun drawStateParticles(canvas: Canvas, t: Double) {
        when (pose.action) {
            PetAction.CELEBRATE, PetAction.TOUCH, PetAction.ACCEPT -> {
                val colors = intArrayOf(0xFFFFD166.toInt(), 0xFFFF8FAB.toInt(), 0xFF6BC3FF.toInt())
                fxPaint.style = Paint.Style.FILL
                for (i in 0 until 3) {
                    val phase = ((t * 0.72 + i * 0.33) % 1.0).toFloat()
                    val alpha = (sin(phase * PI).toFloat() * 225f).toInt().coerceIn(0, 225)
                    val px = 110f + i * 145f + sin(t * 3.0 + i).toFloat() * 14f
                    val py = 155f - phase * 115f
                    val r = 10f + 5f * sin(phase * PI).toFloat()
                    fxPaint.color = (colors[i] and 0x00FFFFFF) or (alpha shl 24)
                    canvas.drawCircle(px, py, r, fxPaint)
                }
            }
            PetAction.PAUSED, PetAction.STOPPED -> {
                fxPaint.style = Paint.Style.FILL
                fxPaint.textSize = 44f
                fxPaint.textAlign = Paint.Align.CENTER
                for (i in 0 until 2) {
                    val phase = ((t * 0.36 + i * 0.5) % 1.0).toFloat()
                    val alpha = (sin(phase * PI).toFloat() * 200f).toInt().coerceIn(0, 200)
                    fxPaint.color = 0x006BC3FF or (alpha shl 24)
                    canvas.drawText(if (i == 0) "z" else "Z", 365f + phase * 65f, 155f - phase * 105f, fxPaint)
                }
            }
            PetAction.THINK, PetAction.OBSERVE, PetAction.ASK -> {
                fxPaint.style = Paint.Style.FILL
                for (i in 0 until 3) {
                    val phase = ((t * 0.55 + i * 0.33) % 1.0).toFloat()
                    val alpha = (sin(phase * PI).toFloat() * 180f).toInt().coerceIn(0, 180)
                    fxPaint.color = 0x005CA4FF or (alpha shl 24)
                    canvas.drawCircle(375f + i * 24f, 130f - phase * 65f, 7f + i * 2.5f, fxPaint)
                }
            }
            PetAction.CONTROL, PetAction.WORK -> {
                val pulse = ((t * 1.05) % 1.0).toFloat()
                val alpha = ((1f - pulse) * 145f).toInt().coerceIn(0, 145)
                strokePaint.color = 0x00257AF4 or (alpha shl 24)
                strokePaint.strokeWidth = 6f
                canvas.drawCircle(256f, 265f, 150f + pulse * 85f, strokePaint)
            }
            else -> Unit
        }
    }

    override fun getIntrinsicWidth() = PetCels.CELL
    override fun getIntrinsicHeight() = PetCels.CELL
    override fun setAlpha(alpha: Int) { if (bitmapPaint.alpha != alpha) { bitmapPaint.alpha = alpha; invalidateSelf() } }
    override fun setColorFilter(colorFilter: ColorFilter?) { bitmapPaint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun close() {
        closed = true
        onAtlasReady = null
        main.removeCallbacks(frameTick)
        parts.values.forEach { if (!it.isRecycled) it.recycle() }
        parts.clear()
    }

    companion object {
        private const val RIG_BASE = 512f
        private const val SKIN_COLOR = 0xFFFEEBDD.toInt()
        private const val LASH_COLOR = 0xFF4B3842.toInt()
        private const val MOUTH_FILL = 0xFFFF8F9E.toInt()
        private const val PROP_NONE = 0
        private const val PROP_BOBA = 1
        private const val PROP_BOOK = 2
        private const val PROP_KEYBOARD = 3
        private const val PROP_WAND = 4
        private const val PROP_MAGNIFIER = 5
        private const val PROP_BELL = 6
        private const val PROP_WARN = 7
        private val PART_NAMES = listOf("head", "torso", "arm_l", "arm_r", "leg_l", "leg_r")
        const val MAX_ATLASES = 6
        const val MAX_BITMAP_BYTES = 16L * 1024 * 1024
    }
}
