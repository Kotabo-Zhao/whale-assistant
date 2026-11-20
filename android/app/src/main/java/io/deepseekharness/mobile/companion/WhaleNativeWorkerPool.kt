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
 * 用于在后台并发执行长耗时编码/资料整理/手机任务，支持：
 * - 与看板共享统一 ID（不重复计数）；
 * - 实时记录中间过程（轮次、当前正在执行的工具/阶段、流式输出尾部预览、耗时），彻底告别黑箱等待；
 * - 即时查询状态、读取完整回传摘要以及用户抢占取消。
 */
internal object WhaleNativeWorkerPool {
    data class WorkerEntry(
        val id: String,
        val title: String,
        @Volatile var state: String,
        @Volatile var summary: String = "",
        @Volatile var currentStep: String = "准备执行",
        @Volatile var livePreview: String = "",
        @Volatile var round: Int = 0,
        val startedAtMs: Long = System.currentTimeMillis(),
        @Volatile var updatedAtMs: Long = System.currentTimeMillis(),
        @Volatile var finishedAtMs: Long = 0L,
        @Volatile var future: Future<*>? = null,
    ) {
        fun json(): JSONObject {
            val now = System.currentTimeMillis()
            val end = if (finishedAtMs > 0L) finishedAtMs else now
            val elapsedSec = ((end - startedAtMs) / 1000L).coerceAtLeast(0L)
            return JSONObject()
                .put("id", id)
                .put("title", title)
                .put("state", state)
                .put("round", round)
                .put("currentStep", currentStep)
                .put("livePreview", livePreview)
                .put("elapsedSec", elapsedSec)
                .put("summary", summary)
                .put("startedAtMs", startedAtMs)
                .put("updatedAtMs", updatedAtMs)
                .put("finishedAtMs", finishedAtMs)
        }
    }

    private val executor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "whale-native-worker").apply { isDaemon = true }
    }
    private val entries = ConcurrentHashMap<String, WorkerEntry>()

    fun submit(title: String, id: String = UUID.randomUUID().toString(), block: () -> String): String {
        val now = System.currentTimeMillis()
        val entry = WorkerEntry(id = id, title = title.take(120), state = "running", updatedAtMs = now)
        entries[id] = entry
        entry.future = executor.submit {
            try {
                val result = block()
                if (entry.state == "running") {
                    entry.state = "completed"
                    entry.currentStep = "已完成"
                    entry.summary = result.take(4000)
                }
            } catch (error: Throwable) {
                if (entry.state != "cancelled") {
                    entry.state = "failed"
                    entry.currentStep = "执行失败"
                    entry.summary = (error.message ?: error.javaClass.simpleName).take(800)
                }
            } finally {
                val doneAt = System.currentTimeMillis()
                entry.finishedAtMs = doneAt
                entry.updatedAtMs = doneAt
            }
        }
        return id
    }

    fun updateProgress(id: String, round: Int? = null, step: String? = null, preview: String? = null) {
        val entry = entries[id] ?: return
        if (entry.state != "running") return
        if (round != null) entry.round = round
        if (!step.isNullOrBlank()) entry.currentStep = step.take(120)
        if (!preview.isNullOrBlank()) entry.livePreview = preview.takeLast(320)
        entry.updatedAtMs = System.currentTimeMillis()
    }

    fun get(id: String): JSONObject? = entries[id]?.json()

    fun activeIds(): Set<String> = entries.values.filter { it.state == "running" }.mapTo(mutableSetOf()) { it.id }

    fun cancel(id: String): Boolean {
        val entry = entries[id] ?: return false
        entry.state = "cancelled"
        entry.currentStep = "已取消"
        entry.summary = "已由用户取消"
        val now = System.currentTimeMillis()
        entry.finishedAtMs = now
        entry.updatedAtMs = now
        entry.future?.cancel(true)
        return true
    }

    fun remove(id: String): Boolean = entries.remove(id) != null

    fun clearFinished(): Int {
        val finishedIds = entries.values.filter { it.state != "running" }.map { it.id }
        finishedIds.forEach { entries.remove(it) }
        return finishedIds.size
    }

    fun activeCount(): Int = entries.values.count { it.state == "running" }

    fun snapshot(): JSONArray {
        val arr = JSONArray()
        entries.values.sortedByDescending { it.startedAtMs }.take(40).forEach { arr.put(it.json()) }
        return arr
    }

    fun recentSummaries(limit: Int = 4): String {
        val recent = entries.values.sortedByDescending { it.finishedAtMs.takeIf { ts -> ts > 0L } ?: it.startedAtMs }.take(limit)
        if (recent.isEmpty()) return ""
        return buildString {
            append("\n以下是当前会话周期内后台 Worker 的最新状态与回传摘要（可直接向用户汇报，无需重复派发）：\n")
            recent.forEach { e ->
                append("- [").append(e.state).append("] ").append(e.title)
                append(" (id=").append(e.id).append(", step=").append(e.currentStep).append(")")
                val body = e.summary.ifBlank { e.livePreview }
                if (body.isNotBlank()) {
                    append("：").append(body.replace("\n", " ").take(420))
                }
                append("\n")
            }
        }
    }
}
