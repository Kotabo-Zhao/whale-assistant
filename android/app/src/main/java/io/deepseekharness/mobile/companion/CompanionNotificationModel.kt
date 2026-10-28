package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
import org.json.JSONObject

/** Read-only and separately authorized; source text remains untrusted and may be sent to a model. */
internal object CompanionNotificationModel {
    fun execute(enabled: Boolean, param: String, now: Long, capture: Boolean, authorized: Boolean,
                connected: Boolean, excluded: Set<String>, load: () -> JSONArray): String {
        if (!enabled) throw RuntimeFailure("NOTIFICATIONS_MODEL_DISABLED", "请先在助手设置中允许对话访问通知摘要")
        val request = try { JSONObject(param) } catch (_: Exception) { throw invalid() }
        val query = if (request.has("query")) request.opt("query") as? String ?: throw invalid() else ""
        val packageName = if (request.has("packageName")) request.opt("packageName") as? String ?: throw invalid() else ""
        if (query.length > 100 || query.any { it.code < 32 || it.code == 127 } ||
            (packageName.isNotEmpty() && !CompanionSourcePolicy.validPackage(packageName))) throw invalid()
        val requested = if (request.has("limit")) request.opt("limit") as? Int ?: throw invalid() else 10
        if (requested !in 1..10) throw invalid()
        val records = try { load() } catch (e: Exception) { throw RuntimeFailure("NOTIFICATIONS_UNAVAILABLE", "无法读取本地通知记录", e) }
        val output = JSONArray()
        var matches = 0
        for (index in records.length() - 1 downTo 0) {
            val row = records.getJSONObject(index)
            val pkg = row.getString("package")
            val title = row.getString("title")
            val text = row.getString("text")
            val time = row.getLong("time")
            if (!CompanionSourcePolicy.validPackage(pkg) || pkg in excluded || (packageName.isNotEmpty() && pkg != packageName) ||
                CompanionStore.sensitive("$title $text") || (query.isNotBlank() && !"$title $text".contains(query.trim(), ignoreCase = true))) continue
            matches++
            if (output.length() < requested) output.put(JSONObject().put("packageName", pkg).put("title", title.take(160)).put("text", text.take(400))
                .put("timeMs", time).put("category", CompanionBrief.category(title, text)))
        }
        return JSONObject().put("schemaVersion", 1).put("source", "saved_notifications").put("checkedAtMs", now)
            .put("captureEnabled", capture).put("listenerAuthorized", authorized).put("listenerConnected", connected)
            .put("items", output).put("truncated", matches > requested).toString()
    }
    private fun invalid() = RuntimeFailure("NOTIFICATIONS_INPUT_INVALID", "通知查询参数无效")
}
