package io.deepseekharness.mobile.companion

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Versioned archive of the structured snapshot and its referenced original objects. */
internal object UnifiedFullBackup {
    const val MAX_BYTES = 4L * 1024 * 1024 * 1024
    private const val MAX_OBJECT_BYTES = 64L * 1024 * 1024
    private const val MAX_OBJECTS = 200_000
    private const val SNAPSHOT = "snapshot.sqlite"
    private const val BUBBLE_HISTORY = "conversations/bubble-history.json"
    private const val BUBBLE_SESSIONS = "conversations/bubble-sessions.json"
    private const val FORMAT = "whale-full-backup-v1"

    private fun originalIds(snapshot: File): Set<String> {
        val ids = linkedSetOf<String>()
        SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val table = db.rawQuery("SELECT type FROM sqlite_master WHERE name='knowledge_records'", null).use {
                it.moveToFirst() && it.getString(0) == "table"
            }
            require(table) { "备份缺少资料表" }
            db.rawQuery("SELECT body FROM knowledge_records", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = JSONObject(cursor.getString(0))
                    val id = row.optString("originalId")
                    if (id.isNotEmpty()) {
                        require(id.matches(Regex("[a-f0-9]{64}"))) { "原件标识无效" }
                        ids.add(id)
                        require(ids.size <= MAX_OBJECTS) { "原件数量超过备份上限" }
                    }
                }
            }
        }
        return ids
    }

    fun export(context: Context): File {
        val app = context.applicationContext
        val snapshot = UnifiedMemoryBackup.export(app)
        val archive = File(app.cacheDir, "whale-full-${UUID.randomUUID()}.zip")
        try {
            val ids = originalIds(snapshot)
            val objects = KnowledgeObjects(app)
            var totalBytes = snapshot.length()
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("format")); zip.write(FORMAT.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                zip.putNextEntry(ZipEntry(SNAPSHOT)); snapshot.inputStream().use { copyBounded(it, zip, UnifiedMemoryBackup.MAX_BYTES) }; zip.closeEntry()
                CompanionBubbleHistory(app).exportArchive()?.let { history ->
                    totalBytes += history.size; require(totalBytes <= MAX_BYTES)
                    zip.putNextEntry(ZipEntry(BUBBLE_HISTORY)); zip.write(history); zip.closeEntry()
                }
                BubbleSessionRegistry(app).exportArchive().let { sessions ->
                    totalBytes += sessions.size; require(totalBytes <= MAX_BYTES)
                    zip.putNextEntry(ZipEntry(BUBBLE_SESSIONS)); zip.write(sessions); zip.closeEntry()
                }
                for (id in ids) {
                    val objectFile = objects.get(id) ?: error("原件不可用，备份未完成")
                    totalBytes += objectFile.length()
                    require(totalBytes <= MAX_BYTES) { "备份内容超过大小上限" }
                    zip.putNextEntry(ZipEntry("objects/$id"))
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    objectFile.inputStream().use { copyBounded(it, zip, MAX_OBJECT_BYTES, digest) }
                    check(digest.digest().hex() == id) { "原件校验失败，备份未完成" }
                    zip.closeEntry()
                    require(archive.length() <= MAX_BYTES) { "备份超过大小上限" }
                }
            }
            require(archive.length() in 1..MAX_BYTES)
            return archive
        } catch (e: Exception) { archive.delete(); throw e }
        finally { snapshot.delete() }
    }

    fun isFullArchive(file: File): Boolean = file.inputStream().use { input ->
        input.read() == 0x50 && input.read() == 0x4b
    }

    /** Adds verified originals before the transactional structured restore; existing originals are never replaced. */
    fun restore(context: Context, archive: File) {
        require(archive.length() in 1..MAX_BYTES)
        val app = context.applicationContext
        val snapshot = File(app.cacheDir, "full-restore-${UUID.randomUUID()}.sqlite")
        val temporary = File(app.cacheDir, "full-object-${UUID.randomUUID()}.tmp")
        var hasBubbleHistory = false
        var hasBubbleSessions = false
        try {
            ZipFile(archive).use { zip ->
                val entries = mutableMapOf<String, ZipEntry>()
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    val entry = enumeration.nextElement()
                    require(!entry.isDirectory && (entry.name == "format" || entry.name == SNAPSHOT || entry.name == BUBBLE_HISTORY || entry.name == BUBBLE_SESSIONS || entry.name.matches(Regex("objects/[a-f0-9]{64}")))) { "备份包含未知文件" }
                    require(entries.put(entry.name, entry) == null && entries.size <= MAX_OBJECTS + 4) { "备份条目重复或过多" }
                }
                val format = zip.getInputStream(requireNotNull(entries["format"])).use { input ->
                    val bytes = java.io.ByteArrayOutputStream(); copyBounded(input, bytes, 64); bytes.toString("UTF-8")
                }
                require(format == FORMAT) { "备份格式不支持" }
                hasBubbleHistory = BUBBLE_HISTORY in entries
                hasBubbleSessions = BUBBLE_SESSIONS in entries
                var totalBytes = 0L
                zip.getInputStream(requireNotNull(entries[SNAPSHOT])).use { input -> snapshot.outputStream().use { output ->
                    totalBytes += copyBounded(input, output, UnifiedMemoryBackup.MAX_BYTES); output.fd.sync()
                } }
                val ids = originalIds(snapshot)
                require(entries.keys.filter { it.startsWith("objects/") }.map { it.removePrefix("objects/") }.toSet() == ids) { "备份的原件清单不完整" }
                val store = KnowledgeObjects(app)
                for (id in ids) {
                    zip.getInputStream(requireNotNull(entries["objects/$id"])).use { input -> temporary.outputStream().use { output ->
                        totalBytes += copyBounded(input, output, minOf(MAX_OBJECT_BYTES, MAX_BYTES - totalBytes)); output.fd.sync()
                    } }
                    store.store(temporary, id, MAX_OBJECT_BYTES)
                    check(temporary.delete())
                }
            }
            UnifiedMemoryBackup.restore(app, snapshot)
            if (hasBubbleSessions) BubbleSessionRegistry(app).mergeArchive(zipRead(archive, BUBBLE_SESSIONS, 50_000))
            if (hasBubbleHistory) CompanionBubbleHistory(app).restoreArchive(zipRead(archive, BUBBLE_HISTORY, 150_000))
        } finally { temporary.delete(); snapshot.delete() }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, maximum: Long, digest: java.security.MessageDigest? = null): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            total += n
            require(total <= maximum) { "备份内容超过大小上限" }
            digest?.update(buffer, 0, n)
            output.write(buffer, 0, n)
        }
        require(total > 0) { "备份内容为空" }
        return total
    }
    private fun zipRead(archive: File, name: String, maximum: Long): ByteArray = ZipFile(archive).use { zip ->
        val output = java.io.ByteArrayOutputStream()
        zip.getInputStream(requireNotNull(zip.getEntry(name))).use { copyBounded(it, output, maximum) }
        output.toByteArray()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
