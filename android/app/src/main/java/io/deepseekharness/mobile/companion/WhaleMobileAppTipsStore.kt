package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 源自 Mobile-Agent-E (arXiv:2501.11733) 的「按 App 自进化 Tips + Shortcuts 持久化记忆」。
 *
 * - **Tips**：记录在特定 App（按包名隔离）中踩过的坑或高效操作经验（如弹窗关闭位置、输入框聚焦顺序）；
 * - **Shortcuts**：记录可复用的多步原子操作子程序摘要，下次进入同一 App 时注入动态上下文区（不破坏静态 KV 前缀），
 *   让手机操控越用越稳、越用越快。
 */
internal object WhaleMobileAppTipsStore {

    private const val PREFS = "whale_mobile_app_tips_v1"
    private const val MAX_TIPS_PER_APP = 6
    private const val MAX_SHORTCUTS_PER_APP = 4

    data class AppKnowledge(
        val packageName: String,
        val tips: List<String> = emptyList(),
        val shortcuts: List<Pair<String, String>> = emptyList(), // name -> stepsSummary
    ) {
        fun json(): JSONObject = JSONObject()
            .put("packageName", packageName)
            .put("tips", JSONArray(tips))
            .put(
                "shortcuts",
                JSONArray().apply {
                    shortcuts.forEach { (n, s) -> put(JSONObject().put("name", n).put("steps", s)) }
                },
            )
    }

    fun load(context: Context, packageName: String): AppKnowledge {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return AppKnowledge("")
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("pkg_$pkg", null) ?: return AppKnowledge(pkg)
        return parseJson(pkg, raw)
    }

    internal fun parseJson(pkg: String, raw: String): AppKnowledge = runCatching {
        val o = JSONObject(raw)
        val tipsArr = o.optJSONArray("tips")
        val tips = mutableListOf<String>()
        if (tipsArr != null) {
            for (i in 0 until tipsArr.length()) tipsArr.optString(i).takeIf(String::isNotBlank)?.let(tips::add)
        }
        val scArr = o.optJSONArray("shortcuts")
        val shortcuts = mutableListOf<Pair<String, String>>()
        if (scArr != null) {
            for (i in 0 until scArr.length()) {
                val item = scArr.optJSONObject(i) ?: continue
                val n = item.optString("name").trim()
                val s = item.optString("steps").trim()
                if (n.isNotEmpty() && s.isNotEmpty()) shortcuts.add(n to s)
            }
        }
        AppKnowledge(pkg, tips, shortcuts)
    }.getOrElse { AppKnowledge(pkg) }

    internal fun mergeTip(cur: AppKnowledge, tip: String): AppKnowledge {
        val clean = tip.trim().take(120)
        if (clean.isEmpty()) return cur
        val list = cur.tips.toMutableList()
        if (list.any { it.equals(clean, ignoreCase = true) }) return cur
        list.add(clean)
        while (list.size > MAX_TIPS_PER_APP) list.removeAt(0)
        return cur.copy(tips = list)
    }

    internal fun mergeShortcut(cur: AppKnowledge, name: String, stepsSummary: String): AppKnowledge {
        val cleanName = name.trim().take(48)
        val cleanSteps = stepsSummary.trim().take(240)
        if (cleanName.isEmpty() || cleanSteps.isEmpty()) return cur
        val list = cur.shortcuts.filterNot { it.first.equals(cleanName, ignoreCase = true) }.toMutableList()
        list.add(cleanName to cleanSteps)
        while (list.size > MAX_SHORTCUTS_PER_APP) list.removeAt(0)
        return cur.copy(shortcuts = list)
    }

    fun recordTip(context: Context, packageName: String, tip: String): AppKnowledge {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return AppKnowledge("")
        val next = mergeTip(load(context, pkg), tip)
        save(context, next)
        return next
    }

    fun recordShortcut(context: Context, packageName: String, name: String, stepsSummary: String): AppKnowledge {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return AppKnowledge("")
        val next = mergeShortcut(load(context, pkg), name, stepsSummary)
        save(context, next)
        return next
    }

    fun buildDynamicPrompt(knowledge: AppKnowledge): String {
        if (knowledge.tips.isEmpty() && knowledge.shortcuts.isEmpty()) return ""
        return buildString {
            append("[当前应用自进化操作经验（").append(knowledge.packageName).append("）]\n")
            if (knowledge.tips.isNotEmpty()) {
                append("- 避坑 Tips：").append(knowledge.tips.takeLast(3).joinToString("；")).append("\n")
            }
            if (knowledge.shortcuts.isNotEmpty()) {
                append("- 可复用 Shortcuts：")
                append(knowledge.shortcuts.takeLast(2).joinToString("；") { "${it.first}（${it.second}）" })
            }
        }.trim()
    }

    private fun save(context: Context, knowledge: AppKnowledge) {
        if (knowledge.packageName.isBlank()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("pkg_${knowledge.packageName}", knowledge.json().toString())
            .apply()
    }
}
