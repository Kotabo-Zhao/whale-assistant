package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionBubbleChatPolicyTest {
    @Test fun proactiveUsesOnlyConfirmedBoundedMemory() {
        val rows = JSONArray().put(JSONObject().put("text", "喜欢喝茶").put("status", "confirmed"))
            .put(JSONObject().put("text", "未确认").put("status", "pending"))
            .put(JSONObject().put("text", "甲".repeat(101)).put("status", "confirmed"))
        assertEquals("喜欢喝茶", CompanionBubbleChatPolicy.proactiveMemory(rows))
        assertNull(CompanionBubbleChatPolicy.proactiveMemory(rows, CompanionBubbleChatPolicy.memoryDigest("喜欢喝茶")))
        assertNull(CompanionBubbleChatPolicy.proactiveMemory(JSONArray().put(JSONObject().put("text", "候选").put("status", "pending"))))
    }
    @Test fun messageMustBeNonemptyAndBounded() {
        assertEquals("你好", CompanionBubbleChatPolicy.validateMessage(" 你好 "))
        assertThrows(IllegalArgumentException::class.java) { CompanionBubbleChatPolicy.validateMessage(" ") }
        assertThrows(IllegalArgumentException::class.java) { CompanionBubbleChatPolicy.validateMessage("甲".repeat(2001)) }
    }
}
