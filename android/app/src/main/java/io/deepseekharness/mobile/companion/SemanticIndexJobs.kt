package io.deepseekharness.mobile.companion

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Rebuilds the derived index incrementally, with bounded work and a persisted cursor. */
internal object SemanticIndexJobs {
    private const val JOB_ID = 780433
    private val wakeup = MemoryJobWakeup(JOB_ID, SemanticIndexJobService::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    fun schedule(context: Context, delayMs: Long = 0L) {
        val app = context.applicationContext
        if (!SemanticControl(app).enabled) return
        wakeup.request(app, delayMs)
    }

    fun cancel(context: Context) = wakeup.cancel(context)
    fun stop(params: JobParameters) = wakeup.stop(params)

    private fun split(text: String): List<Pair<Int, String>> {
        val result = mutableListOf<Pair<Int, String>>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + 480, text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            val part = text.substring(start, end)
            if (part.isNotBlank()) result.add(start to part)
            start = end
        }
        return result
    }

    private fun nextDayDelay(): Long {
        val tomorrow = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 5); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return (tomorrow.timeInMillis - System.currentTimeMillis()).coerceAtLeast(60_000L)
    }

    /** Returns delay until next batch, or null when caught up or disabled. */
    private fun runBatch(context: Context, stopped: AtomicBoolean): Long? {
        val control = SemanticControl(context)
        if (!control.enabled) return null
        val generation = control.generation()
        val model = SemanticEmbeddings(context)
        val knowledge = CompanionKnowledgeStore(context)
        val memories = CompanionMemoryStore(context)
        var position = control.cursor()
        var phase = control.phase()
        var scanned = 0
        var sent = 0
        while (!stopped.get() && control.enabled && generation == control.generation() && scanned < 2048 && sent < 10) {
            val page = if (phase == "memory") memories.semanticPage(position, 128) else knowledge.pageAfter(position, 128)
            if (page.isEmpty()) {
                if (phase == "knowledge" && control.memoryEnabled && memories.enabled) {
                    if (!control.advance(generation, -1, false, "memory")) return if (control.enabled) 5_000L else null
                    position = -1; phase = "memory"
                    continue
                }
                return if (control.advance(generation, position, true)) {
                    control.setStatus("索引已追平已授权的资料和记忆"); null
                } else if (control.enabled) 5_000L else null
            }
            for ((nextPosition, row) in page) {
                if (stopped.get() || !control.enabled || generation != control.generation() || sent >= 10 || scanned >= 2048) break
                scanned++
                val id = row.getString("id")
                val text = row.getString("text")
                val permitted = if (phase == "memory") control.memoryEnabled && memories.enabled && row.optString("status") != "pending"
                    else row.optString("source") != "original"
                if (permitted && text.isNotBlank()) {
                    val revision = CompanionMemorySuppression.digest(text)
                    val indexId = if (phase == "memory") "memory:$id" else id
                    val fresh = SemanticIndex(context).use { it.isCurrent(indexId, revision) }
                    if (!fresh) {
                        if (phase == "memory" && (!control.memoryEnabled || !memories.enabled)) return if (control.enabled) 5_000L else null
                        val chunks = split(text)
                        // A full 8,000-character record can yield 17 segments, while each
                        // inference call accepts at most 16. Preserve order across batches.
                        val vectors = chunks.chunked(16).flatMap { batch -> model.embed(batch.map { it.second }) }
                        if (stopped.get()) return null
                        var published = false
                        val publish = {
                            published = control.publish(generation) {
                                if (phase == "memory") check(control.memoryEnabled && memories.enabled) { "记忆授权已关闭" }
                                SemanticIndex(context).use { index -> index.replace(indexId, revision, chunks.mapIndexed { i, chunk -> chunk.first to vectors[i] }) }
                            }
                        }
                        if (phase == "memory") memories.withUnchangedSemanticRecord(id, text, publish)
                        else knowledge.withUnchangedRecord(id, text, publish)
                        if (!published) return if (control.enabled) 5_000L else null
                        sent++
                    }
                }
                position = nextPosition
                if (!control.advance(generation, position, false)) return if (control.enabled) 5_000L else null
            }
        }
        if (stopped.get() || !control.enabled) return null
        control.setStatus("正在分批建立语义索引")
        return 5_000L
    }

    fun start(service: JobService, params: JobParameters, stopped: AtomicBoolean): Boolean {
        wakeup.start(params)
        executor.execute {
            var delay: Long? = null
            try { delay = runBatch(service.applicationContext, stopped) }
            catch (e: Exception) {
                android.util.Log.w("dsh-semantic", "local semantic indexing failed", e)
                // 异常类型足够用于定位本机运行时问题，不写入资料内容、查询或异常 message。
                SemanticControl(service).setStatus("本地索引失败（${e.javaClass.simpleName}），将稍后重试")
                delay = 30 * 60_000L
            } finally {
                if (!stopped.get()) wakeup.finish(service, params, delay)
            }
        }
        return true
    }
}

class SemanticIndexJobService : JobService() {
    private var stopped = AtomicBoolean(false)
    override fun onStartJob(params: JobParameters): Boolean {
        stopped = AtomicBoolean(false)
        return SemanticIndexJobs.start(this, params, stopped)
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped.set(true); SemanticIndexJobs.stop(params); return true }
}
