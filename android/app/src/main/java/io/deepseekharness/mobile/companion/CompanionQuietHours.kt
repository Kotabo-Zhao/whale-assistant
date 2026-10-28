package io.deepseekharness.mobile.companion

/** Start inclusive, end exclusive; equal times mean an empty interval. */
internal object CompanionQuietHours {
    fun contains(enabled: Boolean, start: Int, end: Int, now: Int): Boolean {
        if (!enabled || start !in 0 until 1440 || end !in 0 until 1440 || now !in 0 until 1440 || start == end) return false
        return if (start < end) now >= start && now < end else now >= start || now < end
    }
}
