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
