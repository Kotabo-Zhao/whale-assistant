package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionBubbleChatPolicyTest {
    @org.junit.Test fun collectsAllAssistantMessagesWithoutDuplicatingUpdates() {
        val replies = BubbleReplyCollector()
        org.junit.Assert.assertEquals("第一段\n包含第二个文本块", replies.put("message-1", listOf("第一段", "包含第二个文本块")))
        org.junit.Assert.assertEquals("第一段\n包含第二个文本块\n\n第二段", replies.put("message-2", listOf("第二段")))
        org.junit.Assert.assertEquals("第一段\n包含第二个文本块\n\n第二段（更新）", replies.put("message-2", listOf("第二段（更新）")))
    }
    @Test fun personaIsInjectedWithCharacterMacrosAndKeepsPermissionsSeparate() {
        val card = JSONObject().put("name", "小鲸").put("description", "{{char}}陪伴{{user}}")
            .put("personality", "温柔").put("scenario", "手机桌面").put("examples", "用户：你好\n小鲸：你好呀")
        val prompt = CompanionBubbleChatPolicy.personaContext(card)
        assertTrue(prompt.contains("小鲸陪伴用户"))
        assertTrue(prompt.contains("温柔")); assertTrue(prompt.contains("示例对话"))
        assertTrue(prompt.contains("不能改变工具权限"))
    }
    @Test fun petRoleplayUsesMatchingWorldEntriesAndPostHistory() {
        val entries = JSONArray().put(JSONObject().put("keys", JSONArray().put("灯塔")).put("content", "{{char}}守护灯塔"))
            .put(JSONObject().put("keys", JSONArray().put("森林")).put("content", "不应出现"))
            .put(JSONObject().put("constant", true).put("enabled", false).put("content", "禁用条目"))
        val card = JSONObject().put("name", "小鲸").put("worldBook", JSONObject().put("entries", entries).toString())
            .put("systemPrompt", "说话简洁").put("postHistory", "{{char}}保持角色")
        val prompt = PetRoleplayPrompt.character(card, listOf(CompanionBubbleChat.Message("user", "我们去灯塔")))
        assertTrue(prompt.contains("小鲸守护灯塔")); assertTrue(prompt.contains("说话简洁"))
        assertFalse(prompt.contains("不应出现")); assertFalse(prompt.contains("禁用条目"))
        assertEquals("小鲸保持角色", PetRoleplayPrompt.afterHistory(card))
    }
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
            CompanionBubbleChat.Message(if (index % 2 == 0) "user" else "assistant", "第${index}条" + "甲".repeat(900))
        }
        val value = CompanionBubbleChatPolicy.compressedContext(messages)
        assertTrue(value.contains("第9条"))
        assertTrue(value.contains("早前摘录"))
        assertTrue(value.length <= 8_000)
    }
    @Test fun extractsOnlyStableKnowledgeCitationsFromToolResults() {
        val id = "00000000-1111-2222-3333-444444444444"
        val revision = "a".repeat(64)
        val value = JSONObject().put("items", JSONArray().put(JSONObject().put("id", id).put("revision", revision)
            .put("documentId", "b".repeat(64)).put("displayName", "合同.pdf").put("chunkIndex", 1).put("chunkCount", 3).put("parsingCoverage", "partial")
            .put("excerpt", "不应进入来源卡的正文")))
        val citations = CompanionBubbleChatPolicy.citationsFromToolResult(value.toString())
        assertEquals(1, citations.size)
        assertEquals("合同.pdf", citations.single().displayName)
        assertEquals(1, citations.single().chunkIndex)
        assertEquals(" · 保存文本第 2/3 段（非页码）", CompanionBubbleChatPolicy.citationSegmentLabel(citations.single()))
        assertEquals(" · 部分解析", CompanionBubbleChatPolicy.citationCoverageLabel(citations.single()))
        assertEquals(" · 模型图片描述", CompanionBubbleChatPolicy.citationCoverageLabel(citations.single().copy(parsingCoverage = "model_description")))
        assertTrue(CompanionBubbleChatPolicy.citationsFromToolResult(JSONObject().put("id", id).toString()).isEmpty())
        assertTrue(CompanionBubbleChatPolicy.citationsFromToolResult(JSONObject().put("id", id).put("revision", revision).put("text", "记忆正文").toString()).isEmpty())
    }
    @Test fun sourceCardsCollapseMultipleChunksFromOneDocument() {
        val document = "c".repeat(64)
        val rows = JSONArray()
        repeat(3) { index -> rows.put(JSONObject().put("id", "00000000-1111-2222-3333-44444444444$index").put("revision", "a".repeat(64))
            .put("documentId", document).put("displayName", "长文档.txt").put("chunkIndex", index).put("chunkCount", 3)) }
        assertEquals(1, CompanionBubbleChatPolicy.citationsFromToolResult(JSONObject().put("items", rows).toString()).size)
    }
    @Test fun sourceCardsExcludeUnmentionedSearchCandidatesAndKeepLaterMatches() {
        val rows = JSONArray()
        listOf("无关甲.pdf", "无关乙.pdf", "observatory_file.txt").forEachIndexed { index, name ->
            rows.put(JSONObject().put("id", "00000000-1111-2222-3333-44444444444$index").put("revision", "a".repeat(64))
                .put("documentId", (index + 1).toString().repeat(64)).put("displayName", name))
        }
        val candidates = CompanionBubbleChatPolicy.citationsFromToolResult(JSONObject().put("items", rows).toString())
        val selected = CompanionBubbleChatPolicy.citedSources("根据 observatory\\_file.txt 的记录，观测在周四。", candidates)
        assertEquals(listOf("observatory_file.txt"), selected.map { it.displayName })
        val read = candidates[2].copy(id = "00000000-1111-2222-3333-444444444449")
        val duplicateName = candidates[2].copy(documentId = "f".repeat(64))
        assertEquals(listOf(read), CompanionBubbleChatPolicy.citedSources("observatory_file.txt", listOf(read, duplicateName)))
        assertTrue(CompanionBubbleChatPolicy.citedSources("没有提及文件名的回复", candidates).isEmpty())
        assertTrue(CompanionBubbleChatPolicy.citedSources("本地资料", listOf(candidates[0].copy(displayName = "本地资料"))).isEmpty())
    }
    @Test fun knowledgeGuidanceUsesExplicitRecentAndRejectsInventedCoordinates() {
        val guidance = CompanionBubbleChatPolicy.knowledgeToolGuidance()
        assertTrue(guidance.contains("mobile_knowledge_search")); assertTrue(guidance.contains("mobile_knowledge_recent"))
        assertTrue(guidance.contains("mobile_knowledge_read")); assertTrue(guidance.contains("id、revision 与 location"))
        assertTrue(guidance.contains("不得推断"))
    }
    @Test fun memoryGuidanceRequiresReadingAReferencedDocumentAgain() {
        val guidance = CompanionBubbleChatPolicy.memoryToolGuidance()
        assertTrue(guidance.contains("mobile_memory_search")); assertTrue(guidance.contains("sourceRecordId/sourceRevision"))
        assertTrue(guidance.contains("mobile_knowledge_read")); assertTrue(guidance.contains("不能把记忆当作原文"))
    }
    @Test fun capabilityGuidanceExplainsRuntimeInjectedSessionIdentity() {
        val guidance = CompanionBubbleChatPolicy.capabilityGuidance()
        assertTrue(guidance.contains("mobile_companion_capabilities"))
        assertTrue(guidance.contains("不得填写 callerSession"))
        assertTrue(guidance.contains("自动附带"))
    }
}
