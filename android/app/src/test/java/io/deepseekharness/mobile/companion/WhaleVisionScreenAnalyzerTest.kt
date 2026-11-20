package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleVisionScreenAnalyzerTest {

    @Test
    fun extractJsonObjectParsesRawMarkdownAndProseWrappedCoordinates() {
        val raw = """{"found": true, "x": 540, "y": 1220, "label": "搜索图标"}"""
        val p1 = WhaleVisionScreenAnalyzer.extractJsonObject(raw)
        assertNotNull(p1)
        assertTrue(p1!!.getBoolean("found"))
        assertEquals(540, p1.getInt("x"))
        assertEquals(1220, p1.getInt("y"))
        assertEquals("搜索图标", p1.getString("label"))

        val fenced = "```json\n{\"found\": true, \"x\": 120, \"y\": 340, \"label\": \"返回\"}\n```"
        val p2 = WhaleVisionScreenAnalyzer.extractJsonObject(fenced)
        assertNotNull(p2)
        assertEquals(120, p2!!.getInt("x"))
        assertEquals(340, p2.getInt("y"))

        val withProse = "好的，我找到了目标按钮：{\"found\": false, \"reason\": \"当前画面未出现关闭按钮\"} 请确认。"
        val p3 = WhaleVisionScreenAnalyzer.extractJsonObject(withProse)
        assertNotNull(p3)
        assertEquals(false, p3!!.getBoolean("found"))

        assertNull(WhaleVisionScreenAnalyzer.extractJsonObject("没有找到任何 JSON 对象"))
        assertNull(WhaleVisionScreenAnalyzer.extractJsonObject("{invalid json"))
    }
}
