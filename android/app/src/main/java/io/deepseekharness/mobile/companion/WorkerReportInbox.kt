package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/** Keeps unsolicited Worker reports separate from the alternating chat transcript. */
internal class WorkerReportInbox(context: Context) {
    private val prefs = context.getSharedPreferences("worker_report_inbox", Context.MODE_PRIVATE)

    init {
        synchronized(lock) {
            if (!prefs.getBoolean("seen_initialized", false)) {
                val existing = list().mapNotNull { it.optString("id").takeIf(String::isNotBlank) }.toSet()
                check(prefs.edit().putStringSet("seen_ids", existing).putBoolean("seen_initialized", true).commit())
            }
        }
    }

    fun list(): List<JSONObject> = synchronized(lock) {
        val rows = JSONArray(prefs.getString("items", "[]") ?: "[]")
        (0 until rows.length()).map { rows.getJSONObject(it) }
    }

    fun unseen(): List<JSONObject> = synchronized(lock) {
        val seen = prefs.getStringSet("seen_ids", emptySet()).orEmpty()
        list().filter { it.optString("id") !in seen }
    }

    fun markSeen(ids: Collection<String>) = synchronized(lock) {
        val existing = list().map { it.optString("id") }.toSet()
        val seen = (prefs.getStringSet("seen_ids", emptySet()).orEmpty() + ids).intersect(existing)
        check(prefs.edit().putStringSet("seen_ids", seen).commit())
    }

    fun add(row: JSONObject, report: JSONObject) = synchronized(lock) {
        val id = "${row.getString("id")}:${report.getLong("revision")}"
        val rows = list()
        if (rows.any { it.optString("id") == id }) return@synchronized
        val entry = JSONObject().put("id", id).put("title", row.optString("title")).put("owner",row.optString("owner"))
            .put("state", report.optString("state"))
            .put("text", report.optString("question").ifBlank { report.optString("summary") })
        val next = (rows + entry).takeLast(40)
        check(prefs.edit().putString("items", JSONArray(next).toString()).commit())
        listeners.forEach { it(JSONObject(entry.toString())) }
    }

    fun addCoordination(report: JSONObject) = synchronized(lock) {
        val owner = report.getString("owner"); val fingerprint = report.getString("fingerprint")
        val summary = report.getString("summary"); val next = report.getString("next")
        require(BubbleSessionRegistry.valid(owner) && fingerprint.matches(Regex("[a-f0-9]{64}")))
        require(summary.isNotBlank() && summary.length <= 6000 && next.length <= 3000)
        val id = "coord:$owner:$fingerprint"
        val rows = list()
        if (rows.any { it.optString("id") == id }) return@synchronized
        val entry = JSONObject().put("id",id).put("owner",owner).put("title","整体进展")
            .put("state","progress").put("kind","coordination")
            .put("text",summary + if (next.isNotBlank()) "\n\n下一步：$next" else "")
        check(prefs.edit().putString("items",JSONArray((rows+entry).takeLast(40)).toString()).commit())
        listeners.forEach { it(JSONObject(entry.toString())) }
    }

    companion object {
        private val lock = Any()
        private val listeners = CopyOnWriteArraySet<(JSONObject) -> Unit>()
        fun subscribe(listener: (JSONObject) -> Unit) { listeners.add(listener) }
        fun unsubscribe(listener: (JSONObject) -> Unit) { listeners.remove(listener) }
    }
}
