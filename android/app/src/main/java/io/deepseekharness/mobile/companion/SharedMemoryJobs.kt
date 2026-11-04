package io.deepseekharness.mobile.companion

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.deepseekharness.mobile.OverlayBallService
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Durable references only; source text stays in the existing knowledge store. */
internal object SharedMemoryJobs {
    private const val JOB_ID = 780432
    /** A bounded retry budget prevents a permanently invalid input from consuming model requests forever. */
    private const val MAX_AUTOMATIC_ATTEMPTS = 5
    /** Model calls are remote and cannot be cancelled reliably, so a stale worker must lose its durable claim. */
    private const val LEASE_DURATION_MS = 15 * 60_000L
    private val lock = Any()
    internal val wakeup = MemoryJobWakeup(JOB_ID, SharedMemoryJobService::class.java, network = true)
    private val executor = Executors.newSingleThreadExecutor()
    private fun database(context: Context): SQLiteDatabase {
        val db = SQLiteDatabase.openOrCreateDatabase(UnifiedUnderstandingStore.path(context), null)
        db.execSQL("CREATE TABLE IF NOT EXISTS shared_jobs (record_id TEXT NOT NULL, text_hash TEXT NOT NULL, event_id TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_at INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL DEFAULT 'queued', lease_token TEXT, lease_until INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(record_id,text_hash,event_id))")
        val columns = db.rawQuery("PRAGMA table_info(shared_jobs)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
        if ("attempts" !in columns) db.execSQL("ALTER TABLE shared_jobs ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
        if ("next_at" !in columns) db.execSQL("ALTER TABLE shared_jobs ADD COLUMN next_at INTEGER NOT NULL DEFAULT 0")
        if ("state" !in columns) db.execSQL("ALTER TABLE shared_jobs ADD COLUMN state TEXT NOT NULL DEFAULT 'queued'")
        if ("lease_token" !in columns) db.execSQL("ALTER TABLE shared_jobs ADD COLUMN lease_token TEXT")
        if ("lease_until" !in columns) db.execSQL("ALTER TABLE shared_jobs ADD COLUMN lease_until INTEGER NOT NULL DEFAULT 0")
        // A process may die while an HTTP request is in flight.  Only an expired lease may be recovered.
        db.execSQL("UPDATE shared_jobs SET state='queued',lease_token=NULL,lease_until=0 WHERE state='running' AND lease_until<=?", arrayOf(System.currentTimeMillis()))
        db.execSQL("CREATE TABLE IF NOT EXISTS shared_completed (record_id TEXT NOT NULL, text_hash TEXT NOT NULL, PRIMARY KEY(record_id,text_hash))")
        // User cancellation is durable: bootstrap must not silently recreate a job the user stopped.
        db.execSQL("CREATE TABLE IF NOT EXISTS shared_cancelled (record_id TEXT NOT NULL, text_hash TEXT NOT NULL, PRIMARY KEY(record_id,text_hash))")
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
                        db.execSQL("INSERT OR IGNORE INTO shared_jobs(record_id,text_hash,event_id,attempts,next_at,state) SELECT record_id,text_hash,event_id,$attempts,$next,'queued' FROM legacy_shared.jobs")
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
        val hash = CompanionMemorySuppression.digest(text)
        db.execSQL("INSERT OR IGNORE INTO shared_jobs(record_id,text_hash,event_id) SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM shared_cancelled WHERE record_id=? AND text_hash=?)", arrayOf(id, hash, event, id, hash))
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
                                val cancelled = db.rawQuery("SELECT 1 FROM shared_cancelled WHERE record_id=? AND text_hash=?", arrayOf(id, hash)).use { it.moveToFirst() }
                                val pending = db.rawQuery("SELECT 1 FROM shared_jobs WHERE record_id=? AND text_hash=?", arrayOf(id, hash)).use { it.moveToFirst() }
                                if (!done && !cancelled && !pending) enqueueRow(db, id, text, stableEvent(text))
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
    internal fun enqueueWithoutSchedule(context: Context, id: String, text: String, event: String = stableEvent(text)) = synchronized(lock) {
        database(context).use { enqueueRow(it, id, text, event) }
    }
    internal fun resetDelays(context: Context) = synchronized(lock) { database(context).use { it.execSQL("UPDATE shared_jobs SET next_at=0 WHERE state='queued'") } }
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
            db.rawQuery("SELECT state,MAX(attempts),MIN(next_at) FROM shared_jobs WHERE record_id=? AND text_hash=? GROUP BY state ORDER BY CASE state WHEN 'failed' THEN 0 ELSE 1 END", arrayOf(id, textHash)).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    val failure = context.getSharedPreferences("shared_job_failures_v1", Context.MODE_PRIVATE)
                        .getString("$id:$textHash", null)
                    val state = cursor.getString(0)
                    return@synchronized JSONObject().put("state", state).put("attempts", cursor.getInt(1))
                        .put("nextAttemptAtMs", if (state == "queued") cursor.getLong(2) else JSONObject.NULL).put("retryable", true)
                        .put("lastErrorCode", failure ?: JSONObject.NULL)
                }
            }
            val completed = db.rawQuery("SELECT 1 FROM shared_completed WHERE record_id=? AND text_hash=?", arrayOf(id, textHash)).use { it.moveToFirst() }
            val cancelled = db.rawQuery("SELECT 1 FROM shared_cancelled WHERE record_id=? AND text_hash=?", arrayOf(id, textHash)).use { it.moveToFirst() }
            JSONObject().put("state", when { completed -> "completed"; cancelled -> "cancelled"; else -> "not_queued" })
                .put("retryable", false)
        }
    }
    internal fun retryRecord(context: Context, id: String, textHash: String): Boolean = synchronized(lock) {
        database(context.applicationContext).use { db ->
            // Do not steal an active lease: the user can stop it, then explicitly retry.
            db.compileStatement("UPDATE shared_jobs SET state='queued',attempts=0,next_at=0,lease_token=NULL,lease_until=0 WHERE record_id=? AND text_hash=? AND state IN ('queued','failed')").use { update ->
                update.bindString(1, id); update.bindString(2, textHash)
                update.executeUpdateDelete() > 0
            }
        }
    }
    /** Cancels this exact record version. A concurrent model reply cannot commit because commitSharedJob rechecks the row. */
    internal fun cancelRecord(context: Context, id: String, textHash: String): Boolean = synchronized(lock) {
        database(context.applicationContext).use { db ->
            db.beginTransaction()
            try {
                val queued = db.delete("shared_jobs", "record_id=? AND text_hash=?", arrayOf(id, textHash))
                db.execSQL("INSERT OR IGNORE INTO shared_cancelled(record_id,text_hash) VALUES (?,?)", arrayOf(id, textHash))
                db.setTransactionSuccessful()
                queued > 0
            } finally { db.endTransaction() }
        }
    }
    /** An explicit user retry revokes a previous cancellation and recreates only this unchanged record version. */
    internal fun resumeRecord(context: Context, id: String, text: String): Boolean = synchronized(lock) {
        val hash = CompanionMemorySuppression.digest(text)
        database(context.applicationContext).use { db ->
            db.beginTransaction()
            try {
                val cancelled = db.delete("shared_cancelled", "record_id=? AND text_hash=?", arrayOf(id, hash)) > 0
                if (cancelled) enqueueRow(db, id, text, stableEvent(text))
                db.setTransactionSuccessful()
                cancelled
            } finally { db.endTransaction() }
        }
    }
    internal data class Job(val id: String, val hash: String, val event: String, val generation: Long = 0, val leaseToken: String? = null)
    /** Atomically claim one due row.  A lease token gates every later completion or retry transition. */
    internal fun claim(context: Context): Job? = synchronized(lock) { database(context).use { db ->
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val candidate = db.rawQuery("SELECT record_id,text_hash,event_id FROM shared_jobs WHERE (state='queued' AND next_at<=?) OR (state='running' AND lease_until<=?) ORDER BY CASE state WHEN 'queued' THEN 0 ELSE 1 END,next_at,rowid LIMIT 1", arrayOf(now.toString(), now.toString())).use {
                if (it.moveToFirst()) Triple(it.getString(0), it.getString(1), it.getString(2)) else null
            }
            if (candidate == null) return@use null
            val token = UUID.randomUUID().toString()
            val claimed = db.compileStatement("UPDATE shared_jobs SET state='running',lease_token=?,lease_until=? WHERE record_id=? AND text_hash=? AND event_id=? AND ((state='queued' AND next_at<=?) OR (state='running' AND lease_until<=?))").use { update ->
                update.bindString(1, token); update.bindLong(2, now + LEASE_DURATION_MS)
                update.bindString(3, candidate.first); update.bindString(4, candidate.second); update.bindString(5, candidate.third)
                update.bindLong(6, now); update.bindLong(7, now)
                update.executeUpdateDelete() == 1
            }
            if (!claimed) return@use null
            val generation = db.rawQuery("SELECT value FROM shared_jobs_meta WHERE key='generation'", null).use { g -> if (g.moveToFirst()) g.getLong(0) else 0L }
            db.setTransactionSuccessful()
            Job(candidate.first, candidate.second, candidate.third, generation, token)
        } finally { db.endTransaction() }
    } }
    internal fun delayUntilNext(context: Context): Long? = synchronized(lock) { database(context).use { db ->
        db.rawQuery("SELECT MIN(CASE state WHEN 'queued' THEN next_at WHEN 'running' THEN lease_until END) FROM shared_jobs WHERE state IN ('queued','running')", null).use { c -> if (!c.moveToFirst() || c.isNull(0)) null else (c.getLong(0) - System.currentTimeMillis()).coerceAtLeast(0) }
    } }
    private fun failureKey(job: Job) = "${job.id}:${job.hash}"

    /** The model receives an ephemeral request id; persisted evidence uses the stable source event id. */
    internal fun bindResultToEvent(raw: String, requestId: String, eventId: String): String {
        UnderstandingModelContract.validate(raw, requestId)
        val root = JSONObject(raw)
        for (key in listOf("claims", "items")) {
            val rows = root.optJSONArray(key) ?: continue
            for (i in 0 until rows.length()) rows.getJSONObject(i).put("evidence_ids", org.json.JSONArray().put(eventId))
        }
        val bound = root.toString()
        UnderstandingModelContract.validate(bound, eventId)
        return bound
    }
    private fun clearFailure(context: Context, job: Job) {
        context.getSharedPreferences("shared_job_failures_v1", Context.MODE_PRIVATE).edit().remove(failureKey(job)).apply()
    }
    private fun defer(context: Context, job: Job, error: Exception) = synchronized(lock) { database(context).use { db ->
        val token = job.leaseToken ?: return@use
        val code = UnderstandingFailure.code(error)
        val attempts = db.rawQuery("SELECT attempts FROM shared_jobs WHERE record_id=? AND text_hash=? AND event_id=? AND state='running' AND lease_token=? AND lease_until>?", arrayOf(job.id, job.hash, job.event, token, System.currentTimeMillis().toString())).use { cursor ->
            if (!cursor.moveToFirst()) return@use 0
            cursor.getInt(0)
        }
        val exhausted = attempts + 1 >= MAX_AUTOMATIC_ATTEMPTS
        val delay = UnderstandingFailure.retryDelayMs(code)
        val transitioned = db.compileStatement("UPDATE shared_jobs SET attempts=attempts+1,state=?,next_at=?,lease_token=NULL,lease_until=0 WHERE record_id=? AND text_hash=? AND event_id=? AND state='running' AND lease_token=? AND lease_until>? AND COALESCE((SELECT CAST(value AS INTEGER) FROM shared_jobs_meta WHERE key='generation'),0)=?").use { update ->
            update.bindString(1, if (exhausted) "failed" else "queued"); update.bindLong(2, if (exhausted) 0L else System.currentTimeMillis() + delay)
            update.bindString(3, job.id); update.bindString(4, job.hash); update.bindString(5, job.event); update.bindString(6, token); update.bindLong(7, System.currentTimeMillis()); update.bindLong(8, job.generation)
            update.executeUpdateDelete() == 1
        }
        if (transitioned) context.getSharedPreferences("shared_job_failures_v1", Context.MODE_PRIVATE)
            .edit().putString(failureKey(job), code).apply()
    } }
    private fun remove(context: Context, job: Job) = synchronized(lock) { database(context).use { db ->
        val token = job.leaseToken ?: return@use false
        db.beginTransaction()
        try {
            val generation=db.rawQuery("SELECT value FROM shared_jobs_meta WHERE key='generation'",null).use { if(it.moveToFirst()) it.getLong(0) else 0L }
            if(generation==job.generation) {
                val deleted = db.delete("shared_jobs","record_id=? AND text_hash=? AND event_id=? AND state='running' AND lease_token=? AND lease_until>?",arrayOf(job.id,job.hash,job.event,token,System.currentTimeMillis().toString()))
                if (deleted == 1) db.execSQL("INSERT OR IGNORE INTO shared_completed(record_id,text_hash) VALUES (?,?)",arrayOf(job.id,job.hash))
                db.setTransactionSuccessful()
                return@use deleted == 1
            }
            db.setTransactionSuccessful()
            false
        } finally { db.endTransaction() }
    } }
    fun schedule(context: Context, delayMs: Long) {
        val app = context.applicationContext
        if (!CompanionMemoryStore(app).enabled) return
        val due = delayUntilNext(app) ?: return
        wakeup.request(app, maxOf(delayMs, due))
    }
    fun runOne(context: Context, stopped: AtomicBoolean, modelOverride: UnderstandingModel? = null): Boolean {
        val app = context.applicationContext
        val job = claim(app) ?: return false
        val knowledge = CompanionKnowledgeStore(app)
        val memory = CompanionMemoryStore(app)
        val row = knowledge.findById(job.id)
        val text = row?.optString("text").orEmpty()
        if (row == null || CompanionMemorySuppression.digest(text) != job.hash) { if (remove(app, job)) clearFailure(app, job); return hasDueWork(app) }
        try {
            check(memory.enabled) { "memory disabled" }
            val model = modelOverride ?: configuredUnderstandingModel(app)
            val requestId = UUID.randomUUID().toString()
            val raw = model.analyze(requestId, text.substringAfter("\n\n文档提取内容：\n", text))
            val boundResult = bindResultToEvent(raw, requestId, job.event)
            val result = JSONObject(boundResult)
            if (stopped.get()) return true
            var persisted = false
            knowledge.withUnchangedRecord(job.id, text) {
                if (!stopped.get()) persisted = memory.commitSharedJob(job, result.getJSONArray("claims"), boundResult)
            }
            if (persisted && !stopped.get()) {
                clearFailure(app, job)
                if (UnderstandingModelContract.actionableItems(result).isNotEmpty()) OverlayBallService.companionTaskHint()
            }
        } catch (e: Exception) {
            if (!stopped.get()) defer(app, job, e)
            throw e
        }
        if (stopped.get()) return true
        return hasDueWork(app)
    }
    fun pendingCount(context: Context): Long = synchronized(lock) { database(context).use { db ->
        db.rawQuery("SELECT COUNT(*) FROM shared_jobs WHERE state IN ('queued','running')", null).use { it.moveToFirst(); it.getLong(0) }
    } }
    private fun hasDueWork(context: Context): Boolean = synchronized(lock) { database(context).use { db ->
        val now = System.currentTimeMillis()
        db.rawQuery("SELECT 1 FROM shared_jobs WHERE (state='queued' AND next_at<=?) OR (state='running' AND lease_until<=?) LIMIT 1", arrayOf(now.toString(), now.toString())).use { it.moveToFirst() }
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
        SharedMemoryJobs.wakeup.start(params)
        executor.execute {
            try {
                val app = applicationContext
                if (!CompanionMemoryStore(app).enabled) {
                    SharedMemoryJobs.status(app, "长期记忆整理已关闭")
                    SharedMemoryJobs.wakeup.finish(this, params, null)
                    return@execute
                }
                SharedMemoryJobs.status(app, "正在整理资料")
                var processed = 0
                var more = true
                while (!cancelled.get() && more && processed < 8) {
                    more = runCatching { SharedMemoryJobs.runOne(app, cancelled) }.getOrDefault(false)
                    processed++
                }
                if (!cancelled.get()) {
                    val due = SharedMemoryJobs.delayUntilNext(app)
                    SharedMemoryJobs.status(app, if (due == null) "已处理完待整理资料" else "还有资料等待整理或重试")
                    SharedMemoryJobs.wakeup.finish(this, params, due?.coerceAtLeast(2_000))
                }
            } catch (_: Exception) {
                if (!cancelled.get()) SharedMemoryJobs.wakeup.finish(this, params, null, retry = true)
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped.set(true); SharedMemoryJobs.wakeup.stop(params); return true }
    override fun onDestroy() { stopped.set(true); executor.shutdown(); super.onDestroy() }
}
