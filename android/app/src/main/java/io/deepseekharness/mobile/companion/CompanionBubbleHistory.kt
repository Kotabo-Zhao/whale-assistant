package io.deepseekharness.mobile.companion

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A bounded private copy of recent messages for restoring the pet after service restart. */
internal class CompanionBubbleHistory(context: Context) {
    data class Snapshot(val sessionId: String, val scope: String, val messages: List<CompanionBubbleChat.Message>)
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "companion-bubble-history-v1.json"))
    private var writable = true

    fun load(): Snapshot? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return try { decode(file.readFully()) } catch (_: Exception) { writable = false; null }
    }
    @Synchronized fun save(snapshot: Snapshot) {
        if (!writable) return
        val bytes = encode(snapshot)
        var output: java.io.FileOutputStream? = null
        try { output = file.startWrite(); output.write(bytes); file.finishWrite(output) }
        catch (e: Exception) { if (output != null) file.failWrite(output); throw e }
    }
    @Synchronized fun exportArchive(): ByteArray? = load()?.let(::encode)
    /** Never overwrites a current local conversation during restore. */
    @Synchronized fun restoreArchive(bytes: ByteArray): Boolean {
        val snapshot = decode(bytes)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) { save(snapshot); return true }
        val archive = AtomicFile(File(file.baseFile.parentFile, "companion-bubble-history-import-${java.util.UUID.randomUUID()}.json"))
        var output: java.io.FileOutputStream? = null
        try { output = archive.startWrite(); output.write(encode(snapshot)); archive.finishWrite(output); return false }
        catch (e: Exception) { if (output != null) archive.failWrite(output); throw e }
    }
    /** Preserve the local transcript before starting an explicitly requested new topic. */
    @Synchronized fun archiveAndClear() {
        check(writable) { "聊天记录无法读取，未开启新话题" }
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
        fun encode(snapshot: Snapshot): ByteArray {
            require(snapshot.sessionId.isNotBlank() && snapshot.sessionId.length <= 256)
            require(snapshot.scope.matches(Regex("[0-9a-f]{64}")))
            require(snapshot.messages.size in 2..18 && snapshot.messages.size % 2 == 0)
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
                rows.put(JSONObject().put("role", message.role).put("text", text).put("images", JSONArray(message.images)).put("citations", citations))
            }
            return JSONObject().put("version", 2).put("sessionId", snapshot.sessionId).put("scope", snapshot.scope).put("messages", rows)
                .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= 2_000_000) }
        }
        fun decode(bytes: ByteArray): Snapshot {
            require(bytes.size <= 2_000_000)
            val root = JSONObject(Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString())
            require(root.getInt("version") in 1..2)
            val rows = root.getJSONArray("messages")
            val snapshot = Snapshot(root.getString("sessionId"), root.getString("scope"), (0 until rows.length()).map {
                rows.getJSONObject(it).let { row ->
                    val citations = row.optJSONArray("citations")?.let { values -> (0 until values.length()).map { values.getJSONObject(it) }.map { citation ->
                        CompanionBubbleChat.Citation(citation.getString("id"), citation.getString("revision"), citation.getString("displayName"), citation.optString("documentId").takeIf(String::isNotBlank), citation.optString("originalId").takeIf(String::isNotBlank), citation.getInt("chunkIndex"), citation.getInt("chunkCount"), citation.optString("parsingCoverage", "unknown"))
                    } } ?: emptyList()
                    CompanionBubbleChat.Message(row.getString("role"), row.getString("text"), row.optJSONArray("images")?.let { images -> (0 until images.length()).map { images.getString(it) } } ?: emptyList(), citations)
                }
            })
            encode(snapshot)
            return snapshot
        }
    }
}
