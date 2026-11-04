package io.deepseekharness.mobile.companion

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Native view and user commands; the guest scheduler remains the authoritative executor. */
internal class WorkerDashboardStore(private val context: Context) {
    private val notifications = WorkerReportInbox(context)
    private val dir = File(context.noBackupFilesDir, "worker-dashboard").apply { mkdirs() }
    private val guestDir = File(context.noBackupFilesDir.canonicalFile, "dsh-runtime/current/root/.dsh/mobile-workers")
    fun put(row: JSONObject) = synchronized(lock) {
        val id = row.getString("id"); require(UUID.fromString(id).toString() == id)
        val target = File(dir, "$id.json")
        val current = try { WorkerCardCatalog.full(target) } catch (_: java.io.FileNotFoundException) { null }
        if (current != null) {
            if (row.optLong("writeSeq", 0) < current.optLong("writeSeq", 0)) return@synchronized
        }
        // The guest mirrors execution. Only the native user-command path grants draft access.
        for (field in listOf("localCreation", "creationRevoked", "authoredDraft")) {
            row.remove(field)
            if (current?.has(field) == true) row.put(field, current.get(field))
        }
        row.put("text", row.getString("title"))
        writeWorker(target, row)
        if (current == null && row.optLong("createdAt") >= processStartedAt &&
            row.optString("owner") != "session-whale-task-desk" && row.optString("state") in ACTIVE_STATES) {
            acceptedListeners.forEach { it() }
        }
        listeners.forEach { it() }
        val oldReports = current?.optJSONArray("reports") ?: JSONArray()
        val known = (0 until oldReports.length()).map { oldReports.getJSONObject(it).optLong("revision") }.toSet()
        val reports = row.optJSONArray("reports") ?: JSONArray()
        for (index in 0 until reports.length()) {
            val report = reports.getJSONObject(index)
            if (report.optLong("revision") !in known && (current != null || report.optLong("finishedAt") >= processStartedAt)) {
                notifications.add(row, report)
            }
        }
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
    private fun catalog(): WorkerCardCatalog {
        if (catalogDir != dir || catalogCache == null) {
            catalogDir = dir; catalogCache = WorkerCardCatalog(dir)
        }
        return checkNotNull(catalogCache)
    }
    private fun writeWorker(file: File, row: JSONObject) {
        val card = WorkerCardCatalog.write(file,row)
        if (catalogDir == dir) catalogCache?.remember(file,card)
    }
    fun activeFor(owner: String): Boolean = synchronized(lock) { catalog().activeFor(owner) }

    fun groups(): JSONArray = synchronized(lock) { catalog().groups() }

    fun page(filter: String = "all", group: String = "", offset: Int = 0, limit: Int = 20, withCounts: Boolean = true): JSONObject = synchronized(lock) {
        val commands = pending()
        val submissions = JSONArray()
        var queued = 0
        for (i in 0 until commands.length()) {
            val item = commands.getJSONObject(i)
            if (item.optString("action") != "start") continue
            queued++
            if (limit > 0) submissions.put(JSONObject().put("task",item.optString("task").take(120))
                .put("notice",item.optString("notice")).put("commandId",item.optString("commandId")))
        }
        catalog().page(guestDir,submissions,queued,filter,group,offset,limit,withCounts)
    }

    /** Page backwards from the newest revision; even a single revision with many progress reports is bounded. */
    fun detail(id: String, offset: Int = 0, limit: Int = 12): JSONObject = synchronized(lock) {
        require(offset >= 0 && limit in 1..40)
        val row = worker(id)
        val detail = WorkerCardCatalog.compact(row)
        for (key in listOf("scope","decisionReason","decisionEvidence","summary","question","progress","uiError"))
            if (row.has(key)) detail.put(key,row.get(key))
        if (guestDir.isDirectory && !File(guestDir,"$id.json").isFile) WorkerCardCatalog.markMissing(detail)
        data class Event(val kind: String, val data: JSONObject, val phase: Int)
        val events = mutableListOf<Event>()
        listOf("inputs" to "input", "progressHistory" to "progress", "reports" to "report").forEachIndexed { phase, (field, kind) ->
            val array = row.optJSONArray(field) ?: return@forEachIndexed
            for (i in 0 until array.length()) events.add(Event(kind, array.getJSONObject(i), phase))
        }
        // Unassigned updates follow numbered revisions. Preserve order within each original array.
        events.sortWith(compareBy<Event> { it.data.optLong("revision",Long.MAX_VALUE) }.thenBy { it.phase })
        val end = (events.size-offset).coerceAtLeast(0)
        val start = (end-limit).coerceAtLeast(0)
        detail.put("events",JSONArray().apply {
            for (i in start until end) {
                val event = events[i]
                val item = JSONObject().put("type",event.kind)
                for (key in listOf("id","revision","kind","text","summary","state","delivery","readAt","createdAt","at","finishedAt"))
                    if (event.data.has(key)) item.put(key,event.data.get(key))
                put(item)
            }
        }).put("historyCount",events.size).put("historyOffset",offset)
    }

    fun list(): JSONObject = synchronized(lock) {
        val rows = dir.listFiles { f -> f.extension == "json" && f.name != "commands.json" }.orEmpty()
            .sortedByDescending { it.lastModified() }
        JSONObject().put("items", JSONArray().apply { rows.forEach { file ->
            val row = WorkerCardCatalog.full(file)
            if (guestDir.isDirectory && !File(guestDir, "${row.getString("id")}.json").isFile) {
                WorkerCardCatalog.markMissing(row)
            }
            put(row)
        } }).put("submissions", JSONArray().apply {
            val commands = pending()
            for (i in 0 until commands.length()) {
                val item = commands.getJSONObject(i)
                if (item.optString("action") == "start") put(item)
            }
        })
    }
    fun submit(task: String, mode: String, commandId: String = UUID.randomUUID().toString(), title: String? = null): JSONObject {
        val text = task.trim()
        require(text.isNotEmpty() && text.length <= 2000 && mode in setOf("read", "device", "create"))
        require(UUID.fromString(commandId).toString() == commandId)
        val command = JSONObject().put("commandId", commandId).put("action", "start")
            .put("task", text).put("mode", mode).put("createdAt", System.currentTimeMillis())
            .apply { title?.takeIf(String::isNotBlank)?.let { put("title", it.take(120)) } }
        if (enqueue(command)) {
            acceptedListeners.forEach { it() }
            listeners.forEach { it() }
        }
        return JSONObject().put("commandId", command.getString("commandId"))
    }
    private fun enqueue(command: JSONObject): Boolean = synchronized(commandLock) {
        val items = pending()
        val id = command.getString("commandId")
        val existing = (0 until items.length()).firstOrNull { items.getJSONObject(it).optString("commandId") == id }?.let(items::getJSONObject)
        if (existing != null) {
            check(existing.optString("action") == command.optString("action") &&
                existing.optString("task") == command.optString("task") && existing.optString("mode") == command.optString("mode"))
            return@synchronized false
        }
        write(File(dir, "commands.json"), items.put(command).toString())
        // The guest watches only this hint. Recovery always reads the durable command queue.
        runCatching {
            if (guestDir.isDirectory && guestDir.canonicalFile == guestDir) {
                java.nio.file.Files.newOutputStream(File(guestDir, ".ui-wake").toPath(), java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    .use { it.write(command.getString("commandId").toByteArray()) }
            }
        }
        true
    }
    fun dispatchUnavailable(id: String, pickupFailure: Boolean = false) {
        val changed = synchronized(commandLock) {
            val items = pending()
            val item = (0 until items.length()).map(items::getJSONObject).firstOrNull { it.optString("commandId") == id }
                ?: return@synchronized false
            val notice = if (pickupFailure) "后台接取失败，已保留任务并等待重试" else "请在主应用打开对话运行环境，任务将自动接取"
            if (item.optString("notice") == notice) return@synchronized false
            item.put("notice", notice)
            write(File(dir, "commands.json"), items.toString())
            true
        }
        if (changed) listeners.forEach { it() }
    }

    fun command(request: JSONObject): JSONObject {
        synchronized(lock) {
            val id = request.getString("id"); require(UUID.fromString(id).toString() == id)
            require(File(dir, "$id.json").isFile)
            require(request.getString("action") in setOf("cancel", "resume", "reply", "add", "priority"))
            require(request.optString("task").length <= 16000)
            enqueue(JSONObject(request.toString()).put("commandId", UUID.randomUUID().toString()))
        }
        listeners.forEach { it() }
        return JSONObject().put("ok", true)
    }
    fun pending(): JSONArray = synchronized(commandLock) {
        val file = AtomicFile(File(dir, "commands.json"))
        try { JSONArray(file.openRead().bufferedReader().use { it.readText() }) }
        catch (_: java.io.FileNotFoundException) { JSONArray() }
    }
    fun batch(skip: Int): JSONArray = synchronized(lock) {
        require(skip >= 0)
        val all = pending()
        JSONArray().apply { for (i in skip.coerceAtMost(all.length()) until minOf(all.length().toLong(), skip.toLong() + 4).toInt()) put(all.getJSONObject(i)) }
    }
    private fun workerFile(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(dir, "$id.json")
    }
    private fun worker(id: String) = WorkerCardCatalog.full(workerFile(id))

    fun acknowledge(id: String, authorWorkerId: String? = null) {
        val changed = synchronized(lock) { synchronized(commandLock) command@{
            val old = pending()
            val command = (0 until old.length()).map(old::getJSONObject).firstOrNull { it.optString("commandId") == id }
                ?: return@command false
            if (command.optString("action") == "start" && command.optString("mode") == "create") {
                val workerId = requireNotNull(authorWorkerId)
                val row = worker(workerId)
                check(row.optString("creationCommandId") == id && row.optString("mode") == "create" &&
                    row.optString("owner") == "session-whale-task-desk" && row.optString("sessionId") == "session-worker-$workerId")
                val bound = command.optString("authorWorkerId")
                check(bound.isEmpty() || bound == workerId)
                if (bound.isEmpty()) {
                    command.put("authorWorkerId", workerId)
                    write(File(dir, "commands.json"), old.toString())
                }
                writeWorker(workerFile(workerId), row.put("localCreation", id))
            } else if (command.optString("action") == "cancel") {
                val workerId = command.getString("id")
                val row = worker(workerId)
                if (row.has("localCreation") || row.optString("mode") == "create") writeWorker(workerFile(workerId), row.put("creationRevoked", true))
            }
            val keep = JSONArray()
            for (i in 0 until old.length()) if (old.getJSONObject(i).optString("commandId") != id) keep.put(old.getJSONObject(i))
            write(File(dir, "commands.json"), keep.toString())
            true
        } }
        if (changed) listeners.forEach { it() }
    }

    fun previewDraft(workerId: String, revision: String) {
        val feature = synchronized(lock) {
            val row = worker(workerId)
            val draft = row.getJSONObject("authoredDraft")
            check(row.has("localCreation") && draft.getString("id") == "task-" + row.getString("localCreation") &&
                draft.getString("revision") == revision) { "草稿已变化，请刷新任务卡" }
            draft.getString("id")
        }
        io.deepseekharness.mobile.features.WhaleFeatureActivity.openTool(context, feature, revision, preview = true)
    }

    /** A queued local cancellation wins even before the guest has acknowledged it. */
    fun author(request: JSONObject): JSONObject = synchronized(lock) {
        val workerId = request.getString("workerId")
        val row = worker(workerId)
        val command = request.getString("commandId")
        val featureId = "task-$command"
        val commands = pending()
        check(UUID.fromString(command).toString() == command && request.getString("id") == featureId &&
            row.optString("localCreation") == command && row.optString("creationCommandId") == command &&
            row.optString("owner") == "session-whale-task-desk" && row.optString("mode") == "create" &&
            row.optString("sessionId") == "session-worker-$workerId" && row.optString("state") == "running" &&
            (row.isNull("reportState") || row.optString("reportState").isEmpty()) && !row.optBoolean("authorizationRevoked") &&
            !row.optBoolean("creationRevoked") && row.getLong("revision") == request.getLong("revision") &&
            row.getString("domainAssignmentId") == request.getString("assignmentId") &&
            (0 until commands.length()).none { i -> commands.getJSONObject(i).let { it.optString("id") == workerId && it.optString("action") == "cancel" } }) {
            "创作授权无效或任务已停止"
        }
        val features = io.deepseekharness.mobile.features.WhaleFeatureStore(context)
        val action = request.getString("action")
        if (action == "list") {
            val existing = features.readAssigned(featureId)
            return@synchronized JSONObject().put("id", featureId).put("latest", existing?.getString("latest").orEmpty())
        }
        val result = when (action) {
            "draft" -> features.draft(request, assignedId = featureId)
            "read" -> {
                val feature = requireNotNull(features.readAssigned(featureId))
                val revision = feature.getString("latest")
                JSONObject(feature.getJSONObject("versions").getJSONObject(revision).toString()).put("revision", revision)
            }
            else -> error("只支持读取或提交工单草稿")
        }
        val revision = if (action == "draft") result.getString("latest") else result.getString("revision")
        row.put("authoredDraft", JSONObject().put("id", featureId).put("revision", revision).put("title", result.getString("title")))
        writeWorker(workerFile(workerId), row)
        listeners.forEach { it() }
        result
    }
    private fun write(path: File, text: String) {
        val file = AtomicFile(path); val output = file.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    companion object {
        private val lock = Any()
        private val commandLock = Any()
        private var catalogDir: File? = null
        private var catalogCache: WorkerCardCatalog? = null
        private val uploads = mutableMapOf<String, Long>()
        private val processStartedAt = System.currentTimeMillis()
        private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
        private val acceptedListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
        fun subscribeAccepted(listener: () -> Unit) { acceptedListeners.add(listener) }
        fun unsubscribeAccepted(listener: () -> Unit) { acceptedListeners.remove(listener) }
        fun subscribe(listener: () -> Unit) { listeners.add(listener) }
        fun unsubscribe(listener: () -> Unit) { listeners.remove(listener) }
        /** 与 guest `workers.js` 的行状态对齐：这些状态表示任务还没结束。 */
        private val ACTIVE_STATES = setOf("queued", "starting", "running", "waiting", "blocked")
    }
}
