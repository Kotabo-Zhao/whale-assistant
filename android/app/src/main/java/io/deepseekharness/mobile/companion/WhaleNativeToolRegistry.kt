package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import io.deepseekharness.mobile.accessibility.AccessibilityAutomationPolicy
import io.deepseekharness.mobile.accessibility.DeepSeekAccessibilityService
import io.deepseekharness.mobile.accessibility.InstalledApplications
import io.deepseekharness.mobile.features.WhaleFeatureStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Whale 100% 原生工具注册与直调层（零 PRoot、零 Node、零 DeepSeek Harness 依赖）。
 *
 * 完整覆盖五大核心能力域：
 * 1. 长期记忆与主人档案（混合 BGE 语义 + SQLite）
 * 2. 本地资料库与秘书待办
 * 3. 本地多文件代码编写与功能工坊离线软件创作（含 window.whaleStorage 持久化与置顶）
 * 4. 原生手机操控（应用检索/启动、无障碍界面树、受控点击/输入/返回，保留系统级敏感界面保护）
 * 5. 设备状态与原生后台 Worker 并发池
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
    private val codeWorkspace by lazy { WhaleNativeCodeWorkspace(File(app.filesDir, "whale-projects")) }

    /** 按本轮用户输入动态选择最小必需工具集（OpenAI Function Calling 格式）。 */
    fun toolSchemas(userText: String): JSONArray {
        val text = userText.trim()
        val array = JSONArray()
        val includeMemory = Regex("(记住|记忆|偏好|习惯|我喜欢|上次|档案|memory|prefer)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includeKnowledge = Regex("(资料|笔记|文档|知识库|总结|保存|记录|note|knowledge|doc)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includeSecretary = Regex("(提醒|事项|待办|日程|安排|计划|今天|明天|todo|task|remind|schedule)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includeWorkshop = Regex("(工坊|小工具|小应用|打卡|计时|记账|置顶|创建.*功能|做个.*软件|workshop|tool)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includeCode = Regex("(写代码|编程|项目|源码|文件|函数|重构|搜索代码|code|project|src|html|js|ts|kotlin|python|json)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includePhone = Regex("(打开|启动|点击|输入|滑动|返回|看看屏幕|当前页面|装了哪些|应用|app|微信|设置|操作手机|screen|click|launch)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includeDevice = Regex("(电量|电池|手机|充电|后台|任务|进度|状态|battery|worker|工单)", RegexOption.IGNORE_CASE).containsMatchIn(text)

        if (includeMemory) {
            array.put(schema("whale_memory_search", "按关键词混合语义检索用户本地长期记忆。", mapOf("query" to "检索关键词"), listOf("query")))
            array.put(schema("whale_memory_save", "将用户明确表达的偏好或长期事实保存到本地长期记忆。", mapOf("text" to "简明完整的记忆内容"), listOf("text")))
            array.put(schema("whale_owner_profile", "读取用户的主人档案摘要与已记录偏好。", emptyMap(), emptyList()))
        }
        if (includeKnowledge) {
            array.put(schema("whale_knowledge_search", "混合语义检索用户保存在本地资料库中的文档和笔记。", mapOf("query" to "检索关键词"), listOf("query")))
            array.put(schema("whale_knowledge_save", "将整理好的文字笔记或摘要直接保存到本地资料库。", mapOf("text" to "要保存的完整正文"), listOf("text")))
        }
        if (includeSecretary) {
            array.put(schema("whale_secretary_list", "查询用户的未完成秘书事项与待办列表。", mapOf("query" to "可选筛选关键词"), emptyList()))
            array.put(schema("whale_secretary_add", "为用户新增一条秘书事项/待办记录。", mapOf("title" to "事项标题", "note" to "可选补充说明"), listOf("title")))
        }
        if (includeWorkshop) {
            array.put(schema("whale_workshop_list", "列出鲸鱼功能工坊中已启用的本地离线小工具（置顶优先）。", emptyMap(), emptyList()))
            array.put(schema(
                "whale_workshop_draft",
                "为鲸鱼功能工坊创作单文件离线 HTML/CSS/JS 可运行软件；支持 window.whaleStorage.get/set/remove 持久化本地数据。",
                mapOf("id" to "英文小写功能标识", "title" to "短标题(<=50字)", "html" to "完整单文件 HTML/CSS/JS 源码"),
                listOf("id", "title", "html"),
            ))
            array.put(schema("whale_workshop_pin", "置顶或取消置顶工坊中某个已启用小工具。", mapOf("id" to "功能英文 id", "pinned" to "true 或 false"), listOf("id", "pinned")))
        }
        if (includeCode) {
            array.put(schema("whale_code_list", "列出本地原生代码项目中的目录与文件。", mapOf("project" to "项目名", "path" to "可选子目录路径"), listOf("project")))
            array.put(schema("whale_code_read", "按行读取本地原生代码项目中的源码文件。", mapOf("project" to "项目名", "path" to "文件相对路径", "startLine" to "起始行(默认1)", "endLine" to "结束行(默认200)"), listOf("project", "path")))
            array.put(schema("whale_code_write", "在本地原生代码项目中创建或修改源码文件。", mapOf("project" to "项目名", "path" to "文件相对路径", "content" to "完整文件源码"), listOf("project", "path", "content")))
            array.put(schema("whale_code_search", "在本地原生代码项目中跨文件检索代码片段。", mapOf("project" to "项目名", "query" to "搜索词"), listOf("project", "query")))
        }
        if (includePhone) {
            array.put(schema("whale_phone_apps", "按名称或拼音检索手机已安装应用及包名。", mapOf("query" to "应用名或关键词"), listOf("query")))
            array.put(schema("whale_phone_launch", "在手机上启动指定包名的目标应用。", mapOf("packageName" to "目标应用完整包名"), listOf("packageName")))
            array.put(schema("whale_phone_ui_tree", "通过原生无障碍服务读取当前屏幕控件树（自动避开密码/支付/系统敏感界面）。", emptyMap(), emptyList()))
            array.put(schema(
                "whale_phone_action",
                "通过原生无障碍服务在当前屏幕执行受控点击、输入或滚动。",
                mapOf("packageName" to "目标应用包名", "action" to "click / input / scroll_forward / scroll_backward", "viewId" to "控件 viewId（可选）", "text" to "输入文本或目标文字（可选）"),
                listOf("packageName", "action"),
            ))
            array.put(schema("whale_phone_back", "在手机当前界面执行一次原生返回操作。", emptyMap(), emptyList()))
        }
        if (includeDevice) {
            array.put(schema("whale_device_battery", "读取当前手机电池电量百分比与充电状态。", emptyMap(), emptyList()))
            array.put(schema("whale_worker_status", "查询当前活跃后台 Worker 任务数量与设备控制状态。", emptyMap(), emptyList()))
        }
        return array
    }

    fun isMutating(name: String): Boolean = name in setOf(
        "whale_memory_save",
        "whale_knowledge_save",
        "whale_secretary_add",
        "whale_workshop_draft",
        "whale_workshop_pin",
        "whale_code_write",
        "whale_phone_launch",
        "whale_phone_action",
        "whale_phone_back",
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
                    val req = JSONObject().put("op", "save").put("text", args.optString("text", "").trim())
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
                    ToolOutcome(name, "保存工坊软件草稿", true, result.toString(), mutating = true)
                }
                "whale_workshop_pin" -> {
                    val id = args.optString("id", "").trim()
                    val pinned = args.optString("pinned", "true").equals("true", ignoreCase = true)
                    val result = WhaleFeatureStore(app).setPinned(id, pinned)
                    ToolOutcome(name, if (pinned) "置顶工坊工具" else "取消置顶工具", true, result.toString(), mutating = true)
                }
                "whale_code_list" -> {
                    val res = codeWorkspace.listFiles(args.optString("project", ""), args.optString("path", ""))
                    ToolOutcome(name, "浏览本地代码目录", true, res.toString())
                }
                "whale_code_read" -> {
                    val startLine = args.optString("startLine", "1").toIntOrNull() ?: 1
                    val endLine = args.optString("endLine", "200").toIntOrNull() ?: 200
                    val res = codeWorkspace.readFile(args.optString("project", ""), args.optString("path", ""), startLine, endLine)
                    ToolOutcome(name, "读取本地源码", true, res.toString())
                }
                "whale_code_write" -> {
                    val res = codeWorkspace.writeFile(args.optString("project", ""), args.optString("path", ""), args.optString("content", ""))
                    ToolOutcome(name, "写入本地代码文件", true, res.toString(), mutating = true)
                }
                "whale_code_search" -> {
                    val res = codeWorkspace.searchCode(args.optString("project", ""), args.optString("query", ""))
                    ToolOutcome(name, "检索本地代码", true, res.toString())
                }
                "whale_phone_apps" -> {
                    val res = InstalledApplications.list(app, args.optString("query", "").trim(), 0)
                    ToolOutcome(name, "检索已安装应用", true, res.toString())
                }
                "whale_phone_launch" -> {
                    val pkg = args.optString("packageName", "").trim()
                    require(AccessibilityAutomationPolicy.validPackage(pkg)) { "包名无效或属于受保护系统组件" }
                    val intent = app.packageManager.getLaunchIntentForPackage(pkg)
                        ?: throw IllegalStateException("找不到应用启动入口：$pkg")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    app.startActivity(intent)
                    ToolOutcome(name, "启动手机应用", true, JSONObject().put("launched", pkg).toString(), mutating = true)
                }
                "whale_phone_ui_tree" -> {
                    val service = DeepSeekAccessibilityService.current()
                        ?: throw IllegalStateException("无障碍服务未开启，请先在「我的 → 手机能力」中开启鲸鱼助手无障碍服务")
                    val res = service.execute("accessibilityTree", "")
                    ToolOutcome(name, "读取当前屏幕控件树", res.ok, res.output)
                }
                "whale_phone_action" -> {
                    val service = DeepSeekAccessibilityService.current()
                        ?: throw IllegalStateException("无障碍服务未开启，请先在「我的 → 手机能力」中开启鲸鱼助手无障碍服务")
                    val res = service.execute("accessibilityAction", args.toString())
                    ToolOutcome(name, "执行原生屏控操作", res.ok, res.output, mutating = true)
                }
                "whale_phone_back" -> {
                    val service = DeepSeekAccessibilityService.current()
                        ?: throw IllegalStateException("无障碍服务未开启，请先在「我的 → 手机能力」中开启鲸鱼助手无障碍服务")
                    val res = service.execute("accessibilityBack", "")
                    ToolOutcome(name, "执行返回键", res.ok, res.output, mutating = true)
                }
                "whale_device_battery" -> {
                    val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    val charging = bm?.isCharging == true
                    ToolOutcome(name, "读取手机电量", true, JSONObject().put("batteryPercent", level).put("charging", charging).toString())
                }
                "whale_worker_status" -> {
                    val active = WorkerDashboardStore(app).activeCount() + WhaleNativeWorkerPool.activeCount()
                    val phase = io.deepseekharness.mobile.DeviceControlState.current.phase
                    val payload = JSONObject()
                        .put("activeWorkers", active)
                        .put("nativeWorkers", WhaleNativeWorkerPool.snapshot())
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
