package io.deepseekharness.mobile.companion

import org.json.JSONObject
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

    @Test
    fun parseRefHandlesIntegerAndHashPrefixedStrings() {
        assertEquals(12, WhaleVisionScreenAnalyzer.parseRef(JSONObject("""{"ref": 12}""")))
        assertEquals(12, WhaleVisionScreenAnalyzer.parseRef(JSONObject("""{"ref": "#12"}""")))
        assertEquals(7, WhaleVisionScreenAnalyzer.parseRef(JSONObject("""{"ref": " #7 "}""")))
        assertNull(WhaleVisionScreenAnalyzer.parseRef(JSONObject("""{"ref": null}""")))
        assertNull(WhaleVisionScreenAnalyzer.parseRef(JSONObject("""{"ref": 0}""")))
    }

    @Test
    fun resolveCoordinatesMapsNormalized0To1000AndUnitFraction() {
        val (x1, y1) = WhaleVisionScreenAnalyzer.resolveCoordinates(
            JSONObject("""{"nx": 500, "ny": 250}"""),
            screenWidth = 1080,
            screenHeight = 2400,
        )
        assertEquals(540, x1)
        assertEquals(600, y1)

        // 偶发 0..1 比例坐标不应被错误压缩到 (1, 1)
        val (x2, y2) = WhaleVisionScreenAnalyzer.resolveCoordinates(
            JSONObject("""{"nx": 0.5, "ny": 0.25}"""),
            screenWidth = 1080,
            screenHeight = 2400,
        )
        assertEquals(540, x2)
        assertEquals(600, y2)

        // 兼容旧模型直接输出像素 x/y
        val (x3, y3) = WhaleVisionScreenAnalyzer.resolveCoordinates(
            JSONObject("""{"x": 300, "y": 800}"""),
            screenWidth = 1080,
            screenHeight = 2400,
        )
        assertEquals(300, x3)
        assertEquals(800, y3)
    }
}
