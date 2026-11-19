package io.deepseekharness.mobile.companion

import android.content.Context
import io.deepseekharness.mobile.features.WhaleFeatureStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Whale 原生工具注册与直调层（零 PRoot、零 Node、零 DeepSeek Harness 依赖）。
 *
 * 直接在 Android 进程内调度记忆、资料、主人档案、功能工坊与后台 Worker 状态，
 * 并按用户输入意图动态裁剪工具 Schema，避免主对话携带几十个无关工具定义。
 */
internal class WhaleNativeToolRegistry(private val context: Context) {
    data class ToolOutcome(
        val tool: String,
        val label: String,
        val ok: Boolean,
        val payload: String,
        val mutating: Boolean = false,
    )

    private val app = context.applicationContext

    /** 按本轮用户输入动态选择最小必需工具集（OpenAI Function Calling 格式）。 */
    fun toolSchemas(userText: String): JSONArray {
        val text = userText.trim()
        val array = JSONArray()
        val includeMemory = Regex("(记住|记忆|偏好|习惯|我喜欢|上次|档案|提醒|事项|memory|remind)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includeWorkshop = Regex("(工坊|小工具|小应用|打卡|计时|记账|创建.*功能|workshop|tool)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includeWorker = Regex("(后台|任务|进度|状态|worker|工单)", RegexOption.IGNORE_CASE).containsMatchIn(text)

        if (includeMemory) {
            array.put(schema(
                name = "whale_memory_search",
                description = "检索用户本地长期记忆（按关键词）。",
                params = mapOf("query" to "检索关键词"),
                required = listOf("query"),
            ))
            array.put(schema(
                name = "whale_owner_profile",
                description = "读取用户的主人档案摘要与已记录偏好。",
                params = emptyMap(),
                required = emptyList(),
            ))
        }
        if (includeWorkshop) {
            array.put(schema(
                name = "whale_workshop_list",
                description = "列出鲸鱼功能工坊中已启用的本地离线小工具（置顶优先）。",
                params = emptyMap(),
                required = emptyList(),
            ))
            array.put(schema(
                name = "whale_workshop_draft",
                description = "为鲸鱼功能工坊创作一个单文件离线 HTML/CSS/JS 小工具；可使用 window.whaleStorage 持久化数据。",
                params = mapOf(
                    "id" to "英文小写功能标识，如 habit-tracker",
                    "title" to "中文或英文短标题（<=50字）",
                    "html" to "完整的单文件 HTML/CSS/JS 源码",
                ),
                required = listOf("id", "title", "html"),
            ))
        }
        if (includeWorker) {
            array.put(schema(
                name = "whale_worker_status",
                description = "查询当前活跃后台 Worker 任务数量与设备控制状态。",
                params = emptyMap(),
                required = emptyList(),
            ))
        }
        return array
    }

    fun isMutating(name: String): Boolean = name == "whale_workshop_draft"

    fun execute(name: String, argsJson: String): ToolOutcome {
        val args = runCatching { JSONObject(argsJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        return try {
            when (name) {
                "whale_memory_search" -> {
                    val query = args.optString("query", "").trim()
                    val store = CompanionMemoryStore(app)
                    val rows = store.search(query)
                    ToolOutcome(name, "检索本地记忆", true, rows.toString())
                }
                "whale_owner_profile" -> {
                    val profile = OwnerContext(app).read()
                    ToolOutcome(name, "读取主人档案", true, OwnerContext.prompt(profile))
                }
                "whale_workshop_list" -> {
                    val tools = WhaleFeatureStore(app).activeTools()
                    val arr = JSONArray()
                    tools.forEach { arr.put(it.json()) }
                    ToolOutcome(name, "读取功能工坊列表", true, arr.toString())
                }
                "whale_workshop_draft" -> {
                    val result = WhaleFeatureStore(app).draft(args)
                    ToolOutcome(name, "保存工坊工具草稿", true, result.toString(), mutating = true)
                }
                "whale_worker_status" -> {
                    val active = WorkerDashboardStore(app).activeCount()
                    val phase = io.deepseekharness.mobile.DeviceControlState.current.phase
                    val payload = JSONObject()
                        .put("activeWorkers", active)
                        .put("deviceControlPhase", phase)
                        .toString()
                    ToolOutcome(name, "检查后台任务状态", true, payload)
                }
                else -> ToolOutcome(name, "未知工具", false, JSONObject().put("error", "UNKNOWN_TOOL").toString())
            }
        } catch (error: Throwable) {
            ToolOutcome(
                name,
                "工具调用失败",
                false,
                JSONObject().put("error", error.message ?: error.javaClass.simpleName).toString(),
                mutating = isMutating(name),
            )
        }
    }

    private fun schema(
        name: String,
        description: String,
        params: Map<String, String>,
        required: List<String>,
    ): JSONObject {
        val props = JSONObject()
        for ((k, desc) in params) {
            props.put(k, JSONObject().put("type", "string").put("description", desc))
        }
        val req = JSONArray()
        required.forEach { req.put(it) }
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", props)
                            .put("required", req),
                    ),
            )
    }
}
