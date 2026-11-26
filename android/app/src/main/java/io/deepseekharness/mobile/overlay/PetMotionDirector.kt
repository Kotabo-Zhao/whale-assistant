package io.deepseekharness.mobile.overlay

import kotlin.math.abs
import kotlin.math.sin

/**
 * 桌宠 60fps 高阶动效与自主演出导演（纯 Kotlin，可纯 JVM 单测）。
 *
 * 解决原有桌宠「状态切换生硬硬切、帧率抖动、待机单调、拖动缺物理惯性」四大痛点：
 * 1. **180ms 缓出交叉淡化（Crossfade）**：任意状态切换平滑溶图，消除跳帧硬切；
 * 2. **阻尼弹簧物理引擎（Spring-Damper）**：状态切换微挤压弹起、落地弹性回弹、拖动速度实时惯性摆动；
 * 3. **6 幕自主待机剧场（28 秒循环）**：待机时自然串联站姿、小碎步、抱臂哈气、干饭、看书、音符蹦跳/深夜小憩，
 *    让桌宠在桌面真正“活”起来。
 */
internal class PetMotionDirector {

    data class Transform(
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
        val offsetYFrac: Float = 0f,
        val rotationDeg: Float = 0f,
        val pivotYFrac: Float = 0.92f,
    )

    private var sx = 1f
    private var sy = 1f
    private var dy = 0f
    private var rot = 0f
    private var pivot = 0.92f

    private var vSx = 0f
    private var vSy = 0f
    private var vDy = 0f
    private var vRot = 0f

    @Volatile private var dragVelX = 0f

    fun setDragVelocity(vxPxPerSec: Float) {
        dragVelX = vxPxPerSec.coerceIn(-2400f, 2400f)
    }

    fun clearDragVelocity() {
        dragVelX = 0f
    }

    /** 任意状态/动作切换时注入轻盈微挤压弹性脉冲，配合 180ms 溶图过渡。 */
    fun triggerTransitionPop(strength: Float = 1f) {
        val s = strength.coerceIn(0.4f, 1.5f)
        vSx += 0.55f * s
        vSy -= 0.65f * s
        vDy += 0.08f * s
    }

    /** 拖动松手落地或轻点时注入 Q 弹落地挤压回弹。 */
    fun triggerLandingBounce() {
        vSx += 1.05f
        vSy -= 1.25f
        vDy += 0.16f
    }

    fun reset() {
        sx = 1f; sy = 1f; dy = 0f; rot = 0f; pivot = 0.92f
        vSx = 0f; vSy = 0f; vDy = 0f; vRot = 0f
        dragVelX = 0f
    }

