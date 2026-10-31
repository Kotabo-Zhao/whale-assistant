package io.deepseekharness.mobile.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BgeBaseZhTokenizerTest {
    private val tokenizer = BgeBaseZhTokenizer(listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "我", "爱", "咖", "啡", "deep", "##seek", "!", "！"))

    @Test fun encodesChinesePunctuationAndWordPiecesLocally() {
        assertArrayEquals(longArrayOf(2, 4, 5, 6, 7, 11, 3), tokenizer.encode("我爱咖啡！"))
        assertArrayEquals(longArrayOf(2, 8, 9, 3), tokenizer.encode("DeepSeek"))
    }

    @Test fun replacesUnknownWordsWithoutBreakingTheSequence() {
        assertArrayEquals(longArrayOf(2, 1, 3), tokenizer.encode("unknownword"))
    }
}
