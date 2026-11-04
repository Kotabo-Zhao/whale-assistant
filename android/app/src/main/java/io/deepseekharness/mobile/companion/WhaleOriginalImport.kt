package io.deepseekharness.mobile.companion

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.deepseekharness.mobile.runtime.RuntimeSharedInbox
import io.deepseekharness.mobile.runtime.RuntimeStore
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

internal data class WhaleParsedContent(val text: String, val coverage: String, val mediaKind: String? = null, val description: Boolean = false) {
    companion object {
        fun document(text: String, mediaKind: String?): WhaleParsedContent {
            val partial = text.contains("[内容过长，已截取]") || text.contains("[仅提取前")
            return WhaleParsedContent(text, if (partial) "partial" else "extracted_text", mediaKind)
        }
    }
}

/** The temporary URI grant is needed only until the verified local original and its parse ticket exist. */
internal object WhaleOriginalImport {
    fun extension(name: String, mime: String): String =
        name.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: when (mime) {
                "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"
                "image/heic", "image/heif" -> "heic"; "application/pdf" -> "pdf"; "text/plain" -> "txt"
                else -> if (mime.startsWith("image/")) "img" else "bin"
            }

    fun save(app: Context, uri: Uri, type: String?, origin: WhaleShareSource, captionIds: List<String>): Boolean {
        require(uri.scheme == "content")
        val resolver = app.contentResolver
        val mime = type.orEmpty().lowercase().take(200)
        val name = runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull().orEmpty()
        val image = mime.startsWith("image/")
        val maxBytes = (if (image) 32L else 64L) * 1024 * 1024
        val inbox = RuntimeSharedInbox(RuntimeStore(app))
        val kind = if (image) "image" else "document"
        val target = File(inbox.stagingDirectory(), "${UUID.randomUUID()}-shared-$kind.${extension(name, mime)}")
        return inbox.withPendingFile { pending ->
            val digest = MessageDigest.getInstance("SHA-256")
            requireNotNull(resolver.openInputStream(uri)).use { input ->
                Files.newOutputStream(pending.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maxBytes) { "文件超过单份大小上限" }
                        digest.update(buffer, 0, count)
                        out.write(buffer, 0, count)
                    }
                    require(total > 0)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val objects = KnowledgeObjects(app)
            objects.store(pending, hash, maxBytes)
            val row = synchronized(CompanionKnowledgeStore.lock) {
                // A deletion during the copy must not leave a new anchor without its object.
                if (objects.get(hash) == null) objects.store(pending, hash, maxBytes)
                val store = CompanionKnowledgeStore(app)
                val original = store.registerOriginal(hash, name, mime, origin, captionIds, queueParse = true)
                RuntimeSharedInbox.withLock { Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
                original
            }
            row.has("parseTicket")
        }
    }
}
