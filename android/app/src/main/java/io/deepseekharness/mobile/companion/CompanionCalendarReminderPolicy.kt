package io.deepseekharness.mobile.companion

/** Suggestions from timed events require user review and never create an alarm directly. */
internal object CompanionCalendarReminderPolicy {
    data class Candidate(val event: CompanionCalendar.Event, val suggestedAt: Long)
    fun candidates(events: List<CompanionCalendar.Event>, now: Long): List<Candidate> {
        require(now > 0)
        return events.mapNotNull { event ->
            if (event.allDay || event.start <= now || event.title.isBlank()) return@mapNotNull null
            val roundedStart = event.start - event.start % 60_000L
            if (roundedStart <= now || roundedStart - now <= 60_000L) return@mapNotNull null
            val before = roundedStart - 10 * 60_000L
            Candidate(event, if (before > now && before - now > 60_000L) before else roundedStart)
        }
    }
}
