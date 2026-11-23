package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 纯原生 Kotlin 后台 Worker 并发池（零 Node.js 依赖）。
 *
 * 融合 DeepSeek Super-agent 核心调度特性：
 * - 3 常规并发槽 + 1 人类交互专属抢占槽（[WhaleSuperagentCore.WorkerSlots]），保证用户追问永不被后台任务饿死；
 * - 结构化 7 态 [WhaleSuperagentCore.TicketStage]（`person/delivery` > `person/answer` > `person/review` > `person/blocked|failed` > `working/queued` > `idle` > `working`）；
 * - 追问透传（[deliverFollowup]）直接绕过主会话注入运行中 Worker，并严格区分 `deliveredInputs` 与 `readInputIds`；
 * - 600 字符截断预览 + `read: "worker/<id>"` 按需分页读取，大幅降低主对话上下文膨胀。
 */
internal object WhaleNativeWorkerPool {
    data class WorkerEntry(
        val id: String,
        val title: String,
        @Volatile var state: String,
        @Volatile var reportState: String = "",
        @Volatile var summary: String = "",
        @Volatile var currentStep: String = "准备执行",
        @Volatile var livePreview: String = "",
        @Volatile var attention: String = "",
        @Volatile var deliveryFailed: Boolean = false,
        @Volatile var hasHumanSinceReport: Boolean = false,
        @Volatile var round: Int = 0,
        val startedAtMs: Long = System.currentTimeMillis(),
        @Volatile var updatedAtMs: Long = System.currentTimeMillis(),
        @Volatile var finishedAtMs: Long = 0L,
        @Volatile var future: Future<*>? = null,
        @Volatile var control: WhaleNativeAgentEngine.RunControl? = null,
        val deliveredInputs: MutableList<Pair<String, String>> = mutableListOf(),
        val readInputIds: MutableSet<String> = mutableSetOf(),
    ) {
        fun stage(): WhaleSuperagentCore.TicketStage = WhaleSuperagentCore.computeStage(
            deliveryFailed = deliveryFailed,
            hasAttention = attention.isNotBlank(),
            reportState = reportState.ifBlank {
                when (state) {
                    "completed" -> "ready"
                    "failed" -> "failed"
                    else -> ""
                }
            },
            hasHumanMessageSinceReport = hasHumanSinceReport,
            assignmentState = state,
        )

        fun json(): JSONObject {
            val now = System.currentTimeMillis()
            val end = if (finishedAtMs > 0L) finishedAtMs else now
            val elapsedSec = ((end - startedAtMs) / 1000L).coerceAtLeast(0L)
            val st = stage()
            val truncated = WhaleSuperagentCore.ResourceTruncator.truncate(
                text = summary.ifBlank { livePreview },
                limit = WhaleSuperagentCore.TRIGGER_PREVIEW_CHARS,
                resourceId = "worker/$id",
            )
            return JSONObject()
                .put("id", id)
                .put("title", title)
                .put("state", state)
                .put("stage", st.code)
                .put("stageLabel", st.label)
                .put("needsHuman", st.needsHuman)
                .put("round", round)
                .put("currentStep", currentStep)
                .put("livePreview", livePreview.takeLast(320))
                .put("attention", attention)
                .put("elapsedSec", elapsedSec)
                .put("summary", truncated.optString("text"))
                .put("unreadFollowups", synchronized(deliveredInputs) { deliveredInputs.count { it.first !in readInputIds } })
                .apply {
                    if (truncated.has("read")) {
                        put("read", truncated.getString("read"))
                        put("totalChars", truncated.getInt("chars"))
                    }
                }
                .put("startedAtMs", startedAtMs)
                .put("updatedAtMs", updatedAtMs)
                .put("finishedAtMs", finishedAtMs)
        }
    }

