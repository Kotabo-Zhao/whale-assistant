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
 * 用于在后台并发执行长耗时编码/资料整理/手机任务，支持与看板共享统一 ID、
 * 即时查询状态、读取完整回传摘要以及用户抢占取消。
 */
internal object WhaleNativeWorkerPool {
    data class WorkerEntry(
        val id: String,
        val title: String,
        @Volatile var state: String,
        @Volatile var summary: String = "",
        val startedAtMs: Long = System.currentTimeMillis(),
        @Volatile var finishedAtMs: Long = 0L,
        @Volatile var future: Future<*>? = null,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("state", state)
            .put("summary", summary)
            .put("startedAtMs", startedAtMs)
            .put("finishedAtMs", finishedAtMs)
    }

    private val executor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "whale-native-worker").apply { isDaemon = true }
    }
    private val entries = ConcurrentHashMap<String, WorkerEntry>()

    fun submit(title: String, id: String = UUID.randomUUID().toString(), block: () -> String): String {
        val entry = WorkerEntry(id = id, title = title.take(120), state = "running")
        entries[id] = entry
        entry.future = executor.submit {
            try {
                val result = block()
                if (entry.state == "running") {
                    entry.state = "completed"
                    entry.summary = result.take(4000)
                }
            } catch (error: Throwable) {
                if (entry.state != "cancelled") {
                    entry.state = "failed"
                    entry.summary = (error.message ?: error.javaClass.simpleName).take(800)
                }
            } finally {
                entry.finishedAtMs = System.currentTimeMillis()
            }
        }
        return id
    }

    fun get(id: String): JSONObject? = entries[id]?.json()

    fun cancel(id: String): Boolean {
        val entry = entries[id] ?: return false
        entry.state = "cancelled"
        entry.summary = "已由用户取消"
        entry.finishedAtMs = System.currentTimeMillis()
        entry.future?.cancel(true)
        return true
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
                append(" (id=").append(e.id).append(")")
                if (e.summary.isNotBlank()) {
                    append("：").append(e.summary.replace("\n", " ").take(420))
                }
                append("\n")
            }
        }
    }
}
