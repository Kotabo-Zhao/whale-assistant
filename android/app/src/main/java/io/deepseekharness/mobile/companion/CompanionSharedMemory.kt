package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The model's observations are searchable, but remain labelled as unverified. */
internal object CompanionSharedMemory {
    fun understandText(context: android.content.Context, text: String): Boolean {
        val app = context.applicationContext
        val key = io.deepseekharness.mobile.runtime.RuntimeStore(app).readModelCredential("deepseek")
        if (key.isNullOrBlank()) return false
        val knowledge = CompanionKnowledgeStore(app)
        val rows = knowledge.search("")
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("text") == text } ?: return false
        val requestId = UUID.randomUUID().toString()
        val result = JSONObject(DeepSeekUnderstandingModel(key, "deepseek-flash").analyze(requestId, text))
        return knowledge.withUnchangedRecord(row.getString("id"), text) {
            CompanionMemoryStore(app).saveSharedFacts("share:" + CompanionMemorySuppression.digest(text), result.getJSONArray("claims"))
        }
    }
    fun add(rows: JSONArray, eventId: String, claims: JSONArray, now: Long, legacyLimit: Boolean, suppressed: (String) -> Boolean): Int {
        require(eventId.matches(Regex("share:[a-f0-9]{64}")) && claims.length() <= 20 && now > 0)
        var added = 0
        for (i in 0 until claims.length()) {
            val value = claims.getJSONObject(i).getString("text").trim()
            if (value.isBlank() || value.length > 600 || CompanionStore.sensitive(value) || suppressed(value)) continue
            if ((0 until rows.length()).any { rows.getJSONObject(it).getString("text") == value }) continue
            if (legacyLimit && rows.length() >= 100) error("长期记忆旧格式已满，请先迁移")
            rows.put(JSONObject().put("id", UUID.randomUUID().toString()).put("text", value).put("status", "confirmed")
                .put("source", "shared_understanding").put("evidenceEventId", eventId).put("createdAt", now))
            added++
        }
        return added
    }
}
