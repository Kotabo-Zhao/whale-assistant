package io.deepseekharness.mobile.companion

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable encrypted wrapper around [UnifiedFullBackup].  The package contains no credentials
 * because the inner backup does not contain credentials; a user-selected passphrase derives its
 * key independently on every device, so no Android Keystore key is exported.
 */
internal object PersonalKnowledgePackage {
    private val magic = byteArrayOf('W'.code.toByte(), 'P'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte())
    private const val VERSION = 1
    private const val ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val HEADER_BYTES = 4 + 1 + 4 + SALT_BYTES + IV_BYTES
    private const val TAG_BYTES = 16
    private const val MAX_PACKAGE_BYTES = UnifiedFullBackup.MAX_BYTES + HEADER_BYTES + TAG_BYTES

    fun export(context: Context, passphrase: CharArray): File {
        var archive: File? = null
        var target: File? = null
        try {
            validatePassphrase(passphrase)
            val app = context.applicationContext
            archive = UnifiedFullBackup.export(app)
            target = File(app.cacheDir, "whale-personal-${java.util.UUID.randomUUID()}.wpk")
            val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
            val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
            val header = header(ITERATIONS, salt, iv)
            val cipher = cipher(Cipher.ENCRYPT_MODE, passphrase, salt, iv, header)
            BufferedOutputStream(target.outputStream()).use { output ->
                output.write(header)
                CipherOutputStream(output, cipher).use { encrypted -> requireNotNull(archive).inputStream().buffered().use { input -> copyBounded(input, encrypted, UnifiedFullBackup.MAX_BYTES) } }
            }
            require(requireNotNull(target).length() in (HEADER_BYTES + TAG_BYTES)..MAX_PACKAGE_BYTES) { "知识包大小无效" }
            return requireNotNull(target)
        } catch (error: Exception) { target?.delete(); throw error }
        finally { archive?.delete(); passphrase.fill('\u0000') }
    }

    /** Decrypts into an app-private temporary full backup; caller must delete the returned file. */
    fun decrypt(context: Context, packageFile: File, passphrase: CharArray): File {
        var target: File? = null
        try {
            validatePassphrase(passphrase)
            require(packageFile.length() in (HEADER_BYTES + TAG_BYTES)..MAX_PACKAGE_BYTES) { "知识包大小无效" }
            target = File(context.applicationContext.cacheDir, "whale-personal-restore-${java.util.UUID.randomUUID()}.zip")
            BufferedInputStream(packageFile.inputStream()).use { input ->
                val header = input.readNBytes(HEADER_BYTES); require(header.size == HEADER_BYTES)
                val fields = parseHeader(header)
                val cipher = cipher(Cipher.DECRYPT_MODE, passphrase, fields.salt, fields.iv, header)
                CipherInputStream(input, cipher).use { encrypted -> requireNotNull(target).outputStream().buffered().use { output -> copyBounded(encrypted, output, UnifiedFullBackup.MAX_BYTES) } }
            }
            require(UnifiedFullBackup.isFullArchive(requireNotNull(target))) { "知识包内容不是有效的完整备份" }
            return requireNotNull(target)
        } catch (error: Exception) { target?.delete(); throw error }
        finally { passphrase.fill('\u0000') }
    }

    private data class Header(val salt: ByteArray, val iv: ByteArray)
    private fun header(iterations: Int, salt: ByteArray, iv: ByteArray): ByteArray = ByteBuffer.allocate(HEADER_BYTES)
        .put(magic).put(VERSION.toByte()).putInt(iterations).put(salt).put(iv).array()
    private fun parseHeader(bytes: ByteArray): Header {
        val buffer = ByteBuffer.wrap(bytes); val incoming = ByteArray(4); buffer.get(incoming)
        require(incoming.contentEquals(magic) && buffer.get().toInt() == VERSION) { "知识包格式不支持" }
        require(buffer.int == ITERATIONS) { "知识包密钥参数不支持" }
        return Header(ByteArray(SALT_BYTES).also(buffer::get), ByteArray(IV_BYTES).also(buffer::get))
    }
    private fun cipher(mode: Int, passphrase: CharArray, salt: ByteArray, iv: ByteArray, header: ByteArray): Cipher {
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(passphrase, salt, ITERATIONS, 256)).encoded
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); updateAAD(header) }
    }
    private fun validatePassphrase(value: CharArray) { require(value.size >= 12 && value.size <= 256) { "口令长度应为 12 到 256 个字符" } }
    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, maximum: Long) {
        val buffer = ByteArray(64 * 1024); var total = 0L
        while (true) { val count = input.read(buffer); if (count < 0) break; if (count == 0) continue; total += count; require(total <= maximum) { "知识包内容超过上限" }; output.write(buffer, 0, count) }
        require(total > 0) { "知识包内容为空" }
    }
}
