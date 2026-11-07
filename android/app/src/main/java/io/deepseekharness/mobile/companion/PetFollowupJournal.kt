package io.deepseekharness.mobile.companion

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Private pending inputs are separate from exportable chat history. */
internal class PetFollowupJournal(path: File, private val writer: TaskStateWriter = TaskStateWriter()) {
    private val file = AtomicFile(path)
    data class Entry(val id: String, val text: String, val stage: String, val receipt: String, val after: String, val plan: JSONObject? = null, val topic: String? = null)

    private fun rows(): JSONArray = try { JSONArray(file.openRead().bufferedReader().use { it.readText() }) }
        catch (_: java.io.FileNotFoundException) { check(!file.baseFile.exists()); JSONArray() }

    fun entries(): List<Entry> = synchronized(lock) {
        val rows = rows()
        (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            Entry(row.getString("id"), row.getString("text"), row.getString("stage"), row.optString("receipt"), row.optString("after"), row.optJSONObject("plan"), row.optString("topic").takeIf(String::isNotBlank))
        }
    }
    fun accept(text: String, after: String = "", topic: String? = null): String = synchronized(lock) {
        val rows = rows()
        check(rows.length() < CAPACITY) { "待处理消息已满（$CAPACITY 条），请先处理已有消息。" }
        val id = UUID.randomUUID().toString()
        write(rows.put(JSONObject().put("id",id).put("text",text).put("stage","queued").put("topic",topic).put("after",after.take(160).dropLastWhile { it.isHighSurrogate() })))
        id
    }
    /** Write the recovery entry before releasing the transcript slot. Repeating after a crash updates the same row. */
    fun park(snapshot: CompanionBubbleHistory.Snapshot): Entry = synchronized(lock) {
        val request = JSONObject(requireNotNull(snapshot.pending))
        val requestId = request.getString("requestId")
        val id = request.optString("followupId", requestId)
        val text = request.getString("user")
        val worker = request.optString("kind") == "worker"
        val host = !worker && request.optString("kind") != "direct" && !request.optBoolean("terminal")
        val stage = if (worker) "dispatch" else if (host) "host" else "draft"
        val plan = when {
            worker -> JSONObject(request.toString()).put("task",request.getString("prompt"))
            host -> JSONObject().put("sourceSession",snapshot.sessionId).put("requestId",requestId)
            else -> null
        }
        val rows = rows()
        val row = (0 until rows.length()).map(rows::getJSONObject).firstOrNull { it.getString("id") == id }
            ?: JSONObject().put("id",id).also(rows::put)
        val receipt = row.optString("receipt")
        val savedStage = if (receipt.isNotBlank()) "review" else stage
        row.put("text",text).put("stage",savedStage).put("topic",snapshot.topicId).put("plan",plan)
        write(rows)
        Entry(id,text,savedStage,receipt,row.optString("after"),plan,snapshot.topicId)
    }
    fun advance(id: String, stage: String, receipt: String = "", plan: JSONObject? = null) = synchronized(lock) {
        val rows = rows()
        val row = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == id }
        row.put("stage",stage).put("receipt",receipt)
        if (plan != null) row.put("plan", plan)
        write(rows)
    }
    fun requeue(entry: Entry, topic: String?): String = synchronized(lock) {
        val rows = rows()
        val row = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == entry.id }
        require(row.getString("stage") in setOf("queued","draft"))
        val id = if (entry.stage == "draft") UUID.randomUUID().toString() else entry.id
        row.put("id",id).put("stage","queued").put("topic",topic).remove("plan")
        write(rows)
        id
    }
    fun remove(id: String) = synchronized(lock) {
        val rows = rows()
        val index = (0 until rows.length()).firstOrNull { rows.getJSONObject(it).getString("id") == id }
        if (index != null) { rows.remove(index); write(rows) }
    }
    private fun write(rows: JSONArray) {
        writer.replace(file.baseFile, rows.toString().toByteArray(Charsets.UTF_8))
    }
    companion object {
        const val CAPACITY = 64
        private val lock = Any()
    }
}
