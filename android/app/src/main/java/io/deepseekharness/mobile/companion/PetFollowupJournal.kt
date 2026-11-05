package io.deepseekharness.mobile.companion

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Private pending inputs are separate from exportable chat history. */
internal class PetFollowupJournal(path: File, private val writer: TaskStateWriter = TaskStateWriter()) {
    private val file = AtomicFile(path)
    data class Entry(val id: String, val text: String, val stage: String, val receipt: String, val after: String, val plan: JSONObject? = null)

    private fun rows(): JSONArray = try { JSONArray(file.openRead().bufferedReader().use { it.readText() }) }
        catch (_: java.io.FileNotFoundException) { check(!file.baseFile.exists()); JSONArray() }

    fun entries(): List<Entry> = synchronized(lock) {
        val rows = rows()
        (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            Entry(row.getString("id"), row.getString("text"), row.getString("stage"), row.optString("receipt"), row.optString("after"), row.optJSONObject("plan"))
        }
    }
    fun accept(text: String, after: String = ""): String = synchronized(lock) {
        val rows = rows()
        check(rows.length() < CAPACITY) { "待处理消息已满（$CAPACITY 条），请先处理已有消息。" }
        val id = UUID.randomUUID().toString()
        write(rows.put(JSONObject().put("id",id).put("text",text).put("stage","queued").put("after",after.take(160).dropLastWhile { it.isHighSurrogate() })))
        id
    }
    fun advance(id: String, stage: String, receipt: String = "", plan: JSONObject? = null) = synchronized(lock) {
        val rows = rows()
        val row = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == id }
        row.put("stage",stage).put("receipt",receipt)
        if (plan != null) row.put("plan", plan)
        write(rows)
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
