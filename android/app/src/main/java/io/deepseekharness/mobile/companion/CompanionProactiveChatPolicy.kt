package io.deepseekharness.mobile.companion

import java.util.Calendar
import java.util.Locale

/** Frequency keys are local-calendar labels, never precise alarm or delivery evidence. */
internal object CompanionProactiveChatPolicy {
    private val frequencies = setOf("daily", "weekly")
    fun key(frequency: String, now: Calendar): String {
        require(frequency in frequencies)
        val unit = when (frequency) {
            "daily" -> String.format(Locale.ROOT, "%04d-%03d", now.get(Calendar.YEAR), now.get(Calendar.DAY_OF_YEAR))
            else -> String.format(Locale.ROOT, "%04d-%02d", now.weekYear, now.get(Calendar.WEEK_OF_YEAR))
        }
        return "$frequency:$unit"
    }
    fun due(frequency: String, lastKey: String?, now: Calendar): Boolean = key(frequency, now) != lastKey
    /** The former v1 daily key was yyyy-MM-dd without a frequency prefix. */
    fun migrateLegacyDailyKey(legacyDay: String?, now: Calendar): String? {
        val former = String.format(Locale.ROOT, "%04d-%02d-%02d", now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        return legacyDay?.takeIf { it == former }?.let { key("daily", now) }
    }
}
