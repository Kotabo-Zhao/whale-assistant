package io.deepseekharness.mobile.companion

import java.security.MessageDigest

/** Only exact repeated deliveries of the same notification are suppressed. */
internal object CompanionNotificationDedup {
    const val WINDOW_MS = 10L * 60 * 1000
    fun digest(key: String?): String? = key?.takeIf { it.isNotEmpty() }?.let {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
    fun contentDigest(title: String, text: String): String = digest("${title.length}:$title$text")!!
    fun isDuplicate(key: String?, content: String, now: Long,
                    priorKey: String?, priorContent: String?, priorTime: Long): Boolean =
        key != null && key == priorKey && content == priorContent &&
            priorTime <= now && now - priorTime < WINDOW_MS
}
