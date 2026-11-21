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
}
