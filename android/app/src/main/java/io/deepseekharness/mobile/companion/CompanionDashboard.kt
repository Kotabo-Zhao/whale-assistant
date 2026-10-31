package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Settings UI uses the same stores as the companion, with bounded, reviewed writes. */
internal class CompanionDashboard(private val context: Context) {
    private val memory get() = CompanionMemoryStore(context)
    private val knowledge get() = CompanionKnowledgeStore(context)
    private val reminder get() = CompanionReminder(context)
    private val recurring get() = CompanionRecurringReminder(context)

    private fun reviewedRows(rows: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until rows.length()) {
            val original = rows.getJSONObject(i).toString()
            put(JSONObject(original).put("_review", original))
        }
    }

    fun execute(request: JSONObject): JSONObject {
        val op = request.getString("operation")
        fun text(key: String) = request.getString(key)
        fun success(ok: Boolean): JSONObject { require(ok) { "内容已变化，请刷新后重试" }; return JSONObject().put("ok", true) }
        return when (op) {
            "overview" -> {
                val store = CompanionStore(context)
                val records = store.read()
                val counts = CompanionBrief.counts(records)
                JSONObject().put("generatedAt", System.currentTimeMillis())
                    .put("captureEnabled", store.notificationCapture)
                    .put("notificationCount", records.length())
                    .put("categories", JSONObject(counts))
                    .put("memoryCount", memory.listForUser().length())
                    .put("knowledgeCount", knowledge.search("").length())
                    .put("reminders", reminder.list()).put("recurring", recurring.list())
            }

            "memoryList" -> {
                val store = CompanionStore(context)
                val suggestions = if (store.notificationMemorySuggestions)
                    CompanionMemorySuggestions.fromRecords(store.read(), store.excludedPackages(), store.dismissedMemorySuggestions()) else emptyList()
                JSONObject().put("items", reviewedRows(memory.listForUser())).put("suggestions", JSONArray().apply {
                    suggestions.forEach { put(JSONObject().put("id", it.id).put("text", it.excerpt).put("source", it.packageName).put("time", it.time)) }
                })
            }
            "memorySuggestionDismiss" -> { CompanionStore(context).dismissMemorySuggestion(text("id")); JSONObject().put("ok", true) }
            "memorySuggestionsReset" -> { CompanionStore(context).clearDismissedMemorySuggestions(); JSONObject().put("ok", true) }
            "memoryReview" -> {
                val review = memory.reviewForUser(text("text"), request.optString("id").ifBlank { null })
                JSONObject().put("snapshot", review.snapshot).put("truncated", review.truncated)
                    .put("candidates", JSONArray().apply { review.candidates.forEach { put(JSONObject().put("status", it.first).put("text", it.second)) } })
            }
            "memoryAdd" -> JSONObject().put("result", memory.addForUser(text("text"), "user_edit", text("snapshot")).name)
            "memoryApprove" -> success(memory.approveForUser(text("id"), text("expectedText"), text("snapshot")))
            "memoryApproveEdit" -> success(memory.approveEditedForUser(text("id"), text("expectedText"), text("text"), text("snapshot")))
            "memoryEdit" -> success(memory.updateForUser(text("id"), text("expected"), text("text"), text("snapshot")))
            "memoryDelete" -> success(memory.deleteForUser(text("id"), text("expected")))
            "memoryResumeAutomatic" -> { memory.resumeAutomaticSources(); JSONObject().put("ok", true) }
            "memoryClear" -> { require(request.optBoolean("confirmed")); memory.clearForUser(); JSONObject().put("ok", true) }
            "knowledgeList" -> JSONObject().put("items", knowledge.search(request.optString("query")))
            "knowledgeAdd" -> JSONObject().put("ok", knowledge.add(text("text"), "text_file"))
            "knowledgeEdit" -> success(knowledge.edit(text("id"), text("expectedText"), text("text")))
            "knowledgeDelete" -> success(knowledge.remove(text("id"), text("expectedText")))
            "reminderList" -> {
                val store = CompanionStore(context)
                JSONObject().put("items", reviewedRows(reminder.list())).put("recurring", reviewedRows(recurring.list()))
                    .put("quietStart", store.quietHoursStart).put("quietEnd", store.quietHoursEnd)
                    .put("notificationStatus", CompanionReminderHealth.read(context).name)
            }
            "quietHoursSet" -> {
                val start = request.getInt("start"); val end = request.getInt("end")
                require(start in 0 until 1440 && end in 0 until 1440) { "时间无效" }
                val store = CompanionStore(context); store.quietHoursStart = start; store.quietHoursEnd = end
                JSONObject().put("ok", true)
            }
            "reminderAdd" -> { reminder.add(text("text"), request.getLong("at")); JSONObject().put("ok", true) }
            "reminderDelete" -> {
                val id = text("id"); val rows = reminder.list()
                val current = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
                require(current?.toString() == text("expected")) { "提醒已变化，请刷新" }
                reminder.remove(id); JSONObject().put("ok", true)
            }
            "recurringAdd" -> { recurring.add(text("text"), request.getInt("hour"), request.getInt("minute"), request.getInt("weekday")); JSONObject().put("ok", true) }
            "recurringEdit" -> success(recurring.edit(text("id"), text("expected"), text("text"), request.getInt("hour"), request.getInt("minute"), request.getInt("weekday")))
            "recurringDelete" -> success(recurring.removeReviewed(text("id"), text("expected")))
            "recurringRetry" -> { recurring.retry(text("id")); JSONObject().put("ok", true) }
            "previewImport" -> preview(text("kind"), text("contents"))
            "applyImport" -> apply(text("token"))
            else -> throw IllegalArgumentException("不支持的助手操作")
        }
    }

    fun export(kind: String): ByteArray = when (kind) {
        "memory" -> memory.exportBackup()
        "knowledge" -> knowledge.exportForUser()
        else -> throw IllegalArgumentException("不支持的备份类型")
    }
    private fun preview(kind: String, contents: String): JSONObject {
        val bytes = contents.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 512_000) { "文件超过大小上限" }
        val token = UUID.randomUUID().toString()
        val entry = when (kind) {
            "memory" -> { val plan = memory.previewImport(bytes); Pending(kind, plan, null, System.currentTimeMillis()) }
            "knowledge" -> { val plan = knowledge.previewImport(bytes); Pending(kind, null, plan, System.currentTimeMillis()) }
            else -> throw IllegalArgumentException("不支持的备份类型")
        }
        synchronized(pending) { pending.clear(); pending[token] = entry }
        val additions = entry.memory?.additions ?: checkNotNull(entry.knowledge).additions
        val skipped = entry.memory?.skipped ?: checkNotNull(entry.knowledge).skipped
        return JSONObject().put("token", token).put("items", JSONArray(additions)).put("skipped", skipped)
    }
    private fun apply(token: String): JSONObject {
        val entry = synchronized(pending) { pending.remove(token) } ?: error("导入预览已失效，请重新选择文件")
        require(System.currentTimeMillis() - entry.at < 15 * 60_000) { "导入预览已过期" }
        val count = when (entry.kind) {
            "memory" -> memory.applyImport(checkNotNull(entry.memory))
            else -> knowledge.applyImport(checkNotNull(entry.knowledge))
        }
        return JSONObject().put("count", count)
    }
    private data class Pending(val kind: String, val memory: MemoryImportPlan?, val knowledge: KnowledgeImportPlan?, val at: Long)
    companion object { private val pending = mutableMapOf<String, Pending>() }
}
