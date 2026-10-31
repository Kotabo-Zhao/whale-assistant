package io.deepseekharness.mobile.companion

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.deepseekharness.mobile.runtime.RuntimeStore
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Durable references only; source text stays in the existing knowledge store. */
internal object SharedMemoryJobs {
    private const val JOB_ID = 780432
    private val lock = Any()
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private fun database(context: Context): SQLiteDatabase {
        val db = SQLiteDatabase.openOrCreateDatabase(File(context.applicationContext.noBackupFilesDir, "shared-memory-jobs-v1.db"), null)
        db.execSQL("CREATE TABLE IF NOT EXISTS jobs (record_id TEXT NOT NULL, text_hash TEXT NOT NULL, event_id TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(record_id,text_hash,event_id))")
        val columns = db.rawQuery("PRAGMA table_info(jobs)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
        if ("attempts" !in columns) db.execSQL("ALTER TABLE jobs ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
        if ("next_at" !in columns) db.execSQL("ALTER TABLE jobs ADD COLUMN next_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS completed (record_id TEXT NOT NULL, text_hash TEXT NOT NULL, PRIMARY KEY(record_id,text_hash))")
        return db
    }
    private fun stableEvent(text: String) = "share:" + CompanionMemorySuppression.digest(text.substringAfter("\n\n文档提取内容：\n", text))
    private fun enqueueRow(db: SQLiteDatabase, id: String, text: String, event: String) {
        require(id.matches(Regex("[a-f0-9-]{36}")) && event.matches(Regex("share:[a-f0-9]{64}")))
        db.execSQL("INSERT OR IGNORE INTO jobs(record_id,text_hash,event_id) VALUES (?,?,?)", arrayOf(id, CompanionMemorySuppression.digest(text), event))
    }
    /** Also catches an interrupted share between saving the record and enqueueing it. */
    fun bootstrap(context: Context) {
        val app = context.applicationContext
        executor.execute {
            runCatching {
                val rows = CompanionKnowledgeStore(app).search("")
                synchronized(lock) { database(app).use { db ->
                    db.beginTransaction()
                    try {
                        for (i in 0 until rows.length()) {
                            val row = rows.getJSONObject(i); val text = row.getString("text"); val id = row.getString("id")
                            val hash = CompanionMemorySuppression.digest(text)
                            val done = db.rawQuery("SELECT 1 FROM completed WHERE record_id=? AND text_hash=?", arrayOf(id, hash)).use { it.moveToFirst() }
                            val pending = db.rawQuery("SELECT 1 FROM jobs WHERE record_id=? AND text_hash=?", arrayOf(id, hash)).use { it.moveToFirst() }
                            if (!done && !pending) enqueueRow(db, id, text, stableEvent(text))
                        }
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                } }
                schedule(app, 0)
            }
        }
    }
    fun enqueue(context: Context, text: String, event: String = stableEvent(text)): Boolean {
        val app = context.applicationContext
        val rows = CompanionKnowledgeStore(app).search("")
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("text") == text } ?: return false
        enqueueWithoutSchedule(app, row.getString("id"), text, event)
        // Delayed so an in-flight foreground import can complete and cancel its job first.
        schedule(app, 60_000)
        return true
    }
    internal fun enqueueWithoutSchedule(context: Context, id: String, text: String, event: String) = synchronized(lock) {
        database(context).use { enqueueRow(it, id, text, event) }
    }
    internal fun resetDelays(context: Context) = synchronized(lock) { database(context).use { it.execSQL("UPDATE jobs SET next_at=0") } }
    fun retryNow(context: Context) { resetDelays(context); schedule(context, 0) }
    fun complete(context: Context, text: String, event: String = stableEvent(text)) {
        val hash = CompanionMemorySuppression.digest(text)
        synchronized(lock) { database(context).use { db ->
            db.beginTransaction()
            try {
                db.rawQuery("SELECT record_id FROM jobs WHERE text_hash=? AND event_id=?", arrayOf(hash, event)).use { c ->
                    val ids = mutableListOf<String>()
                    while (c.moveToNext()) ids.add(c.getString(0))
                    ids.forEach { id ->
                        db.execSQL("INSERT OR IGNORE INTO completed(record_id,text_hash) VALUES (?,?)", arrayOf(id, hash))
                        db.delete("jobs", "record_id=? AND text_hash=?", arrayOf(id, hash))
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } }
    }
    fun completeVersion(context: Context, id: String, text: String) = synchronized(lock) { database(context).use { db ->
        val hash = CompanionMemorySuppression.digest(text)
        db.execSQL("INSERT OR IGNORE INTO completed(record_id,text_hash) VALUES (?,?)", arrayOf(id, hash))
        db.delete("jobs", "record_id=? AND text_hash=?", arrayOf(id, hash))
    } }
    internal data class Job(val id: String, val hash: String, val event: String)
    private fun next(context: Context): Job? = synchronized(lock) { database(context).use { db ->
        db.rawQuery("SELECT record_id,text_hash,event_id FROM jobs WHERE next_at<=? ORDER BY next_at,rowid LIMIT 1", arrayOf(System.currentTimeMillis().toString())).use { if (it.moveToFirst()) Job(it.getString(0), it.getString(1), it.getString(2)) else null }
    } }
    private fun delayUntilNext(context: Context): Long? = synchronized(lock) { database(context).use { db ->
        db.rawQuery("SELECT MIN(next_at) FROM jobs", null).use { c -> if (!c.moveToFirst() || c.isNull(0)) null else (c.getLong(0) - System.currentTimeMillis()).coerceAtLeast(0) }
    } }
    private fun defer(context: Context, job: Job) = synchronized(lock) { database(context).use { db ->
        db.execSQL("UPDATE jobs SET attempts=attempts+1,next_at=? WHERE record_id=? AND text_hash=? AND event_id=?",
            arrayOf(System.currentTimeMillis() + 30 * 60_000L, job.id, job.hash, job.event))
    } }
    private fun remove(context: Context, job: Job) = synchronized(lock) { database(context).use {
        it.execSQL("INSERT OR IGNORE INTO completed(record_id,text_hash) VALUES (?,?)", arrayOf(job.id, job.hash))
        it.delete("jobs", "record_id=? AND text_hash=? AND event_id=?", arrayOf(job.id, job.hash, job.event))
    } }
    fun schedule(context: Context, delayMs: Long) {
        val app = context.applicationContext
        if (running.get()) return
        val due = delayUntilNext(app) ?: return
        val info = JobInfo.Builder(JOB_ID, ComponentName(app, SharedMemoryJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(maxOf(delayMs.coerceAtLeast(0), due)).setPersisted(true).build()
        check(app.getSystemService(JobScheduler::class.java)?.schedule(info) == JobScheduler.RESULT_SUCCESS) { "scheduler unavailable" }
    }
    fun runOne(context: Context, stopped: AtomicBoolean, modelOverride: UnderstandingModel? = null): Boolean {
        val app = context.applicationContext
        val job = next(app) ?: return false
        val knowledge = CompanionKnowledgeStore(app)
        val row = knowledge.findById(job.id)
        val text = row?.optString("text").orEmpty()
        if (row == null || CompanionMemorySuppression.digest(text) != job.hash) { remove(app, job); return next(app) != null }
        try {
            check(CompanionMemoryStore(app).enabled) { "memory disabled" }
            val model = modelOverride ?: DeepSeekUnderstandingModel(RuntimeStore(app).readModelCredential("deepseek") ?: error("model unavailable"), "deepseek-flash")
            val requestId = UUID.randomUUID().toString()
            val raw = model.analyze(requestId, text)
            UnderstandingModelContract.validate(raw, requestId)
            val result = JSONObject(raw)
            if (stopped.get()) return true
            knowledge.withUnchangedRecord(job.id, text) {
                if (!stopped.get()) CompanionMemoryStore(app).saveSharedFacts(job.event, result.getJSONArray("claims"))
            }
        } catch (e: Exception) {
            if (!stopped.get()) defer(app, job)
            throw e
        }
        if (stopped.get()) return true
        remove(app, job)
        return next(app) != null
    }
    fun begin() = running.compareAndSet(false, true)
    fun end() { running.set(false) }
    fun pendingCount(context: Context): Long = synchronized(lock) { database(context).use { db ->
        db.rawQuery("SELECT COUNT(*) FROM jobs", null).use { it.moveToFirst(); it.getLong(0) }
    } }
    fun status(context: Context, value: String) {
        context.getSharedPreferences("shared_memory_status_v1", Context.MODE_PRIVATE).edit().putString("status", value).apply()
    }
    fun readStatus(context: Context): String = context.getSharedPreferences("shared_memory_status_v1", Context.MODE_PRIVATE)
        .getString("status", "等待处理").orEmpty()
}

class SharedMemoryJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var stopped = AtomicBoolean(false)
    override fun onStartJob(params: JobParameters): Boolean {
        val cancelled = AtomicBoolean(false); stopped = cancelled
        executor.execute {
            if (!SharedMemoryJobs.begin()) { if (!cancelled.get()) jobFinished(params, true); return@execute }
            SharedMemoryJobs.status(applicationContext, "正在整理资料")
            val result = runCatching { SharedMemoryJobs.runOne(applicationContext, cancelled) }
            SharedMemoryJobs.end()
            if (!cancelled.get()) {
                jobFinished(params, false)
                if (result.isFailure) {
                    SharedMemoryJobs.status(applicationContext, "处理失败，等待网络、模型或记忆设置恢复后重试")
                    SharedMemoryJobs.schedule(applicationContext, 0)
                } else {
                    val pending = SharedMemoryJobs.pendingCount(applicationContext)
                    SharedMemoryJobs.status(applicationContext, when {
                        pending == 0L -> "已处理完待整理资料"
                        result.getOrDefault(false) -> "已处理一条，等待继续"
                        else -> "有 ${pending} 条资料等待重试"
                    })
                    SharedMemoryJobs.schedule(applicationContext, 2_000)
                }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped.set(true); return true }
    override fun onDestroy() { stopped.set(true); executor.shutdown(); super.onDestroy() }
}
