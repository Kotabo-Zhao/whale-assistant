package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test

class MemorySearchIndexTest {
    @Test fun termsNormalizeCjkLatinAndSupplementaryCharacters() {
        val indexed = MemorySearchIndex.terms("咖啡 ABC 🐋")
        assertTrue(indexed.containsAll(MemorySearchIndex.terms("咖啡")))
        assertTrue(indexed.containsAll(MemorySearchIndex.terms("abc")))
        assertTrue(indexed.containsAll(MemorySearchIndex.terms("🐋")))
        assertEquals(MemorySearchIndex.terms("ＡＢ"), MemorySearchIndex.terms("ab"))
    }
}
