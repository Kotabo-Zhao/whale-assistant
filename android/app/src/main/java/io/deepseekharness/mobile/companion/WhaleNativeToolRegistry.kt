package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import io.deepseekharness.mobile.AppLanguage
import io.deepseekharness.mobile.AppThemePreference
import io.deepseekharness.mobile.OverlayBallService
import io.deepseekharness.mobile.accessibility.AccessibilityAutomationPolicy
import io.deepseekharness.mobile.accessibility.DeepSeekAccessibilityService
import io.deepseekharness.mobile.accessibility.InstalledApplications
import io.deepseekharness.mobile.features.WhaleFeatureActivity
import io.deepseekharness.mobile.features.WhaleFeatureStore
import io.deepseekharness.mobile.overlay.OverlayBallPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Whale 100% 原生全软件控制工具注册与直调层（零 PRoot、零 Node、零 DeepSeek Harness 依赖）。
 *
 * 共注册 37 项进程内原生工具，赋予 Agent 对整个软件与受控手机能力的完全控制权：
 * 1. 全软件导航、双套设计风格（极简·类Grok / 鲸鱼娘·可爱）、明暗主题、语言与全部伴侣/自治设置
 * 2. 长期记忆、主人档案、角色人设卡片与多模态本地资料库
 * 3. 秘书待办全生命周期（查询 / 新增 / 完成 / 删除）
 * 4. 功能工坊全生命周期（列表 / 创作草稿 / 立即启用 / 直接打开运行 / 置顶）
 * 5. 本地多文件代码工程（项目列表 / 目录 / 精读 / 写入 / 精准补丁 / 删除 / 搜索）
 * 6. 悬浮桌宠启停与尺寸位置控制
 * 7. 原生手机操控（应用检索/启动、无障碍界面树、点击/输入/滑动/返回/全局手势，坚守系统级敏感界面保护）
 * 8. 电池状态与后台原生 Worker 并发状态
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

    /** 按本轮用户输入动态选择工具集；同时始终允许全软件控制意图命中。 */
    fun toolSchemas(userText: String): JSONArray {
        val text = userText.trim()
        val array = JSONArray()
        val includeAppControl = Regex(
            "(跳转|打开.*页|切到|去.*页|主题|深色|浅色|暗黑|风格|极简|grok|可爱|鲸鱼娘|语言|英文|中文|设置|开关|自治|流畅|平衡|严格|动效|静音|通知|备份|navigate|theme|style|dark|light|setting|language)",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(text)
        val includePersona = Regex("(人设|角色|性格|称呼|persona|character)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includePet = Regex("(桌宠|悬浮球|悬浮窗|宠物|大小|位置|隐藏桌宠|开启桌宠|关闭桌宠|pet|overlay|ball)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includeMemory = Regex("(记住|记忆|偏好|习惯|我喜欢|上次|档案|memory|prefer)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includeKnowledge = Regex("(资料|笔记|文档|知识库|总结|保存|记录|note|knowledge|doc)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includeSecretary = Regex("(提醒|事项|待办|日程|安排|计划|完成|勾掉|删掉.*事项|今天|明天|todo|task|remind|schedule)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includeWorkshop = Regex("(工坊|小工具|小应用|打卡|计时|记账|置顶|启用|运行.*工具|创建.*功能|做个.*软件|workshop|tool)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includeCode = Regex("(写代码|编程|项目|源码|文件|函数|重构|补丁|修改代码|删除文件|搜索代码|code|project|src|html|js|ts|kotlin|python|json)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includePhone = Regex("(打开|启动|点击|输入|滑动|返回|看看屏幕|当前页面|装了哪些|应用|app|微信|设置|操作手机|无障碍|shizuku|副屏|screen|click|launch)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val includeDevice = Regex("(电量|电池|手机|充电|后台|任务|进度|状态|battery|worker|工单)", RegexOption.IGNORE_CASE).containsMatchIn(text)

        if (includeAppControl) {
            array.put(schema(
                "whale_app_navigate",
                "将鲸鱼助手界面跳转到指定页面。",
                mapOf("view" to "目标页面：whale-chat / assistant-memory / assistant-tasks / tools / settings / sessions / files / terminal"),
                listOf("view"),
            ))
            array.put(schema(
                "whale_app_appearance",
                "实时修改整个软件的视觉风格、明暗主题、强调色或减少动效开关。",
                mapOf(
                    "style" to "可选：simple（极简·类Grok）或 cute（鲸鱼娘·可爱）",
                    "theme" to "可选：system / light / dark",
                    "accent" to "可选：ocean / mint / amber / rose",
                    "reduceMotion" to "可选：true 或 false",
                ),
                emptyList(),
            ))
            array.put(schema(
                "whale_app_language",
                "实时切换整个软件的界面语言。",
                mapOf("language" to "zh-CN 或 en"),
                listOf("language"),
            ))
            array.put(schema("whale_app_settings_get", "读取整个软件当前的主题、风格、语言、自治模式、记忆/通知开关及悬浮桌宠状态。", emptyMap(), emptyList()))
            array.put(schema(
                "whale_app_settings_set",
                "修改鲸鱼助手任意伴侣开关、自治模式或布局设置。",
                mapOf(
                    "autonomyPreset" to "可选：fluent（流畅）/ balanced（平衡）/ strict（严格）",
                    "key" to "可选单项配置键，如 backgroundUnderstanding / backgroundAutoMemory / notificationBubbles / automaticNotificationMemory / automaticAssistantMemory / automaticUsageMemory / proactiveMemoryChat / modelInitiative / contextFollowup / bubbleMemoryAccess / reduceMotion",
                    "value" to "对应的布尔值 true/false 或字符串值",
                ),
                emptyList(),
            ))
        }
        if (includePersona) {
            array.put(schema("whale_persona_list", "列出全部助手角色人设卡片及当前激活人设。", emptyMap(), emptyList()))
            array.put(schema(
                "whale_persona_manage",
                "新增/修改、切换激活或删除助手角色人设卡片。",
                mapOf(
                    "action" to "save / activate / delete",
                    "id" to "人设 id（activate/delete 必填，save 可选）",
                    "name" to "人设名称（save 必填）",
                    "prompt" to "人设设定与说话风格（save 可选）",
                ),
                listOf("action"),
            ))
        }
        if (includePet) {
            array.put(schema(
                "whale_pet_control",
                "控制全屏悬浮桌宠：启动、隐藏关闭、调整尺寸或重置屏幕位置。",
                mapOf(
                    "action" to "start / stop / resize / reset_position / state",
                    "sizeDp" to "resize 时可选尺寸：48 / 56 / 64 / 72",
                    "state" to "state 时可选桌宠状态：idle / listening / thinking / happy",
                ),
                listOf("action"),
            ))
        }
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
            array.put(schema("whale_secretary_complete", "将指定编号的秘书事项标记为已完成。", mapOf("id" to "事项 UUID 编号"), listOf("id")))
            array.put(schema("whale_secretary_delete", "删除指定编号的秘书事项。", mapOf("id" to "事项 UUID 编号"), listOf("id")))
        }
        if (includeWorkshop) {
            array.put(schema("whale_workshop_list", "列出鲸鱼功能工坊中已启用的本地离线小工具（置顶优先）。", emptyMap(), emptyList()))
            array.put(schema(
                "whale_workshop_draft",
                "为鲸鱼功能工坊创作单文件离线 HTML/CSS/JS 可运行软件；支持 window.whaleStorage.get/set/remove 持久化本地数据。",
                mapOf("id" to "英文小写功能标识", "title" to "短标题(<=50字)", "html" to "完整单文件 HTML/CSS/JS 源码"),
                listOf("id", "title", "html"),
            ))
            array.put(schema("whale_workshop_activate", "将刚创建的工坊草稿立即发布并启用为正式小工具。", mapOf("id" to "功能英文 id", "revision" to "草稿返回的 revision 哈希"), listOf("id", "revision")))
            array.put(schema("whale_workshop_open", "在屏幕上直接打开某个已启用的工坊小工具，或打开工坊总览页。", mapOf("id" to "可选功能英文 id（留空则打开工坊库）"), emptyList()))
            array.put(schema("whale_workshop_pin", "置顶或取消置顶工坊中某个已启用小工具。", mapOf("id" to "功能英文 id", "pinned" to "true 或 false"), listOf("id", "pinned")))
        }
        if (includeCode) {
            array.put(schema("whale_code_projects", "列出本地原生代码工作区中已有的全部项目及其文件数与体积。", emptyMap(), emptyList()))
            array.put(schema("whale_code_list", "列出本地原生代码项目中的目录与文件。", mapOf("project" to "项目名", "path" to "可选子目录路径"), listOf("project")))
            array.put(schema("whale_code_read", "按行读取本地原生代码项目中的源码文件。", mapOf("project" to "项目名", "path" to "文件相对路径", "startLine" to "起始行(默认1)", "endLine" to "结束行(默认200)"), listOf("project", "path")))
            array.put(schema("whale_code_write", "在本地原生代码项目中创建或覆盖写入源码文件。", mapOf("project" to "项目名", "path" to "文件相对路径", "content" to "完整文件源码"), listOf("project", "path", "content")))
            array.put(schema("whale_code_patch", "在本地源码文件中精准替换唯一匹配的代码片段（比整文件重写更省 token）。", mapOf("project" to "项目名", "path" to "文件相对路径", "oldText" to "原始唯一代码片段", "newText" to "替换后的代码片段"), listOf("project", "path", "oldText", "newText")))
            array.put(schema("whale_code_delete", "安全删除本地代码项目中的单个文件。", mapOf("project" to "项目名", "path" to "文件相对路径"), listOf("project", "path")))
            array.put(schema("whale_code_search", "在本地原生代码项目中跨文件检索代码片段。", mapOf("project" to "项目名", "query" to "搜索词"), listOf("project", "query")))
        }
        if (includePhone) {
            array.put(schema("whale_phone_apps", "按名称或拼音检索手机已安装应用及包名。", mapOf("query" to "应用名或关键词"), listOf("query")))
            array.put(schema("whale_phone_launch", "在手机上启动指定包名的目标应用。", mapOf("packageName" to "目标应用完整包名"), listOf("packageName")))
            array.put(schema("whale_phone_ui_tree", "极速读取当前屏幕精简控件树（自动剪枝布局噪声、分配 #ref 编号与中心坐标，自动屏蔽密码/支付/系统敏感界面）。", emptyMap(), emptyList()))
            array.put(schema(
                "whale_phone_action",
                "通过高性能屏控引擎执行点击、长按、输入、滑动、返回、桌面或最近任务；支持 #ref、viewId、可见文本或坐标，自动手势回退兼容 Flutter/Compose/WebView。",
                mapOf(
                    "action" to "click / long_click / input / swipe / back / home / recents / wait",
                    "ref" to "可选：控件树返回的整数 r 编号（最快 O(1) 命中）",
                    "text" to "可选：目标按钮文字或要输入的文本",
                    "viewId" to "可选：控件 viewId 或短后缀",
                    "direction" to "可选（swipe 时）：up / down / left / right",
                    "x" to "可选：像素 X 坐标",
                    "y" to "可选：像素 Y 坐标",
                ),
                listOf("action"),
            ))
            array.put(schema(
                "whale_phone_batch",
                "单轮原子执行最多 8 步连贯手机操作并在结束后自动回传最新精简控件树，大幅降低多步屏控延迟。",
                mapOf("steps" to "JSON 数组字符串，例如 [{action: click, text: 搜索}, {action: input, text: 鲸鱼}]"),
                listOf("steps"),
            ))
            array.put(schema("whale_phone_back", "在手机当前界面执行一次原生返回操作。", emptyMap(), emptyList()))
            array.put(schema(
                "whale_phone_global",
                "执行原生屏控坐标点击、文本输入，或查询无障碍服务就绪状态。",
                mapOf("command" to "status / tap / inputText", "param" to "可选 JSON 参数"),
                listOf("command"),
            ))
        }
        if (includeDevice) {
            array.put(schema("whale_device_battery", "读取当前手机电池电量百分比与充电状态。", emptyMap(), emptyList()))
            array.put(schema("whale_worker_status", "查询后台 Worker 任务执行状态、完整回传结果与最近简报。", mapOf("id" to "可选：指定某个 Worker 任务 ID 以读取完整结果"), emptyList()))
            array.put(schema(
                "whale_worker_submit",
                "派发一个后台 Worker 异步执行长耗时任务（写代码、整理资料、多步查证或后台手机任务），桌宠与主界面均拥有完全派发权限。",
                mapOf("task" to "完整清晰的后台任务说明", "title" to "可选简短任务标题", "mode" to "可选模式：create / read / device，默认 create"),
                listOf("task"),
            ))
            array.put(schema("whale_worker_cancel", "取消一个正在运行的原生后台 Worker 任务。", mapOf("id" to "Worker 任务 ID"), listOf("id")))
        }
        if (array.length() == 0) {
            // 保底核心工具集：避免任意自然口语问句被关键词漏判导致“无工具可用”
            array.put(schema("whale_memory_search", "按关键词混合语义检索用户本地长期记忆。", mapOf("query" to "检索关键词"), listOf("query")))
            array.put(schema("whale_memory_save", "将用户明确表达的偏好或长期事实保存到本地长期记忆。", mapOf("text" to "简明完整的记忆内容"), listOf("text")))
            array.put(schema("whale_knowledge_search", "混合语义检索用户保存在本地资料库中的文档和笔记。", mapOf("query" to "检索关键词"), listOf("query")))
            array.put(schema("whale_secretary_list", "查询用户的未完成秘书事项与待办列表。", mapOf("query" to "可选筛选关键词"), emptyList()))
            array.put(schema("whale_secretary_add", "为用户新增一条秘书事项/待办记录。", mapOf("title" to "事项标题", "note" to "可选补充说明"), listOf("title")))
            array.put(schema("whale_app_navigate", "将鲸鱼助手界面跳转到指定页面。", mapOf("view" to "目标页面：whale-chat / assistant-memory / assistant-tasks / tools / settings"), listOf("view")))
            array.put(schema("whale_app_appearance", "实时修改整个软件的视觉风格、明暗主题或强调色。", mapOf("style" to "simple 或 cute", "theme" to "system / light / dark"), emptyList()))
            array.put(schema("whale_phone_apps", "按名称或拼音检索手机已安装应用及包名。", mapOf("query" to "应用名或关键词"), listOf("query")))
            array.put(schema("whale_phone_launch", "在手机上启动指定包名的目标应用。", mapOf("packageName" to "目标应用完整包名"), listOf("packageName")))
            array.put(schema("whale_phone_ui_tree", "极速读取当前屏幕精简控件树。", emptyMap(), emptyList()))
            array.put(schema("whale_phone_action", "执行点击、输入、滑动、返回等原生屏控操作。", mapOf("action" to "click / input / swipe / back / home", "ref" to "可选 r 编号", "text" to "可选目标文字"), listOf("action")))
            array.put(schema("whale_worker_submit", "派发后台 Worker 异步执行长耗时任务（桌宠与主界面同等完全权限）。", mapOf("task" to "后台任务说明", "title" to "可选短标题"), listOf("task")))
            array.put(schema("whale_worker_status", "查询后台 Worker 任务执行状态。", emptyMap(), emptyList()))
        }
        return array
    }

    fun isMutating(name: String): Boolean = name in setOf(
        "whale_worker_submit",
        "whale_worker_cancel",
        "whale_app_navigate",
        "whale_app_appearance",
        "whale_app_language",
        "whale_app_settings_set",
        "whale_persona_manage",
        "whale_pet_control",
        "whale_memory_save",
        "whale_knowledge_save",
        "whale_secretary_add",
        "whale_secretary_complete",
        "whale_secretary_delete",
        "whale_workshop_draft",
        "whale_workshop_activate",
        "whale_workshop_open",
        "whale_workshop_pin",
        "whale_code_write",
        "whale_code_patch",
        "whale_code_delete",
        "whale_phone_launch",
        "whale_phone_action",
        "whale_phone_batch",
        "whale_phone_back",
        "whale_phone_global",
    )

    fun execute(name: String, argsJson: String): ToolOutcome {
        val args = runCatching { JSONObject(argsJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        return try {
            when (name) {
                "whale_app_navigate" -> {
                    val view = args.str("view", "").trim()
                    val allowed = setOf(
                        "whale-chat",
                        "conversation",
                        "assistant-memory",
                        "assistant-tasks",
                        "tools",
                        "settings",
                        "sessions",
                        "files",
                        "terminal",
                    )
                    require(view in allowed) { "不支持的目标页面：$view" }
                    WhaleAppControlBus.emit(JSONObject().put("action", "navigate").put("view", view))
                    ToolOutcome(name, "切换软件页面至 $view", true, JSONObject().put("ok", true).put("view", view).toString(), mutating = true)
                }
                "whale_app_appearance" -> {
                    val style = args.str("style", "").trim().lowercase()
                    val theme = args.str("theme", "").trim().lowercase()
                    val accent = args.str("accent", "").trim().lowercase()
                    val reduceMotionRaw = args.str("reduceMotion", "").trim().lowercase()
                    val payload = JSONObject().put("action", "appearance")
                    if (style in setOf("simple", "cute")) {
                        app.getSharedPreferences("whale-interface", Context.MODE_PRIVATE).edit().putString("style", style).apply()
                        payload.put("style", style)
                    }
                    if (theme in setOf("system", "light", "dark")) {
                        AppThemePreference.save(app, theme)
                        payload.put("theme", theme)
                    }
                    if (accent in setOf("ocean", "mint", "amber", "rose")) {
                        payload.put("accent", accent)
                    }
                    if (reduceMotionRaw == "true" || reduceMotionRaw == "false") {
                        val reduced = reduceMotionRaw == "true"
                        CompanionStore(app).reduceMotion = reduced
                        payload.put("reduceMotion", reduced)
                    }
                    WhaleAppControlBus.emit(payload)
                    ToolOutcome(name, "调整软件视觉外观", true, payload.toString(), mutating = true)
                }
                "whale_app_language" -> {
                    val lang = args.str("language", "").trim()
                    require(lang in setOf("zh-CN", "en")) { "仅支持 zh-CN 或 en" }
                    AppLanguage.save(app, lang)
                    WhaleAppControlBus.emit(JSONObject().put("action", "language").put("language", lang))
                    ToolOutcome(name, "切换界面语言为 $lang", true, JSONObject().put("ok", true).put("language", lang).toString(), mutating = true)
                }
                "whale_app_settings_get" -> {
                    val store = CompanionStore(app)
                    val style = app.getSharedPreferences("whale-interface", Context.MODE_PRIVATE).getString("style", "cute")
                    val petPrefs = OverlayBallPreferences.from(app)
                    val state = JSONObject()
                        .put("style", style)
                        .put("theme", AppThemePreference.current(app))
                        .put("language", AppLanguage.current(app))
                        .put("bubblePermissionPreset", store.bubblePermissionPreset)
                        .put("bubbleMemoryAccess", store.bubbleMemoryAccess)
                        .put("proactiveMemoryChat", store.proactiveMemoryChat)
                        .put("modelInitiative", store.modelInitiative)
                        .put("contextFollowup", store.contextFollowup)
                        .put("backgroundUnderstanding", store.backgroundUnderstanding)
                        .put("backgroundAutoMemory", store.backgroundAutoMemory)
                        .put("notificationBubbles", store.notificationBubbles)
                        .put("candidateNotifications", store.candidateNotifications)
                        .put("automaticNotificationMemory", store.automaticNotificationMemory)
                        .put("automaticAssistantMemory", store.automaticAssistantMemory)
                        .put("automaticUsageMemory", store.automaticUsageMemory)
                        .put("reduceMotion", store.reduceMotion)
                        .put("inQuietHours", store.inQuietHours())
                        .put("petSizeDp", petPrefs.readSizeDp())
                        .put("accessibilityConnected", DeepSeekAccessibilityService.current() != null)
                    ToolOutcome(name, "读取全软件配置状态", true, state.toString())
                }
                "whale_app_settings_set" -> {
                    val store = CompanionStore(app)
                    val preset = args.str("autonomyPreset", "").trim().lowercase()
                    val applied = JSONObject().put("ok", true)
                    if (preset in setOf("fluent", "balanced", "strict")) {
                        when (preset) {
                            "fluent" -> {
                                store.bubblePermissionPreset = "full"
                                store.bubbleMemoryAccess = true
                                store.backgroundAutoMemory = true
                            }
                            "balanced" -> {
                                store.bubblePermissionPreset = "standard"
                                store.bubbleMemoryAccess = true
                            }
                            "strict" -> {
                                store.bubblePermissionPreset = "ask"
                            }
                        }
                        applied.put("autonomyPreset", preset)
                        WhaleAppControlBus.emit(JSONObject().put("action", "autonomy").put("preset", preset))
                    }
                    val key = args.str("key", "").trim()
                    val rawVal = args.str("value", "").trim()
                    if (key.isNotEmpty()) {
                        val boolVal = rawVal.equals("true", ignoreCase = true)
                        when (key) {
                            "bubbleMemoryAccess" -> store.bubbleMemoryAccess = boolVal
                            "proactiveMemoryChat" -> store.proactiveMemoryChat = boolVal
                            "modelInitiative" -> store.modelInitiative = boolVal
                            "contextFollowup" -> store.contextFollowup = boolVal
                            "backgroundUnderstanding" -> store.backgroundUnderstanding = boolVal
                            "backgroundAutoMemory" -> store.backgroundAutoMemory = boolVal
                            "notificationBubbles" -> store.notificationBubbles = boolVal
                            "candidateNotifications" -> store.candidateNotifications = boolVal
                            "notificationMemorySuggestions" -> store.notificationMemorySuggestions = boolVal
                            "automaticNotificationMemory" -> store.automaticNotificationMemory = boolVal
                            "automaticAssistantMemory" -> store.automaticAssistantMemory = boolVal
                            "automaticUsageMemory" -> store.automaticUsageMemory = boolVal
                            "reduceMotion" -> store.reduceMotion = boolVal
                            "chatWidthMode" -> store.chatWidthMode = rawVal
                            "chatHeightMode" -> store.chatHeightMode = rawVal
                            else -> throw IllegalArgumentException("不支持的配置项：$key")
                        }
                        applied.put("key", key).put("value", rawVal)
                    }
                    ToolOutcome(name, "更新软件设置", true, applied.toString(), mutating = true)
                }
                "whale_persona_list" -> {
                    val res = CompanionPersonaStore(app).list()
                    ToolOutcome(name, "读取角色人设列表", true, res.toString())
                }
                "whale_persona_manage" -> {
                    val store = CompanionPersonaStore(app)
                    when (val action = args.str("action", "").trim().lowercase()) {
                        "save" -> {
                            val card = JSONObject().put("name", args.str("name", "").trim())
                            val id = args.str("id", "").trim()
                            if (id.isNotEmpty()) card.put("id", id)
                            val prompt = args.str("prompt", "").trim()
                            if (prompt.isNotEmpty()) card.put("prompt", prompt)
                            val saved = store.save(card)
                            ToolOutcome(name, "保存角色人设", true, saved.toString(), mutating = true)
                        }
                        "activate" -> {
                            val id = args.str("id", "").trim()
                            require(id.isNotEmpty()) { "请提供要激活的人设 id" }
                            store.activate(id)
                            ToolOutcome(name, "切换助手人设", true, JSONObject().put("ok", true).put("activeId", id).toString(), mutating = true)
                        }
                        "delete" -> {
                            val id = args.str("id", "").trim()
                            require(id.isNotEmpty()) { "请提供要删除的人设 id" }
                            store.delete(id)
                            ToolOutcome(name, "删除助手人设", true, JSONObject().put("ok", true).put("deletedId", id).toString(), mutating = true)
                        }
                        else -> throw IllegalArgumentException("不支持的人设操作：$action")
                    }
                }
                "whale_pet_control" -> {
                    val action = args.str("action", "").trim().lowercase()
                    when (action) {
                        "start" -> {
                            val intent = Intent(app, OverlayBallService::class.java)
                            runCatching { app.startForegroundService(intent) }.onFailure { app.startService(intent) }
                            ToolOutcome(name, "启动悬浮桌宠", true, JSONObject().put("ok", true).put("action", "start").toString(), mutating = true)
                        }
                        "stop" -> {
                            app.stopService(Intent(app, OverlayBallService::class.java))
                            ToolOutcome(name, "隐藏悬浮桌宠", true, JSONObject().put("ok", true).put("action", "stop").toString(), mutating = true)
                        }
                        "resize" -> {
                            val size = args.str("sizeDp", "56").toIntOrNull() ?: 56
                            OverlayBallPreferences.from(app).writeSizeDp(size)
                            ToolOutcome(name, "调整桌宠尺寸为 ${size}dp", true, JSONObject().put("ok", true).put("sizeDp", size).toString(), mutating = true)
                        }
                        "reset_position" -> {
                            OverlayBallPreferences.from(app).clearPosition()
                            ToolOutcome(name, "重置悬浮桌宠位置", true, JSONObject().put("ok", true).toString(), mutating = true)
                        }
                        else -> throw IllegalArgumentException("不支持的桌宠操作：$action")
                    }
                }
                "whale_memory_search" -> {
                    val query = args.str("query", "").trim()
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
                    val req = JSONObject().put("op", "save").put("text", args.str("text", "").trim())
                    val result = CompanionMemoryStore(app).execute(req.toString(), fromPetTurn = true)
                    ToolOutcome(name, "保存长期记忆", true, result, mutating = true)
                }
                "whale_owner_profile" -> {
                    val profile = OwnerContext(app).read()
                    ToolOutcome(name, "读取主人档案", true, OwnerContext.prompt(profile))
                }
                "whale_knowledge_search" -> {
                    val query = args.str("query", "").trim()
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
                    val text = args.str("text", "").trim()
                    val saved = CompanionKnowledgeStore(app).add(text, source = "whale_native_chat")
                    ToolOutcome(name, "保存到本地资料库", saved, JSONObject().put("saved", saved).toString(), mutating = true)
                }
                "whale_secretary_list" -> {
                    val query = args.str("query", "").trim()
                    val result = UnifiedUnderstandingStore.open(app).use { it.queue.secretary.list(query = query, activeOnly = true, limit = 20) }
                    ToolOutcome(name, "查询秘书事项", true, result.toString())
                }
                "whale_secretary_add" -> {
                    val req = JSONObject()
                        .put("action", "create")
                        .put("operationId", UUID.randomUUID().toString())
                        .put("title", args.str("title", "").trim())
                    val note = args.str("note", "").trim()
                    if (note.isNotEmpty()) req.put("note", note)
                    val result = UnifiedUnderstandingStore.open(app).use { it.queue.secretary.execute(req, owner = "whale-main") }
                    ToolOutcome(name, "记录秘书事项", true, result.toString(), mutating = true)
                }
                "whale_secretary_complete" -> {
                    val id = args.str("id", "").trim()
                    val result = UnifiedUnderstandingStore.open(app).use { store ->
                        val revision = store.queue.secretary.public(id).optString("revision", "")
                        require(revision.isNotEmpty()) { "未找到指定事项或版本信息缺失：$id" }
                        val req = JSONObject()
                            .put("action", "update")
                            .put("operationId", UUID.randomUUID().toString())
                            .put("id", id)
                            .put("expectedRevision", revision)
                            .put("state", "done")
                        store.queue.secretary.execute(req, owner = "whale-main")
                    }
                    ToolOutcome(name, "完成秘书事项", true, result.toString(), mutating = true)
                }
                "whale_secretary_delete" -> {
                    val id = args.str("id", "").trim()
                    val result = UnifiedUnderstandingStore.open(app).use { store ->
                        val revision = store.queue.secretary.public(id).optString("revision", "")
                        require(revision.isNotEmpty()) { "未找到指定事项或版本信息缺失：$id" }
                        val req = JSONObject()
                            .put("action", "delete")
                            .put("operationId", UUID.randomUUID().toString())
                            .put("id", id)
                            .put("expectedRevision", revision)
                        store.queue.secretary.execute(req, owner = "whale-main")
                    }
                    ToolOutcome(name, "删除秘书事项", true, result.toString(), mutating = true)
                }
                "whale_workshop_list" -> {
                    val tools = WhaleFeatureStore(app).activeTools()
                    val arr = JSONArray()
                    tools.forEach { arr.put(it.json()) }
                    ToolOutcome(name, "读取功能工坊列表", true, arr.toString())
                }
                "whale_workshop_draft" -> {
                    val store = WhaleFeatureStore(app)
                    val id = args.str("id", "").trim()
                    if (id.isNotEmpty() && args.str("baseRevision", "").isEmpty()) {
                        val latest = store.read(id)?.let { if (it.isNull("latest")) "" else it.optString("latest", "") }.orEmpty()
                        if (latest.isNotEmpty()) args.put("baseRevision", latest)
                    }
                    val result = store.draft(args)
                    ToolOutcome(name, "保存工坊软件草稿", true, result.toString(), mutating = true)
                }
                "whale_workshop_activate" -> {
                    val id = args.str("id", "").trim()
                    val revision = args.str("revision", "").trim()
                    val store = WhaleFeatureStore(app)
                    val expectedActive = store.read(id)?.let { if (it.isNull("active")) "" else it.optString("active", "") }.orEmpty()
                    val result = store.activate(id, revision, expectedActive)
                    ToolOutcome(name, "发布启用工坊工具", true, result.toString(), mutating = true)
                }
                "whale_workshop_open" -> {
                    val id = args.str("id", "").trim()
                    if (id.isEmpty()) {
                        WhaleFeatureActivity.openLibrary(app)
                        ToolOutcome(name, "打开功能工坊总览", true, JSONObject().put("opened", "library").toString(), mutating = true)
                    } else {
                        val tool = WhaleFeatureStore(app).activeTools().firstOrNull { it.id == id }
                            ?: throw IllegalStateException("未找到已启用的工坊工具：$id")
                        WhaleFeatureActivity.openTool(app, tool.id, tool.revision)
                        ToolOutcome(name, "直接打开工坊工具：${tool.title}", true, tool.json().toString(), mutating = true)
                    }
                }
                "whale_workshop_pin" -> {
                    val id = args.str("id", "").trim()
                    val pinned = args.str("pinned", "true").equals("true", ignoreCase = true)
                    val result = WhaleFeatureStore(app).setPinned(id, pinned)
                    ToolOutcome(name, if (pinned) "置顶工坊工具" else "取消置顶工具", true, result.toString(), mutating = true)
                }
                "whale_code_projects" -> {
                    val res = codeWorkspace.listProjects()
                    ToolOutcome(name, "列出本地代码项目", true, res.toString())
                }
                "whale_code_list" -> {
                    val res = codeWorkspace.listFiles(args.str("project", ""), args.str("path", ""))
                    ToolOutcome(name, "浏览本地代码目录", true, res.toString())
                }
                "whale_code_read" -> {
                    val startLine = args.str("startLine", "1").toIntOrNull() ?: 1
                    val endLine = args.str("endLine", "200").toIntOrNull() ?: 200
                    val res = codeWorkspace.readFile(args.str("project", ""), args.str("path", ""), startLine, endLine)
                    ToolOutcome(name, "读取本地源码", true, res.toString())
                }
                "whale_code_write" -> {
                    val res = codeWorkspace.writeFile(args.str("project", ""), args.str("path", ""), args.str("content", ""))
                    ToolOutcome(name, "写入本地代码文件", true, res.toString(), mutating = true)
                }
                "whale_code_patch" -> {
                    val res = codeWorkspace.patchFile(
                        args.str("project", ""),
                        args.str("path", ""),
                        args.str("oldText", ""),
                        args.str("newText", ""),
                    )
                    ToolOutcome(name, "精准修改本地代码", true, res.toString(), mutating = true)
                }
                "whale_code_delete" -> {
                    val res = codeWorkspace.deleteFile(args.str("project", ""), args.str("path", ""))
                    ToolOutcome(name, "删除本地代码文件", true, res.toString(), mutating = true)
                }
                "whale_code_search" -> {
                    val res = codeWorkspace.searchCode(args.str("project", ""), args.str("query", ""))
                    ToolOutcome(name, "检索本地代码", true, res.toString())
                }
                "whale_phone_apps" -> {
                    val res = InstalledApplications.list(app, args.str("query", "").trim(), 0)
                    ToolOutcome(name, "检索已安装应用", true, res.toString())
                }
                "whale_phone_launch" -> {
                    val pkg = args.str("packageName", "").trim()
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
                    val res = withPhoneControlNotice("正在读取屏幕…") { service.fastControl("fastTree", "") }
                    ToolOutcome(name, "极速读取精简控件树", res.ok, res.text)
                }
                "whale_phone_action" -> {
                    val service = DeepSeekAccessibilityService.current()
                        ?: throw IllegalStateException("无障碍服务未开启，请先在「我的 → 手机能力」中开启鲸鱼助手无障碍服务")
                    val action = args.str("action", "操作").trim()
                    val target = args.str("text").ifBlank { args.str("ref") }.take(12)
                    val label = if (target.isNotEmpty()) "操控：$action「$target」" else "操控：$action"
                    val res = withPhoneControlNotice(label) { service.fastControl("fastAct", args.toString()) }
                    ToolOutcome(name, "极速执行原生屏控", res.ok, res.text, mutating = true)
                }
                "whale_phone_batch" -> {
                    val service = DeepSeekAccessibilityService.current()
                        ?: throw IllegalStateException("无障碍服务未开启，请先在「我的 → 手机能力」中开启鲸鱼助手无障碍服务")
                    val rawSteps = args.opt("steps")
                    val batchPayload = when (rawSteps) {
                        is JSONArray -> JSONObject().put("steps", rawSteps).toString()
                        is String -> {
                            val trimmed = rawSteps.trim()
                            if (trimmed.startsWith("[")) JSONObject().put("steps", JSONArray(trimmed)).toString() else trimmed
                        }
                        else -> args.toString()
                    }
                    val res = withPhoneControlNotice("执行连续操控…") { service.fastControl("fastBatch", batchPayload) }
                    ToolOutcome(name, "批量连贯操控手机", res.ok, res.text, mutating = true)
                }
                "whale_phone_back" -> {
                    val service = DeepSeekAccessibilityService.current()
                        ?: throw IllegalStateException("无障碍服务未开启，请先在「我的 → 手机能力」中开启鲸鱼助手无障碍服务")
                    val res = service.fastControl("fastAct", JSONObject().put("action", "back").toString())
                    ToolOutcome(name, "执行返回键", res.ok, res.text, mutating = true)
                }
                "whale_phone_global" -> {
                    val cmd = args.str("command", "status").trim()
                    if (cmd == "status") {
                        val ready = DeepSeekAccessibilityService.current() != null
                        ToolOutcome(name, "检查无障碍屏控状态", true, JSONObject().put("accessibilityConnected", ready).toString())
                    } else {
                        require(cmd in setOf("tap", "inputText")) { "不支持的全局屏控命令：$cmd" }
                        val service = DeepSeekAccessibilityService.current()
                            ?: throw IllegalStateException("无障碍服务未开启，请先在「我的 → 手机能力」中开启鲸鱼助手无障碍服务")
                        val res = service.execute(cmd, args.str("param", ""))
                        ToolOutcome(name, "执行屏控指令：$cmd", res.ok, res.text, mutating = true)
                    }
                }
                "whale_device_battery" -> {
                    val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    val charging = bm?.isCharging == true
                    ToolOutcome(name, "读取手机电量", true, JSONObject().put("batteryPercent", level).put("charging", charging).toString())
                }
                "whale_worker_status" -> {
                    val targetId = args.str("id").trim()
                    val active = WorkerDashboardStore(app).activeCount() + WhaleNativeWorkerPool.activeCount()
                    val phase = io.deepseekharness.mobile.DeviceControlState.current.phase
                    val briefings = JSONArray().apply {
                        runCatching { WorkerReportInbox(app).briefings().takeLast(6) }.getOrNull().orEmpty().forEach { put(it) }
                    }
                    val payload = JSONObject()
                        .put("activeWorkers", active)
                        .put("nativeWorkers", WhaleNativeWorkerPool.snapshot())
                        .put("recentBriefings", briefings)
                        .put("deviceControlPhase", phase)
                    if (targetId.isNotEmpty()) {
                        WhaleNativeWorkerPool.get(targetId)?.let { payload.put("targetWorker", it) }
                    }
                    ToolOutcome(name, "检查后台任务与回传结果", true, payload.toString())
                }
                "whale_worker_submit" -> {
                    val task = args.str("task").trim()
                    require(task.isNotEmpty()) { "缺少后台任务说明 task" }
                    val title = args.str("title").trim().takeIf { it.isNotEmpty() } ?: task.take(36)
                    val mode = args.str("mode", "create").trim().takeIf { it in setOf("read", "device", "create") } ?: "create"
                    val receipt = WorkerRuntimeWake.submit(
                        context = app,
                        task = task,
                        mode = mode,
                        title = title,
                        needs = "none",
                        sourceSession = null,
                    )
                    val workerId = receipt.optString("commandId")
                    ToolOutcome(name, "已派发后台 Worker：$title", true, receipt.put("id", workerId).put("title", title).put("mode", mode).toString(), mutating = true)
                }
                "whale_worker_cancel" -> {
                    val id = args.str("id").trim()
                    require(id.isNotEmpty()) { "缺少 Worker ID" }
                    val cancelled = WhaleNativeWorkerPool.cancel(id)
                    ToolOutcome(name, if (cancelled) "已取消后台 Worker" else "未找到运行中的后台 Worker", cancelled, JSONObject().put("id", id).put("cancelled", cancelled).toString(), mutating = true)
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

    private fun <T> withPhoneControlNotice(label: String, block: () -> T): T {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { io.deepseekharness.mobile.OverlayBallService.closeConversation() }
        }
        io.deepseekharness.mobile.DeviceControlState.notifyNativeStep("operating", label)
        android.os.SystemClock.sleep(110L)
        return try {
            block()
        } finally {
            io.deepseekharness.mobile.DeviceControlState.notifyNativeStep("idle", "")
        }
    }

    private fun JSONObject.str(key: String, fallback: String = ""): String =
        if (!has(key) || isNull(key)) fallback else optString(key, fallback)

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
