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
    companion object {
        fun encode(snapshot: Snapshot): ByteArray {
            require(snapshot.sessionId.isNotBlank() && snapshot.sessionId.length <= 256)
            require(snapshot.scope.matches(Regex("[0-9a-f]{64}")))
            require(snapshot.messages.size in 2..18 && snapshot.messages.size % 2 == 0)
            val rows = JSONArray()
            snapshot.messages.forEachIndexed { index, message ->
                require(message.role == if (index % 2 == 0) "user" else "assistant")
                require(message.images.size <= 6 && message.images.all { it.matches(Regex("sha256:[a-f0-9]{64}")) })
                rows.put(JSONObject().put("role", message.role).put("text", CompanionBubbleChatPolicy.validateMessage(message.text)).put("images", JSONArray(message.images)))
            }
            return JSONObject().put("version", 1).put("sessionId", snapshot.sessionId).put("scope", snapshot.scope).put("messages", rows)
                .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= 150_000) }
        }
        fun decode(bytes: ByteArray): Snapshot {
            require(bytes.size <= 150_000)
            val root = JSONObject(Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString())
            require(root.getInt("version") == 1)
            val rows = root.getJSONArray("messages")
            val snapshot = Snapshot(root.getString("sessionId"), root.getString("scope"), (0 until rows.length()).map {
                rows.getJSONObject(it).let { row -> CompanionBubbleChat.Message(row.getString("role"), row.getString("text"), row.optJSONArray("images")?.let { images -> (0 until images.length()).map { images.getString(it) } } ?: emptyList()) }
            })
            encode(snapshot)
            return snapshot
        }
    }
}
