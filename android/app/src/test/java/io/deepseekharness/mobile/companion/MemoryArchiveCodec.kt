package io.deepseekharness.mobile.companion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Format prototype used to test size and hash validation for archived memory. */
internal object MemoryArchiveCodec {
    data class Payload(val data: ByteArray, val originalSize: Int, val sha256: String)

    fun encode(input: ByteArray): Payload {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(input) }
        return Payload(output.toByteArray(), input.size, digest(input))
    }

    /** Refuse corrupt streams and bound allocation even when metadata is untrusted. */
    fun decode(payload: Payload, maxBytes: Int): ByteArray {
        require(maxBytes >= 0 && payload.originalSize in 0..maxBytes) { "invalid archive size" }
        require(payload.sha256.matches(Regex("[0-9a-f]{64}"))) { "invalid archive hash" }
        val result = ByteArrayOutputStream(minOf(payload.originalSize, 8192))
        GZIPInputStream(ByteArrayInputStream(payload.data)).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count <= payload.originalSize - result.size()) { "archive exceeds declared size" }
                result.write(buffer, 0, count)
            }
        }
        val bytes = result.toByteArray()
        require(bytes.size == payload.originalSize && digest(bytes) == payload.sha256) { "archive integrity check failed" }
        return bytes
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
