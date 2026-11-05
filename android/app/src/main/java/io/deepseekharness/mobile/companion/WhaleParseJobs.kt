package io.deepseekharness.mobile.companion

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.deepseekharness.mobile.runtime.RuntimeSharedInbox
import io.deepseekharness.mobile.runtime.RuntimeStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded decode at a time. The OS job is persisted; the only work payload lives beside the original. */
internal object WhaleParseJobs {
    const val JOB_ID = 780434
    internal val executor = Executors.newSingleThreadExecutor()
    private val delivery = Executors.newSingleThreadExecutor()
    private var active: JobParameters? = null
    private var wakeReserved = false

    fun reserve(app: Context) = synchronized(CompanionKnowledgeStore.lock) {
        val scheduler = app.getSystemService(JobScheduler::class.java)
        if (active != null || (wakeReserved && scheduler.getPendingJob(JOB_ID) != null)) return@synchronized
        val info = JobInfo.Builder(JOB_ID, ComponentName(app, WhaleParseJobService::class.java))
            .setMinimumLatency(0).setOverrideDeadline(0).setPersisted(true).build()
        check(scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS) { "解析任务无法登记" }
        wakeReserved = true
    }

    fun recover(app: Context) {
        executor.execute {
            if (CompanionKnowledgeStore(app).withParseDatabase { it.hasPendingParse() }) reserve(app)
        }
    }

    fun deliverInbox(app: Context) {
        delivery.execute { runCatching { RuntimeSharedInbox(RuntimeStore(app)).deliver() } }
    }

    fun started(params: JobParameters) = synchronized(CompanionKnowledgeStore.lock) { active = params; wakeReserved = false }
    fun ended(params: JobParameters) = synchronized(CompanionKnowledgeStore.lock) {
        if (active === params) active = null
        wakeReserved = false
    }

    fun process(app: Context, anchor: org.json.JSONObject, stopped: AtomicBoolean) {
        val store = CompanionKnowledgeStore(app)
        val ticket = anchor.getString("parseTicket")
        val attempt = runCatching {
            val file = KnowledgeObjects(app).getVerified(anchor.getString("originalId"))
            val mime = anchor.optString("mime")
            if (mime.startsWith("image/")) WhaleImageImport.parse(app, file)
            else WhaleDocumentImport.parse(app, file, WhaleOriginalImport.extension(anchor.optString("displayName"), mime),
                mime, anchor.optInt("audioFromSeconds",0), stopped::get)
        }
        val parsed = attempt.getOrNull()
        val failure = if (parsed == null) WhaleParseFailure.from(attempt.exceptionOrNull()) else null
        synchronized(CompanionKnowledgeStore.lock) {
            if (stopped.get()) return
            val rows = try { store.commitParsed(ticket, parsed, failure) }
                catch (_: IllegalArgumentException) { store.commitParsed(ticket, null, WhaleParseFailure.PARSE_FAILED) }
                catch (_: IllegalStateException) { store.commitParsed(ticket, null, WhaleParseFailure.PARSE_FAILED) }
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val text = row.getString("text")
                val event = if (parsed?.description == true) "share:" + anchor.getString("originalId")
                    else "share:" + CompanionMemorySuppression.digest(text)
                SharedMemoryJobs.enqueueWithoutSchedule(app, row.getString("id"), text, event)
            }
            if (rows.length() > 0) SharedMemoryJobs.schedule(app, 0)
        }
    }
}

class WhaleParseJobService : JobService() {
    private var cancellation: AtomicBoolean? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val stopped = AtomicBoolean(false)
        cancellation = stopped
        WhaleParseJobs.started(params)
        WhaleParseJobs.executor.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val app = applicationContext
            val store = CompanionKnowledgeStore(app)
            try {
                while (!stopped.get()) {
                    val anchor = synchronized(CompanionKnowledgeStore.lock) {
                        if (stopped.get()) return@execute
                        store.claimParse().also {
                            if (it == null) { WhaleParseJobs.ended(params); jobFinished(params, false) }
                        }
                    } ?: break
                    WhaleParseJobs.process(app, anchor, stopped)
                }
            } catch (_: Exception) {
                synchronized(CompanionKnowledgeStore.lock) {
                    if (!stopped.get()) { WhaleParseJobs.ended(params); jobFinished(params, true) }
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        synchronized(CompanionKnowledgeStore.lock) { cancellation?.set(true); WhaleParseJobs.ended(params) }
        return true
    }
}
