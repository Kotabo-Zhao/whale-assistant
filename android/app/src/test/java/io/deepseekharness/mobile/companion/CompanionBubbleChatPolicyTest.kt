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
    @Test fun localMarkdownAttachmentIsExtractedWithoutAcceptingOtherPaths() {
        val hash = "ab" + "1".repeat(62)
        val result = CompanionBubbleChatPolicy.parseInlineImages("截图： ![手机](</root/.dsh/attachments/v1/objects/ab/$hash>)")
        assertEquals("截图：", result.text)
        assertEquals(listOf("sha256:$hash"), result.images)
        assertTrue(CompanionBubbleChatPolicy.parseInlineImages("![x](</root/.dsh/attachments/v1/objects/ff/$hash>)").images.isEmpty())
        assertTrue(CompanionBubbleChatPolicy.parseInlineImages("![x](https://example.com/x)").images.isEmpty())
    }
    @Test fun messageMustBeNonemptyAndBounded() {
        assertEquals("你好", CompanionBubbleChatPolicy.validateMessage(" 你好 "))
        assertThrows(IllegalArgumentException::class.java) { CompanionBubbleChatPolicy.validateMessage(" ") }
        assertThrows(IllegalArgumentException::class.java) { CompanionBubbleChatPolicy.validateMessage("甲".repeat(2001)) }
    }
}
