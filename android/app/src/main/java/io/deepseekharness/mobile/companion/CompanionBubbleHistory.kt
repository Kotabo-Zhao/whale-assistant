package io.deepseekharness.mobile.companion

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A bounded private copy of recent messages for restoring the pet after service restart. */
internal class CompanionBubbleHistory(context: Context, private val writer: TaskStateWriter = TaskStateWriter()) {
    data class Snapshot(val sessionId: String, val scope: String, val messages: List<CompanionBubbleChat.Message>, val pending: String? = null, val continuedMain: Boolean = false, val topicId: String = sessionId)
    private val app = context.applicationContext
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "companion-bubble-history-v1.json"))
    internal val sourceFile: File get() = file.baseFile
    val followups = PetFollowupJournal(File(file.baseFile.parentFile, "pet-followups-v1.json"), writer)
    private var writable: Boolean? = null

    fun load(): Snapshot? = synchronized(lock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            writable = true
            return@synchronized null
        }
        try { decode(file.readFully()).also { writable = true } } catch (_: Exception) { writable = false; null }
    }
    fun save(snapshot: Snapshot, requireWritable: Boolean = false): Unit = synchronized(lock) {
        if (writable == null) load()
        if (writable != true) { check(!requireWritable) { "对话记录不可写，未交办新任务。" }; return@synchronized }
        val bytes = encode(snapshot)
        PetReplyOperation.whileActive { writer.replace(file.baseFile, bytes) }
        if (snapshot.pending == null && snapshot.messages.lastOrNull()?.role == "assistant") ContextFollowupEvents.chat(app, "pet")
    }
    fun exportArchive(): ByteArray? = load()?.copy(pending = null)?.let(::encode)
    /** Never overwrites a current local conversation during restore. */
    fun restoreArchive(bytes: ByteArray): Boolean = synchronized(lock) {
        // Imported history is an archive, not permission to submit unfinished work on another installation.
        val snapshot = decode(bytes).copy(pending = null)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) { writable = true; save(snapshot,requireWritable = true); return@synchronized true }
        val archive = AtomicFile(File(file.baseFile.parentFile, "companion-bubble-history-import-${java.util.UUID.randomUUID()}.json"))
        var output: java.io.FileOutputStream? = null
        try { output = archive.startWrite(); output.write(encode(snapshot)); archive.finishWrite(output); return false }
        catch (e: Exception) { if (output != null) archive.failWrite(output); throw e }
    }
    /** Preserve the local transcript before starting an explicitly requested new topic. */
    fun archiveAndClear(): Unit = synchronized(lock) {
        if (writable == null) load()
        check(writable == true) { "聊天记录无法读取，未开启新话题" }
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return
        val bytes = file.readFully()
        decode(bytes)
        val archive = AtomicFile(File(file.baseFile.parentFile, "companion-bubble-history-${java.util.UUID.randomUUID()}.json"))
        var output: java.io.FileOutputStream? = null
        try { output = archive.startWrite(); output.write(bytes); archive.finishWrite(output) }
        catch (e: Exception) { if (output != null) archive.failWrite(output); throw e }
        file.delete()
        check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) { "无法切换话题，原记录已保留" }
    }
    companion object {
        // The live pet and backup controller construct separate handles for the same AtomicFile.
        private val lock = Any()
        fun encode(snapshot: Snapshot): ByteArray {
            snapshot.pending?.let { value ->
                val request = JSONObject(value)
                require(request.getString("requestId").isNotBlank() && request.getString("prompt").isNotBlank())
                CompanionBubbleChatPolicy.validateMessage(request.getString("user"))
            }
            require(snapshot.sessionId.isNotBlank() && snapshot.sessionId.length <= 256)
            require(snapshot.topicId.isNotBlank() && snapshot.topicId.length <= 256)
            require(snapshot.scope.matches(Regex("[0-9a-f]{64}")))
            require(snapshot.messages.size in 0..18 && snapshot.messages.size % 2 == 0)
            val rows = JSONArray()
            snapshot.messages.forEachIndexed { index, message ->
                require(message.role == if (index % 2 == 0) "user" else "assistant")
                require(message.images.size <= 6 && message.images.all { it.matches(Regex("sha256:[a-f0-9]{64}")) })
                val citations = JSONArray()
                message.citations.take(6).forEach { citation ->
                    require(citation.id.matches(Regex("[a-f0-9-]{36}")) && citation.revision.matches(Regex("[a-f0-9]{64}")))
                    require(citation.displayName.isNotBlank() && citation.displayName.length <= 160 && citation.chunkIndex >= 0 && citation.chunkCount in 1..10_000 && citation.chunkIndex < citation.chunkCount)
                    citation.documentId?.let { require(it.matches(Regex("[a-f0-9]{64}"))) }
                    citation.originalId?.let { require(it.matches(Regex("[a-f0-9]{64}"))) }
                    citations.put(JSONObject().put("id", citation.id).put("revision", citation.revision).put("displayName", citation.displayName)
                        .put("chunkIndex", citation.chunkIndex).put("chunkCount", citation.chunkCount).put("parsingCoverage", citation.parsingCoverage)
                        .apply { citation.documentId?.let { put("documentId", it) }; citation.originalId?.let { put("originalId", it) } })
                }
                val text = if (message.role == "user") CompanionBubbleChatPolicy.validateMessage(message.text) else message.text.also { require(it.isNotBlank() && it.length <= 100_000) }
                rows.put(JSONObject().put("role", message.role).put("text", text).put("images", JSONArray(message.images)).put("citations", citations)
                    .apply { if (message.atMs > 0) put("atMs", message.atMs) })
            }
            return JSONObject().put("version", 3).put("sessionId", snapshot.sessionId).put("scope", snapshot.scope).put("messages", rows)
                .put("pending", snapshot.pending).put("continuedMain", snapshot.continuedMain).put("topicId",snapshot.topicId)
                .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= 2_000_000) }
        }
        fun decode(bytes: ByteArray): Snapshot {
            require(bytes.size <= 2_000_000)
            val root = JSONObject(Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString())
            require(root.getInt("version") in 1..3)
            val rows = root.getJSONArray("messages")
            val snapshot = Snapshot(root.getString("sessionId"), root.getString("scope"), (0 until rows.length()).map {
                rows.getJSONObject(it).let { row ->
                    val citations = row.optJSONArray("citations")?.let { values -> (0 until values.length()).map { values.getJSONObject(it) }.map { citation ->
                        CompanionBubbleChat.Citation(citation.getString("id"), citation.getString("revision"), citation.getString("displayName"), citation.optString("documentId").takeIf(String::isNotBlank), citation.optString("originalId").takeIf(String::isNotBlank), citation.getInt("chunkIndex"), citation.getInt("chunkCount"), citation.optString("parsingCoverage", "unknown"))
                    } } ?: emptyList()
                    CompanionBubbleChat.Message(row.getString("role"), row.getString("text"), row.optJSONArray("images")?.let { images -> (0 until images.length()).map { images.getString(it) } } ?: emptyList(), citations, row.optLong("atMs",0L))
                }
            }, root.optString("pending").takeIf { it.isNotBlank() && it != "null" }, root.optBoolean("continuedMain"), root.optString("topicId",root.getString("sessionId")))
            encode(snapshot)
            return snapshot
        }
    }
}
