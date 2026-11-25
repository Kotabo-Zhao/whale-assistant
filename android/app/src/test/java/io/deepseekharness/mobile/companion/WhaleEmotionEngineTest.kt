package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleEmotionEngineTest {

    @Test
    fun `tired user shifts mood to CARING and increases empathy and warmth`() {
        val base = WhaleEmotionEngine.defaultState("default", "鲸鱼娘")
        val next = WhaleEmotionEngine.evolveFromTurn(base, "今天加班太累了，头疼得要崩溃", hourOfDay = 23)
        assertEquals(WhaleEmotionEngine.Mood.CARING, next.mood)
        assertTrue(next.empathy > base.empathy)
        assertTrue(next.warmth > base.warmth)
        assertTrue(next.trust > base.trust)
        assertEquals(1, next.interactions)
    }

    @Test
    fun `praise and playful banter cultivate bond level and playfulness`() {
        var state = WhaleEmotionEngine.defaultState("u1", "乌萨奇")
        repeat(4) {
            state = WhaleEmotionEngine.evolveFromTurn(state, "哈哈太可爱了，谢谢你真厉害！", hourOfDay = 15)
        }
        assertTrue(state.trust > 40)
        assertTrue(state.bondLevel >= 4)
        assertTrue(state.warmth > 76)
    }

    @Test
    fun `direct work instructions cultivate directness and milestone note`() {
        var state = WhaleEmotionEngine.defaultState("default", "鲸鱼娘").copy(directness = 76)
        state = WhaleEmotionEngine.evolveFromTurn(state, "直接给我代码修复方案，别废话", hourOfDay = 14)
        assertEquals(WhaleEmotionEngine.Mood.FOCUSED, state.mood)
        assertTrue(state.directness >= 78)
        assertTrue(state.growthNotes.any { "干脆利落" in it })
        val prompt = WhaleEmotionEngine.buildDynamicPrompt(state)
        assertTrue(prompt.contains("当前情感与性格养成状态"))
        assertTrue(prompt.contains("专注高效"))
    }

    @Test
    fun `polite requests and neutral words do not false-trigger tired or frustrated mood`() {
        val base = WhaleEmotionEngine.defaultState("default", "鲸鱼娘")
        val next = WhaleEmotionEngine.evolveFromTurn(
            base,
            "麻烦帮我查一下累计电费和玩具订单乱码的问题",
            hourOfDay = 14,
        )
        assertEquals(WhaleEmotionEngine.Mood.CALM, next.mood)
        assertEquals(base.empathy, next.empathy)
        assertEquals(base.playfulness, next.playfulness)
    }

    @Test
    fun `deep reflection triggers every 12 turns and parses clean growth notes`() {
        val base = WhaleEmotionEngine.defaultState("default", "鲸鱼娘")
        assertFalse(WhaleEmotionEngine.shouldRunDeepReflection(base.copy(interactions = 0)))
        assertFalse(WhaleEmotionEngine.shouldRunDeepReflection(base.copy(interactions = 11)))
        assertTrue(WhaleEmotionEngine.shouldRunDeepReflection(base.copy(interactions = 12)))
        assertTrue(WhaleEmotionEngine.shouldRunDeepReflection(base.copy(interactions = 24)))

        val prompt = WhaleEmotionEngine.buildReflectionPrompt("鲸鱼娘", listOf("帮我直接看报错", "简洁点"), base)
        assertTrue(prompt.contains("鲸鱼娘"))
        assertTrue(prompt.contains("帮我直接看报错"))

        assertEquals("主人排查代码问题时喜欢先给根因再给补丁", WhaleEmotionEngine.parseReflectionNote("「主人排查代码问题时喜欢先给根因再给补丁」"))
        assertNull(WhaleEmotionEngine.parseReflectionNote("NONE"))
        assertNull(WhaleEmotionEngine.parseReflectionNote("   "))
    }
}
