package io.deepseekharness.mobile.companion

internal object WorkerAccess {
    fun settingsForReport(row: org.json.JSONObject): CompanionPreferencePage? {
        if (row.optString("state") != "blocked" || row.optBoolean("authorizationRevoked") || row.optBoolean("missingLedger")) return null
        val summary = row.optString("summary")
        return when {
            "KNOWLEDGE_MODEL_DISABLED" in summary -> CompanionPreferencePage.LIBRARY
            "MEMORY_BUBBLE_DISABLED" in summary || "MEMORY_DISABLED" in summary -> CompanionPreferencePage.MEMORY
            "TASK_MODEL_DISABLED" in summary -> CompanionPreferencePage.NOTIFICATIONS
            else -> null
        }
    }
    fun denial(mode: String, needs: String, memory: Boolean, library: Boolean, preset: String): String? = when {
        needs in setOf("library","both") && !library -> "这项工作需要访问本地资料。请先在资料设置中开启对话访问。"
        needs in setOf("memory","both") && !memory -> "这项工作需要访问长期记忆。请开启长期记忆和桌宠记忆访问。"
        mode == "create" && preset == "read-only" -> "生成工具草稿需要可创作的桌宠会话权限。"
        else -> null
    }
}
