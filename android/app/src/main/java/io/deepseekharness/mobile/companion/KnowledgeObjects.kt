package io.deepseekharness.mobile.companion

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Private, content-addressed copies of user-selected originals. Never replaces an existing object. */
internal class KnowledgeObjects(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.noBackupFilesDir, "knowledge-objects-v1")

    private fun objectFile(id: String): File {
        require(id.matches(Regex("[a-f0-9]{64}")))
        return File(File(root, id.take(2)), id)
    }

    fun get(id: String): File? = synchronized(lock) {
        val file = objectFile(id)
        file.takeIf { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
    }

    fun getVerified(id: String): File = synchronized(lock) {
        val file = get(id) ?: error("原件不存在")
        check(!java.nio.file.Files.isSymbolicLink(root.toPath()) && !java.nio.file.Files.isSymbolicLink(file.parentFile.toPath()))
        check(digest(file, 64L * 1024 * 1024) == id) { "原件校验失败" }
        file
    }

    fun store(source: File, expectedId: String, maxBytes: Long): File = synchronized(lock) {
        require(source.isFile && source.length() in 1..maxBytes)
        val target = objectFile(expectedId)
        check(target.parentFile.isDirectory || target.parentFile.mkdirs()) { "无法创建原件存储" }
        check(!java.nio.file.Files.isSymbolicLink(root.toPath()))
        check(!java.nio.file.Files.isSymbolicLink(target.parentFile.toPath()))
        if (target.exists()) {
            check(!java.nio.file.Files.isSymbolicLink(target.toPath()) && digest(target, maxBytes) == expectedId) { "原件副本校验失败" }
            return@synchronized target
        }
        val temporary = File(target.parentFile, ".pending-${UUID.randomUUID()}")
        try {
            source.inputStream().use { input -> temporary.outputStream().use { output ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    require(total <= maxBytes)
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
                check(total > 0 && digest.digest().hex() == expectedId) { "原件校验失败" }
            } }
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            target
        } finally { temporary.delete() }
    }

    /** Export a verified temporary copy for a user-selected viewer. */
    fun copyForPreview(id: String, suffix: String): File = synchronized(lock) {
        require(suffix.matches(Regex("\\.[a-z0-9]{1,8}")))
        val source = getVerified(id)
        val directory = File(app.cacheDir, "external-share")
        check(directory.isDirectory || directory.mkdirs())
        check(!java.nio.file.Files.isSymbolicLink(directory.toPath()))
        val target = File(directory, "${UUID.randomUUID()}$suffix")
        try {
            source.inputStream().use { input -> target.outputStream().use { output ->
                input.copyTo(output, 64 * 1024)
                output.fd.sync()
            } }
            check(digest(target, 64L * 1024 * 1024) == id) { "预览副本校验失败" }
            target
        } catch (e: Exception) { target.delete(); throw e }
    }

    /** Deletes only this content-addressed object after validating its exact digest and path. */
    fun deleteVerified(id: String): Boolean = synchronized(lock) {
        val file = objectFile(id)
        if (!file.exists()) return@synchronized false
        check(file.isFile && !java.nio.file.Files.isSymbolicLink(root.toPath()) && !java.nio.file.Files.isSymbolicLink(file.parentFile.toPath()) && !java.nio.file.Files.isSymbolicLink(file.toPath()))
        check(digest(file, 64L * 1024 * 1024) == id) { "原件校验失败" }
        java.nio.file.Files.delete(file.toPath())
        !file.exists()
    }

    private fun digest(file: File, maxBytes: Long): String {
        require(file.length() in 1..maxBytes)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= maxBytes)
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    companion object { private val lock = Any() }
}
