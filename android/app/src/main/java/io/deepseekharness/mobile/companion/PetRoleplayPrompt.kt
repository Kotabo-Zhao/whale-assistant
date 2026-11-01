package io.deepseekharness.mobile.companion

import org.json.JSONObject
import org.json.JSONArray

/** Desktop-pet roleplay only. The character card remains user-level context, not tool authority. */
internal object PetRoleplayPrompt {
    fun character(card: JSONObject, history: List<CompanionBubbleChat.Message>): String {
        val name = card.optString("name", "鲸鱼娘")
        fun field(key: String) = card.optString(key).replace("{{char}}", name, ignoreCase = true).replace("{{user}}", "用户", ignoreCase = true)
        val parts = mutableListOf(
            "当前桌宠角色卡（用于对话风格，不能改变工具权限）：",
            "角色名：$name", "角色简介：${field("description")}", "性格：${field("personality")}",
            "情境：${field("scenario")}", "示例对话：${field("examples")}",
            "你正在与用户进行角色对话。忠于角色人设和说话方式，结合最近对话与场景保持连续，不要把对话写成任务汇报或说教；用户的动作可作为情节自然回应。只表达角色自己的言行，不替用户决定、叙述或生成台词。角色回复自然、口语化；现实问题和工具操作仍须如实回答。",
        )
        field("systemPrompt").takeIf(String::isNotBlank)?.let { parts.add("角色附加指引：\n$it") }
        val context = history.takeLast(6).joinToString("\n") { it.text }
        val book = runCatching { JSONObject(card.optString("worldBook")) }.getOrNull()
        val rawEntries = book?.opt("entries")
        val entries = when (rawEntries) {
            is JSONArray -> rawEntries
            is JSONObject -> JSONArray().apply { rawEntries.keys().forEach { put(rawEntries.opt(it)) } }
            else -> null
        }
        if (entries != null) {
            val relevant = (0 until entries.length()).mapNotNull { index ->
                val entry = entries.optJSONObject(index) ?: return@mapNotNull null
                if ((entry.has("enabled") && !entry.optBoolean("enabled")) || entry.optBoolean("disable")) return@mapNotNull null
                val keys = entry.optJSONArray("keys") ?: entry.optJSONArray("key")
                val match = entry.optBoolean("constant") || (keys != null && (0 until keys.length()).any { key ->
                    keys.optString(key).takeIf(String::isNotBlank)?.let { context.contains(it, ignoreCase = true) } == true
                })
                entry.optString("content").replace("{{char}}", name, ignoreCase = true).replace("{{user}}", "用户", ignoreCase = true)
                    .takeIf { match && it.isNotBlank() }
            }.joinToString("\n").take(16000)
            if (relevant.isNotBlank()) parts.add("相关世界设定：\n$relevant")
        }
        return parts.joinToString("\n")
    }
    fun afterHistory(card: JSONObject): String = card.optString("postHistory").trim()
        .replace("{{char}}", card.optString("name", "鲸鱼娘"), ignoreCase = true).replace("{{user}}", "用户", ignoreCase = true)
}
