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
}
