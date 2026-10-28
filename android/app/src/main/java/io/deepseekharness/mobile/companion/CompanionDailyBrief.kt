package io.deepseekharness.mobile.companion

import java.util.Calendar
import java.util.Locale

/** One local-day hint in a two-hour window; not a precise alarm or delivery guarantee. */
internal object CompanionDailyBriefPolicy {
    const val WINDOW_MINUTES = 120
    fun dayKey(now: Calendar): String = String.format(Locale.ROOT, "%04d-%03d", now.get(Calendar.YEAR), now.get(Calendar.DAY_OF_YEAR))
    fun due(enabled: Boolean, quiet: Boolean, ready: Boolean, now: Calendar, minute: Int, lastDay: String?): Boolean {
        if (!enabled || quiet || !ready || minute !in 0 until 1440 || lastDay == dayKey(now)) return false
        val current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return current >= minute && current < minOf(1440, minute + WINDOW_MINUTES)
    }
}
