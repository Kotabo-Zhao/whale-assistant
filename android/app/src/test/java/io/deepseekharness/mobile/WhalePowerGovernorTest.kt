package io.deepseekharness.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class WhalePowerGovernorTest {
    @Test
    fun selectsEcoWhenPowerSaveOrLowBatteryOrReduceMotion() {
        assertEquals(WhalePowerGovernor.Profile.ECO, WhalePowerGovernor.resolve(80, false, true, false))
        assertEquals(WhalePowerGovernor.Profile.ECO, WhalePowerGovernor.resolve(80, true, false, true))
        assertEquals(WhalePowerGovernor.Profile.ECO, WhalePowerGovernor.resolve(12, false, false, false))
        assertEquals(0.0f, WhalePowerGovernor.durationScale(WhalePowerGovernor.Profile.ECO), 0.001f)
    }

    @Test
    fun selectsBalancedOnMidBatteryAndPerformanceWhenHealthyOrCharging() {
        assertEquals(WhalePowerGovernor.Profile.BALANCED, WhalePowerGovernor.resolve(25, false, false, false))
        assertEquals(WhalePowerGovernor.Profile.PERFORMANCE, WhalePowerGovernor.resolve(12, true, false, false))
        assertEquals(WhalePowerGovernor.Profile.PERFORMANCE, WhalePowerGovernor.resolve(70, false, false, false))
        assertEquals(2, WhalePowerGovernor.pollIntervalMultiplier(WhalePowerGovernor.Profile.BALANCED))
    }
}
