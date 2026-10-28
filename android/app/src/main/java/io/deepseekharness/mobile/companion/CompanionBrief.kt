package io.deepseekharness.mobile.companion

import org.json.JSONArray

/** 本地规则仅供整理；无法判断通知的重要性或证明用户没有遗漏事项。 */
internal object CompanionBrief {
    private val rules = listOf(
        "日程与待办" to Regex("会议|日程|待办|提醒|预约|calendar|meeting|reminder", RegexOption.IGNORE_CASE),
        "物流与出行" to Regex("快递|取件|物流|配送|航班|登机|列车|delivery|parcel", RegexOption.IGNORE_CASE),
        "消息" to Regex("消息|回复|私信|message|replied", RegexOption.IGNORE_CASE),
    )

    fun category(title: String, text: String): String {
        val content = "$title $text"
        return rules.firstOrNull { it.second.containsMatchIn(content) }?.first ?: "其他"
    }

    data class Candidate(val packageName: String, val time: Long, val title: String, val text: String, val reason: String, val notificationKeyHash: String? = null, val notificationContentHash: String? = null)
    private val candidateRules = listOf(
        "可能是日程" to Regex("会议|预约|开会|meeting|appointment", RegexOption.IGNORE_CASE),
        "可能需要取件" to Regex("取件|待取|领取快递|pickup|pick up|parcel ready", RegexOption.IGNORE_CASE),
        "可能需要回复" to Regex("待回复|请回复|请回电|reply requested|please reply", RegexOption.IGNORE_CASE),
    )
    fun candidateReason(title: String, text: String): String? =
        candidateRules.firstOrNull { it.second.containsMatchIn("$title $text") }?.first
    /** Suggestions only: keep their original source and time; never infer due dates or create reminders. */
    fun candidates(records: JSONArray): List<Candidate> {
        val output = mutableListOf<Candidate>()
        for (index in records.length() - 1 downTo 0) {
            val row = records.optJSONObject(index) ?: continue
            val title = row.optString("title")
            val text = row.optString("text")
            val source = row.optString("package")
            val time = row.optLong("time", 0)
            if (source.isBlank() || time <= 0 || (title.isBlank() && text.isBlank()) || CompanionStore.sensitive("$title $text")) continue
            val reason = candidateReason(title, text) ?: continue
            output.add(Candidate(source, time, title, text, reason,
                row.optString("notificationKeyHash").takeIf(CompanionNotificationTarget::validDigest),
                row.optString("notificationContentHash").takeIf(CompanionNotificationTarget::validDigest)))
            if (output.size >= 20) break
        }
        return output
    }

    fun counts(records: JSONArray): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        for (index in 0 until records.length()) {
            val row = records.optJSONObject(index) ?: continue
            val category = category(row.optString("title"), row.optString("text"))
            result[category] = (result[category] ?: 0) + 1
        }
        return result
    }
}

/** Validate stored package identifiers before resolving an explicit app launcher. */
internal object CompanionSourcePolicy {
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*){1,12}$")
    fun validPackage(value: String): Boolean = value.length <= 160 && packagePattern.matches(value)
    fun matchesResolved(value: String, resolvedPackage: String?): Boolean = validPackage(value) && resolvedPackage == value
}

/** A single explicit local date and time is only a picker suggestion, never a scheduled alarm. */
internal object CompanionNotificationTimeSuggestion {
    private val explicit = Regex("(?<![0-9])([0-9]{4})[-年]([0-9]{1,2})[-月]([0-9]{1,2})(?:日)?[ T]+([0-9]{1,2}):([0-9]{2})(?![0-9:])")
    private val relative = Regex("(今天|明天|后天)\\s*(上午|下午|晚上)?\\s*([0-9]{1,2}):([0-9]{2})(?![0-9:])")
    private val relativeDay = Regex("今天|明天|后天")
    private val clock = Regex("(?<![0-9:])[0-9]{1,2}:[0-9]{2}(?![0-9:])")
    private val anyDate = Regex("(?<![0-9])(?:[0-9]{1,4}[-年/])?[0-9]{1,2}[-月/][0-9]{1,2}(?:日)?")
    private val otherClock = Regex("[0-9一二三四五六七八九十两]{1,3}点")
    fun find(text: String, now: Long, zone: java.time.ZoneId, notificationTime: Long? = null): Long? {
        val matches = explicit.findAll(text).take(2).toList()
        if (matches.isEmpty()) return findRelative(text, now, zone, notificationTime)
        if (matches.size != 1 || relativeDay.containsMatchIn(text) || clock.findAll(text).take(2).count() != 1) return null
        return try {
            val values = matches.single().groupValues.drop(1).map(String::toInt)
            val local = java.time.LocalDateTime.of(values[0], values[1], values[2], values[3], values[4])
            val offsets = zone.rules.getValidOffsets(local)
            if (offsets.size != 1) return null
            val millis = local.toInstant(offsets.single()).toEpochMilli()
            millis.takeIf { it > now && it - now > 10_000 }
        } catch (_: Exception) { null }
    }

    private fun findRelative(text: String, now: Long, zone: java.time.ZoneId, notificationTime: Long?): Long? {
        val posted = notificationTime ?: return null
        // Older snapshots and future timestamps are unreliable anchors for relative language.
        if (posted <= 0 || posted > now || now - posted > 24 * 60 * 60 * 1000L) return null
        if (relativeDay.findAll(text).take(2).count() != 1 || clock.findAll(text).take(2).count() != 1 || anyDate.containsMatchIn(text) || otherClock.containsMatchIn(text)) return null
        val match = relative.find(text) ?: return null
        return try {
            val rawHour = match.groupValues[3].toInt()
            val minute = match.groupValues[4].toInt()
            val period = match.groupValues[2]
            val hour = when (period) {
                "上午" -> if (rawHour in 1..12) rawHour % 12 else return null
                "下午", "晚上" -> if (rawHour in 1..12) rawHour % 12 + 12 else return null
                else -> rawHour
            }
            val dayOffset = when (match.groupValues[1]) { "今天" -> 0L; "明天" -> 1L; else -> 2L }
            val local = java.time.Instant.ofEpochMilli(posted).atZone(zone).toLocalDate().plusDays(dayOffset).atTime(hour, minute)
            val offsets = zone.rules.getValidOffsets(local)
            if (offsets.size != 1) return null
            val millis = local.toInstant(offsets.single()).toEpochMilli()
            millis.takeIf { it > now && it - now > 10_000 }
        } catch (_: Exception) { null }
    }
}
