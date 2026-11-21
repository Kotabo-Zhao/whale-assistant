package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleCognitiveEngineTest {

    @Test
    fun modelRouterDownshiftsExpensiveReasonerForShortChatOrHighBudget() {
        val fastShort = WhaleModelRouter.route("deepseek-reasoner", "在吗？", hasImages = false, dailyBudgetProgress = 0.1f)
        assertEquals("deepseek-chat", fastShort.model)
        assertEquals(WhaleModelRouter.Tier.FAST, fastShort.tier)

        val deepPlan = WhaleModelRouter.route("deepseek-reasoner", "帮我深度分析一下这个架构重构方案的利弊", hasImages = false, dailyBudgetProgress = 0.2f)
        assertEquals("deepseek-reasoner", deepPlan.model)
        assertEquals(WhaleModelRouter.Tier.DEEP, deepPlan.tier)

        val vision = WhaleModelRouter.route("deepseek-v4-pro", "看图", hasImages = true, dailyBudgetProgress = 0.9f)
        assertEquals("deepseek-v4-pro", vision.model)
        assertEquals(WhaleModelRouter.Tier.DEEP, vision.tier)
    }

    @Test
    fun extractSuggestionsStripsInlineMarkerAndParsesUpToThreeChips() {
        val raw = "好的，我已经帮你把明天早上的日程整理好了。\n<!--whale_next:[\"顺手设个8点闹钟\",\"查看本周其他日程\",\"导出成待办清单\",\"忽略第四条\"]-->"
        val (clean, chips) = WhaleNativeAgentEngine.extractSuggestions(raw)
        assertEquals("好的，我已经帮你把明天早上的日程整理好了。", clean)
        assertEquals(listOf("顺手设个8点闹钟", "查看本周其他日程", "导出成待办清单"), chips)
        assertTrue(!clean.contains("whale_next"))
    }

    @Test
    fun inferFallbackSuggestionsGeneratesChoiceAndYesNoQuickReplies() {
        val ab = WhaleNativeAgentEngine.inferFallbackSuggestions("美团那单吃饭的事，你还没说今晚吃还是改明天？")
        assertEquals(listOf("今晚吃", "改明天", "先不急"), ab)

        val yn = WhaleNativeAgentEngine.inferFallbackSuggestions("刚给你建的习惯打卡小工具，要不要现在先塞两条习惯进去试试？")
        assertEquals(listOf("好呀，直接做", "先不用了", "我有补充"), yn)

        val plain = WhaleNativeAgentEngine.inferFallbackSuggestions("已经帮你把桌宠尺寸调到 160dp 了。")
        assertTrue(plain.isEmpty())
    }

    @Test
    fun skillMarkdownAndGithubUrlResolution() {
        val urls = WhaleSkillStore.resolveCandidateUrls("https://github.com/acme/weather-skill")
        assertTrue(urls.contains("https://raw.githubusercontent.com/acme/weather-skill/main/SKILL.md"))
        val parsed = WhaleSkillStore.parseSkillMarkdown(
            "---\nname: weather-brief\ndescription: 查询天气并给出穿衣建议\n---\n# 步骤\n1. 查询天气",
        )
        assertEquals("weather-brief", parsed.slug)
        assertEquals("查询天气并给出穿衣建议", parsed.description)
    }

    @Test
    fun mcpRpcBodyParsesJsonAndSseFrames() {
        val direct = WhaleMcpManager.parseRpcBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}")
        assertEquals(1, direct.getInt("id"))
        val sse = WhaleMcpManager.parseRpcBody("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"ok\":true}}\n\n")
        assertEquals(2, sse.getInt("id"))
        assertEquals("https://mcp.example.com/sse", WhaleMcpManager.validateUrl("https://mcp.example.com/sse"))
    }
}
