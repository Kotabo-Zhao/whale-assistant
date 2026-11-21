package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 持久化目标图存储（Persistent Goal Graph）。
 *
 * 跨会话、跨天追踪用户的长期目标与子步骤状态，使前台对话、桌宠和后台 Worker
 * 共享同一张目标推进状态机，不再「换个会话就失忆」。
 */
internal object WhaleGoalGraphStore {
    private const val PREFS = "whale_goal_graph_v1"
    private const val KEY_GOALS = "goals_json"
    private const val MAX_GOALS = 30

    data class Goal(
        val id: String,
        val title: String,
        val status: String, // active | blocked | completed | paused
        val nextAction: String,
        val subTasks: List<String>,
        val blockers: String,
        val updatedAt: Long,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("status", status)
            .put("nextAction", nextAction)
            .put("subTasks", JSONArray(subTasks))
            .put("blockers", blockers)
            .put("updatedAt", updatedAt)

        companion object {
            fun fromJson(obj: JSONObject): Goal? {
                val id = obj.optString("id").trim()
                val title = obj.optString("title").trim()
                if (id.isEmpty() || title.isEmpty()) return null
                val subs = ArrayList<String>()
                val arr = obj.optJSONArray("subTasks")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i).trim()
                        if (s.isNotEmpty()) subs.add(s)
                    }
                }
                return Goal(
                    id = id,
                    title = title,
                    status = obj.optString("status", "active").ifBlank { "active" },
                    nextAction = obj.optString("nextAction", "").trim(),
                    subTasks = subs,
                    blockers = obj.optString("blockers", "").trim(),
                    updatedAt = obj.optLong("updatedAt", 0L),
                )
            }
        }
    }

    @Synchronized
    fun list(context: Context, includeCompleted: Boolean = false): List<Goal> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GOALS, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Goal>()
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i)?.let(Goal::fromJson) ?: continue
            if (includeCompleted || g.status != "completed") out.add(g)
        }
        return out.sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun create(
        context: Context,
        title: String,
        nextAction: String = "",
        subTasks: List<String> = emptyList(),
        blockers: String = "",
    ): Goal {
        val cleanTitle = title.trim().take(80)
        require(cleanTitle.isNotEmpty()) { "目标标题不能为空" }
        val all = list(context, includeCompleted = true).toMutableList()
        val existing = all.indexOfFirst { it.title.equals(cleanTitle, ignoreCase = true) && it.status != "completed" }
        val now = System.currentTimeMillis()
        val goal = Goal(
            id = if (existing >= 0) all[existing].id else "goal-${UUID.randomUUID().toString().take(8)}",
            title = cleanTitle,
            status = "active",
            nextAction = nextAction.trim().take(120),
            subTasks = subTasks.map { it.trim().take(80) }.filter(String::isNotEmpty).take(8),
            blockers = blockers.trim().take(120),
            updatedAt = now,
        )
        if (existing >= 0) all[existing] = goal else all.add(0, goal)
        saveAll(context, all)
        return goal
    }

    @Synchronized
    fun update(
        context: Context,
        idOrTitle: String,
        status: String? = null,
        nextAction: String? = null,
        subTasks: List<String>? = null,
        blockers: String? = null,
    ): Goal? {
        val key = idOrTitle.trim()
        if (key.isEmpty()) return null
        val all = list(context, includeCompleted = true).toMutableList()
        val idx = all.indexOfFirst {
            it.id == key || it.title.equals(key, ignoreCase = true) || it.title.contains(key, ignoreCase = true)
        }
        if (idx < 0) return null
        val prev = all[idx]
        val validStatus = status?.trim()?.lowercase()?.takeIf { it in setOf("active", "blocked", "completed", "paused") }
        val updated = prev.copy(
            status = validStatus ?: prev.status,
            nextAction = nextAction?.trim()?.take(120) ?: prev.nextAction,
            subTasks = subTasks?.map { it.trim().take(80) }?.filter(String::isNotEmpty)?.take(8) ?: prev.subTasks,
            blockers = blockers?.trim()?.take(120) ?: prev.blockers,
            updatedAt = System.currentTimeMillis(),
        )
        all[idx] = updated
        saveAll(context, all)
        return updated
    }

    fun promptSummary(context: Context, maxItems: Int = 4): String {
        val active = list(context, includeCompleted = false).take(maxItems)
        if (active.isEmpty()) return ""
        return buildString {
            append("[持续目标图] ")
            append(
                active.joinToString("；") { g ->
                    val next = if (g.nextAction.isNotEmpty()) "→下一步:${g.nextAction}" else ""
                    val blk = if (g.blockers.isNotEmpty()) "(阻塞:${g.blockers})" else ""
                    "${g.title}[${g.status}]$next$blk"
                },
            )
        }
    }

    private fun saveAll(context: Context, goals: List<Goal>) {
        val arr = JSONArray()
        goals.sortedByDescending { it.updatedAt }.take(MAX_GOALS).forEach { arr.put(it.json()) }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GOALS, arr.toString())
            .apply()
    }
}
