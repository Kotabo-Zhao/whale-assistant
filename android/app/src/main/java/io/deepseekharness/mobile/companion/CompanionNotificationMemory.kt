package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A bounded local rollup of observed notifications, not a conclusion about the user. */
internal object CompanionNotificationMemory {
    private const val MAX_EVENTS = 4
    fun upsert(rows: JSONArray, packageName: String, title: String, body: String, now: Long): Boolean {
        if (now <= 0 || CompanionAutomaticMemory.fromNotification(packageName, title, body) == null) return false
        val event = CompanionNotificationRecords.truncate(listOf(title.trim(), body.trim()).filter { it.isNotEmpty() }.distinct().joinToString("："), 100)
        if (event.isBlank()) return false
        val existing = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull {
            it.optString("source") == "auto_notification_group" && it.optString("sourcePackage") == packageName
        }
        val previous = existing?.optJSONArray("events")
        val events = mutableListOf<String>()
        if (previous != null) for (i in 0 until minOf(previous.length(), MAX_EVENTS)) {
            val value = previous.opt(i) as? String ?: return false
            if (value.isBlank() || value.length > 100 || CompanionStore.sensitive(value)) return false
            events.add(value)
        }
        if (event in events) return false
        events.add(event)
        while (events.size > MAX_EVENTS) events.removeAt(0)
        val prefix = "来自 $packageName 的近期通知（未经核实）："
        val summary = CompanionNotificationRecords.truncate(prefix + events.joinToString("；"), 600)
        if (existing != null) {
            existing.put("events", JSONArray(events)).put("text", summary).put("updatedAt", now)
        } else {
            if (rows.length() >= 100) return false
            rows.put(JSONObject().put("id", UUID.randomUUID().toString()).put("text", summary)
                .put("source", "auto_notification_group").put("sourcePackage", packageName)
                .put("events", JSONArray(events)).put("status", "confirmed").put("createdAt", now))
        }
        return true
    }
}
