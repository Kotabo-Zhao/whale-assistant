package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionAssistantAutoMemoryTest {
    @Test fun `automatic mode saves proposed preference and makes it searchable`() {
        val rows = JSONArray()
        val engine = CompanionMemoryEngine({ rows }, {}, true)
        val result = JSONObject(engine.execute("""{"op":"save","text":"我喜欢喝咖啡"}"""))
        assertFalse(result.getBoolean("pending"))
        assertEquals("confirmed", rows.getJSONObject(0).getString("status"))
        assertEquals(1, JSONObject(engine.execute("""{"op":"search","query":"咖啡"}""")).getJSONArray("items").length())
    }
    @Test fun `old pending entry stays pending when automatic mode is enabled`() {
        val rows = JSONArray()
        val original = CompanionMemoryEngine({ rows }, {})
        assertTrue(JSONObject(original.execute("""{"op":"save","text":"我喜欢喝茶"}""")).getBoolean("pending"))
        val automatic = CompanionMemoryEngine({ rows }, {}, true)
        val result = JSONObject(automatic.execute("""{"op":"save","text":"我喜欢喝茶"}"""))
        assertTrue(result.getBoolean("alreadyExists")); assertTrue(result.getBoolean("pending"))
        assertEquals(0, JSONObject(automatic.execute("""{"op":"search","query":"茶"}""")).getJSONArray("items").length())
    }
    @Test fun `disabled mode still requires review and sensitive content is rejected`() {
        val rows = JSONArray()
        val engine = CompanionMemoryEngine({ rows }, {})
        assertTrue(JSONObject(engine.execute("""{"op":"save","text":"我爱散步"}""")).getBoolean("pending"))
        assertThrows(Exception::class.java) { CompanionMemoryEngine({ rows }, {}, true).execute("""{"op":"save","text":"验证码123456"}""") }
    }
}
