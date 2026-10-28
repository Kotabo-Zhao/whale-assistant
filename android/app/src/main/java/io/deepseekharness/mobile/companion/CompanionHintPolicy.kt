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

/** In-memory only: reminders expire rather than resurfacing long after their context. */
internal class CompanionHintQueue<T> {
    private data class Entry<T>(val value: T, val at: Long)
    private val items = java.util.ArrayDeque<Entry<T>>()
    fun offer(value: T, now: Long): Boolean {
        if (now < 0 || items.size >= 5) return false
        items.addLast(Entry(value, now))
        return true
    }
    fun take(now: Long): T? {
        while (items.isNotEmpty()) {
            val item = items.removeFirst()
            if (now >= item.at && now - item.at <= 5 * 60_000L) return item.value
        }
        return null
    }
    fun isNotEmpty(): Boolean = items.isNotEmpty()
    fun clear() = items.clear()
}
