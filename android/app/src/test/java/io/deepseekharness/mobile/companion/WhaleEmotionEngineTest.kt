package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
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
}
