package io.deepseekharness.mobile.companion

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object BackgroundUnderstandingPolicy {
    fun allowed(enabled: Boolean, capture: Boolean, excluded: Boolean, listener: Boolean) = enabled && capture && !excluded && listener
    fun canRequest(count: Int, limit: Int) = count >= 0 && limit > 0 && count < limit
}

/** Notification-only opt-in pipeline. No other source is implicitly authorized. */
internal object BackgroundUnderstanding {
    private const val JOB_ID = 780431
    private const val DAILY_LIMIT = 20
    private val ingestExecutor = Executors.newSingleThreadExecutor()
    private val budgetLock = Any()
    private val recoveryLock = Any()
    private var recovered = false
    val jobRunning = AtomicBoolean(false)

    fun ingest(context: Context, pkg: String, title: String, text: String, notificationKey: String, postedAt: Long) {
        val app = context.applicationContext
        if (!CompanionSourcePolicy.validPackage(pkg) || CompanionStore.sensitive("$title $text")) return
        val body = "标题：${title.take(160)}\n内容：${text.take(400)}"
        ingestExecutor.execute {
            if (!allowed(app, pkg)) return@execute
            runCatching {
                UnifiedUnderstandingStore.open(app).use { store ->
                    if (!allowed(app, pkg)) return@use
                    val source = "notification:$pkg"
                    store.queue.setSourceEnabled(source, true)
                    if (!allowed(app, pkg)) { store.queue.setSourceEnabled(source, false); return@use }
                    val thread = notificationKey.takeIf { it.isNotBlank() }?.let { CompanionMemorySuppression.digest("notification:$pkg:$it") }
                    store.queue.ingest(source, UUID.randomUUID().toString(), body, thread, postedAt)
                }
                schedule(app, 0)
            }.onFailure { status(app, "保存任务失败") }
        }
    }

    fun resume(context: Context) {
        val app = context.applicationContext
        ingestExecutor.execute { if (hasPending(app)) schedule(app, 0) }
    }

    fun stop(context: Context): Boolean {
        val app = context.applicationContext
        app.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        val revoked = runCatching { UnifiedUnderstandingStore.open(app).use { it.queue.revokeAll() } }.isSuccess
        status(app, "已停止")
        return revoked
    }

    fun revokeExcluded(context: Context, packages: Set<String>): Boolean {
        if (packages.isEmpty()) return true
        return runCatching { UnifiedUnderstandingStore.open(context).use { store ->
            packages.filter(CompanionSourcePolicy::validPackage).forEach { store.queue.setSourceEnabled("notification:$it", false) }
        } }.isSuccess
    }

    fun allowed(context: Context, pkg: String): Boolean {
        val settings = CompanionStore(context)
        return BackgroundUnderstandingPolicy.allowed(settings.backgroundUnderstanding, settings.notificationCapture,
            pkg in settings.excludedPackages(), NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
    }

    fun schedule(context: Context, delayMs: Long) {
        if (jobRunning.get()) return
        if (!CompanionStore(context).backgroundUnderstanding || !CompanionStore(context).notificationCapture) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, UnderstandingJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setMinimumLatency(delayMs.coerceAtLeast(0)).build()
        if (context.getSystemService(JobScheduler::class.java)?.schedule(job) != JobScheduler.RESULT_SUCCESS) status(context, "系统未接受后台调度")
    }

    private fun reserveBudget(context: Context): Boolean = synchronized(budgetLock) {
        val prefs = context.getSharedPreferences("understanding_runtime_v1", Context.MODE_PRIVATE)
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
        val count = if (prefs.getString("day", null) == day) prefs.getInt("calls", 0) else 0
        if (!BackgroundUnderstandingPolicy.canRequest(count, DAILY_LIMIT)) return@synchronized false
        prefs.edit().putString("day", day).putInt("calls", count + 1).commit()
    }

    fun status(context: Context, value: String) {
        context.getSharedPreferences("understanding_runtime_v1", Context.MODE_PRIVATE).edit()
            .putString("status", value).putLong("updated", System.currentTimeMillis()).apply()
    }

    fun hasPending(context: Context): Boolean = runCatching {
        UnifiedUnderstandingStore.open(context).use { it.queue.pendingCount() > 0 }
    }.getOrDefault(false)

    fun runOne(context: Context, stopped: AtomicBoolean): Boolean {
        val app = context.applicationContext
        UnifiedUnderstandingStore.open(app).use { store ->
            synchronized(recoveryLock) {
                if (!recovered) { store.queue.recoverInterrupted(); recovered = true }
            }
            val now = System.currentTimeMillis()
            val claim = store.queue.claim(now) ?: return store.queue.pendingCount() > 0
            val pkg = claim.source.removePrefix("notification:")
            if (stopped.get()) { store.queue.retry(claim, now + 60_000L); return true }
            if (!claim.source.startsWith("notification:") || !allowed(app, pkg)) {
                store.queue.setSourceEnabled(claim.source, false)
                return store.queue.pendingCount() > 0
            }
            if (!reserveBudget(app)) {
                store.queue.retry(claim, now + 60 * 60_000L)
                status(app, "已达到每日 20 次预算，等待重试")
                return true
            }
            try {
                val event = store.queue.eventFor(claim) ?: error("event unavailable")
                if (!allowed(app, pkg) || stopped.get()) { store.queue.retry(claim, now + 60_000); return true }
                val output = configuredUnderstandingModel(app).analyze(event.first, event.second)
                if (stopped.get()) { store.queue.retry(claim, System.currentTimeMillis() + 60_000L); return true }
                if (!allowed(app, pkg)) { store.queue.setSourceEnabled(claim.source, false); return store.queue.pendingCount() > 0 }
                check(CompanionMemoryStore(app).commitUnderstanding(store.queue, claim, event.first, output))
                runCatching { CompanionReminder(app).restore() }
                status(app, "已完成一条分析，结果待查看")
            } catch (_: Exception) {
                store.queue.retry(claim, System.currentTimeMillis() + 30 * 60_000L)
                status(app, "模型处理失败，将稍后重试")
            }
            return store.queue.pendingCount() > 0
        }
    }
}

class UnderstandingJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var stopped = AtomicBoolean(false)
    override fun onStartJob(params: JobParameters): Boolean {
        val runStopped = AtomicBoolean(false)
        stopped = runStopped
        BackgroundUnderstanding.jobRunning.set(true)
        executor.execute {
            val more = runCatching { BackgroundUnderstanding.runOne(applicationContext, runStopped) }
                .getOrElse { BackgroundUnderstanding.status(applicationContext, "后台任务失败，将稍后重试"); true }
            if (!runStopped.get()) {
                jobFinished(params, false)
                BackgroundUnderstanding.jobRunning.set(false)
                if (more || BackgroundUnderstanding.hasPending(applicationContext)) BackgroundUnderstanding.schedule(applicationContext, 30 * 60_000L)
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped.set(true); BackgroundUnderstanding.jobRunning.set(false); return true }
    override fun onDestroy() { stopped.set(true); BackgroundUnderstanding.jobRunning.set(false); executor.shutdownNow(); super.onDestroy() }
}
