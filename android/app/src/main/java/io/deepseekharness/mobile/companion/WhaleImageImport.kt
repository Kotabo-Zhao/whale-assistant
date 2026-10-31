package io.deepseekharness.mobile.companion

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import io.deepseekharness.mobile.runtime.RuntimeSharedInbox
import io.deepseekharness.mobile.runtime.RuntimeStore
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Preserve the source before sending a user-selected image for visual understanding. */
internal object WhaleImageImport {
    private const val MAX_BYTES = 32L * 1024 * 1024
    private const val MAX_DIMENSION = 2048

    fun import(context: Context, uri: Uri): String {
        require(uri.scheme == "content")
        val app = context.applicationContext
        val inbox = RuntimeSharedInbox(RuntimeStore(app))
        val dir = inbox.stagingDirectory()
        val suffix = when (runCatching { app.contentResolver.getType(uri) }.getOrNull()?.lowercase()) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/heic", "image/heif" -> ".heic"
            else -> ".img"
        }
        val target = File(dir, "${UUID.randomUUID()}-shared-image$suffix")
        val sourceDigest = java.security.MessageDigest.getInstance("SHA-256")
        val mime = runCatching { app.contentResolver.getType(uri) }.getOrNull().orEmpty()
        val name = runCatching { app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull().orEmpty()
        var originalId = ""
        inbox.withPendingFile { pending ->
            val input = requireNotNull(app.contentResolver.openInputStream(uri))
            input.use { source ->
                Files.newOutputStream(pending.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        size += n
                        require(size <= MAX_BYTES) { "image too large" }
                        sourceDigest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                    }
                    require(size > 0)
                }
            }
            originalId = sourceDigest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            KnowledgeObjects(app).store(pending, originalId, MAX_BYTES)
            CompanionKnowledgeStore(app).registerOriginal(originalId, name, mime)
            RuntimeSharedInbox.withLock { Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
        }
        val apiKey = runCatching { RuntimeStore(app).readModelCredential("deepseek") }.getOrNull()
        if (apiKey.isNullOrBlank()) {
            val delivered = runCatching { inbox.deliver() }.getOrDefault(false)
            val where = if (delivered) "图片已保存到收件箱" else "图片已暂存"
            return "$where；图片分析需要在模型设置中配置 DeepSeek API 密钥"
        }
        val analysis = runCatching {
            val (bitmap, rotation) = RuntimeSharedInbox.withLock {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(target.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0)
                var sample = 1
                while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_DIMENSION) sample *= 2
                val image = requireNotNull(BitmapFactory.decodeFile(target.path, BitmapFactory.Options().apply { inSampleSize = sample }))
                val orientation = runCatching { android.media.ExifInterface(target.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
                image to when (orientation) { 3 -> 180; 6 -> 90; 8 -> 270; else -> 0 }
            }
            val rotated = if (rotation == 0) bitmap else android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }, true)
            try {
                val bytes = java.io.ByteArrayOutputStream()
                check(rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bytes))
                DeepSeekVisionModel(apiKey).analyze(bytes.toByteArray())
            } finally {
                if (rotated !== bitmap) rotated.recycle()
                bitmap.recycle()
            }
        }.getOrNull()
        val knowledge = CompanionKnowledgeStore(app)
        val saved = analysis?.takeIf { it.isNotBlank() && it.length <= CompanionKnowledgeData.MAX_TEXT && '\u0000' !in it }
            ?.let { runCatching { knowledge.registerImageAnalysis(originalId, it) }.getOrNull() }
        val delivered = runCatching { inbox.deliver() }.getOrDefault(false)
        val where = if (delivered) "图片已保存到收件箱" else "图片已暂存，运行环境就绪后可用"
        if (saved == null) return "$where；图片分析未完成，请检查图片格式、模型及网络"
        knowledge.updateOriginalCoverage(originalId, "model_description")
        val stableId = "share:$originalId"
        SharedMemoryJobs.enqueueWithoutSchedule(app, saved.getString("id"), saved.getString("text"), stableId)
        SharedMemoryJobs.schedule(app, 0)
        return where
    }
}
