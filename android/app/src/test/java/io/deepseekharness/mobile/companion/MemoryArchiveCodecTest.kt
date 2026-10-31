package io.deepseekharness.mobile.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryArchiveCodecTest {
    @Test fun roundTripPreservesBytes() {
        val original = ("会议改期，保留时间和来源。\n".repeat(1000)).toByteArray(Charsets.UTF_8)
        val archive = MemoryArchiveCodec.encode(original)
        assertArrayEquals(original, MemoryArchiveCodec.decode(archive, original.size))
    }

    @Test fun rejectsCorruptionAndOversizedPayload() {
        val archive = MemoryArchiveCodec.encode("证据".toByteArray(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { MemoryArchiveCodec.decode(archive, 1) }
        assertThrows(IllegalArgumentException::class.java) { MemoryArchiveCodec.decode(archive.copy(sha256 = "0".repeat(64)), 100) }
        assertThrows(Exception::class.java) { MemoryArchiveCodec.decode(archive.copy(data = archive.data.copyOf(archive.data.size - 3)), 100) }
        assertThrows(IllegalArgumentException::class.java) { MemoryArchiveCodec.decode(archive.copy(originalSize = 1), 100) }
    }
}
