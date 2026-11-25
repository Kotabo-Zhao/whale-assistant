package io.deepseekharness.mobile.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleCapabilityPrimerTest {

    @Test
    fun `primer injects exact signatures for phone, python, and emotion queries`() {
        val phone = WhaleCapabilityPrimer.buildPrimer("帮我打开美团截屏看一下")
        assertTrue(phone.contains("whale_phone_screen"))
        assertTrue(phone.contains("whale_phone_vision_click"))

        val py = WhaleCapabilityPrimer.buildPrimer("用 python 画一个柱状图并导出 pdf")
        assertTrue(py.contains("whale_code_run"))

        val emo = WhaleCapabilityPrimer.buildPrimer("你现在心情怎么样，性格可以更温柔一点")
        assertTrue(emo.contains("whale_emotion_state"))
    }

    @Test
    fun `fuzzy tool suggestion recovers from typos and guessed names`() {
        val pool = listOf("whale_phone_click", "whale_phone_vision_click", "whale_code_run", "whale_emotion_state", "whale_app_control")
        val hits = WhaleCapabilityPrimer.suggestClosestTools("phone_click", pool)
        assertTrue(hits.isNotEmpty())
        assertEquals("whale_phone_click", hits.first())
    }

    @Test
    fun `normalized 0-1000 vision coordinates map accurately to physical screen resolution`() {
        val json = JSONObject().put("nx", 500).put("ny", 250)
        val (x, y) = WhaleVisionScreenAnalyzer.resolveCoordinates(json, screenWidth = 1260, screenHeight = 2800)
        assertEquals(630, x)
        assertEquals(700, y)
    }
}
