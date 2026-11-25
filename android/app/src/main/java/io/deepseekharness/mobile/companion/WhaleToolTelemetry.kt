package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地工具调用耗时与成功率遥测（零网络开销、线程安全）。
 *
 * 记录每个工具的调用次数、成功率、平均耗时与最近失败原因，
 * 供 `whale_self_capabilities` 自检诊断高频超时或易错工具/MCP。
 */
internal object WhaleToolTelemetry {

    data class ToolMetric(
        val tool: String,
        val calls: Int = 0,
        val successes: Int = 0,
        val failures: Int = 0,
        val totalElapsedMs: Long = 0L,
        val lastError: String = "",
        val lastCalledAtMs: Long = 0L,
    ) {
        val avgElapsedMs: Long
            get() = if (calls <= 0) 0L else totalElapsedMs / calls

        val successRatePct: Int
            get() = if (calls <= 0) 100 else ((successes * 100.0) / calls).toInt().coerceIn(0, 100)

        fun json(): JSONObject = JSONObject()
            .put("tool", tool)
            .put("calls", calls)
            .put("successes", successes)
            .put("failures", failures)
            .put("successRatePct", successRatePct)
            .put("avgElapsedMs", avgElapsedMs)
            .apply { if (lastError.isNotBlank()) put("lastError", lastError) }
    }

    private val metrics = ConcurrentHashMap<String, ToolMetric>()

    fun record(tool: String, ok: Boolean, elapsedMs: Long, errorHint: String = "") {
        val key = tool.trim()
        if (key.isEmpty()) return
        val safeElapsed = elapsedMs.coerceAtLeast(0L)
        val cleanErr = if (ok) "" else errorHint.trim().take(120)
        metrics.compute(key) { _, prev ->
            val base = prev ?: ToolMetric(tool = key)
            base.copy(
                calls = base.calls + 1,
                successes = base.successes + if (ok) 1 else 0,
                failures = base.failures + if (ok) 0 else 1,
                totalElapsedMs = base.totalElapsedMs + safeElapsed,
                lastError = if (ok) base.lastError else cleanErr.ifBlank { base.lastError },
                lastCalledAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun snapshot(limit: Int = 12): JSONObject {
        val all = metrics.values.toList()
        val totalCalls = all.sumOf { it.calls }
        val totalFails = all.sumOf { it.failures }
        val flaky = all
            .filter { it.failures > 0 }
            .sortedWith(compareByDescending<ToolMetric> { it.failures }.thenBy { it.successRatePct })
            .take(limit)
        val busiest = all
            .sortedByDescending { it.calls }
            .take(limit)
        return JSONObject()
            .put("totalCalls", totalCalls)
            .put("totalFailures", totalFails)
            .put("flakyTools", JSONArray().apply { flaky.forEach { put(it.json()) } })
            .put("topTools", JSONArray().apply { busiest.forEach { put(it.json()) } })
    }

    fun clear() {
        metrics.clear()
    }
}
