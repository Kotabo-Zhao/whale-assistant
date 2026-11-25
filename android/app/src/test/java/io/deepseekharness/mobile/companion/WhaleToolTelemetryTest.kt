package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WhaleToolTelemetryTest {

    @Before
    fun setUp() {
        WhaleToolTelemetry.clear()
    }

    @Test
    fun `records tool latency, success rate, and highlights flaky tools`() {
        WhaleToolTelemetry.record("whale_phone_vision_click", ok = true, elapsedMs = 400L)
        WhaleToolTelemetry.record("whale_phone_vision_click", ok = false, elapsedMs = 800L, errorHint = "未找到目标")
        WhaleToolTelemetry.record("whale_code_run", ok = true, elapsedMs = 200L)

        val snap = WhaleToolTelemetry.snapshot()
        assertEquals(3, snap.getInt("totalCalls"))
        assertEquals(1, snap.getInt("totalFailures"))

        val flaky = snap.getJSONArray("flakyTools")
        assertEquals(1, flaky.length())
        val first = flaky.getJSONObject(0)
        assertEquals("whale_phone_vision_click", first.getString("tool"))
        assertEquals(50, first.getInt("successRatePct"))
        assertEquals(600L, first.getLong("avgElapsedMs"))
        assertTrue(first.getString("lastError").contains("未找到目标"))
    }
}