    /**
     * 按真实帧间隔 [dtSec] 推进阻尼弹簧与二次动效（60fps / 120fps 均帧率无关）。
     */
    fun step(action: PetAction, nowMs: Long, dtSec: Float, reduced: Boolean): Transform {
        if (reduced) {
            reset()
            return Transform()
        }
        val dt = dtSec.coerceIn(0.004f, 0.045f)
        val sec = (nowMs % 120_000L) / 1000.0

        var targetSx = 1f
        var targetSy = 1f
        var targetDy = 0f
        var targetRot = 0f
        var targetPivot = 0.92f

        when (action) {
            PetAction.IDLE, PetAction.WAIT -> {
                val breath = sin(sec * 2.0 * Math.PI / 2.8).toFloat()
                val sway = sin(sec * 2.0 * Math.PI / 5.6).toFloat()
                targetSx = 1f - 0.014f * breath
                targetSy = 1f + 0.018f * breath
                targetDy = -0.010f * breath
                targetRot = 1.6f * sway
            }
            PetAction.PAUSED, PetAction.STOPPED -> {
                val slow = sin(sec * 2.0 * Math.PI / 3.8).toFloat()
                targetSx = 1f + 0.018f * slow
                targetSy = 1f - 0.015f * slow
                targetDy = 0.005f * slow
            }
            PetAction.DRAG -> {
                val swing = sin(sec * 2.0 * Math.PI / 0.85).toFloat()
                val velTilt = (dragVelX / 150f).coerceIn(-14f, 14f)
                targetPivot = 0.20f
                targetRot = (velTilt + 5.5f * swing).coerceIn(-18f, 18f)
                targetSx = 0.98f
                targetSy = 1.03f
                // 衰减拖动惯性速度，松手后自然归零
                dragVelX *= (1f - (dt * 7f).coerceAtMost(0.9f))
            }
            PetAction.CELEBRATE, PetAction.ACCEPT, PetAction.REPLY, PetAction.NOTICE -> {
                val hop = abs(sin(sec * 2.0 * Math.PI / 0.72)).toFloat()
                targetSx = 1f - 0.024f * hop + 0.014f * (1f - hop)
                targetSy = 1f + 0.032f * hop - 0.014f * (1f - hop)
                targetDy = -0.028f * hop
                targetRot = 3.2f * sin(sec * 2.0 * Math.PI / 0.72).toFloat()
            }
            PetAction.TOUCH -> {
                val bounce = sin(sec * 2.0 * Math.PI / 0.55).toFloat()
                targetSx = 1f + 0.025f * bounce
                targetSy = 1f - 0.025f * bounce
                targetDy = -0.014f * abs(bounce)
                targetRot = 2.4f * bounce
            }
            PetAction.CONTROL, PetAction.WORK -> {
                val pulse = abs(sin(sec * 2.0 * Math.PI / 0.90)).toFloat()
                targetSx = 1f - 0.015f * pulse
                targetSy = 1f + 0.020f * pulse
                targetDy = -0.016f * pulse
                targetRot = 2.2f * sin(sec * 2.0 * Math.PI / 1.8).toFloat()
            }
            else -> {
                val gentle = sin(sec * 2.0 * Math.PI / 2.2).toFloat()
                targetSx = 1f - 0.010f * gentle
                targetSy = 1f + 0.013f * gentle
                targetDy = -0.008f * gentle
            }
        }

        // 欠阻尼弹簧积分（弹性鲜活但快速收敛，无震荡发散）
        val k = 150f
        val c = 18.5f
        vSx += ((targetSx - sx) * k - vSx * c) * dt
        vSy += ((targetSy - sy) * k - vSy * c) * dt
        vDy += ((targetDy - dy) * k - vDy * c) * dt
        vRot += ((targetRot - rot) * (k * 0.85f) - vRot * (c * 0.92f)) * dt

        sx = (sx + vSx * dt).coerceIn(0.84f, 1.18f)
        sy = (sy + vSy * dt).coerceIn(0.84f, 1.18f)
        dy = (dy + vDy * dt).coerceIn(-0.12f, 0.12f)
        rot = (rot + vRot * dt).coerceIn(-22f, 22f)
        pivot += (targetPivot - pivot) * (dt * 12f).coerceAtMost(1f)

        return Transform(sx, sy, dy, rot, pivot)
    }

    companion object {
        const val CROSSFADE_MS = 180L
        const val IDLE_REEL_CYCLE_MS = 28_000L

        /** 缓出三次方溶图曲线，保证切换平滑无突兀感。 */
        fun crossfadeProgress(elapsedMs: Long, durationMs: Long = CROSSFADE_MS): Float {
            if (durationMs <= 0L || elapsedMs >= durationMs) return 1f
            if (elapsedMs <= 0L) return 0f
            val t = elapsedMs.toFloat() / durationMs.toFloat()
            val inv = 1f - t
            return (1f - inv * inv * inv).coerceIn(0f, 1f)
        }

        /**
         * 乌萨奇 6 幕自主待机剧场（28 秒无缝循环）：
         * 站姿呼吸 → 小碎步活跃 → 抱臂「ハァ？」→ 干饭吃披萨 → 捧书看 → 音符蹦跳（深夜 23:00–05:59 自动切换为趴地小憩）。
         */
        fun resolveUsagiIdleClip(elapsedMs: Long, hourOfDay: Int): String {
            val phase = elapsedMs.coerceAtLeast(0L) % IDLE_REEL_CYCLE_MS
            val isLateNight = hourOfDay >= 23 || hourOfDay <= 5
            return when {
                phase < 6_000L -> "idle"
                phase < 10_000L -> "control"
                phase < 14_000L -> "touch"
                phase < 19_000L -> "drink"
                phase < 23_500L -> if (isLateNight) "sleep" else "reading"
                else -> if (isLateNight) "sleep" else "celebrate"
            }
        }
    }
}
