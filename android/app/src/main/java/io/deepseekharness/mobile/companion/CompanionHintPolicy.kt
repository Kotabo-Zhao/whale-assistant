package io.deepseekharness.mobile.companion

/** A due reminder may preempt routine hints, but never another visible reminder. */
internal object CompanionHintPolicy {
    const val ROUTINE_INTERVAL_MS = 120_000L
    fun mayShow(reminder: Boolean, reminderVisible: Boolean, now: Long, last: Long): Boolean {
        if (reminderVisible || now < 0 || last < 0) return false
        if (reminder) return true
        return last == 0L || (now >= last && now - last >= ROUTINE_INTERVAL_MS)
    }
}
