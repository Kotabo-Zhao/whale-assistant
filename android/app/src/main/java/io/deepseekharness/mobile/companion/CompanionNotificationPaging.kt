package io.deepseekharness.mobile.companion

/** Bounds initial view creation while preserving newest-first access to the entire local record list. */
internal object CompanionNotificationPaging {
    const val PAGE_SIZE = 10
    fun indices(total: Int, visible: Int): List<Int> {
        val size = total.coerceIn(0, CompanionStore.MAX_RECORDS)
        val count = visible.coerceIn(0, size)
        return (size - 1 downTo size - count).toList()
    }
    fun next(visible: Int): Int = (visible.coerceIn(0, CompanionStore.MAX_RECORDS) + PAGE_SIZE).coerceAtMost(CompanionStore.MAX_RECORDS)
}
