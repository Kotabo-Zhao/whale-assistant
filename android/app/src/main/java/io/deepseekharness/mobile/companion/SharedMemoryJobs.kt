package io.deepseekharness.mobile.companion

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
        val db = SQLiteDatabase.openOrCreateDatabase(UnifiedUnderstandingStore.path(context), null)
        db.execSQL("CREATE TABLE IF NOT EXISTS shared_jobs (record_id TEXT NOT NULL, text_hash TEXT NOT NULL, event_id TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(record_id,text_hash,event_id))")
        val columns = db.rawQuery("PRAGMA table_info(shared_jobs)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
        if ("attempts" !in columns) db.execSQL("ALTER TABLE shared_jobs ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
        if ("next_at" !in columns) db.execSQL("ALTER TABLE shared_jobs ADD COLUMN next_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS shared_completed (record_id TEXT NOT NULL, text_hash TEXT NOT NULL, PRIMARY KEY(record_id,text_hash))")
        db.execSQL("CREATE TABLE IF NOT EXISTS shared_jobs_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        val migrated = db.rawQuery("SELECT value FROM shared_jobs_meta WHERE key='legacy_imported'", null).use { it.moveToFirst() }
        if (!migrated) {
            val old = File(context.applicationContext.noBackupFilesDir, "shared-memory-jobs-v1.db")
            if (old.isFile) db.execSQL("ATTACH DATABASE ? AS legacy_shared", arrayOf(old.absolutePath))
            try {
                db.beginTransaction()
                try {
                    if (old.isFile) {
                        val names = db.rawQuery("PRAGMA legacy_shared.table_info(jobs)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
                        val attempts = if ("attempts" in names) "attempts" else "0"
                        val next = if ("next_at" in names) "next_at" else "0"
                        db.execSQL("INSERT OR IGNORE INTO shared_jobs SELECT record_id,text_hash,event_id,$attempts,$next FROM legacy_shared.jobs")
                        val hasCompleted = db.rawQuery("SELECT name FROM legacy_shared.sqlite_master WHERE name='completed'", null).use { it.moveToFirst() }
                        if (hasCompleted) db.execSQL("INSERT OR IGNORE INTO shared_completed SELECT record_id,text_hash FROM legacy_shared.completed")
                    }
                    db.execSQL("INSERT INTO shared_jobs_meta VALUES ('legacy_imported','1')")
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            } catch (e: Exception) { db.close(); throw e }
            finally { if (old.isFile && db.isOpen) db.execSQL("DETACH DATABASE legacy_shared") }
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS knowledge_source_events (record_id TEXT NOT NULL, event_id TEXT NOT NULL, PRIMARY KEY(record_id,event_id))")
        db.execSQL("INSERT OR IGNORE INTO knowledge_source_events SELECT record_id,event_id FROM shared_jobs")
        return db
    }
    private fun stableEvent(text: String) = "share:" + CompanionMemorySuppression.digest(text.substringAfter("\n\n文档提取内容：\n", text))
    private fun enqueueRow(db: SQLiteDatabase, id: String, text: String, event: String) {
        require(id.matches(Regex("[a-f0-9-]{36}")) && event.matches(Regex("share:[a-f0-9]{64}")))
        db.execSQL("INSERT OR IGNORE INTO knowledge_source_events(record_id,event_id) VALUES (?,?)", arrayOf(id,event))
        db.execSQL("INSERT OR IGNORE INTO shared_jobs(record_id,text_hash,event_id) VALUES (?,?,?)", arrayOf(id, CompanionMemorySuppression.digest(text), event))
    }
    /** Also catches an interrupted share between saving the record and enqueueing it. */
    fun bootstrap(context: Context) {
        val app = context.applicationContext
        executor.execute {
            runCatching {
                // Ensure legacy migration is complete before scanning the shared database.
                CompanionKnowledgeStore(app).recent(0, 1)
                var lastPosition = -1L
                while (true) {
                    val page = synchronized(lock) { database(app).use { db ->
                        val batch = mutableListOf<Triple<Long, String, String>>()
                        db.rawQuery("SELECT position,id,body FROM knowledge_records WHERE position>? ORDER BY position LIMIT 128", arrayOf(lastPosition.toString())).use { cursor ->
                            while (cursor.moveToNext()) {
                                val row = JSONObject(cursor.getString(2))
                                if (row.optString("source") != "original") batch.add(Triple(cursor.getLong(0), cursor.getString(1), row.getString("text")))
                                else batch.add(Triple(cursor.getLong(0), cursor.getString(1), ""))
                            }
                        }
                        db.beginTransaction()
                        try {
                            for ((_, id, text) in batch) {
                                if (text.isBlank()) continue
                                val hash = CompanionMemorySuppression.digest(text)
                                val done = db.rawQuery("SELECT 1 FROM shared_completed WHERE record_id=? AND text_hash=?", arrayOf(id, hash)).use { it.moveToFirst() }
                                val pending = db.rawQuery("SELECT 1 FROM shared_jobs WHERE record_id=? AND text_hash=?", arrayOf(id, hash)).use { it.moveToFirst() }
                                if (!done && !pending) enqueueRow(db, id, text, stableEvent(text))
                            }
                            db.setTransactionSuccessful()
                        } finally { db.endTransaction() }
                        batch
                    } }
                    if (page.isEmpty()) break
                    lastPosition = page.last().first
                    if (page.size < 128) break
                }
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
    internal fun resetDelays(context: Context) = synchronized(lock) { database(context).use { it.execSQL("UPDATE shared_jobs SET next_at=0") } }
    fun retryNow(context: Context) { resetDelays(context); schedule(context, 0) }
    fun complete(context: Context, text: String, event: String = stableEvent(text)) {
        val hash = CompanionMemorySuppression.digest(text)
        synchronized(lock) { database(context).use { db ->
            db.beginTransaction()
            try {
                db.rawQuery("SELECT record_id FROM shared_jobs WHERE text_hash=? AND event_id=?", arrayOf(hash, event)).use { c ->
                    val ids = mutableListOf<String>()
                    while (c.moveToNext()) ids.add(c.getString(0))
                    ids.forEach { id ->
                        db.execSQL("INSERT OR IGNORE INTO shared_completed(record_id,text_hash) VALUES (?,?)", arrayOf(id, hash))
                        db.delete("shared_jobs", "record_id=? AND text_hash=?", arrayOf(id, hash))
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } }
    }
    fun completeVersion(context: Context, id: String, text: String) = synchronized(lock) { database(context).use { db ->
        val hash = CompanionMemorySuppression.digest(text)
        db.execSQL("INSERT OR IGNORE INTO shared_completed(record_id,text_hash) VALUES (?,?)", arrayOf(id, hash))
        db.delete("shared_jobs", "record_id=? AND text_hash=?", arrayOf(id, hash))
    } }
    /** Reports persisted queue state only; it cannot determine whether a worker is currently running. */
    internal fun status(context: Context, id: String, textHash: String): JSONObject = synchronized(lock) {
        database(context.applicationContext).use { db ->
            db.rawQuery("SELECT MAX(attempts), MIN(next_at) FROM shared_jobs WHERE record_id=? AND text_hash=?", arrayOf(id, textHash)).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    val failure = context.getSharedPreferences("shared_job_failures_v1", Context.MODE_PRIVATE)
                        .getString("$id:$textHash", null)
                    return@synchronized JSONObject().put("state", "queued").put("attempts", cursor.getInt(0))
                        .put("nextAttemptAtMs", cursor.getLong(1)).put("retryable", true)
                        .put("lastErrorCode", failure ?: JSONObject.NULL)
                }
            }
            val completed = db.rawQuery("SELECT 1 FROM shared_completed WHERE record_id=? AND text_hash=?", arrayOf(id, textHash)).use { it.moveToFirst() }
            JSONObject().put("state", if (completed) "completed" else "not_queued")
                .put("retryable", false)
        }
    }
    internal fun retryRecord(context: Context, id: String, textHash: String): Boolean = synchronized(lock) {
        database(context.applicationContext).use { db ->
            db.compileStatement("UPDATE shared_jobs SET next_at=0 WHERE record_id=? AND text_hash=?").use { update ->
                update.bindString(1, id); update.bindString(2, textHash)
                update.executeUpdateDelete() > 0
            }
        }
    }
    internal data class Job(val id: String, val hash: String, val event: String, val generation: Long = 0)
    private fun next(context: Context): Job? = synchronized(lock) { database(context).use { db ->
        db.rawQuery("SELECT record_id,text_hash,event_id FROM shared_jobs WHERE next_at<=? ORDER BY next_at,rowid LIMIT 1", arrayOf(System.currentTimeMillis().toString())).use { if (it.moveToFirst()) Job(it.getString(0), it.getString(1), it.getString(2), db.rawQuery("SELECT value FROM shared_jobs_meta WHERE key='generation'", null).use { g -> if (g.moveToFirst()) g.getLong(0) else 0L }) else null }
    } }
    private fun delayUntilNext(context: Context): Long? = synchronized(lock) { database(context).use { db ->
        db.rawQuery("SELECT MIN(next_at) FROM shared_jobs", null).use { c -> if (!c.moveToFirst() || c.isNull(0)) null else (c.getLong(0) - System.currentTimeMillis()).coerceAtLeast(0) }
    } }
    private fun failureKey(job: Job) = "${job.id}:${job.hash}"
    private fun failureCode(error: Exception): String = when (error) {
        is UnderstandingRequestException -> error.code
        is java.net.SocketTimeoutException -> "timeout"
        is java.io.IOException -> "network"
        is org.json.JSONException, is IllegalArgumentException -> "invalid_model_output"
        else -> "processing_failed"
    }
    private fun clearFailure(context: Context, job: Job) {
        context.getSharedPreferences("shared_job_failures_v1", Context.MODE_PRIVATE).edit().remove(failureKey(job)).apply()
    }
    private fun defer(context: Context, job: Job, error: Exception) = synchronized(lock) { database(context).use { db ->
        val code = failureCode(error)
        val delay = if (code in setOf("credentials", "configuration", "request_rejected")) 6 * 60 * 60_000L else 30 * 60_000L
        db.execSQL("UPDATE shared_jobs SET attempts=attempts+1,next_at=? WHERE record_id=? AND text_hash=? AND event_id=? AND COALESCE((SELECT CAST(value AS INTEGER) FROM shared_jobs_meta WHERE key='generation'),0)=?",
            arrayOf(System.currentTimeMillis() + delay, job.id, job.hash, job.event,job.generation))
        if (currentGeneration(context, job)) context.getSharedPreferences("shared_job_failures_v1", Context.MODE_PRIVATE)
            .edit().putString(failureKey(job), code).apply()
    } }
    private fun currentGeneration(context: Context, job: Job): Boolean = synchronized(lock) { database(context).use { db ->
        db.rawQuery("SELECT value FROM shared_jobs_meta WHERE key='generation'", null).use { (if (it.moveToFirst()) it.getLong(0) else 0L) == job.generation }
    } }
    private fun remove(context: Context, job: Job) = synchronized(lock) { database(context).use { db ->
        db.beginTransaction()
        try {
            val generation=db.rawQuery("SELECT value FROM shared_jobs_meta WHERE key='generation'",null).use { if(it.moveToFirst()) it.getLong(0) else 0L }
            if(generation==job.generation) {
                db.execSQL("INSERT OR IGNORE INTO shared_completed(record_id,text_hash) VALUES (?,?)",arrayOf(job.id,job.hash))
                db.delete("shared_jobs","record_id=? AND text_hash=? AND event_id=?",arrayOf(job.id,job.hash,job.event))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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
        if (row == null || CompanionMemorySuppression.digest(text) != job.hash) { remove(app, job); clearFailure(app, job); return next(app) != null }
        try {
            check(CompanionMemoryStore(app).enabled) { "memory disabled" }
            val model = modelOverride ?: configuredUnderstandingModel(app)
            val requestId = UUID.randomUUID().toString()
            val raw = model.analyze(requestId, text)
            UnderstandingModelContract.validate(raw, requestId)
            val result = JSONObject(raw)
            if (!currentGeneration(app, job)) return next(app) != null
            if (stopped.get()) return true
            knowledge.withUnchangedRecord(job.id, text) {
                if (!stopped.get()) CompanionMemoryStore(app).commitSharedJob(job, result.getJSONArray("claims"))
            }
        } catch (e: Exception) {
            if (!stopped.get()) defer(app, job, e)
            throw e
        }
        if (stopped.get()) return true
        if (currentGeneration(app, job)) { remove(app, job); clearFailure(app, job) }
        return next(app) != null
    }
    fun begin() = running.compareAndSet(false, true)
    fun end() { running.set(false) }
    fun pendingCount(context: Context): Long = synchronized(lock) { database(context).use { db ->
        db.rawQuery("SELECT COUNT(*) FROM shared_jobs", null).use { it.moveToFirst(); it.getLong(0) }
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
