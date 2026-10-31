package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhaleSharedTextTest {
    @Test fun acceptsOnlyBoundedNonemptyTextShares() {
        val send = "android.intent.action.SEND"
        assertEquals("  你好\n世界  ", WhaleSharedText.accept(send, "text/plain", "  你好\n世界  "))
        assertNull(WhaleSharedText.accept("android.intent.action.VIEW", "text/plain", "text"))
        assertNull(WhaleSharedText.accept(send, "image/png", "text"))
        assertNull(WhaleSharedText.accept(send, "text/plain", "   "))
        assertNull(WhaleSharedText.accept(send, "text/plain", "a\u0000b"))
        assertNull(WhaleSharedText.accept(send, "text/plain", "x".repeat(8001)))
        assertEquals(8000, WhaleSharedText.accept(send, "text/plain", "x".repeat(8000))?.length)
    }

    @Test fun decodesTextFilesStrictlyAndRejectsInvalidContent() {
        assertEquals("你好", WhaleSharedText.readFile("\uFEFF你好".byteInputStream()))
        assertNull(WhaleSharedText.readFile("a\u0000b".byteInputStream()))
        org.junit.Assert.assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            WhaleSharedText.readFile(byteArrayOf(0xc3.toByte(), 0x28).inputStream())
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            WhaleSharedText.readFile(ByteArray(32001) { 65 }.inputStream())
        }
    }
}
