package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleAgentBrainEnhancerTest {

    @Test
    fun memoryConsolidatorMergesDuplicatesAndResolvesNewerPreferenceConflicts() {
        val items = listOf(
            WhaleAgentBrainEnhancer.MemoryConsolidator.MemoryItem("m1", "偏好深色模式和极简回复风格", 100L),
            WhaleAgentBrainEnhancer.MemoryConsolidator.MemoryItem("m2", "偏好深色模式和极简回复风格，不要多余客套", 200L),
            WhaleAgentBrainEnhancer.MemoryConsolidator.MemoryItem("m3", "常用城市：北京", 150L),
            WhaleAgentBrainEnhancer.MemoryConsolidator.MemoryItem("m4", "常用城市：上海", 300L),
            WhaleAgentBrainEnhancer.MemoryConsolidator.MemoryItem("m5", "正在备考 5G 基带数字芯片设计面试", 250L),
        )
        val plan = WhaleAgentBrainEnhancer.MemoryConsolidator.computePlan(items)
        // m1 与 m2 高度相似，保留较新的 m2，淘汰 m1
        assertTrue("m1" in plan.duplicateIdsToDrop)
        // m3 与 m4 同属「常用城市」冲突，保留较新的 m4（上海），淘汰 m3（北京）
        assertTrue("m3" in plan.supersededConflictIdsToDrop)
        assertEquals(3, plan.keptCount)
    }

    @Test
    fun modelRouterDownshiftsExpensiveModelsOnInternalTasks() {
        assertEquals("deepseek-chat", WhaleModelRouter.route("deepseek-v4-pro", "你好", false, 0.1f).model)
        assertEquals("deepseek-chat", WhaleModelRouter.route("deepseek-reasoner", "查一下今天天气", false, 0.9f).model)
    }

    @Test
    fun checkpointRelevanceBlocksCrossTopicHijackOnShortReplies() {
        val staleSalaryCheckpoint = "用户核心目标：制作谈薪 Excel 对比表，计算总包与期权税后收入，已保存至 salary_compare.xlsx。"
        val newTopicHistory = listOf(
            "user" to "帮我看看这个开源项目 Kotabo-Zhao/whale-assistant",
            "assistant" to "要不要我去翻一下这个 GitHub 项目的源码结构？",
        )
        // 用户回复短接续句“好呀，直接做”，旧谈薪检查点与当前 GitHub 话题无重叠，必须拦截防止串号
        assertEquals(
            false,
            WhaleHarnessCore.PrefixCacheCompactor.isCheckpointRelevant(staleSalaryCheckpoint, newTopicHistory, "好呀，直接做"),
        )
        // 若仍在谈薪话题中，则允许沿用检查点
        val sameTopicHistory = listOf(
            "user" to "谈薪 Excel 里把年终奖也加上",
            "assistant" to "好的，我准备更新 salary_compare.xlsx，要继续吗？",
        )
        assertEquals(
            true,
            WhaleHarnessCore.PrefixCacheCompactor.isCheckpointRelevant(staleSalaryCheckpoint, sameTopicHistory, "好呀，直接做"),
        )
    }
}
