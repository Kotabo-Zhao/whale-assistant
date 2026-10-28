package io.deepseekharness.mobile.companion

import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId

/** Local snapshot; list state is evidence of scheduling, not proof of delivery. */
internal object CompanionBriefReminders {
    const val MAX_ITEMS = 10
    data class Item(val at: Long, val text: String, val state: String)
    data class Snapshot(val items: List<Item>, val truncated: Boolean)
    fun read(rows: JSONArray, recurring: Boolean, now: Long, zone: ZoneId): Snapshot {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val items = mutableListOf<Item>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val key = if (recurring) "nextAt" else "at"
            require(row.opt(key) is Number && row.opt("text") is String && row.opt("state") is String) { "提醒数据无效" }
            val at = row.getLong(key)
            val text = row.getString("text")
            val state = row.getString("state")
            require(at > 0 && text.isNotBlank() && text.length <= 200) { "提醒数据无效" }
            val label = if (recurring) when (state) {
                "scheduled" -> if (row.optBoolean("schedulePending", false)) "等待恢复安排" else if (at <= now) "计划时间已过，状态待核对" else "已安排下一次"
                "failed" -> "安排失败"
                else -> error("重复提醒状态无效")
            } else when (state) {
                "scheduled" -> if (at <= now) "计划时间已过，状态待核对" else "已安排"
                "delivered" -> "已交给系统"
                "missed" -> "已错过"
                "failed" -> "安排或投递失败"
                else -> error("提醒状态无效")
            }
            if (at >= start && at < end) items.add(Item(at, text, label))
        }
        val sorted = items.sortedBy { it.at }
        return Snapshot(sorted.take(MAX_ITEMS), sorted.size > MAX_ITEMS)
    }
}
