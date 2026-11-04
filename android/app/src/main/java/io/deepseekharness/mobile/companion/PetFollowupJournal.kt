package io.deepseekharness.mobile.companion

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Private pending inputs are separate from exportable chat history. */
internal class PetFollowupJournal(path: File) {
    private val file = AtomicFile(path)
    data class Entry(val id: String, val text: String, val stage: String, val receipt: String, val after: String)

    private fun rows(): JSONArray = try { JSONArray(file.openRead().bufferedReader().use { it.readText() }) }
        catch (_: java.io.FileNotFoundException) { check(!file.baseFile.exists()); JSONArray() }

    fun entries(): List<Entry> = synchronized(lock) {
        val rows = rows()
        (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            Entry(row.getString("id"), row.getString("text"), row.getString("stage"), row.optString("receipt"), row.optString("after"))
        }
    }
    fun accept(text: String, after: String = ""): String = synchronized(lock) {
        val rows = rows()
        check(rows.length() < 12) { "有 12 条消息尚未处理，请先查看消息状态。" }
        val id = UUID.randomUUID().toString()
        write(rows.put(JSONObject().put("id",id).put("text",text).put("stage","queued").put("after",after.take(160).dropLastWhile { it.isHighSurrogate() })))
        id
    }
    fun advance(id: String, stage: String, receipt: String = "") = synchronized(lock) {
        val rows = rows()
        val row = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == id }
        row.put("stage",stage).put("receipt",receipt)
        write(rows)
    }
    fun remove(id: String) = synchronized(lock) {
        val rows = rows()
        val index = (0 until rows.length()).firstOrNull { rows.getJSONObject(it).getString("id") == id }
        if (index != null) { rows.remove(index); write(rows) }
    }
    private fun write(rows: JSONArray) {
        val output = file.startWrite()
        try { output.write(rows.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    private companion object { val lock = Any() }
}
