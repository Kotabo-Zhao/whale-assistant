package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 程序性记忆（Procedural Skill / Playbook）存储。
 *
 * 把用户反复交代或认可的个人工作流沉淀成可复用规则（例如「发链接时自动提炼 3 点并存资料库」），
 * 每次推理前自动注入动态上下文，让 Agent 越用越懂主人习惯。
 */
internal object WhalePlaybookStore {
    private const val PREFS = "whale_playbooks_v1"
    private const val KEY_ITEMS = "playbooks_json"
    private const val MAX_ITEMS = 20

    data class Playbook(
        val id: String,
        val trigger: String,
        val steps: String,
        val updatedAt: Long,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("id", id)
            .put("trigger", trigger)
            .put("steps", steps)
            .put("updatedAt", updatedAt)

        companion object {
            fun fromJson(obj: JSONObject): Playbook? {
                val id = obj.optString("id").trim()
                val trigger = obj.optString("trigger").trim()
                val steps = obj.optString("steps").trim()
                if (id.isEmpty() || trigger.isEmpty() || steps.isEmpty()) return null
                return Playbook(id, trigger, steps, obj.optLong("updatedAt", 0L))
            }
        }
    }

    @Synchronized
    fun list(context: Context): List<Playbook> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Playbook>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let(Playbook::fromJson)?.let(out::add)
        }
        return out.sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun save(context: Context, trigger: String, steps: String): Playbook {
        val cleanTrigger = trigger.trim().take(60)
        val cleanSteps = steps.trim().take(180)
        require(cleanTrigger.isNotEmpty() && cleanSteps.isNotEmpty()) { "触发条件和执行步骤不能为空" }
        val all = list(context).toMutableList()
        val idx = all.indexOfFirst { it.trigger.equals(cleanTrigger, ignoreCase = true) }
        val item = Playbook(
            id = if (idx >= 0) all[idx].id else "pb-${UUID.randomUUID().toString().take(8)}",
            trigger = cleanTrigger,
            steps = cleanSteps,
            updatedAt = System.currentTimeMillis(),
        )
        if (idx >= 0) all[idx] = item else all.add(0, item)
        val arr = JSONArray()
        all.sortedByDescending { it.updatedAt }.take(MAX_ITEMS).forEach { arr.put(it.json()) }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, arr.toString())
            .apply()
        return item
    }

    fun promptSummary(context: Context, maxItems: Int = 5): String {
        val items = list(context).take(maxItems)
        if (items.isEmpty()) return ""
        return "[个人习惯规则] " + items.joinToString("；") { "当「${it.trigger}」时→${it.steps}" }
    }
}
