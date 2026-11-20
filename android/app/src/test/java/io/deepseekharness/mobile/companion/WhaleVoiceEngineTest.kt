package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleVoiceEngineTest {

    @Test
    fun cleanForSpeechStripsMarkdownCodeBlocksAndUrls() {
        val raw = "**太棒啦！** 我已经帮你查好了 [官方文档](https://example.com/docs)，代码如下：\n```kotlin\nprintln(1)\n```\n详情看 https://foo.bar/test"
        val cleaned = WhaleVoiceEngine.cleanForSpeech(raw)
        assertFalse(cleaned.contains("**"))
        assertFalse(cleaned.contains("https://"))
        assertTrue(cleaned.contains("太棒啦"))
        assertTrue(cleaned.contains("官方文档"))
        assertTrue(cleaned.contains("代码片段已省略"))
    }

    @Test
    fun splitSentencesProducesNaturalStreamingChunks() {
        val text = "早上好呀！今天天气很不错。我已经帮你把日程整理好了，要不要现在看看？"
        val chunks = WhaleVoiceEngine.splitSentences(text, maxChunkChars = 20)
        assertEquals(3, chunks.size)
        assertEquals("早上好呀！今天天气很不错。", chunks[0])
        assertTrue(chunks[1].startsWith("我已经帮你把日程整理好了"))
    }
}
