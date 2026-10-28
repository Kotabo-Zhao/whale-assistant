package io.deepseekharness.mobile

import org.junit.Assert.*
import org.junit.Test

class SharedTextImportTest {
    @Test fun preservesTextAndRejectsEmptyOrOversizedInput() {
        val original = "  网页标题\nhttps://example.com/a?x=1  "
        assertEquals(original, String(SharedTextImport.encode(original)!!, Charsets.UTF_8))
        assertNull(SharedTextImport.encode(null))
        assertNull(SharedTextImport.encode("  \n"))
        assertNull(SharedTextImport.encode("a".repeat(65_537)))
        assertNull(SharedTextImport.encode("鲸".repeat(50_000)))
        assertNotNull(SharedTextImport.encode("a".repeat(65_536)))
    }
}
