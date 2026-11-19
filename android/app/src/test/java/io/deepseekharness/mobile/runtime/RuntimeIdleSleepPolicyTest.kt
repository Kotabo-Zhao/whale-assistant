package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeIdleSleepPolicyTest {
    @Test
    fun keepsAwakeWhenWorkerOrTerminalOrForegroundActive() {
        val base = RuntimeIdleSleepPolicy.Snapshot(
            runtimeRunning = true,
            activeWorkerCount = 0,
            terminalOpen = false,
            virtualScreenActive = false,
            appInForeground = false,
            idleDurationMs = 10 * 60 * 1_000L,
        )
        assertEquals(
            RuntimeIdleSleepPolicy.Decision.KEEP_AWAKE,
            RuntimeIdleSleepPolicy.evaluate(base.copy(activeWorkerCount = 1)),
        )
        assertEquals(
            RuntimeIdleSleepPolicy.Decision.KEEP_AWAKE,
            RuntimeIdleSleepPolicy.evaluate(base.copy(terminalOpen = true)),
        )
        assertEquals(
            RuntimeIdleSleepPolicy.Decision.KEEP_AWAKE,
            RuntimeIdleSleepPolicy.evaluate(base.copy(appInForeground = true)),
        )
    }

    @Test
    fun hibernatesAfterIdleThresholdAndTightensInBatterySaver() {
        val idleThreeMinutes = RuntimeIdleSleepPolicy.Snapshot(
            runtimeRunning = true,
            activeWorkerCount = 0,
            terminalOpen = false,
            virtualScreenActive = false,
            appInForeground = false,
            idleDurationMs = 3 * 60 * 1_000L,
            batterySaverEnabled = false,
        )
        assertEquals(
            RuntimeIdleSleepPolicy.Decision.KEEP_AWAKE,
            RuntimeIdleSleepPolicy.evaluate(idleThreeMinutes),
        )
        assertEquals(
            RuntimeIdleSleepPolicy.Decision.HIBERNATE_PROOT,
            RuntimeIdleSleepPolicy.evaluate(idleThreeMinutes.copy(batterySaverEnabled = true)),
        )
        assertEquals(
            RuntimeIdleSleepPolicy.Decision.HIBERNATE_PROOT,
            RuntimeIdleSleepPolicy.evaluate(idleThreeMinutes.copy(idleDurationMs = 5 * 60 * 1_000L)),
        )
    }
}
