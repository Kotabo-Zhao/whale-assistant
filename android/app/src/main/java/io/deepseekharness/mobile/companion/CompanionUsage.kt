package io.deepseekharness.mobile.companion

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/** Optional, local, approximate usage-based shortcut suggestions; no app data is read. */
internal object CompanionUsage {
    const val MIN_MS = 20L * 60 * 1000
    const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    data class Candidate(val packageName: String, val foregroundMs: Long)
    enum class State { OK, NO_PERMISSION, UNAVAILABLE }
    data class Snapshot(val state: State, val candidates: List<Candidate> = emptyList())
    fun rank(rows: List<Candidate>, existing: Set<String>, own: String): List<Candidate> {
        if (existing.size >= CompanionShortcutPolicy.MAX_SHORTCUTS) return emptyList()
        val totals = linkedMapOf<String, Long>()
        for (row in rows) {
            val pkg = row.packageName
            if (!CompanionSourcePolicy.validPackage(pkg) || pkg == own || pkg in existing || row.foregroundMs <= 0) continue
            val current = totals[pkg] ?: 0L
            totals[pkg] = if (row.foregroundMs > Long.MAX_VALUE - current) Long.MAX_VALUE else current + row.foregroundMs
        }
        return totals.map { Candidate(it.key, it.value) }.filter { it.foregroundMs >= MIN_MS }
            .sortedWith(compareByDescending<Candidate> { it.foregroundMs }.thenBy { it.packageName }).take(30)
    }
    fun load(context: Context, existing: Set<String>, now: Long = System.currentTimeMillis()): Snapshot {
        return try {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return Snapshot(State.UNAVAILABLE)
            @Suppress("DEPRECATION")
            if (appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) != AppOpsManager.MODE_ALLOWED) return Snapshot(State.NO_PERMISSION)
            val manager = context.getSystemService(UsageStatsManager::class.java) ?: return Snapshot(State.UNAVAILABLE)
            val data = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - WINDOW_MS, now) ?: return Snapshot(State.UNAVAILABLE)
            val ranked = rank(data.map { Candidate(it.packageName, it.totalTimeInForeground) }, existing, context.packageName)
            val launchable = ranked.filter { candidate ->
                val launch = runCatching { context.packageManager.getLaunchIntentForPackage(candidate.packageName) }.getOrNull()
                CompanionSourcePolicy.matchesResolved(candidate.packageName, launch?.component?.packageName)
            }.take(5)
            Snapshot(State.OK, launchable)
        } catch (_: SecurityException) { Snapshot(State.NO_PERMISSION) }
          catch (_: Exception) { Snapshot(State.UNAVAILABLE) }
    }
}

internal object CompanionUsageHintPolicy {
    const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    const val PROMPT_INTERVAL_MS = 24L * 60 * 60 * 1000
    fun due(now: Long, checked: Long, prompted: Long): Boolean =
        now > 0 && (checked <= 0 || checked > now || now - checked >= CHECK_INTERVAL_MS) &&
            (prompted <= 0 || prompted > now || now - prompted >= PROMPT_INTERVAL_MS)
}
