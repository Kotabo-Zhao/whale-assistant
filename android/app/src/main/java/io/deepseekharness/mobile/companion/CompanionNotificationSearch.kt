package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject

/** Local snapshot search only; it never sends or stores the query or notification contents. */
internal object CompanionNotificationSearch {
    data class Match(val row: JSONObject, val sourceName: String)
    fun find(rows: JSONArray, query: String, resolveName: (String) -> String): List<Match> {
        val term = query.trim()
        require(term.isNotEmpty() && term.length <= 100) { "请输入不超过 100 字的关键词" }
        require(rows.length() <= CompanionStore.MAX_RECORDS) { "通知记录过多" }
        val labels = mutableMapOf<String, String>()
        val found = mutableListOf<Match>()
        for (i in rows.length() - 1 downTo 0) {
            val row = rows.getJSONObject(i)
            val pkg = row.getString("package")
            val name = labels.getOrPut(pkg) { runCatching { resolveName(pkg) }.getOrDefault(pkg).ifBlank { pkg } }
            if (listOf(name, pkg, row.getString("title"), row.getString("text")).any { it.contains(term, ignoreCase = true) }) {
                found.add(Match(row, name))
            }
        }
        return found.sortedByDescending { it.row.getLong("time") }
    }
}
