package io.deepseekharness.mobile.companion

/** Local bubble text only; it is not sent to the model or a notification service. */
internal object CompanionHintContent {
    const val GENERIC = "提醒到了，要和我聊聊吗？点击打开对话。"
    const val OPEN_ONCE_REMINDERS = "open_once_reminders"
    fun recurring(text: String, showDetails: Boolean): String {
        if (!showDetails || CompanionStore.sensitive(text)) return GENERIC
        val compact = text.replace(Regex("[\\p{Cc}\\s]+"), " ").trim()
        if (compact.isEmpty()) return GENERIC
        var end = minOf(compact.length, 64)
        if (end < compact.length && end > 0 && Character.isHighSurrogate(compact[end - 1]) && Character.isLowSurrogate(compact[end])) end--
        val excerpt = compact.substring(0, end) + if (end < compact.length) "…" else ""
        return "提醒：$excerpt 点击打开对话。"
    }
}
