package io.deepseekharness.mobile.companion

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A view of pet requests, not another executor. The owning Harness session keeps the transcript. */
internal class PetExecutionStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "pet-executions")

    fun update(id: String, sessionId: String, title: String, state: String) = synchronized(lock) {
        require(id == UUID.fromString(id).toString())
        require(state in setOf("running", "waiting", "completed", "interrupted", "failed", "permission"))
        directory.mkdirs()
        val file = AtomicFile(File(directory, "$id.json"))
        if (state == "running" && file.baseFile.exists() &&
            JSONObject(file.openRead().bufferedReader().use { it.readText() }).optString("state") == "completed") return@synchronized
        val row = JSONObject().put("id", id).put("sessionId", sessionId).put("text", title.take(200))
            .put("state", state).put("process", process).put("updatedAt", System.currentTimeMillis())
        val output = file.startWrite()
        try { output.write(row.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        listeners.forEach { runCatching(it) }
    }

    fun page(offset: Int): JSONObject = synchronized(lock) {
        require(offset >= 0)
        val files = directory.listFiles { file -> file.extension == "json" }.orEmpty().sortedByDescending { it.lastModified() }
        val items = JSONArray()
        files.drop(offset).take(30).forEach { file ->
            val row = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
            if (row.optString("process") != process && row.optString("state") in setOf("running", "waiting", "permission"))
                row.put("state", "interrupted")
            row.remove("process")
            items.put(row)
        }
        JSONObject().put("items", items).put("nextOffset", if (offset + 30 < files.size) offset + 30 else JSONObject.NULL)
    }

    companion object {
        private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
        fun subscribe(listener: () -> Unit) { listeners.add(listener) }
        fun unsubscribe(listener: () -> Unit) { listeners.remove(listener) }
        private val lock = Any()
        private val process = UUID.randomUUID().toString()
    }
}
