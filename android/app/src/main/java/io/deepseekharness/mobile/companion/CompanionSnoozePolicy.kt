package io.deepseekharness.mobile.companion

/** User-selected delay creates a separate one-time reminder; existing reminders are unchanged. */
internal object CompanionSnoozePolicy {
    val minutes = listOf(10, 30, 60)
    fun target(now: Long, delayMinutes: Int): Long {
        require(now > 0 && delayMinutes in minutes) { "延后时间无效" }
        return Math.addExact(now, delayMinutes * 60_000L)
    }
}
