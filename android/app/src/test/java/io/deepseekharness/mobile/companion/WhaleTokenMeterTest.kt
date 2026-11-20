package io.deepseekharness.mobile.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class WhaleTokenMeterTest {

    @Test
    fun compactLabelFormatsSmallThousandAndMillionTokensWithCost() {
        val small = WhaleTokenMeter.Snapshot(
            day = "2026-11-20",
            promptTokens = 320,
            cacheHitTokens = 100,
            completionTokens = 180,
            costMicroYuan = 2_000,
            dailyBudgetTokens = 200_000,
        )
        assertEquals(500L, small.totalTokens)
        assertEquals("✨ 500", small.compactLabel())
        assertTrue(small.progress in 0.002f..0.003f)

        val mid = WhaleTokenMeter.Snapshot(
            day = "2026-11-20",
            promptTokens = 10_000,
            cacheHitTokens = 4_000,
            completionTokens = 2_500,
            costMicroYuan = 34_000,
            dailyBudgetTokens = 200_000,
        )
        assertEquals(12_500L, mid.totalTokens)
        assertEquals("✨ 12.5k · ¥0.03", mid.compactLabel())

        val overBudget = WhaleTokenMeter.Snapshot(
            day = "2026-11-20",
            promptTokens = 1_200_000,
            cacheHitTokens = 0,
            completionTokens = 300_000,
            costMicroYuan = 4_800_000,
            dailyBudgetTokens = 200_000,
        )
        assertEquals(1.0f, overBudget.progress, 0.0001f)
        assertEquals("✨ 1.5M · ¥4.80", overBudget.compactLabel())
    }

    @Test
    fun extractsCacheHitTokensAcrossAllMajorProviderFormats() {
        assertEquals(
            4200L,
            WhaleTokenMeter.extractCacheHitTokens(JSONObject().put("prompt_cache_hit_tokens", 4200)),
        )
        assertEquals(
            8192L,
            WhaleTokenMeter.extractCacheHitTokens(
                JSONObject().put("prompt_tokens_details", JSONObject().put("cached_tokens", 8192)),
            ),
        )
        assertEquals(
            3100L,
            WhaleTokenMeter.extractCacheHitTokens(JSONObject().put("cache_read_input_tokens", 3100)),
        )
    }

    @Test
    fun pricesDeepSeekPeakAndOffPeakAndClaudeTiersAccurately() {
        val offPeakSat = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.NOVEMBER, 21, 8, 0, 0) // Saturday 08:00 UTC -> off-peak
        }.timeInMillis
        val peakFri = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.NOVEMBER, 20, 8, 0, 0) // Friday 08:00 UTC -> peak
        }.timeInMillis

        assertFalse(WhaleTokenMeter.isDeepSeekPeakUtc(offPeakSat))
        assertTrue(WhaleTokenMeter.isDeepSeekPeakUtc(peakFri))

        val offPeakCost = WhaleTokenMeter.estimateCostMicroYuan(100_000, 10_000, 90_000, "deepseek-chat", offPeakSat)
        val peakCost = WhaleTokenMeter.estimateCostMicroYuan(100_000, 10_000, 90_000, "deepseek-chat", peakFri)
        assertEquals(offPeakCost * 2L, peakCost)

        // Claude Haiku 5.5 should be far cheaper than Claude Opus 5.5
        val haiku = WhaleTokenMeter.estimateCostMicroYuan(100_000, 10_000, 0, "claude-haiku-5.5", offPeakSat)
        val opus = WhaleTokenMeter.estimateCostMicroYuan(100_000, 10_000, 0, "claude-opus-5.5", offPeakSat)
        assertTrue(haiku * 20L < opus)
    }
}
