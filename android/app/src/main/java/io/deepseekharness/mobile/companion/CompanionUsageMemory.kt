package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Observations only; use cannot prove preference or a sensitive personal attribute. */
internal object CompanionUsageMemory {
    fun summary(snapshot: CompanionUsage.Snapshot, own: String, excluded: Set<String>, notifications: JSONArray = JSONArray()): String? {
        if (snapshot.state != CompanionUsage.State.OK) return null
        val items = snapshot.candidates.filter { it.packageName != own && it.packageName !in excluded && CompanionSourcePolicy.validPackage(it.packageName) && it.foregroundMs >= CompanionUsage.MIN_MS }.take(5)
        if (items.isEmpty()) return null
        val counts = mutableMapOf<String, Int>()
        for (i in 0 until notifications.length()) {
            val pkg = notifications.optJSONObject(i)?.optString("package") ?: continue
            if (pkg in excluded || !CompanionSourcePolicy.validPackage(pkg)) continue
            counts[pkg] = (counts[pkg] ?: 0) + 1
        }
        val combined = "最近约7天应用使用与本机留存通知（系统估计，非偏好判断）：" + items.joinToString("；") {
            val count = counts[it.packageName]
            "${it.packageName}约${it.foregroundMs / 60_000}分钟" + (if (count == null) "" else "，留存${count}条通知")
        }
        return CompanionNotificationRecords.truncate(combined, 600)
    }
    fun upsert(rows: JSONArray, text: String, now: Long): Boolean {
        require(text.isNotBlank() && text.length <= 600 && now > 0 && !CompanionStore.sensitive(text))
        val existing = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("source") == "auto_usage" }
        if (existing != null) {
            if (existing.optString("text") == text) return false
            existing.put("text", text).put("updatedAt", now)
            return true
        }
        if (rows.length() >= 100) return false
        rows.put(JSONObject().put("id", UUID.randomUUID().toString()).put("text", text).put("source", "auto_usage").put("status", "confirmed").put("createdAt", now))
        return true
    }
}
