package io.deepseekharness.mobile.companion

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Native view and user commands; the guest scheduler remains the authoritative executor. */
internal class WorkerDashboardStore(context: Context) {
    private val dir = File(context.noBackupFilesDir, "worker-dashboard").apply { mkdirs() }
    private val guestDir = File(context.noBackupFilesDir, "dsh-runtime/current/root/.dsh/mobile-workers")
    fun put(row: JSONObject) = synchronized(lock) {
        val id = row.getString("id"); require(UUID.fromString(id).toString() == id)
        val target = File(dir, "$id.json")
        if (target.exists()) {
            val current = JSONObject(AtomicFile(target).openRead().bufferedReader().use { it.readText() })
            if (row.optLong("writeSeq", 0) < current.optLong("writeSeq", 0)) return@synchronized
        }
        row.put("text", row.getString("title"))
        write(target, row.toString())
    }
    /** Large task histories arrive in bounded pieces; only complete JSON replaces the visible record. */
    fun putChunk(chunk: JSONObject) = synchronized(lock) {
        val id = chunk.getString("id"); require(UUID.fromString(id).toString() == id)
        val seq = chunk.getLong("writeSeq"); val offset = chunk.getLong("offset")
        val bytes = android.util.Base64.decode(chunk.getString("data"), android.util.Base64.NO_WRAP)
        require(bytes.isNotEmpty() && bytes.size <= 48000 && offset >= 0)
        val path = File(dir, "$id.upload")
        if (offset == 0L) { path.writeBytes(byteArrayOf()); uploads[id] = seq }
        require(uploads[id] == seq && path.isFile && path.length() == offset)
        path.appendBytes(bytes)
        if (chunk.getBoolean("final")) {
            val row = JSONObject(path.readText(Charsets.UTF_8))
            require(row.getString("id") == id && row.getLong("writeSeq") == seq)
            put(row)
            uploads.remove(id); path.delete()
        }
    }
    fun list(): JSONObject = synchronized(lock) {
        val rows = dir.listFiles { f -> f.extension == "json" && f.name != "commands.json" }.orEmpty()
            .sortedByDescending { it.lastModified() }
        JSONObject().put("items", JSONArray().apply { rows.forEach { file ->
            val row = JSONObject(file.readText())
            if (guestDir.isDirectory && !File(guestDir, "${row.getString("id")}.json").isFile) {
                row.put("missingLedger", true)
                row.put("uiError", "运行环境中找不到任务执行记录；已保存的会话仍可查看，无法从此卡继续。")
            }
            put(row)
        } })
    }
    fun command(request: JSONObject): JSONObject = synchronized(lock) {
        val id = request.getString("id"); require(UUID.fromString(id).toString() == id)
        require(File(dir, "$id.json").isFile)
        require(request.getString("action") in setOf("cancel", "resume", "reply", "add"))
        require(request.optString("task").length <= 16000)
        val commands = pending()
        commands.put(JSONObject(request.toString()).put("commandId", UUID.randomUUID().toString()))
        write(File(dir, "commands.json"), commands.toString())
        JSONObject().put("ok", true)
    }
    fun pending(): JSONArray = synchronized(lock) {
        val file = AtomicFile(File(dir, "commands.json"))
        if (!file.baseFile.exists()) JSONArray() else JSONArray(file.openRead().bufferedReader().use { it.readText() })
    }
    fun acknowledge(id: String) = synchronized(lock) {
        val old = pending(); val keep = JSONArray()
        for (i in 0 until old.length()) if (old.getJSONObject(i).getString("commandId") != id) keep.put(old.getJSONObject(i))
        write(File(dir, "commands.json"), keep.toString())
    }
    private fun write(path: File, text: String) {
        val file = AtomicFile(path); val output = file.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    companion object { private val lock = Any(); private val uploads = mutableMapOf<String, Long>() }
}
