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

    data class Candidate(val packageName: String, val time: Long, val title: String, val text: String, val reason: String)
    private val candidateRules = listOf(
        "可能是日程" to Regex("会议|预约|开会|meeting|appointment", RegexOption.IGNORE_CASE),
        "可能需要取件" to Regex("取件|待取|领取快递|pickup|pick up|parcel ready", RegexOption.IGNORE_CASE),
        "可能需要回复" to Regex("待回复|请回复|请回电|reply requested|please reply", RegexOption.IGNORE_CASE),
    )
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
            val reason = candidateRules.firstOrNull { it.second.containsMatchIn("$title $text") }?.first ?: continue
            output.add(Candidate(source, time, title, text, reason))
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