    // 3 常规后台槽 + 1 人类交互专属抢占槽 = 4 线程
    private val executor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "whale-native-worker").apply { isDaemon = true }
    }
    private val entries = ConcurrentHashMap<String, WorkerEntry>()
    internal val slots = WhaleSuperagentCore.WorkerSlots(
        runningProvider = { activeIds() },
        maxWorkers = { 3 },
    )

    fun submit(
        title: String,
        id: String = UUID.randomUUID().toString(),
        priorityBy: String = "launch",
        block: () -> String,
    ): String {
        val now = System.currentTimeMillis()
        val entry = WorkerEntry(id = id, title = title.take(120), state = "queued", currentStep = "排队等待槽位", updatedAtMs = now)
        entries[id] = entry
        val reserved = runCatching {
            slots.reserve(id, by = priorityBy)
            true
        }.getOrDefault(false)
        entry.future = executor.submit {
            if (!reserved) {
                // 等待常规槽位释放（人类追问不受阻塞）
                while (entry.state == "queued") {
                    if (runCatching { slots.reserve(id, by = priorityBy); true }.getOrDefault(false)) break
                    try {
                        Thread.sleep(600L)
                    } catch (_: InterruptedException) {
                        entry.state = "cancelled"
                        return@submit
                    }
                }
            }
            if (entry.state == "cancelled") {
                slots.free(id)
                return@submit
            }
            entry.state = "running"
            entry.currentStep = "准备执行"
            entry.updatedAtMs = System.currentTimeMillis()
            try {
                val result = block()
                if (entry.state == "running") {
                    entry.state = "completed"
                    entry.reportState = "ready"
                    entry.currentStep = "已完成"
                    entry.summary = result.take(24_000)
                }
            } catch (error: Throwable) {
                if (entry.state != "cancelled") {
                    entry.state = "failed"
                    entry.reportState = "failed"
                    entry.currentStep = "执行失败"
                    entry.summary = (error.message ?: error.javaClass.simpleName).take(2000)
                }
            } finally {
                slots.free(id)
                entry.control = null
                val doneAt = System.currentTimeMillis()
                entry.finishedAtMs = doneAt
                entry.updatedAtMs = doneAt
            }
        }
        return id
    }

    fun attachControl(id: String, control: WhaleNativeAgentEngine.RunControl?) {
        entries[id]?.control = control
    }

    /** 追问透传（Pass-through）：直接将用户追加指令送达目标 Worker，绕过主对话重读上下文。 */
    fun deliverFollowup(id: String, message: String): Pair<Boolean, String> {
        val entry = entries[id] ?: return false to "未找到对应的后台任务（id=$id）"
        val clean = message.trim()
        if (clean.isEmpty()) return false to "追问内容不能为空"
        val inputId = "in-${UUID.randomUUID().toString().take(8)}"
        synchronized(entry.deliveredInputs) {
            entry.deliveredInputs.add(inputId to clean)
            entry.hasHumanSinceReport = true
            entry.attention = ""
            entry.deliveryFailed = false
            entry.updatedAtMs = System.currentTimeMillis()
        }
        val ctrl = entry.control
        if (entry.state == "running" && ctrl != null) {
            ctrl.steer(clean)
            return true to inputId
        }
        return true to inputId
    }

    /** 标记某 Worker 在完成一轮推理/汇报后，已真正将投递的追问读入模型上下文（inputsRead）。 */
    fun markInputsRead(id: String): List<String> {
        val entry = entries[id] ?: return emptyList()
        return synchronized(entry.deliveredInputs) {
            val newlyRead = entry.deliveredInputs.map { it.first }.filter { it !in entry.readInputIds }
            entry.readInputIds.addAll(newlyRead)
            newlyRead
        }
    }

    fun unreadFollowups(id: String): List<Pair<String, String>> {
        val entry = entries[id] ?: return emptyList()
        return synchronized(entry.deliveredInputs) {
            entry.deliveredInputs.filter { it.first !in entry.readInputIds }
        }
    }

    fun setAttention(id: String, question: String, reportState: String = "blocked") {
        val entry = entries[id] ?: return
        entry.attention = question.trim().take(600)
        entry.reportState = reportState
        entry.hasHumanSinceReport = false
        entry.updatedAtMs = System.currentTimeMillis()
    }

    fun updateProgress(id: String, round: Int? = null, step: String? = null, preview: String? = null) {
        val entry = entries[id] ?: return
        if (entry.state != "running") return
        if (round != null) entry.round = round
        if (!step.isNullOrBlank()) entry.currentStep = step.take(120)
        if (!preview.isNullOrBlank()) entry.livePreview = preview.takeLast(480)
        entry.updatedAtMs = System.currentTimeMillis()
    }

    fun get(id: String): JSONObject? = entries[id]?.json()

    fun readFullResult(id: String): String? = entries[id]?.let { it.summary.ifBlank { it.livePreview } }

    fun activeIds(): Set<String> = entries.values.filter { it.state == "running" }.mapTo(mutableSetOf()) { it.id }

    fun cancel(id: String): Boolean {
        val entry = entries[id] ?: return false
        entry.state = "cancelled"
        entry.currentStep = "已取消"
        entry.summary = "已由用户取消"
        val now = System.currentTimeMillis()
        entry.finishedAtMs = now
        entry.updatedAtMs = now
        entry.control?.cancel()
        entry.future?.cancel(true)
        slots.free(id)
        return true
    }

    fun remove(id: String): Boolean = entries.remove(id) != null

    fun clearFinished(): Int {
        val finishedIds = entries.values.filter { it.state != "running" && it.state != "queued" }.map { it.id }
        finishedIds.forEach { entries.remove(it) }
        return finishedIds.size
    }

    fun activeCount(): Int = entries.values.count { it.state == "running" || it.state == "queued" }

    fun snapshot(): JSONArray {
        val arr = JSONArray()
        entries.values.sortedByDescending { it.startedAtMs }.take(40).forEach { arr.put(it.json()) }
        return arr
    }

    fun recentSummaries(limit: Int = 4, maxAgeMs: Long = 20 * 60 * 1000L): String {
        val now = System.currentTimeMillis()
        val recent = entries.values
            .filter { e ->
                e.state == "running" || e.state == "queued" || e.attention.isNotBlank() ||
                    (now - (e.finishedAtMs.takeIf { ts -> ts > 0L } ?: e.updatedAtMs) <= maxAgeMs)
            }
            .sortedByDescending { it.finishedAtMs.takeIf { ts -> ts > 0L } ?: it.startedAtMs }
            .take(limit)
        if (recent.isEmpty()) return ""
        return buildString {
            append("\n以下是当前会话周期内后台 Worker 的最新状态与精简卡片（全文超 600 字已自动截断，需细节可调用 whale_read_resource）：\n")
            recent.forEach { e ->
                val st = e.stage()
                append("- [").append(st.code).append("/").append(st.label).append("] ").append(e.title)
                append(" (id=").append(e.id).append(", step=").append(e.currentStep).append(")")
                val body = e.summary.ifBlank { e.livePreview }
                if (body.isNotBlank()) {
                    val trunc = WhaleSuperagentCore.ResourceTruncator.truncate(body.replace("\n", " "), 360, "worker/${e.id}")
                    append("：").append(trunc.optString("text"))
                    if (trunc.has("read")) append(" [read=").append(trunc.getString("read")).append("]")
                }
                append("\n")
            }
        }
    }
}
