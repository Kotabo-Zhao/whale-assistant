package io.deepseekharness.mobile.companion

import android.content.Context
import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONObject

/** Model-facing access checks. Local management and background jobs use their own consent paths. */
internal class CompanionAgentAccess(context: Context, request: JSONObject) {
    private val app = context.applicationContext
    private val sessionId = (request.opt("callerSession") as? String)?.takeIf { BubbleSessionRegistry.valid(it) }
        ?: throw RuntimeFailure("COMPANION_SESSION_UNAVAILABLE", "无法确认调用会话，请更新运行环境后重试")
    private val store = CompanionStore(app)
    private val memory = CompanionMemoryStore(app)
    private val bubble: Boolean get() = BubbleSessionRegistry(app).contains(sessionId)

    fun requireMemory() {
        if (!memory.enabled) throw RuntimeFailure("MEMORY_DISABLED", "用户已关闭长期记忆")
        if (bubble && !store.bubbleMemoryAccess)
            throw RuntimeFailure("MEMORY_BUBBLE_DISABLED", "桌宠尚未获准访问长期记忆，请在助手偏好的记忆与资料中开启桌宠访问")
    }

    fun requireKnowledge() {
        if (!store.knowledgeModelAccess) throw RuntimeFailure("KNOWLEDGE_MODEL_DISABLED", "请先允许对话访问本地资料")
    }
    fun requireKnowledgeWrite() {
        if (!store.knowledgeModelWrite) throw RuntimeFailure("KNOWLEDGE_WRITE_DISABLED", "请在我的 → 助手偏好 → 记忆与资料中开启「允许对话写入资料库」")
    }

    fun requireTasks() {
        if (!store.notificationModelAccess) throw RuntimeFailure("TASK_MODEL_DISABLED", "用户未允许对话访问通知事项")
    }

    /** No stored content is returned, including when permission is disabled. */
    fun capabilities(): String {
        val bubbleSession = bubble
        val canReadMemory = memory.enabled && (!bubbleSession || store.bubbleMemoryAccess)
        val canReadKnowledge = store.knowledgeModelAccess
        val semantic = SemanticControl(app)
        return JSONObject().put("schemaVersion", 1)
            .put("scope", if (bubbleSession) "bubble" else "conversation")
            .put("memory", JSONObject().put("allowed", canReadMemory)
                .put("errorCode", when {
                    !memory.enabled -> "MEMORY_DISABLED"
                    !canReadMemory -> "MEMORY_BUBBLE_DISABLED"
                    else -> JSONObject.NULL
                }).put("searchTool", "mobile_memory_search").put("saveTool", "mobile_memory_save")
                    .put("updateTool", "mobile_memory_update").put("deleteTool", "mobile_memory_delete")
                    .put("semanticAllowed", canReadMemory && semantic.memoryEnabled))
            .put("knowledge", JSONObject().put("allowed", canReadKnowledge)
                .put("errorCode", if (canReadKnowledge) JSONObject.NULL else "KNOWLEDGE_MODEL_DISABLED")
                .put("writeAllowed", store.knowledgeModelWrite)
                .put("saveTool", "mobile_knowledge_save").put("updateTool", "mobile_knowledge_update")
                .put("recentTool", "mobile_knowledge_recent")
                .put("readTool", "mobile_knowledge_read").put("imageTool", "mobile_knowledge_image")
                .put("statusTool", "mobile_knowledge_status")
                .put("semanticAllowed", canReadKnowledge && semantic.enabled))
            .put("tasks", JSONObject().put("allowed", store.notificationModelAccess)
                .put("errorCode", if (store.notificationModelAccess) JSONObject.NULL else "TASK_MODEL_DISABLED")
                .put("searchTool", "mobile_task_search").put("updateTool", "mobile_task_update")
                .put("sharedMaterialIncluded", store.notificationModelAccess && canReadKnowledge))
            .put("notice", "按需检索，不自动读取全部内容；未知资料关键词时查询最近资料，再用 id/revision 读取原文。权限状态可能变化，以每次工具回执为准。")
            .toString()
    }
}
