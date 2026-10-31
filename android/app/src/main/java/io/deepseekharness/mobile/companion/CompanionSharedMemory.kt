package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The model's observations are searchable, but remain labelled as unverified. */
internal object CompanionSharedMemory {
    fun add(rows: JSONArray, eventId: String, claims: JSONArray, now: Long, legacyLimit: Boolean, suppressed: (String) -> Boolean): Int {
        require(eventId.matches(Regex("share:[a-f0-9]{64}")) && claims.length() <= 20 && now > 0)
        var added = 0
        for (i in 0 until claims.length()) {
            val value = claims.getJSONObject(i).getString("text").trim()
            if (value.isBlank() || value.length > 600 || CompanionStore.sensitive(value) || suppressed(value)) continue
            if ((0 until rows.length()).any { rows.getJSONObject(it).getString("text") == value }) continue
            if (legacyLimit && rows.length() >= 100) error("长期记忆旧格式已满，请先迁移")
            rows.put(JSONObject().put("id", UUID.randomUUID().toString()).put("text", value).put("status", "pending")
                .put("source", "shared_understanding").put("evidenceEventId", eventId).put("createdAt", now))
            added++
        }
        return added
    }
}
