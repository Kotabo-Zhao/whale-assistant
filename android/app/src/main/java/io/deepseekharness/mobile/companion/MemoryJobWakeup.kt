package io.deepseekharness.mobile.companion

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock

/** Coalesce many row mutations into one OS wakeup, retaining requests received while the job is draining. */
internal class MemoryJobWakeup(private val id: Int, private val service: Class<out JobService>, private val network: Boolean = false) {
    private var scheduledAt = Long.MAX_VALUE
    private var duringRun = Long.MAX_VALUE
    private var active: JobParameters? = null

    @Synchronized fun request(app: Context, delayMs: Long): Boolean {
        val delay = delayMs.coerceAtLeast(0L)
        val due = SystemClock.elapsedRealtime() + delay
        if (active != null) { duringRun = minOf(duringRun, due); return false }
        val scheduler = app.getSystemService(JobScheduler::class.java)
        if (scheduledAt <= due && scheduler.getPendingJob(id) != null) return false
        val job = JobInfo.Builder(id, ComponentName(app, service))
            .setMinimumLatency(delay).setPersisted(true)
        if (network) job.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        check(scheduler.schedule(job.build()) == JobScheduler.RESULT_SUCCESS)
        scheduledAt = due
        return true
    }

    @Synchronized fun start(params: JobParameters) {
        active = params
        scheduledAt = Long.MAX_VALUE
        duringRun = Long.MAX_VALUE
    }

    @Synchronized fun finish(owner: JobService, params: JobParameters, delayMs: Long?, retry: Boolean = false) {
        if (active !== params) return
        active = null
        val now = SystemClock.elapsedRealtime()
        val next = minOf(duringRun, delayMs?.let { now + it.coerceAtLeast(0) } ?: Long.MAX_VALUE)
        duringRun = Long.MAX_VALUE
        // Reserve the next wake before releasing this one. If Android rejects it, keep the OS retry.
        val reservationFailed = !retry && next != Long.MAX_VALUE && runCatching { request(owner, next - now) }
            .onFailure { android.util.Log.w("whale-jobs", "OS wakeup deferred for job $id") }.isFailure
        owner.jobFinished(params, retry || reservationFailed)
    }

    @Synchronized fun stop(params: JobParameters) {
        if (active === params) active = null
    }

    @Synchronized fun cancel(app: Context) {
        active = null
        scheduledAt = Long.MAX_VALUE
        duringRun = Long.MAX_VALUE
        app.getSystemService(JobScheduler::class.java).cancel(id)
    }
}
