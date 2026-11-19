package io.deepseekharness.mobile.companion

import android.content.Context
import android.os.BatteryManager
import io.deepseekharness.mobile.features.WhaleFeatureStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Whale 原生工具注册与直调层（零 PRoot、零 Node、零 DeepSeek Harness 依赖）。
 *
 * 直接在 Android 进程内调度记忆、资料库、主人档案、秘书事项、功能工坊（含置顶）、
 * 手机电池状态与后台 Worker 状态，并按用户输入意图动态裁剪工具 Schema。
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
        val includeMemory = Regex("(记住|记忆|偏好|习惯|我喜欢|上次|档案|memory|prefer)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includeKnowledge = Regex("(资料|笔记|文档|知识库|总结|保存|记录|note|knowledge|doc)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includeSecretary = Regex("(提醒|事项|待办|日程|安排|计划|今天|明天|todo|task|remind|schedule)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includeWorkshop = Regex("(工坊|小工具|小应用|打卡|计时|记账|置顶|创建.*功能|workshop|tool)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includeDevice = Regex("(电量|电池|手机|充电|后台|任务|进度|状态|battery|worker|工单)", RegexOption.IGNORE_CASE).containsMatchIn(text)

        if (includeMemory) {
            array.put(schema(
                name = "whale_memory_search",
                description = "按关键词检索用户本地长期记忆。",
                params = mapOf("query" to "检索关键词"),
                required = listOf("query"),
            ))
            array.put(schema(
                name = "whale_memory_save",
                description = "将用户明确表达的偏好或长期事实保存到本地长期记忆。",
                params = mapOf("text" to "简明完整的记忆内容"),
                required = listOf("text"),
            ))
            array.put(schema(
                name = "whale_owner_profile",
                description = "读取用户的主人档案摘要与已记录偏好。",
                params = emptyMap(),
                required = emptyList(),
            ))
        }
        if (includeKnowledge) {
            array.put(schema(
                name = "whale_knowledge_search",
                description = "按关键词检索用户保存在本地资料库中的文档和笔记。",
                params = mapOf("query" to "检索关键词"),
                required = listOf("query"),
            ))
            array.put(schema(
                name = "whale_knowledge_save",
                description = "将整理好的文字笔记或摘要直接保存到本地资料库。",
                params = mapOf("text" to "要保存的完整正文"),
                required = listOf("text"),
            ))
        }
        if (includeSecretary) {
            array.put(schema(
                name = "whale_secretary_list",
                description = "查询用户的未完成秘书事项与待办列表。",
                params = mapOf("query" to "可选筛选关键词，留空返回全部活跃事项"),
                required = emptyList(),
            ))
            array.put(schema(
                name = "whale_secretary_add",
                description = "为用户新增一条秘书事项/待办记录。",
                params = mapOf("title" to "事项标题", "note" to "可选补充说明"),
                required = listOf("title"),
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
                description = "为鲸鱼功能工坊创作单文件离线 HTML/CSS/JS 小工具；支持 window.whaleStorage.get/set/remove 持久化本地数据。",
                params = mapOf(
                    "id" to "英文小写功能标识，如 habit-tracker",
                    "title" to "中文或英文短标题（<=50字）",
                    "html" to "完整的单文件 HTML/CSS/JS 源码",
                ),
                required = listOf("id", "title", "html"),
            ))
            array.put(schema(
                name = "whale_workshop_pin",
                description = "置顶或取消置顶工坊中某个已启用小工具，置顶后会排在桌宠菜单最前面。",
                params = mapOf("id" to "功能英文 id", "pinned" to "true 表示置顶，false 表示取消置顶"),
                required = listOf("id", "pinned"),
            ))
        }
        if (includeDevice) {
            array.put(schema(
                name = "whale_device_battery",
                description = "读取当前手机电池电量百分比与充电状态。",
                params = emptyMap(),
                required = emptyList(),
            ))
            array.put(schema(
                name = "whale_worker_status",
                description = "查询当前活跃后台 Worker 任务数量与设备控制状态。",
                params = emptyMap(),
                required = emptyList(),
            ))
        }
        return array
    }

    fun isMutating(name: String): Boolean = name in setOf(
        "whale_memory_save",
        "whale_knowledge_save",
        "whale_secretary_add",
        "whale_workshop_draft",
        "whale_workshop_pin",
    )

    fun execute(name: String, argsJson: String): ToolOutcome {
        val args = runCatching { JSONObject(argsJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        return try {
            when (name) {
                "whale_memory_search" -> {
                    val query = args.optString("query", "").trim()
                    val hybrid = if (query.isNotEmpty()) {
                        runCatching {
                            SemanticMemorySearch.execute(app, JSONObject().put("query", query).put("limit", 6).toString())
                        }.getOrNull()
                    } else null
                    val result = hybrid ?: CompanionMemoryStore(app).execute(
                        JSONObject().put("op", "search").put("query", query).toString(),
                        fromPetTurn = true,
                    )
                    ToolOutcome(name, if (hybrid != null) "混合语义检索记忆" else "检索本地记忆", true, result)
                }
                "whale_memory_save" -> {
                    val req = JSONObject()
                        .put("op", "save")
                        .put("text", args.optString("text", "").trim())
                    val result = CompanionMemoryStore(app).execute(req.toString(), fromPetTurn = true)
                    ToolOutcome(name, "保存长期记忆", true, result, mutating = true)
                }
                "whale_owner_profile" -> {
                    val profile = OwnerContext(app).read()
                    ToolOutcome(name, "读取主人档案", true, OwnerContext.prompt(profile))
                }
                "whale_knowledge_search" -> {
                    val query = args.optString("query", "").trim()
                    val hybrid = if (query.isNotEmpty()) {
                        runCatching {
                            SemanticKnowledgeSearch.execute(app, JSONObject().put("query", query).put("limit", 5).toString())
                        }.getOrNull()
                    } else null
                    if (hybrid != null) {
                        ToolOutcome(name, "混合语义检索资料库", true, hybrid)
                    } else {
                        val store = CompanionKnowledgeStore(app)
                        val rows = if (query.isEmpty()) store.recent(0, 12) else store.searchPage(query, 0, 12).first
                        ToolOutcome(name, "检索本地资料库", true, rows.toString())
                    }
                }
                "whale_knowledge_save" -> {
                    val text = args.optString("text", "").trim()
                    val saved = CompanionKnowledgeStore(app).add(text, source = "whale_native_chat")
                    ToolOutcome(name, "保存到本地资料库", saved, JSONObject().put("saved", saved).toString(), mutating = true)
                }
                "whale_secretary_list" -> {
                    val query = args.optString("query", "").trim()
                    val result = SecretaryLedger(app).list(query = query, activeOnly = true, limit = 20)
                    ToolOutcome(name, "查询秘书事项", true, result.toString())
                }
                "whale_secretary_add" -> {
                    val req = JSONObject()
                        .put("action", "add")
                        .put("title", args.optString("title", "").trim())
                        .put("note", args.optString("note", "").trim())
                    val result = SecretaryLedger(app).execute(req, owner = "whale-main")
                    ToolOutcome(name, "记录秘书事项", true, result.toString(), mutating = true)
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
                "whale_workshop_pin" -> {
                    val id = args.optString("id", "").trim()
                    val pinned = args.optString("pinned", "true").equals("true", ignoreCase = true)
                    val result = WhaleFeatureStore(app).setPinned(id, pinned)
                    ToolOutcome(name, if (pinned) "置顶工坊工具" else "取消置顶工具", true, result.toString(), mutating = true)
                }
                "whale_device_battery" -> {
                    val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    val charging = bm?.isCharging == true
                    val payload = JSONObject().put("batteryPercent", level).put("charging", charging).toString()
                    ToolOutcome(name, "读取手机电量", true, payload)
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
