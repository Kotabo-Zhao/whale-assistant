package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionBubbleHistoryTest {
    @org.junit.Test fun preservesLongAssistantReplies() {
        val text = "长回复".repeat(2_000)
        val snapshot = CompanionBubbleHistory.Snapshot("session-1", "a".repeat(64), listOf(
            CompanionBubbleChat.Message("user", "请详细回答"), CompanionBubbleChat.Message("assistant", text)))
        org.junit.Assert.assertEquals(text, CompanionBubbleHistory.decode(CompanionBubbleHistory.encode(snapshot)).messages.last().text)
    }
    @Test fun roundTripAndRejectMalformedSnapshot() {
        val snapshot = CompanionBubbleHistory.Snapshot("session-1", "a".repeat(64), listOf(
            CompanionBubbleChat.Message("user", "你好"), CompanionBubbleChat.Message("assistant", "欢迎回来")))
        assertEquals(snapshot, CompanionBubbleHistory.decode(CompanionBubbleHistory.encode(snapshot)))
        assertThrows(IllegalArgumentException::class.java) { CompanionBubbleHistory.encode(snapshot.copy(scope = "invalid")) }
        assertThrows(IllegalArgumentException::class.java) { CompanionBubbleHistory.encode(snapshot.copy(messages = snapshot.messages.reversed())) }
    }
    @Test fun preservesCitationCardsAndReadsVersionOneWithoutThem() {
        val citation = CompanionBubbleChat.Citation("00000000-1111-2222-3333-444444444444", "a".repeat(64), "资料.txt", null, null, 0, 1, "complete")
        val snapshot = CompanionBubbleHistory.Snapshot("session-1", "b".repeat(64), listOf(
            CompanionBubbleChat.Message("user", "你好"), CompanionBubbleChat.Message("assistant", "答案", citations = listOf(citation))))
        assertEquals(citation, CompanionBubbleHistory.decode(CompanionBubbleHistory.encode(snapshot)).messages[1].citations.single())
        val old = org.json.JSONObject().put("version", 1).put("sessionId", "session-1").put("scope", "b".repeat(64))
            .put("messages", org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("text", "你好").put("images", org.json.JSONArray()))
                .put(org.json.JSONObject().put("role", "assistant").put("text", "答案").put("images", org.json.JSONArray())))
        assertTrue(CompanionBubbleHistory.decode(old.toString().toByteArray()).messages[1].citations.isEmpty())
    }
}
