package io.deepseekharness.mobile.runtime

/**
 * PRoot / Node 本地运行时空闲休眠与功耗治理策略（纯决策逻辑，便于 JVM 单测）。
 *
 * 目标：避免应用在无活跃 Worker、无终端且无近期对话时长期空转 PRoot 容器和持有前台保活，
 * 显著降低待机耗电与发热；收到新对话或触发器时可随时按需冷/热唤醒。
 */
object RuntimeIdleSleepPolicy {
    /** 默认空闲 5 分钟后允许休眠 PRoot 容器。 */
    const val DEFAULT_IDLE_THRESHOLD_MS = 5 * 60 * 1_000L

    /** 系统省电模式下缩短至 2 分钟。 */
    const val BATTERY_SAVER_IDLE_THRESHOLD_MS = 2 * 60 * 1_000L

    enum class Decision {
        /** 存在活跃任务、终端或近期交互，保持运行。 */
        KEEP_AWAKE,

        /** 已无活跃负载且超过空闲阈值，释放保活并休眠 PRoot 容器。 */
        HIBERNATE_PROOT,
    }

    data class Snapshot(
        val runtimeRunning: Boolean,
        val activeWorkerCount: Int,
        val terminalOpen: Boolean,
        val virtualScreenActive: Boolean,
        val appInForeground: Boolean,
        val idleDurationMs: Long,
        val batterySaverEnabled: Boolean = false,
        val customIdleThresholdMs: Long? = null,
    )

    fun effectiveThresholdMs(snapshot: Snapshot): Long {
        val base = snapshot.customIdleThresholdMs?.coerceAtLeast(30_000L)
            ?: DEFAULT_IDLE_THRESHOLD_MS
        return if (snapshot.batterySaverEnabled) {
            minOf(base, BATTERY_SAVER_IDLE_THRESHOLD_MS)
        } else {
            base
        }
    }

    fun evaluate(snapshot: Snapshot): Decision {
        if (!snapshot.runtimeRunning) return Decision.KEEP_AWAKE
        if (snapshot.activeWorkerCount > 0) return Decision.KEEP_AWAKE
        if (snapshot.terminalOpen || snapshot.virtualScreenActive) return Decision.KEEP_AWAKE
        if (snapshot.appInForeground) return Decision.KEEP_AWAKE

        val threshold = effectiveThresholdMs(snapshot)
        return if (snapshot.idleDurationMs >= threshold) {
            Decision.HIBERNATE_PROOT
        } else {
            Decision.KEEP_AWAKE
        }
    }
}
