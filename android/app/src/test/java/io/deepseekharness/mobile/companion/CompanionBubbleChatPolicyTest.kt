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
    @Test fun toolScopeChangesOnlyWithPermissionOrCapabilityChanges() {
        assertEquals(
            CompanionBubbleChatPolicy.toolScope(true, true, true, false),
            CompanionBubbleChatPolicy.toolScope(true, true, true, false),
        )
        assertNotEquals(
            CompanionBubbleChatPolicy.toolScope(true, true, true, false),
            CompanionBubbleChatPolicy.toolScope(false, true, false, false),
        )
    }
    @Test fun newSessionContextKeepsRecentMessagesAndBoundsOlderExcerpts() {
        val messages = (0 until 10).map { index ->
            CompanionBubbleChat.Message(if (index % 2 == 0) "user" else "assistant", "第$index条" + "甲".repeat(900))
        }
        val value = CompanionBubbleChatPolicy.compressedContext(messages)
        assertTrue(value.contains("第9条"))
        assertTrue(value.contains("早前摘录"))
        assertTrue(value.length <= 8_000)
    }
}
