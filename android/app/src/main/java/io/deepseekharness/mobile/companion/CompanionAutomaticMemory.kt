package io.deepseekharness.mobile.companion

/** An observed notification is evidence from another app, not a statement by the user. */
internal object CompanionAutomaticMemory {
    fun fromNotification(packageName: String, title: String, text: String): String? {
        if (!CompanionSourcePolicy.validPackage(packageName) || CompanionStore.sensitive("$title $text")) return null
        val content = listOf(title.trim(), text.trim()).filter { it.isNotEmpty() }.distinct().joinToString("：")
        if (content.isEmpty()) return null
        val prefix = "来自 $packageName 的通知（未经核实）："
        return prefix + CompanionNotificationRecords.truncate(content, 600 - prefix.length)
    }
}
