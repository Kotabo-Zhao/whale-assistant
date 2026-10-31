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
    /** The conversation draft carries a value that mobile_knowledge_read verifies before reading. */
    private fun reviewedKnowledgeRows(rows: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val original = row.toString()
            put(JSONObject(original).put("_review", original)
                .put("revision", CompanionMemorySuppression.digest(row.getString("text"))))
        }
    }

    fun execute(request: JSONObject): JSONObject {
        val op = request.getString("operation")
        fun text(key: String) = request.getString(key)
        fun success(ok: Boolean): JSONObject { require(ok) { "内容已变化，请刷新后重试" }; return JSONObject().put("ok", true) }
        fun withCurrentKnowledge(action: (String, String, String) -> Boolean): JSONObject {
            val id = text("id")
            val expectedText = text("expectedText")
            val hash = CompanionMemorySuppression.digest(expectedText)
            var completed = false
            check(knowledge.withUnchangedRecord(id, expectedText) { completed = action(id, expectedText, hash) }) { "资料已变化，请刷新后重试" }
            return success(completed)
        }
        return when (op) {
            "overview" -> {
                val store = CompanionStore(context)
                val records = store.read()
                val counts = CompanionBrief.counts(records)
                val tasks = UnifiedUnderstandingStore.open(context).use { it.queue.listActionableItems(3) }
                JSONObject().put("generatedAt", System.currentTimeMillis())
                    .put("captureEnabled", store.notificationCapture)
                    .put("notificationCount", records.length())
                    .put("categories", JSONObject(counts))
                    .put("memoryCount", memory.listForUser().length())
                    .put("knowledgeCount", knowledge.search("").length())
                    .put("tasks", JSONArray().apply { tasks.forEach { item -> put(JSONObject()
                        .put("id", item.id).put("text", item.title).put("state", item.state)
                        .put("reminderAtMs", item.reminderAtMs ?: JSONObject.NULL)) } })
                    .put("reminders", reminder.list()).put("recurring", recurring.list())
            }

            "memoryList" -> {
                val store = CompanionStore(context)
                val suggestions = if (store.notificationMemorySuggestions)
                    CompanionMemorySuggestions.fromRecords(store.read(), store.excludedPackages(), store.dismissedMemorySuggestions()) else emptyList()
                val page = if (memory.usesDatabase()) memory.pageForUser(null, null) else null
                JSONObject().put("items", reviewedRows(page?.rows ?: memory.listForUser())).put("suggestions", JSONArray().apply {
                    suggestions.forEach { put(JSONObject().put("id", it.id).put("text", it.excerpt).put("source", it.packageName).put("time", it.time)) }
                }).apply {
                    if (page != null) { put("nextBefore", page.next ?: JSONObject.NULL); put("revision", page.revision) }
                }
            }
            "memoryPage" -> {
                require(memory.usesDatabase()) { "记忆数据库不可用" }
                val before = request.opt("before") as? Number ?: throw IllegalArgumentException("记忆分页参数无效")
                val revision = request.opt("revision") as? Number ?: throw IllegalArgumentException("记忆分页参数无效")
                val page = memory.pageForUser(before.toLong(), revision.toLong())
                JSONObject().put("items", reviewedRows(page.rows)).put("nextBefore", page.next ?: JSONObject.NULL).put("revision", page.revision)
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
            "memoryBatchDelete" -> {
                val entries = request.optJSONArray("entries") ?: throw IllegalArgumentException("批量删除参数无效")
                val deleted = memory.deleteManyForUser(entries) ?: throw IllegalStateException("记忆已变化，请刷新后重试")
                JSONObject().put("ok", true).put("deleted", deleted)
            }
            "memoryResumeAutomatic" -> { memory.resumeAutomaticSources(); JSONObject().put("ok", true) }
            "memoryClear" -> { require(request.optBoolean("confirmed")); memory.clearForUser(); JSONObject().put("ok", true) }
            "knowledgeList" -> {
                val rawQuery = request.opt("query")
                val query = when (rawQuery) {
                    null, JSONObject.NULL -> ""
                    is String -> rawQuery
                    else -> throw IllegalArgumentException("资料查询参数无效")
                }
                val rawOffset = request.opt("offset")
                val offset = when (rawOffset) {
                    null, JSONObject.NULL -> 0
                    is Int -> rawOffset
                    is Long -> rawOffset.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
                        ?: throw IllegalArgumentException("资料查询参数无效")
                    else -> throw IllegalArgumentException("资料查询参数无效")
                }
                require(query.length <= 100 && offset >= 0) { "资料查询参数无效" }
                fun filter(key: String, values: Set<String>): String? {
                    if (!request.has(key) || request.isNull(key)) return null
                    return (request.opt(key) as? String)?.takeIf { it in values }
                        ?: throw IllegalArgumentException("资料查询参数无效")
                }
                val source = filter("source", setOf("shared_text", "text_file", "original", "image_analysis"))
                val coverage = filter("coverage", setOf("not_parsed", "partial", "extracted_text", "model_description"))
                val (items, nextOffset) = knowledge.searchPage(query, offset, 20, source, coverage)
                JSONObject().put("items", reviewedKnowledgeRows(items)).put("nextOffset", nextOffset ?: JSONObject.NULL)
                    .put("source", source ?: JSONObject.NULL).put("coverage", coverage ?: JSONObject.NULL)
            }
            "knowledgeAdd" -> JSONObject().put("ok", knowledge.add(text("text"), "text_file"))
            "knowledgeEdit" -> success(knowledge.edit(text("id"), text("expectedText"), text("text")))
            "knowledgeDelete" -> success(knowledge.remove(text("id"), text("expectedText")))
            "knowledgeBatchDelete" -> {
                val entries = request.optJSONArray("entries") ?: throw IllegalArgumentException("批量删除参数无效")
                val removed = knowledge.removeMany(entries) ?: throw IllegalStateException("资料已变化，请刷新后重试")
                JSONObject().put("ok", true).put("deleted", removed.length())
            }
            "knowledgeUndoDelete" -> success(knowledge.undoRemove(text("id")))
            "knowledgeBatchUndoDelete" -> {
                val ids = request.optJSONArray("ids") ?: throw IllegalArgumentException("批量撤销参数无效")
                val values = (0 until ids.length()).map { ids.optString(it) }
                success(knowledge.undoMany(values))
            }
            "knowledgeProcessingStatus" -> {
                val id = text("id")
                val expectedText = text("expectedText")
                var status: JSONObject? = null
                check(knowledge.withUnchangedRecord(id, expectedText) {
                    status = SharedMemoryJobs.status(context, id, CompanionMemorySuppression.digest(expectedText))
                }) { "资料已变化，请刷新后重试" }
                checkNotNull(status).put("memoryEnabled", memory.enabled)
            }
            "knowledgeProcessingRetry" -> {
                require(memory.enabled) { "长期记忆整理已关闭" }
                withCurrentKnowledge { id, _, hash ->
                    SharedMemoryJobs.retryRecord(context, id, hash).also { if (it) SharedMemoryJobs.schedule(context, 0) }
                }
            }
            "knowledgeProcessingCancel" -> withCurrentKnowledge { id, _, hash ->
                SharedMemoryJobs.cancelRecord(context, id, hash)
            }
            "knowledgeProcessingResume" -> {
                require(memory.enabled) { "长期记忆整理已关闭" }
                withCurrentKnowledge { id, value, _ ->
                    SharedMemoryJobs.resumeRecord(context, id, value).also { if (it) SharedMemoryJobs.schedule(context, 0) }
                }
            }
            "knowledgeRemoveOriginal" -> {
                val removal = knowledge.removeOriginal(text("originalId"), text("id"), text("expectedText"))
                require(removal.records > 0) { "资料已变化，请刷新后重试" }
                JSONObject().put("ok", true).put("records", removal.records)
                    .put("objectDeleted", removal.objectDeleted).put("undoAvailable", false)
            }
            "reminderList" -> {
                val store = CompanionStore(context)
                JSONObject().put("items", reviewedRows(reminder.list())).put("recurring", reviewedRows(recurring.list()))
                    .put("quietStart", store.quietHoursStart).put("quietEnd", store.quietHoursEnd)
                    .put("notificationStatus", CompanionReminderHealth.read(context).name)
            }
            "taskList" -> UnifiedUnderstandingStore.open(context).use { data ->
                JSONObject().put("items", JSONArray().apply {
                    data.queue.listItems(100).forEach { item -> put(JSONObject()
                        .put("id", item.id).put("text", item.title).put("state", item.state)
                        .put("sourceKind", if (item.eventId.startsWith("share:")) "shared_material" else "notification")
                        .put("sourceRecordId", data.queue.sharedRecordId(item.eventId) ?: JSONObject.NULL)
                        .put("reminderStatus", item.reminderStatus).put("reminderAtMs", item.reminderAtMs ?: JSONObject.NULL)
                        .put("revision", CompanionTaskModel.revision(item)))
                })
            }
            "taskUpdate" -> {
                val id = text("id")
                val expectedState = text("expectedState")
                val expectedRevision = text("expectedRevision")
                val next = request.optString("state").takeIf { it.isNotBlank() }
                val hasReminder = request.has("reminderAtMs") && !request.isNull("reminderAtMs")
                val reminderAtMs = when (val value = request.opt("reminderAtMs")) {
                    is Int -> value.toLong()
                    is Long -> value
                    is Double -> value.toLong().takeIf { it.toDouble() == value }
                    else -> null
                }
                require(expectedState in setOf("candidate", "open", "done", "cancelled")) { "事项状态无效" }
                require((next != null) xor hasReminder) { "请只选择一种事项操作" }
                require(next == null || next in setOf("open", "done", "cancelled")) { "事项状态无效" }
                require(!hasReminder || reminderAtMs != null) { "提醒时间无效" }
                val changed = UnifiedUnderstandingStore.open(context).use { data ->
                    val current = data.queue.item(id) ?: return@use null
                    require(current.state == expectedState && CompanionTaskModel.revision(current) == expectedRevision) { "事项已变化，请刷新后重试" }
                    val updated = if (reminderAtMs != null) data.queue.setItemReminder(id, expectedState, reminderAtMs, System.currentTimeMillis())
                    else data.queue.updateItem(id, expectedState, checkNotNull(next))
                    if (updated) data.queue.item(id) else null
                } ?: throw IllegalStateException("事项已变化，请刷新后重试")
                try {
                    if (reminderAtMs != null) CompanionReminder(context).upsertLinked(changed.id, changed.title, reminderAtMs, changed.reminderRevision)
                    else if (next in setOf("done", "cancelled")) CompanionReminder(context).removeLinked(id)
                } catch (e: Exception) {
                    if (reminderAtMs != null) runCatching { UnifiedUnderstandingStore.open(context).use { it.queue.disableItemReminder(changed.id, reminderAtMs, changed.reminderRevision) } }
                    throw IllegalStateException("事项已更新，但关联提醒未能安排；已撤销本次提醒状态", e)
                }
                success(true)
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
