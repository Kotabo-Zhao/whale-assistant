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
 * 用于在后台并发执行长耗时编码/资料整理/手机任务，支持即时查询状态与用户抢占取消。
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

    private val executor = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "whale-native-worker").apply { isDaemon = true }
    }
    private val entries = ConcurrentHashMap<String, WorkerEntry>()

    fun submit(title: String, block: () -> String): String {
        val id = UUID.randomUUID().toString()
        val entry = WorkerEntry(id = id, title = title.take(120), state = "running")
        entries[id] = entry
        entry.future = executor.submit {
            try {
                val result = block()
                if (entry.state == "running") {
                    entry.state = "completed"
                    entry.summary = result.take(2000)
                }
            } catch (error: Throwable) {
                if (entry.state != "cancelled") {
                    entry.state = "failed"
                    entry.summary = (error.message ?: error.javaClass.simpleName).take(500)
                }
            } finally {
                entry.finishedAtMs = System.currentTimeMillis()
            }
        }
        return id
    }

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
        entries.values.sortedByDescending { it.startedAtMs }.take(20).forEach { arr.put(it.json()) }
        return arr
    }
}
