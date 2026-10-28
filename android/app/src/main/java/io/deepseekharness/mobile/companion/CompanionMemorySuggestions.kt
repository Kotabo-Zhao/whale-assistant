package io.deepseekharness.mobile.companion

import org.json.JSONArray

/** Local, untrusted notification excerpts. They are not facts or persisted memories. */
internal object CompanionMemorySuggestions {
    data class Candidate(val id: String, val packageName: String, val time: Long, val excerpt: String)
    // Entire notification body must be a short, explicit first-person preference, not a command or paragraph.
    private val preference = Regex("^我(?:喜欢|偏好|习惯|不喜欢|不吃|不喝)[^\\r\\n。！？!?]{2,80}[。！？!?]?$", RegexOption.DOT_MATCHES_ALL)
    fun extract(text: String): String? {
        val value = text.trim()
        if (value.length > 90 || CompanionStore.sensitive(value) || !preference.matches(value)) return null
        return value
    }
    fun fromRecords(rows: JSONArray, excluded: Set<String>, dismissed: Set<String>): List<Candidate> {
        val result = mutableListOf<Candidate>()
        for (index in rows.length() - 1 downTo 0) {
            val row = rows.optJSONObject(index) ?: continue
            val pkg = row.optString("package")
            if (!CompanionSourcePolicy.validPackage(pkg) || pkg in excluded) continue
            val text = extract(row.optString("text")) ?: continue
            val time = row.optLong("time", 0)
            if (time <= 0) continue
            val id = CompanionNotificationDedup.digest("${pkg.length}:$pkg:$time:$text") ?: continue
            if (id !in dismissed) result.add(Candidate(id, pkg, time, text))
        }
        return result.take(20)
    }
}
