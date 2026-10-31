package io.deepseekharness.mobile.companion

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.deepseekharness.mobile.runtime.RuntimeStore
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Rebuilds the derived index incrementally, with bounded work and a persisted cursor. */
internal object SemanticIndexJobs {
    private const val JOB_ID = 780433
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    fun schedule(context: Context, delayMs: Long = 0L) {
        val app = context.applicationContext
        if (!SemanticControl(app).enabled) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(app, SemanticIndexJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setMinimumLatency(delayMs.coerceAtLeast(0))
            .setPersisted(true).build()
        check(app.getSystemService(JobScheduler::class.java)?.schedule(job) == JobScheduler.RESULT_SUCCESS)
    }

    fun cancel(context: Context) { context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }

    private fun split(text: String): List<Pair<Int, String>> {
        val result = mutableListOf<Pair<Int, String>>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + 1500, text.length)
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
        if (!control.enabled || !CompanionStore(context).knowledgeModelAccess) return null
        val generation = control.generation()
        val key = RuntimeStore(context).readModelCredential("openai")
            ?: throw UnderstandingRequestException("credentials", "尚未配置 OpenAI 凭据")
        val model = SemanticEmbeddings(key)
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
                if (stopped.get() || !control.enabled || !CompanionStore(context).knowledgeModelAccess || generation != control.generation() || sent >= 10 || scanned >= 2048) break
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
                        if (!control.reserve("index")) { control.setStatus("今日索引请求额度已用完"); return nextDayDelay() }
                        val chunks = split(text)
                        val vectors = model.embed(chunks.map { it.second })
                        if (stopped.get()) return null
                        var published = false
                        val publish = {
                            published = control.publish(generation) {
                                check(CompanionStore(context).knowledgeModelAccess) { "资料授权已关闭" }
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
        if (!running.compareAndSet(false, true)) return false
        executor.execute {
            var delay: Long? = null
            try { delay = runBatch(service.applicationContext, stopped) }
            catch (e: Exception) {
                SemanticControl(service).setStatus(when (e) {
                    is UnderstandingRequestException -> if (e.code == "credentials") "请配置 OpenAI 密钥" else "向量服务暂不可用，将稍后重试"
                    is java.io.IOException -> "网络不可用，将稍后重试"
                    else -> "索引处理失败，将稍后重试"
                })
                delay = 30 * 60_000L
            } finally {
                running.set(false)
                if (!stopped.get()) {
                    service.jobFinished(params, false)
                    if (delay != null) runCatching { schedule(service, delay) }
                }
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
    override fun onStopJob(params: JobParameters): Boolean { stopped.set(true); return true }
}
