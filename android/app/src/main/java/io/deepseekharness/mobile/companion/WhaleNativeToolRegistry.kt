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
import io.deepseekharness.mobile.virtualscreen.VirtualScreenCommands
import io.deepseekharness.mobile.virtualscreen.VirtualScreenPolicy
import io.deepseekharness.mobile.virtualscreen.VirtualScreenPreferences
import io.deepseekharness.mobile.virtualscreen.VirtualScreenService
import io.deepseekharness.mobile.virtualscreen.VirtualScreenStart
import io.deepseekharness.mobile.virtualscreen.VirtualScreenStartRequest
import io.deepseekharness.mobile.virtualscreen.VirtualScreenTargetKind
import io.deepseekharness.mobile.virtualscreen.putVirtualScreenStart
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Whale 100% 原生全软件控制工具注册与直调层（零 PRoot、零 Node、零 DeepSeek Harness 依赖）。
 *
 * 共注册 94 项进程内原生工具，赋予 Agent 对整个软件与受控手机能力的完全控制权：
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

    data class ToolSchemaBundle(
        val schemas: JSONArray,
        val rawChars: Int,
        val compactChars: Int,
        val totalDynamicTools: Int,
        val injectedDynamicTools: Int,
        val deferredCustomNames: List<String>,
        val deferredMcpNames: List<String>,
    )

    /** 兼容旧调用入口：返回已完成极致瘦身与排序的 Tool Schema 数组。 */
    fun toolSchemas(userText: String, expandedCategories: Set<String> = emptySet(), isWorker: Boolean = false): JSONArray =
        toolSchemaBundle(userText, expandedCategories, isWorker).schemas

    /**
     * 按本轮用户输入与按需展开状态动态装配精简工具集：
     * - 核心常驻仅保留 6~7 个最高频入口，重型管理/专域工具按语义关键词或 whale_tools_expand 懒加载；
     * - 自造 `custom_*` 与外部 `mcp__*` 工具按词法相关度仅自动注入 Top-3，其余生成极短单行索引，彻底防止工具增多导致每轮 Token 爆炸；
     * - 最终统一经 [WhaleContextBudgeter.minifyToolSchemas] 瘦身并按工具名确定性排序，最大化 KV Prefix Cache 命中率。
     */
    fun toolSchemaBundle(
        userText: String,
        expandedCategories: Set<String> = emptySet(),
        isWorker: Boolean = false,
        explicitDynamicNames: Set<String> = emptySet(),
    ): ToolSchemaBundle {
        val text = userText.trim()
        val q = text.lowercase()
        val array = JSONArray()
        val expandAll = "all" in expandedCategories ||
            listOf("有哪些工具", "能力清单", "所有工具", "全部工具", "你能做什么", "有什么功能", "all tools").any { it in q }

        val includeMemoryAdmin = expandAll || "memory" in expandedCategories ||
            listOf("档案", "主人", "修改记忆", "更新记忆", "清理噪音", "垃圾记忆", "目标", "计划", "习惯", "工作流", "playbook", "goal", "profile", "memory", "偏好", "称呼").any { it in q }
        val includeKnowledgeAdmin = expandAll || "knowledge" in expandedCategories || "memory" in expandedCategories ||
            listOf("资料库", "笔记", "收藏", "存笔记", "保存资料", "读资料", "删资料", "knowledge", "note", "文章", "总结保存").any { it in q }
        val includeSecretaryAdmin = expandAll || "secretary" in expandedCategories || "device" in expandedCategories ||
            listOf("完成待办", "删待办", "删除待办", "取消待办", "守护", "规则", "取件码", "自动监听", "routine").any { it in q }
        val includeToolAuthoring = expandAll || "custom" in expandedCategories || "code" in expandedCategories || "extensions" in expandedCategories ||
            listOf("造工具", "自造", "自定义工具", "建一个工具", "做个工具", "写个工具", "删工具", "custom_", "tool_create").any { it in q }
        val includeAppControl = expandAll || "settings" in expandedCategories ||
            listOf("设置", "主题", "深色", "浅色", "风格", "极简", "可爱", "语言", "页面", "跳转", "刷新", "自治", "权限", "开关", "theme", "style", "setting").any { it in q }
        val includePersona = expandAll || "settings" in expandedCategories ||
            listOf("人设", "角色", "性格", "语气", "口癖", "称呼", "叫我", "说话方式", "世界书", "开场白", "persona").any { it in q }
        val includePet = expandAll || "settings" in expandedCategories ||
            listOf("桌宠", "悬浮", "尺寸", "大小", "鲸鱼娘", "pet", "resize").any { it in q }
        val includeWorkshop = expandAll || "code" in expandedCategories || "workshop" in expandedCategories ||
            listOf("工坊", "小工具", "小软件", "小程序", "做一个", "做个", "开发", "网页", "html", "打卡", "计算器", "番茄钟", "记账", "倒计时", "背单词", "游戏", "workshop", "mini app").any { it in q }
        val includeCode = expandAll || "code" in expandedCategories ||
            listOf("代码", "文件", "目录", "脚本", "编程", "工程", "写入", "补丁", "ppt", "word", "excel", "pdf", "导出", "安装包", "python", "code", "file", "patch").any { it in q }
        val hasPhoneVerb = listOf("打开", "启动", "点击", "截屏", "截图", "屏幕", "滑动", "返回", "桌面", "副屏", "操控", "无障碍", "修复", "screen", "click", "launch").any { it in q }
        val includePhone = expandAll || "phone" in expandedCategories || hasPhoneVerb ||
            (listOf("手机", "应用", "app", "微信", "抖音", "淘宝", "支付宝", "地图", "美团").any { it in q } && !listOf("搜", "查一下", "搜索", "资料").any { it in q })
        val includeDevice = expandAll || "device" in expandedCategories || "settings" in expandedCategories ||
            listOf("电量", "电池", "媒体", "音量", "播放", "暂停", "剪贴板", "粘贴", "复制", "通知", "定时", "每天", "提醒我", "后台", "状态", "链接", "分享", "battery", "media", "clipboard", "schedule", "status").any { it in q }
        val includeExtensions = expandAll || "extensions" in expandedCategories || "mcp" in expandedCategories || "skill" in expandedCategories ||
            listOf("skill", "技能", "mcp", "安装", "扩展", "插件", "服务器", "github.com", "skill.md").any { it in q }

        val isCasualChitchat = !isWorker && !expandAll && expandedCategories.isEmpty() &&
            text.length <= 12 &&
            !includeAppControl && !includePersona && !includePet && !includeWorkshop && !includeCode && !includePhone && !includeDevice && !includeExtensions &&
            !listOf("记", "存", "查", "搜", "提醒", "待办", "日程", "闹钟", "任务", "朗读", "说", "帮我", "总结", "分析", "http", "www").any { it in q }
        if (isCasualChitchat) {
            array.put(schema("whale_recall", "并行检索长期记忆与本地资料。", mapOf("query" to "关键词", "scope" to "all/memory/knowledge"), listOf("query")))
            array.put(schema("whale_web_search", "联网实时搜索网页与中文社区。", mapOf("query" to "关键词", "platform" to "auto/bilibili/xiaohongshu/zhihu"), listOf("query")))
            array.put(schema("whale_secretary_list", "查看未完成待办与日程。", emptyMap(), emptyList()))
            array.put(schema("whale_tools_expand", "热展开专域工具组或指定工具名。", mapOf("category" to "memory/knowledge/secretary/custom/mcp/code/phone/device/settings/extensions/all", "names" to "可选逗号分隔精确工具名"), listOf("category")))
            val rawLen = array.toString().length
            val minified = WhaleContextBudgeter.minifyToolSchemas(array)
            return ToolSchemaBundle(minified, rawLen, minified.toString().length, 0, 0, emptyList(), emptyList())
        }

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
                    "style" to "可选：simple（极简·类Grok）、cute（鲸鱼娘·可爱）或 usagi（乌萨奇·兔兔）",
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
                "管理角色卡：优先用 action=refine 根据对话增量修正当前角色卡（只合并改动字段，不丢失原有设定）；也支持 save（新建/整卡编辑）、activate（切换）、delete（删除）。",
                mapOf(
                    "action" to "refine（推荐：增量修正当前角色卡）/ save / activate / delete",
                    "id" to "可选角色卡 id（refine 留空默认当前激活角色卡）",
                    "name" to "角色名",
                    "description" to "角色简介",
                    "personality" to "完整性格与说话方式",
                    "appendPersonality" to "refine 专用：追加一条性格/口癖/称呼偏好而不覆盖原文",
                    "scenario" to "相处情境",
                    "examples" to "示例对话",
                    "appendExample" to "refine 专用：追加一句典型台词示例",
                    "greeting" to "开场白",
                    "systemPrompt" to "角色附加指引",
                    "postHistory" to "对话后持续指引",
                    "worldBookKey" to "refine 专用：新增世界书触发关键词（逗号分隔）",
                    "worldBookContent" to "refine 专用：新增世界书设定内容",
                    "reason" to "本次修正的简短原因说明",
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
                    "sizeDp" to "resize 时无级尺寸（44~160 任意整数 dp，默认 88）",
                    "state" to "state 时可选桌宠状态：idle / listening / thinking / happy",
                ),
                listOf("action"),
            ))
        }
        // 精简核心常驻：记忆召回、记忆保存、待办查询/新增
        array.put(schema("whale_recall", "并行混合语义检索用户的长期记忆、习惯规则与本地资料库。", mapOf("query" to "检索关键词", "scope" to "all(默认)/memory/knowledge"), listOf("query")))
        array.put(schema("whale_memory_save", "将用户明确表达的偏好或长期事实保存到本地长期记忆。", mapOf("text" to "简明完整的记忆内容"), listOf("text")))
        if (includeMemoryAdmin) {
            array.put(schema(
                "whale_memory_update",
                "纠正或更新指定 ID 的本地长期记忆内容（自动保留修改历史）。",
                mapOf("id" to "记忆 UUID", "text" to "更新后的完整记忆内容(1~600字)"),
                listOf("id", "text"),
            ))
            array.put(schema(
                "whale_memory_purge_noise",
                "批量清理长期记忆中自动抓取的垃圾应用通知噪音（绝不会删除主人档案或用户手动保存的记忆）。",
                mapOf("dryRun" to "可选：true 仅预览数量和样例，false(默认) 直接清理"),
                emptyList(),
            ))
            array.put(schema("whale_owner_profile", "读取用户的主人档案摘要与已记录偏好。", emptyMap(), emptyList()))
            array.put(schema(
                "whale_owner_profile_edit",
                "直接设置、更新或清空用户主人档案中的指定字段（立即生效为 confirmed）。支持字段：preferred_name(称呼)、language(语言)、timezone(时区如Asia/Shanghai)、location(常用城市)、occupation(工作与身份)、communication(沟通偏好)、routine(作息与提醒偏好)、devices(常用设备)、projects(主要项目)、current_focus(近期安排)。",
                mapOf(
                    "field" to "字段名：preferred_name / language / timezone / location / occupation / communication / routine / devices / projects / current_focus",
                    "action" to "操作类型：set（默认，设置或更新）或 forget（清空遗忘该字段）",
                    "value" to "新的字段内容（action=set 时必填，1~240字；timezone 需 IANA 时区如 Asia/Shanghai）",
                    "validUntilDays" to "可选，仅 current_focus 使用：有效天数，默认 14",
                ),
                listOf("field"),
            ))
            array.put(schema(
                "whale_goal_create",
                "创建或更新一个跨天持续推进的长期目标（持久化目标图），记录下一步行动和拆分子步骤。",
                mapOf(
                    "title" to "目标标题（简洁明确，如「准备下周系统架构面试」）",
                    "nextAction" to "下一步具体可执行动作",
                    "subTasks" to "可选：逗号分隔的子步骤清单",
                    "blockers" to "可选：当前阻塞点",
                ),
                listOf("title"),
            ))
            array.put(schema(
                "whale_goal_update",
                "更新已有长期目标的状态（active/blocked/completed/paused）、下一步动作或阻塞点。",
                mapOf(
                    "idOrTitle" to "目标 ID 或标题关键词",
                    "status" to "可选：active / blocked / completed / paused",
                    "nextAction" to "可选：更新后的下一步行动",
                    "blockers" to "可选：更新后的阻塞点",
                ),
                listOf("idOrTitle"),
            ))
            array.put(schema(
                "whale_goal_list",
                "列出用户当前正在推进的持久化长期目标图。",
                mapOf("includeCompleted" to "可选：true 包含已完成目标，默认 false"),
                emptyList(),
            ))
            array.put(schema(
                "whale_playbook_save",
                "把用户认可或反复要求的个人工作流沉淀为可复用习惯规则（程序性记忆）。",
                mapOf(
                    "trigger" to "触发场景（如「发文章链接时」「早安问候时」）",
                    "steps" to "自动执行的步骤（如「提炼3条核心要点并存入资料库」）",
                ),
                listOf("trigger", "steps"),
            ))
            array.put(schema(
                "whale_playbook_list",
                "查看已沉淀的个人习惯工作流规则（Playbooks）。",
                emptyMap(),
                emptyList(),
            ))
        }
        if (includeKnowledgeAdmin) {
            array.put(schema("whale_knowledge_save", "将整理好的文字笔记或摘要保存到本地资料库。", mapOf("text" to "完整正文"), listOf("text")))
            array.put(schema("whale_knowledge_read", "按 ID 读取本地资料库某条笔记/文档的完整正文。", mapOf("id" to "资料记录 ID"), listOf("id")))
            array.put(schema("whale_knowledge_delete", "按 ID 删除本地资料库中的过期或重复记录（可撤销）。", mapOf("id" to "资料记录 ID"), listOf("id")))
        }
        array.put(schema("whale_secretary_list", "查询用户的未完成秘书事项与待办列表。", mapOf("query" to "可选筛选关键词"), emptyList()))
        array.put(schema("whale_secretary_add", "为用户新增一条秘书事项/待办记录。", mapOf("title" to "事项标题", "note" to "可选补充说明"), listOf("title")))
        if (includeSecretaryAdmin) {
            array.put(schema("whale_secretary_complete", "将指定编号的秘书事项标记为已完成。", mapOf("id" to "事项 UUID 编号"), listOf("id")))
            array.put(schema("whale_secretary_delete", "删除指定编号的秘书事项。", mapOf("id" to "事项 UUID 编号"), listOf("id")))
            array.put(schema(
                "whale_routine_manage",
                "管理事件触发型主动守护规则（0 Token 本地通知过滤：如取件码自动转待办、出行变动归档、账单存资料库）。",
                mapOf(
                    "action" to "list / create / toggle / delete",
                    "name" to "规则名称（create 时必填）",
                    "keywordRegex" to "本地触发关键词或正则（create 时必填，如 取件码|菜鸟驿站）",
                    "actionKind" to "动作类型：add_todo(默认) / save_knowledge / pet_notify",
                    "packageFilter" to "可选：限定应用包名片段",
                    "idOrName" to "toggle 或 delete 时的规则 ID 或名称",
                    "enabled" to "toggle 时 true/false",
                ),
                listOf("action"),
            ))
        }
        if (includeWorkshop) {
            array.put(schema("whale_workshop_list", "列出鲸鱼功能工坊中已启用的本地离线小软件（置顶优先）。", emptyMap(), emptyList()))
            array.put(schema(
                "whale_workshop_read",
                "读取某个已有工坊小软件的完整 HTML/CSS/JS 源码与版本号，便于迭代升级或修复 Bug。",
                mapOf("id" to "功能英文 id"),
                listOf("id"),
            ))
            array.put(schema(
                "whale_workshop_draft",
                "在功能工坊创建或更新单文件离线小软件（支持高达 512KB，100% 离线运行，禁止外链 CDN）。内置 2MB 持久化 window.whaleStorage(get/set/remove/keys/exportAll/importAll) 与原生桥接 window.whaleApp(toast/haptic/copy/share/speak/theme) 及 .whale-card/.whale-chip 样式类；建议直接传 autoActivate=true、autoOpen=true 一步完成创作、发布并打开。",
                mapOf(
                    "id" to "英文小写连字符标识(如 habit-tracker)",
                    "title" to "短标题(<=50字)",
                    "html" to "完整移动端自适应单文件 HTML/CSS/JS 源码",
                    "autoActivate" to "可选：true（默认 true）直接发布启用，省去二次调用",
                    "autoOpen" to "可选：true 发布后立即在屏幕上打开展示",
                ),
                listOf("id", "title", "html"),
            ))
            array.put(schema("whale_workshop_activate", "将工坊草稿发布并启用为正式小软件。", mapOf("id" to "功能英文 id", "revision" to "草稿返回的 revision 哈希"), listOf("id", "revision")))
            array.put(schema("whale_workshop_open", "在屏幕上直接打开某个已启用的工坊小软件，或打开工坊总览页。", mapOf("id" to "可选功能英文 id（留空则打开工坊库）"), emptyList()))
            array.put(schema("whale_workshop_pin", "置顶或取消置顶工坊中某个小软件。", mapOf("id" to "功能英文 id", "pinned" to "true 或 false"), listOf("id", "pinned")))
            array.put(schema("whale_workshop_delete", "删除指定的工坊小软件。", mapOf("id" to "功能英文 id"), listOf("id")))
        }
        if (includeCode) {
            array.put(schema("whale_code_projects", "列出本地原生代码工作区中已有的全部项目及其文件数与体积。", emptyMap(), emptyList()))
            array.put(schema("whale_code_list", "列出本地原生代码项目中的目录与文件。", mapOf("project" to "项目名", "path" to "可选子目录路径"), listOf("project")))
            array.put(schema("whale_code_read", "按行读取本地原生代码项目中的源码文件。", mapOf("project" to "项目名", "path" to "文件相对路径", "startLine" to "起始行(默认1)", "endLine" to "结束行(默认200)"), listOf("project", "path")))
            array.put(schema("whale_code_write", "在本地原生代码项目中创建或覆盖写入源码文件。", mapOf("project" to "项目名", "path" to "文件相对路径", "content" to "完整文件源码"), listOf("project", "path", "content")))
            array.put(schema("whale_code_patch", "在本地源码文件中精准替换唯一匹配的代码片段（比整文件重写更省 token）。", mapOf("project" to "项目名", "path" to "文件相对路径", "oldText" to "原始唯一代码片段", "newText" to "替换后的代码片段"), listOf("project", "path", "oldText", "newText")))
            array.put(schema("whale_code_delete", "安全删除本地代码项目中的单个文件。", mapOf("project" to "项目名", "path" to "文件相对路径"), listOf("project", "path")))
            array.put(schema("whale_code_search", "在本地原生代码项目中跨文件检索代码片段。", mapOf("project" to "项目名", "query" to "搜索词"), listOf("project", "query")))
            array.put(schema(
                "whale_html_to_pdf",
                "使用 Android 原生 WebView 将 HTML/CSS/SVG 直接排版渲染为多页 A4 矢量 PDF（零外部依赖，适合报告/简历/图表/讲义），并可一键导出到手机下载目录或系统分享。",
                mapOf(
                    "html" to "完整 HTML 源码（或留空改用 file 参数读取项目内 HTML 文件）",
                    "file" to "可选：项目内已有 HTML 文件相对路径",
                    "project" to "项目目录名（默认 scratch）",
                    "fileName" to "输出 PDF 文件名，例如 report.pdf",
                    "export" to "可选：true（默认）导出到手机下载目录，false 仅存工作区",
                    "share" to "可选：true 导出后立即弹出系统分享面板，默认 false",
                ),
                emptyList(),
            ))
            array.put(schema(
                "whale_remote_exec",
                "通过用户可选配置的远程算力桥接节点（电脑/云服务器）执行 Shell 或 Python 命令（适合 Node.js/uv/FFmpeg/重型编译等手机端无法直接运行的任务）。",
                mapOf(
                    "command" to "要在远程节点执行的命令或 Python 脚本",
                    "language" to "可选：shell（默认）或 python",
                    "timeoutSec" to "可选：超时秒数（3-180，默认 45）",
                ),
                listOf("command"),
            ))
            array.put(schema(
                "whale_remote_mcp",
                "通过远程桥接节点调用用户电脑上的本地 stdio MCP 服务（如 B站 MCP / uv / npx 工具）。",
                mapOf(
                    "action" to "list（列出可用服务或某服务的工具）或 call（调用工具）",
                    "server" to "MCP 服务名（action=list 时可留空以列出全部已配置服务）",
                    "tool" to "当 action=call 时必填：要调用的 MCP 工具名",
                    "arguments" to "当 action=call 时可选：JSON 对象格式的参数",
                ),
                listOf("action"),
            ))
            array.put(schema(
                "whale_file_export",
                "将代码工作区生成的任意文件（如 .pptx/.docx/.xlsx/.pdf/.zip/.png/.csv）导出保存到手机「下载/WhaleAssistant」目录，并可选立即调起系统分享面板交给用户。",
                mapOf(
                    "project" to "项目目录名（默认 scratch）",
                    "path" to "项目内相对文件路径，例如 deck.pptx 或 out/report.pdf",
                    "share" to "可选：true 导出后立即弹出系统分享/打开面板，默认 false",
                ),
                listOf("path"),
            ))
            array.put(schema(
                "whale_py_install",
                "运行时从国内 PyPI 镜像（清华/阿里/官方回退）动态下载并安装纯 Python Wheel 包（py3-none-any.whl）到私有 site-packages，安装后 whale_code_run 可直接 import 使用。",
                mapOf(
                    "package" to "PyPI 包名，例如 qrcode / tabulate / markdown / fpdf2",
                    "mirrorUrl" to "可选自定义 HTTPS 镜像地址（默认自动优先清华/阿里镜像）",
                ),
                listOf("package"),
            ))
            array.put(schema(
                "whale_code_run",
                "在手机本地直接运行 Python 3.11 代码（内嵌 CPython，支持编程式工具调用 PTC：代码内可直接调用 `tools.<工具名>(**kwargs)` 串联多个工具并用 `inspect(...)` 输出精炼结果，1 轮完成多步编排）或本地 Shell 命令。",
                mapOf(
                    "project" to "项目名（运行工作目录，不存在则自动创建）",
                    "lang" to "运行语言：python（默认，内嵌 CPython 3.11 + PTC 工具桥接）或 sh（本地沙箱 Shell）",
                    "code" to "直接执行的代码或命令（与 file 二选一）",
                    "file" to "可选：运行项目内已有相对路径脚本文件（如 main.py）",
                    "timeoutSec" to "可选超时秒数（默认 45，Python 最高支持 180 秒，Shell 最高 120 秒）",
                ),
                listOf("project"),
            ))
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
            array.put(schema("whale_phone_vision", "多模态看图识屏：安全截取当前手机屏幕并调用视觉模型分析画面、图标、图片内容及按钮坐标（自动拦截密码/支付/锁屏敏感页面）。", mapOf("question" to "可选：想看懂的内容或想找的图标/按钮描述"), emptyList()))
            array.put(schema("whale_phone_vision_click", "视觉定位并直接点击：截屏让多模态视觉模型定位目标图标或无文字按钮的精确屏幕坐标，并立即执行手势点击（适合游戏、Canvas、自定义图标按钮）。", mapOf("target" to "要点击的目标描述，例如：右上角放大镜图标 / 红色关闭按钮"), listOf("target")))
            array.put(schema(
                "whale_macro_manage",
                "教它做一遍（0 Token 录制宏与视觉自愈）：录制用户手动操作路径并确定性回放，遇弹窗或改版返回 needs_ai_heal 供单步自愈。",
                mapOf(
                    "action" to "list / start_record / stop_record / replay / delete",
                    "name" to "录制或回放的宏名称（如 每日签到）",
                    "idOrName" to "replay / delete 时的宏 ID 或名称",
                    "startIndex" to "可选：视觉自愈后续跑的起始步骤序号（默认 0）",
                    "confirmedHighRisk" to "可选：高危步骤经用户确认后传 true",
                ),
                listOf("action"),
            ))
            array.put(schema("whale_automation_heal", "一键诊断并自动修复无障碍服务（含僵尸断连自动通过 Shizuku 重连）、空拦截白名单与副屏就绪状态。", mapOf("relaxWhitelist" to "可选：true（默认）当白名单为空时自动关闭拦截限制"), emptyList()))
            array.put(schema("whale_virtual_screen_control", "启动、停止或查询「目标应用副屏」状态（在后台独立虚拟屏幕上运行目标应用，不打扰主屏幕；需已开启 Shizuku）。", mapOf("action" to "state / start / stop", "target" to "action=start 时的目标应用包名（可用 whale_phone_apps 查）或组件名"), listOf("action")))
            array.put(schema("whale_virtual_screen_action", "在已启动的「目标应用副屏」独立虚拟屏幕上执行点击、输入、滑动、读控件树或返回操作。", mapOf("sessionId" to "可选：副屏会话 ID（不填自动取当前活跃副屏）", "action" to "操作类型：tap / swipe / text / tree / key / back / home / follow", "x" to "可选 X 坐标", "y" to "可选 Y 坐标", "text" to "可选输入文本"), listOf("action")))
            array.put(schema("whale_virtual_screen_vision", "多模态看图识别「目标应用副屏」当前画面内容与按钮坐标。", mapOf("sessionId" to "可选：副屏会话 ID（不填自动取当前活跃副屏）", "question" to "可选：想看懂的副屏画面或按钮描述"), emptyList()))
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
            if (isWorker) {
            array.put(schema(
                "whale_worker_ask",
                "当后台 Worker 遇到关键信息缺失、需要用户做决定或需要站点授权登录时，主动向用户/Router 发起提问并挂起等待回复（进入 person/answer 状态）。调用后请立即结束本轮输出，等待用户回复自动唤醒。",
                mapOf("question" to "要向用户确认的具体、简洁问题（≤600字）"),
                listOf("question"),
            ))
        }
        array.put(schema(
            "whale_worker_board",
            "Router 与多个并行后台 Worker 共享的键值黑板：用于实时读写结构化中间产物（文件路径、提炼要点、数据 JSON），无需等整个任务结束即可协同交换。",
            mapOf(
                "action" to "put（写入）/ get（读取）/ list（列出已有键）/ remove（删除）",
                "key" to "黑板键名，例如 research/topic_a 或 ppt/outline",
                "value" to "action=put 时要写入的内容（≤8000字）",
                "prefix" to "action=list 时可选前缀过滤",
            ),
            listOf("action"),
        ))
        array.put(schema("whale_worker_status", "查询后台 Worker 任务执行状态、完整回传结果与最近简报。", mapOf("id" to "可选：指定某个 Worker 任务 ID 以读取完整结果"), emptyList()))
            if (!isWorker) {
                array.put(schema(
                    "whale_worker_submit",
                    "派发一个独立后台 Worker 异步执行长耗时任务（写代码、整理资料、多步查证或后台手机任务）。派发成功后请直接简短告知用户并等待回传，切勿在主会话重复动手执行。",
                    mapOf("task" to "完整清晰的后台任务说明（<=1800字）", "title" to "可选简短任务标题", "mode" to "可选模式：create / read / device，默认 create", "inheritContext" to "可选：true 时附带近期父对话摘要，默认 false"),
                    listOf("task"),
                ))
            }
            array.put(schema("whale_worker_cancel", "取消一个正在运行的原生后台 Worker 任务。", mapOf("id" to "Worker 任务 ID"), listOf("id")))
            array.put(schema(
                "whale_worker_followup",
                "向正在运行的后台 Worker 直接透传用户追问或补充条件（绕过主会话，省 Token，享有 +1 人类优先槽位，并持久化防崩溃丢失）。",
                mapOf("id" to "目标 Worker 任务 ID", "message" to "用户追问或补充条件（<=240字，简洁直述）"),
                listOf("id", "message"),
            ))
            array.put(schema(
                "whale_read_resource",
                "分页按需读取被截断或落盘的完整资源正文（支持 spill/<id>、worker/<id>、checkpoint/<id>、knowledge/<id>）。",
                mapOf("resource" to "资源路径，如 spill/<id>、worker/<id> 或 checkpoint/<id>", "offset" to "可选起始字符偏移（默认 0）", "limit" to "可选本页字符数（默认 2000，上限 6000）"),
                listOf("resource"),
            ))
            array.put(schema(
                "whale_todo_write",
                "为复杂多步任务维护结构化整表任务清单（简单单步问答无需调用）；每次传入完整 todos JSON 数组覆盖更新进度。",
                mapOf("todos" to "JSON 数组字符串，每项包含 content 与 status（pending / in_progress / completed）"),
                listOf("todos"),
            ))
            array.put(schema(
                "whale_proactive_mode",
                "管理主动智能的影子观察模式（off 关闭 / observe 默认仅出草稿与 would_accept 日志 / auto 全自动执行）以及查看/清理推测执行草稿箱。",
                mapOf("action" to "status / set_mode / list_drafts / record_draft / dismiss_draft", "mode" to "set_mode 时可选：off / observe / auto", "id" to "dismiss_draft 时的草稿 ID", "title" to "record_draft 时的草稿标题", "draftAction" to "record_draft 时的预备动作/草稿内容", "reason" to "record_draft 时的触发原因"),
                listOf("action"),
            ))
            array.put(schema("whale_worker_manage", "管理与清理后台 Worker 历史及桌宠红点徽标（解决界面残余数字与历史记录删不掉的问题）。", mapOf("action" to "clear_completed（清理已完成与残留徽标）/ delete（按 id 删除单条）/ clear_badge（仅清零桌宠待办红点）", "id" to "action=delete 时的 Worker ID"), listOf("action")))
            array.put(schema("whale_ui_refresh", "强制刷新鲸鱼助手主界面与悬浮桌宠状态徽标，同步最新数据视图。", emptyMap(), emptyList()))
            array.put(schema("whale_voice_speak", "用硅基流动 CosyVoice2 拟人语音让桌宠大声说出一句话。", mapOf("text" to "要朗读的自然中文或英文内容", "voice" to "可选音色：diana / claire / bella / anna / alex / benjamin"), listOf("text")))
            array.put(schema("whale_voice_config", "修改桌宠语音配置（切换音色、语速或开关自动朗读）。", mapOf("autoSpeakPet" to "可选：true 或 false", "voice" to "可选：diana（欢快女声）/ claire（温柔女声）/ bella（活力女声）/ anna（沉稳女声）/ alex（沉稳男声）", "speed" to "可选语速 0.7-1.5"), emptyList()))
            array.put(schema("whale_web_search", "联网实时搜索网页与中文社区内容（支持 B站原生直搜、小红书/知乎/GitHub 定向检索及中文多引擎聚合），返回标题、摘要与链接。", mapOf("query" to "搜索关键词", "platform" to "可选平台定向：auto（自动识别）/ bilibili（B站原生直搜）/ xiaohongshu（小红书）/ zhihu（知乎）/ github", "limit" to "可选返回条数（1-8，默认 5）"), listOf("query")))
            array.put(schema(
            "whale_image_search",
            "联网搜图与以图搜图（支持关键词搜高清图片/找同款/配图，或传本地 imagePath 走百度识图以图搜图）。",
            mapOf(
                "query" to "搜图关键词或画面描述（如 乌萨奇 周边 挂件、某款耳机外观）",
                "imagePath" to "可选：本地图片绝对路径，用于以图搜图上传",
                "limit" to "返回图片数量（1~10，默认 6）",
            ),
            emptyList(),
        ))
        array.put(schema("whale_web_render", "使用手机原生离屏 WebView 执行 JS 渲染后提取动态网页正文与链接（专治小红书笔记/搜索页、B站动态页、SPA 前端渲染网站，自动复用已授权登录态 Cookie）。", mapOf("url" to "完整 https:// 或 http:// 网址", "waitMs" to "可选等待 JS 渲染毫秒数（1000-8000，默认 2600）", "maxChars" to "可选最大字符数（默认 6000）"), listOf("url")))
            array.put(schema("whale_site_auth", "管理通用站点登录态凭据库（Android Keystore 加密存储 Cookie，明文永不暴露给模型）：支持拉起内嵌 WebView 供用户自行登录并授权保存站点 Cookie、查看已授权域名列表或撤销授权。", mapOf("action" to "open_login（拉起站点登录页）/ list（查看已授权域名）/ revoke（撤销指定域名授权）", "url" to "open_login 时的目标 https:// 网址（如 https://www.bilibili.com）或 revoke 时的域名", "label" to "可选站点名称备注（如 B站）"), listOf("action")))
            array.put(schema("whale_web_fetch", "联网抓取指定 HTTPS/HTTP 网页或文本接口的正文内容（已授权站点自动携带加密登录态 Cookie）。", mapOf("url" to "完整网页 URL", "maxChars" to "可选最大字符数，默认 6000"), listOf("url")))
            array.put(schema("whale_memory_delete", "按 ID 删除一条本地长期记忆。", mapOf("id" to "记忆 UUID"), listOf("id")))
            array.put(schema("whale_clipboard", "读取或写入安卓系统剪贴板文本。", mapOf("action" to "get 或 set", "text" to "action=set 时要写入的文本"), listOf("action")))
            array.put(schema("whale_device_media", "控制手机媒体音量或播放状态。", mapOf("action" to "volume_up / volume_down / mute / set_volume / play_pause / next / prev", "percent" to "set_volume 时的音量百分比 0-100"), listOf("action")))
            array.put(schema("whale_calendar_read", "读取手机系统日历近期日程（需已授予日历读取权限）。", emptyMap(), emptyList()))
            array.put(schema(
                "whale_calendar_create",
                "在手机系统日历中新增一条日程事件（有写入权限直接静默写入，否则自动拉起预填好的系统日历确认页）。",
                mapOf(
                    "title" to "日程标题",
                    "startIso" to "开始时间，ISO 格式如 2026-11-21T09:30 或毫秒时间戳",
                    "durationMinutes" to "持续分钟数（默认 60）",
                    "location" to "可选地点",
                    "description" to "可选备注说明",
                ),
                listOf("title", "startIso"),
            ))
            array.put(schema(
                "whale_alarm_set",
                "调用手机原生系统闹钟设置指定时间的闹钟（不怕后台被杀）。",
                mapOf(
                    "hour" to "小时 (0~23)",
                    "minute" to "分钟 (0~59)",
                    "message" to "可选闹钟标签",
                    "skipUi" to "可选：true(默认) 尽量静默创建",
                ),
                listOf("hour", "minute"),
            ))
            array.put(schema(
                "whale_timer_set",
                "调用手机原生系统时钟设置倒计时计时器（例如番茄钟、休息提醒）。",
                mapOf(
                    "seconds" to "倒计时总秒数 (1~86400)",
                    "message" to "可选计时器标签",
                    "skipUi" to "可选：true(默认) 尽量直接启动",
                ),
                listOf("seconds"),
            ))
            array.put(schema("whale_notifications_read", "检索本机已捕获的应用通知摘要（自动过滤密码与验证码敏感内容）。", mapOf("query" to "可选搜索关键词", "limit" to "可选条数 1-10，默认 8"), emptyList()))
            array.put(schema("whale_schedule_create", "创建一个到点自动唤醒执行的后台定时/周期任务，执行完自动回传给桌宠和主对话。", mapOf("title" to "任务简短标题", "task" to "到点后要自主执行的完整任务说明", "delayMinutes" to "多少分钟后触发（1-10080）", "repeatDaily" to "可选：true 表示每天循环"), listOf("task", "delayMinutes")))
            array.put(schema("whale_schedule_list", "列出全部尚未触发或每日循环的定时任务。", emptyMap(), emptyList()))
            array.put(schema("whale_schedule_cancel", "按 ID 取消一个定时任务。", mapOf("id" to "定时任务 ID"), listOf("id")))
            array.put(schema("whale_device_status", "一键查询手机综合状态：电量、充电、音量、网络、无障碍、桌宠、活跃 Worker 及今日 Token 与估算费用。", emptyMap(), emptyList()))
            array.put(schema("whale_open_url", "用手机系统默认浏览器打开指定网页链接。", mapOf("url" to "完整 http:// 或 https:// 网址"), listOf("url")))
            array.put(schema("whale_share_text", "唤起安卓系统原生分享面板，将文案或摘要直接分享到微信、备忘录等任意应用。", mapOf("text" to "要分享的正文内容", "title" to "可选分享标题"), listOf("text")))
        }
        array.put(schema(
            "whale_self_capabilities",
            "实时自检 Agent 自身的完整能力清单与环境状态：返回全部可展开工具域、已自造的 custom_* 工具、已装 Skill/MCP、功能工坊小软件、0-Token 录制宏、主动守护规则以及无障碍/副屏/Python 就绪状态。不确定能否完成任务时优先调用此工具自检。",
            emptyMap(),
            emptyList(),
        ))
        array.put(schema(
            "whale_tools_expand",
            "热展开尚未加载的专域工具组或指定工具名，下一轮立即生效。分类：memory/knowledge/secretary/custom/mcp/phone/code/settings/device/extensions/all。",
            mapOf(
                "category" to "分类：memory/knowledge/secretary/custom/mcp/phone/code/settings/device/extensions/all",
                "names" to "可选：逗号分隔的精确工具名（如 custom_snr_calc,mcp__srv__tool）",
            ),
            listOf("category"),
        ))
        if (includeToolAuthoring) {
            array.put(schema(
                "whale_tool_create",
                "自主创造或更新持久化自定义工具（custom_*），支持 python/composite/http_json/js，创建后立刻热加载调用。",
                mapOf(
                    "name" to "custom_ 开头的小写字母数字下划线工具名",
                    "description" to "工具用途说明(2~200字)",
                    "kind" to "python(推荐)/composite/http_json/js",
                    "paramsJson" to """可选参数描述 JSON，如 {"city":"城市"}""",
                    "requiredJson" to """可选必填参数 JSON 数组，如 ["city"]""",
                    "definitionJson" to """必填定义 JSON：python/js 传 {"code":"..."}；composite 传 {"steps":[...]}；http_json 传 {"url":"..."}""",
                ),
                listOf("name", "description", "kind", "definitionJson"),
            ))
            array.put(schema("whale_tool_list", "列出已保存的全部自造工具（custom_*）。", emptyMap(), emptyList()))
            array.put(schema("whale_tool_delete", "删除指定的自造工具。", mapOf("name" to "custom_* 工具名"), listOf("name")))
        }
        // 仅在已有已启用 Skill 或按需展开 extensions 时才注入 Skill/MCP Schema，日常对话零额外 Token 消耗
        if (includeExtensions || WhaleSkillStore.hasEnabledSkills(app)) {
            array.put(schema(
                "whale_skill_read",
                "读取已安装 Skill 的完整 SKILL.md 规范。",
                mapOf("name" to "Skill 名称或 slug"),
                listOf("name"),
            ))
        }
        if (includeExtensions) {
            array.put(schema("whale_skill_list", "列出已安装的 Skill。", emptyMap(), emptyList()))
            array.put(schema(
                "whale_skill_install",
                "安装或创建标准 SKILL.md 技能（支持 GitHub/HTTPS URL 自动下载，或传入 name+description+content 本地创建）。",
                mapOf(
                    "url" to "可选：GitHub 或 https SKILL.md 地址",
                    "name" to "技能名",
                    "description" to "技能简介",
                    "content" to "Markdown 技能正文",
                ),
                emptyList(),
            ))
            array.put(schema(
                "whale_skill_manage",
                "启停或删除已安装 Skill。",
                mapOf("action" to "enable / disable / delete", "name" to "Skill 名称或 slug"),
                listOf("action", "name"),
            ))
            array.put(schema("whale_mcp_list", "列出已配置 MCP 服务器及工具。", emptyMap(), emptyList()))
            array.put(schema(
                "whale_mcp_add",
                "接入标准 HTTP/JSON-RPC MCP 服务器并自动拉取工具。",
                mapOf(
                    "url" to "MCP 地址（https:// 或局域网 http://）",
                    "name" to "可选显示名",
                    "id" to "可选英文标识",
                    "authHeader" to "可选 Authorization 头",
                ),
                listOf("url"),
            ))
            array.put(schema(
                "whale_mcp_call",
                "调用指定 MCP 服务器的工具。",
                mapOf("server" to "服务器 id 或名", "tool" to "工具名", "argumentsJson" to "可选 JSON 参数"),
                listOf("server", "tool"),
            ))
            array.put(schema(
                "whale_mcp_manage",
                "刷新、启停或移除 MCP 服务器。",
                mapOf("action" to "refresh / enable / disable / remove", "id" to "服务器 id 或名"),
                listOf("action", "id"),
            ))
        }
        val allCustom = WhaleCustomToolEngine.customToolSchemas(app)
        val customSel = WhaleContextBudgeter.selectRelevantDynamicTools(
            allSchemas = allCustom,
            userQuery = text,
            expandAll = expandAll || "custom" in expandedCategories,
            explicitNames = explicitDynamicNames,
            maxAutoInject = 3,
        )
        customSel.selected.forEach { array.put(it) }

        val allMcp = WhaleMcpManager.mcpToolSchemas(app)
        val mcpSel = WhaleContextBudgeter.selectRelevantDynamicTools(
            allSchemas = allMcp,
            userQuery = text,
            expandAll = expandAll || "mcp" in expandedCategories || "extensions" in expandedCategories,
            explicitNames = explicitDynamicNames,
            maxAutoInject = 3,
        )
        mcpSel.selected.forEach { array.put(it) }

        val selectedCustomNames = customSel.selected.mapNotNull { it.optJSONObject("function")?.optString("name") }.toSet()
        val selectedMcpNames = mcpSel.selected.mapNotNull { it.optJSONObject("function")?.optString("name") }.toSet()
        val deferredChars = allCustom.filter { it.optJSONObject("function")?.optString("name") !in selectedCustomNames }.sumOf { it.toString().length } +
            allMcp.filter { it.optJSONObject("function")?.optString("name") !in selectedMcpNames }.sumOf { it.toString().length }
        val rawChars = array.toString().length + deferredChars
        val minified = WhaleContextBudgeter.minifyToolSchemas(array)
        return ToolSchemaBundle(
            schemas = minified,
            rawChars = rawChars,
            compactChars = minified.toString().length,
            totalDynamicTools = allCustom.size + allMcp.size,
            injectedDynamicTools = customSel.selected.size + mcpSel.selected.size,
            deferredCustomNames = customSel.deferredNames,
            deferredMcpNames = mcpSel.deferredNames,
        )
    }

    fun isMutating(name: String): Boolean = name.startsWith("custom_") || name.startsWith("mcp__") || name in setOf(
        "whale_skill_install",
        "whale_skill_manage",
        "whale_mcp_add",
        "whale_mcp_call",
        "whale_mcp_manage",
        "whale_tool_create",
        "whale_tool_delete",
        "whale_worker_submit",
        "whale_worker_cancel",
        "whale_worker_followup",
        "whale_proactive_mode",
        "whale_memory_delete",
        "whale_clipboard",
        "whale_device_media",
        "whale_schedule_create",
        "whale_schedule_cancel",
        "whale_open_url",
        "whale_share_text",
        "whale_phone_vision_click",
        "whale_worker_manage",
        "whale_ui_refresh",
        "whale_virtual_screen_control",
        "whale_virtual_screen_action",
        "whale_voice_speak",
        "whale_voice_config",
        "whale_app_navigate",
        "whale_app_appearance",
        "whale_app_language",
        "whale_app_settings_set",
        "whale_persona_manage",
        "whale_pet_control",
        "whale_memory_save",
        "whale_memory_update",
        "whale_memory_purge_noise",
        "whale_knowledge_delete",
        "whale_calendar_create",
        "whale_alarm_set",
        "whale_timer_set",
        "whale_owner_profile_edit",
        "whale_goal_create",
        "whale_goal_update",
        "whale_playbook_save",
        "whale_knowledge_save",
        "whale_secretary_add",
        "whale_secretary_complete",
        "whale_secretary_delete",
        "whale_workshop_draft",
        "whale_workshop_activate",
        "whale_workshop_open",
        "whale_workshop_pin",
        "whale_workshop_delete",
        "whale_code_write",
        "whale_code_patch",
        "whale_code_delete",
        "whale_code_run",
        "whale_html_to_pdf",
        "whale_file_export",
        "whale_remote_exec",
        "whale_remote_mcp",
        "whale_py_install",
        "whale_site_auth",
        "whale_worker_ask",
        "whale_worker_board",
        "whale_todo_write",
        "whale_phone_launch",
        "whale_phone_action",
        "whale_phone_batch",
        "whale_phone_back",
        "whale_phone_global",
    )

    fun execute(name: String, argsJson: String, callerSessionId: String = "whale-main"): ToolOutcome {
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
                    if (style in setOf("simple", "cute", "usagi")) {
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
                        "refine" -> {
                            val id = args.str("id", "").trim()
                            val refined = store.refineActive(args, id)
                            ToolOutcome(name, "根据对话修正角色卡：${refined.optString("name")}", true, refined.toString(), mutating = true)
                        }
                        "save" -> {
                            val card = JSONObject().put("name", args.str("name", "").trim())
                            val id = args.str("id", "").trim()
                            if (id.isNotEmpty()) card.put("id", id)
                            for (f in listOf("description", "personality", "scenario", "examples", "greeting", "systemPrompt", "postHistory", "worldBook")) {
                                val v = args.str(f, "").trim()
                                if (v.isNotEmpty()) card.put(f, v)
                            }
                            val fallbackPrompt = args.str("prompt", "").trim()
                            if (fallbackPrompt.isNotEmpty() && !card.has("systemPrompt")) card.put("systemPrompt", fallbackPrompt)
                            val saved = store.save(card)
                            ToolOutcome(name, "保存角色卡：${saved.optString("name")}", true, saved.toString(), mutating = true)
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
                            val raw = args.str("sizeDp", "88").toIntOrNull() ?: OverlayBallPreferences.DEFAULT_SIZE_DP
                            val size = raw.coerceIn(OverlayBallPreferences.MIN_SIZE_DP, OverlayBallPreferences.MAX_SIZE_DP)
                            OverlayBallPreferences.from(app).writeSizeDp(size)
                            val applied = OverlayBallService.applyCompanionSizePreference()
                            ToolOutcome(name, "调整桌宠尺寸为 ${size}dp", true, JSONObject().put("ok", true).put("sizeDp", size).put("appliedLive", applied).toString(), mutating = true)
                        }
                        "reset_position" -> {
                            OverlayBallPreferences.from(app).clearPosition()
                            ToolOutcome(name, "重置悬浮桌宠位置", true, JSONObject().put("ok", true).toString(), mutating = true)
                        }
                        else -> throw IllegalArgumentException("不支持的桌宠操作：$action")
                    }
                }
                "whale_recall" -> {
                    val query = args.str("query", "").trim()
                    val scope = args.str("scope", "all").trim().lowercase().ifEmpty { "all" }
                    val wantMem = scope == "all" || scope == "memory"
                    val wantKn = scope == "all" || scope == "knowledge"
                    val exec = java.util.concurrent.Executors.newFixedThreadPool(2)
                    try {
                        val memFuture = if (wantMem) exec.submit(java.util.concurrent.Callable {
                            val hybrid = if (query.isNotEmpty()) {
                                runCatching { SemanticMemorySearch.execute(app, JSONObject().put("query", query).put("limit", 6).toString()) }.getOrNull()
                            } else null
                            hybrid ?: CompanionMemoryStore(app).execute(
                                JSONObject().put("op", "search").put("query", query).toString(),
                                fromPetTurn = true,
                            )
                        }) else null
                        val knFuture = if (wantKn) exec.submit(java.util.concurrent.Callable {
                            val hybrid = if (query.isNotEmpty()) {
                                runCatching { SemanticKnowledgeSearch.execute(app, JSONObject().put("query", query).put("limit", 5).toString()) }.getOrNull()
                            } else null
                            if (hybrid != null) hybrid else {
                                val store = CompanionKnowledgeStore(app)
                                val rows = if (query.isEmpty()) store.recent(0, 8) else store.searchPage(query, 0, 8).first
                                rows.toString()
                            }
                        }) else null
                        val out = JSONObject().put("query", query).put("scope", scope)
                        if (memFuture != null) {
                            val memRaw = runCatching { memFuture.get(700L, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault("[]")
                            out.put("memories", runCatching { org.json.JSONTokener(memRaw).nextValue() }.getOrDefault(memRaw))
                        }
                        if (knFuture != null) {
                            val knRaw = runCatching { knFuture.get(800L, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault("[]")
                            out.put("knowledge", runCatching { org.json.JSONTokener(knRaw).nextValue() }.getOrDefault(knRaw))
                        }
                        val label = when (scope) {
                            "memory" -> "检索长期记忆"
                            "knowledge" -> "检索本地资料"
                            else -> "并行检索记忆与资料"
                        }
                        ToolOutcome(name, label, true, out.toString())
                    } finally {
                        exec.shutdownNow()
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
                "whale_memory_update" -> {
                    val id = args.str("id", "").trim()
                    val newText = args.str("text", "").trim()
                    require(id.isNotEmpty() && newText.isNotEmpty()) { "更新记忆需要提供 id 与 text" }
                    val store = CompanionMemoryStore(app)
                    val rows = store.listForUser()
                    var expectedText: String? = null
                    for (i in 0 until rows.length()) {
                        val row = rows.optJSONObject(i) ?: continue
                        if (row.optString("id") == id) {
                            expectedText = row.optString("text")
                            break
                        }
                    }
                    require(expectedText != null) { "未找到记忆 ID：$id" }
                    val req = JSONObject()
                        .put("op", "update")
                        .put("id", id)
                        .put("expectedText", expectedText)
                        .put("text", newText)
                        .put("operationId", java.util.UUID.randomUUID().toString())
                    val result = store.execute(req.toString(), fromPetTurn = true)
                    ToolOutcome(name, "更新长期记忆", true, result, mutating = true)
                }
                "whale_memory_purge_noise" -> {
                    val dryRun = args.optBoolean("dryRun", false)
                    val store = CompanionMemoryStore(app)
                    val rows = store.listForUser()
                    val noiseIds = mutableListOf<String>()
                    val samples = org.json.JSONArray()
                    for (i in 0 until rows.length()) {
                        val row = rows.optJSONObject(i) ?: continue
                        if (row.has("profileKey")) continue
                        val src = row.optString("source", "").lowercase()
                        val evSrc = row.optString("evidenceSource", "").lowercase()
                        val isNoise = src.contains("notification") || evSrc.contains("notification") ||
                            src == "auto_notification" || src == "ambient_capture"
                        if (isNoise) {
                            val id = row.optString("id")
                            if (id.isNotEmpty()) {
                                noiseIds.add(id)
                                if (samples.length() < 8) samples.put(row.optString("text").take(80))
                            }
                        }
                    }
                    var removed = 0
                    if (!dryRun) {
                        for (id in noiseIds) {
                            val res = runCatching {
                                JSONObject(store.execute(JSONObject().put("op", "delete").put("id", id).toString(), fromPetTurn = true))
                            }.getOrNull()
                            if (res?.optBoolean("removed") == true) removed++
                        }
                    }
                    val out = JSONObject()
                        .put("dryRun", dryRun)
                        .put("matchedNoiseCount", noiseIds.size)
                        .put("removedCount", removed)
                        .put("remainingTotal", (rows.length() - removed).coerceAtLeast(0))
                        .put("samples", samples)
                    ToolOutcome(name, if (dryRun) "预览通知噪音记忆" else "清理通知噪音记忆", true, out.toString(), mutating = !dryRun)
                }
                "whale_owner_profile" -> {
                    val profile = OwnerContext(app).read()
                    ToolOutcome(name, "读取主人档案", true, OwnerContext.prompt(profile))
                }
                "whale_goal_create" -> {
                    val title = args.optString("title", "").trim()
                    val nextAction = args.optString("nextAction", "").trim()
                    val subTasks = args.optString("subTasks", "").split(',', '，', ';', '；').map(String::trim).filter(String::isNotEmpty)
                    val blockers = args.optString("blockers", "").trim()
                    val g = WhaleGoalGraphStore.create(app, title, nextAction, subTasks, blockers)
                    ToolOutcome(name, "已建立目标：${g.title}", true, JSONObject().put("ok", true).put("goal", g.json()).toString())
                }
                "whale_goal_update" -> {
                    val idOrTitle = args.optString("idOrTitle", "").trim()
                    val status = args.optString("status", "").takeIf(String::isNotBlank)
                    val nextAction = args.optString("nextAction", "").takeIf(String::isNotBlank)
                    val blockers = if (args.has("blockers")) args.optString("blockers", "") else null
                    val updated = WhaleGoalGraphStore.update(app, idOrTitle, status = status, nextAction = nextAction, blockers = blockers)
                    if (updated != null) {
                        ToolOutcome(name, "已更新目标：${updated.title}", true, JSONObject().put("ok", true).put("goal", updated.json()).toString())
                    } else {
                        ToolOutcome(name, "未找到匹配目标", false, JSONObject().put("ok", false).put("error", "未找到目标：$idOrTitle").toString())
                    }
                }
                "whale_goal_list" -> {
                    val inc = args.optBoolean("includeCompleted", false)
                    val list = WhaleGoalGraphStore.list(app, includeCompleted = inc)
                    val arr = JSONArray()
                    list.forEach { arr.put(it.json()) }
                    ToolOutcome(name, "读取目标图（${list.size} 项）", true, JSONObject().put("ok", true).put("count", list.size).put("goals", arr).toString())
                }
                "whale_playbook_save" -> {
                    val trigger = args.optString("trigger", "").trim()
                    val stepsStr = args.optString("steps", "").trim()
                    val pb = WhalePlaybookStore.save(app, trigger, stepsStr)
                    ToolOutcome(name, "已记住习惯规则：${pb.trigger}", true, JSONObject().put("ok", true).put("playbook", pb.json()).toString())
                }
                "whale_playbook_list" -> {
                    val list = WhalePlaybookStore.list(app)
                    val arr = JSONArray()
                    list.forEach { arr.put(it.json()) }
                    ToolOutcome(name, "读取习惯规则（${list.size} 条）", true, JSONObject().put("ok", true).put("count", list.size).put("playbooks", arr).toString())
                }
                "whale_owner_profile_edit" -> {
                    val field = args.str("field", "").trim()
                    val action = args.str("action", "set").trim().lowercase().ifEmpty { "set" }
                    require(field in OwnerProfile.fields.keys) { "不支持的主人档案字段：$field" }
                    val store = CompanionMemoryStore(app)
                    val currentView = store.profile(forUser = true)
                    val fieldsArr = currentView.optJSONArray("fields")
                    var expectedRevision = ""
                    if (fieldsArr != null) {
                        for (i in 0 until fieldsArr.length()) {
                            val item = fieldsArr.optJSONObject(i) ?: continue
                            if (item.optString("field") == field) {
                                expectedRevision = item.optString("revision")
                                break
                            }
                        }
                    }
                    require(expectedRevision.isNotEmpty()) { "未能读取主人档案字段版本：$field" }
                    val req = JSONObject()
                        .put("action", action)
                        .put("field", field)
                        .put("expectedRevision", expectedRevision)
                    if (action == "set") {
                        val value = args.str("value", "").trim()
                        require(value.isNotEmpty()) { "设置主人档案字段 $field 需要提供 value" }
                        req.put("value", value)
                        req.put("operationId", java.util.UUID.randomUUID().toString())
                        if (field == "current_focus") {
                            val days = args.optInt("validUntilDays", 14).coerceIn(1, 365)
                            req.put("validUntilMs", System.currentTimeMillis() + days.toLong() * 86_400_000L)
                        }
                    }
                    val res = store.changeProfile(req, forUser = true, fromPetTurn = true)
                    val fieldLabel = OwnerProfile.fields[field] ?: field
                    val stepLabel = if (action == "forget") "清空主人档案：$fieldLabel" else "更新主人档案：$fieldLabel"
                    ToolOutcome(name, stepLabel, true, res.toString(), mutating = true)
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
                "whale_knowledge_read" -> {
                    val id = args.str("id", "").trim()
                    require(id.isNotEmpty()) { "请提供资料记录 id" }
                    val row = CompanionKnowledgeStore(app).findById(id)
                        ?: throw IllegalArgumentException("未找到该资料记录：$id")
                    val text = row.optString("text", "")
                    val out = JSONObject()
                        .put("id", row.optString("id"))
                        .put("source", row.optString("source"))
                        .put("createdAt", row.optLong("createdAt"))
                        .put("length", text.length)
                        .put("text", text.take(16000))
                    ToolOutcome(name, "读取资料全文", true, out.toString())
                }
                "whale_knowledge_delete" -> {
                    val id = args.str("id", "").trim()
                    require(id.isNotEmpty()) { "请提供要删除的资料记录 id" }
                    val store = CompanionKnowledgeStore(app)
                    val row = store.findById(id)
                        ?: throw IllegalArgumentException("未找到该资料记录：$id")
                    val removed = store.remove(id, row.optString("text", ""))
                    ToolOutcome(name, "删除本地资料", removed, JSONObject().put("id", id).put("removed", removed).toString(), mutating = true)
                }
                "whale_routine_manage" -> {
                    val action = args.str("action", "list").trim().lowercase()
                    when (action) {
                        "list" -> {
                            val rules = WhaleRoutineEngine.list(app)
                            val arr = JSONArray()
                            rules.forEach { arr.put(it.json()) }
                            ToolOutcome(name, "查看主动守护规则（${rules.size} 条）", true, JSONObject().put("count", rules.size).put("rules", arr).toString())
                        }
                        "create" -> {
                            val created = WhaleRoutineEngine.create(
                                app,
                                name = args.str("name", ""),
                                keywordRegex = args.str("keywordRegex", ""),
                                actionKind = args.str("actionKind", "add_todo"),
                                packageFilter = args.str("packageFilter", ""),
                                titleTemplate = args.str("titleTemplate", ""),
                                cooldownMinutes = args.optInt("cooldownMinutes", 10),
                            )
                            ToolOutcome(name, "已创建守护规则：${created.name}", true, JSONObject().put("ok", true).put("rule", created.json()).toString(), mutating = true)
                        }
                        "toggle" -> {
                            val target = args.str("idOrName", args.str("name", ""))
                            val en = args.optBoolean("enabled", true)
                            val updated = WhaleRoutineEngine.toggle(app, target, en)
                                ?: throw IllegalArgumentException("未找到守护规则：$target")
                            ToolOutcome(name, if (en) "已启用规则：${updated.name}" else "已暂停规则：${updated.name}", true, JSONObject().put("ok", true).put("rule", updated.json()).toString(), mutating = true)
                        }
                        "delete" -> {
                            val target = args.str("idOrName", args.str("name", ""))
                            val ok = WhaleRoutineEngine.delete(app, target)
                            ToolOutcome(name, if (ok) "已删除守护规则" else "未找到守护规则", ok, JSONObject().put("ok", ok).toString(), mutating = true)
                        }
                        else -> throw IllegalArgumentException("不支持的守护规则操作：$action")
                    }
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
                "whale_workshop_read" -> {
                    val id = args.str("id", "").trim()
                    val doc = WhaleFeatureStore(app).readHtml(id)
                        ?: throw IllegalArgumentException("未找到工坊小软件：$id")
                    ToolOutcome(name, "读取小软件源码：$id", true, doc.toString())
                }
                "whale_workshop_draft" -> {
                    val store = WhaleFeatureStore(app)
                    val id = args.str("id", "").trim()
                    if (id.isNotEmpty() && args.str("baseRevision", "").isEmpty()) {
                        val latest = store.read(id)?.let { if (it.isNull("latest")) "" else it.optString("latest", "") }.orEmpty()
                        if (latest.isNotEmpty()) args.put("baseRevision", latest)
                    }
                    var result = store.draft(args)
                    val autoActivate = if (args.has("autoActivate")) args.optBoolean("autoActivate", true) else true
                    val autoOpen = args.optBoolean("autoOpen", false)
                    if (autoActivate) {
                        val rev = result.optString("latest")
                        val curActive = result.optString("active")
                        if (rev.isNotEmpty() && rev != curActive) {
                            result = store.activate(id, rev, curActive)
                        }
                        if (autoOpen && rev.isNotEmpty()) {
                            WhaleFeatureActivity.openTool(app, id, rev)
                            result.put("opened", true)
                        }
                    }
                    ToolOutcome(name, if (autoActivate) "发布小软件：${result.optString("title")}" else "保存工坊软件草稿", true, result.toString(), mutating = true)
                }
                "whale_workshop_delete" -> {
                    val id = args.str("id", "").trim()
                    val removed = WhaleFeatureStore(app).delete(id)
                    ToolOutcome(name, "删除工坊小软件：$id", removed, JSONObject().put("id", id).put("removed", removed).toString(), mutating = true)
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
                "whale_html_to_pdf" -> {
                    val project = args.str("project").trim().ifBlank { "scratch" }
                    val relFile = args.str("file").trim()
                    val html = if (relFile.isNotEmpty()) {
                        val safeProj = project.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(48).ifBlank { "scratch" }
                        val projectDir = File(File(app.filesDir, "whale-projects"), safeProj).canonicalFile
                        val target = File(projectDir, relFile.trimStart('/')).canonicalFile
                        require(target.path.startsWith(projectDir.path + File.separator) && target.isFile) { "未找到 HTML 文件：$relFile" }
                        target.readText(Charsets.UTF_8)
                    } else {
                        args.str("html")
                    }
                    require(html.isNotBlank()) { "请提供 html 内容或项目内 file 路径" }
                    val fileName = args.str("fileName", "document.pdf").trim().ifBlank { "document.pdf" }
                    val exportDl = args.str("export", "true").trim().lowercase() != "false"
                    val shareNow = args.str("share", "false").trim().lowercase() == "true"
                    val res = WhaleHtmlPdfExporter.renderToPdf(app, html, project, fileName, exportDl, shareNow)
                    ToolOutcome(name, "生成 PDF：${res.optString("path")}（${res.optInt("pages")} 页）", true, res.toString(), mutating = true)
                }
                "whale_site_auth" -> {
                    when (args.str("action", "list").trim().lowercase()) {
                        "open_login" -> {
                            val url = args.str("url").trim()
                            require(url.startsWith("https://", ignoreCase = true)) { "仅支持 https:// 网址登录授权" }
                            val label = args.str("label").trim()
                            WhaleSiteLoginActivity.launch(app, url, label)
                            ToolOutcome(
                                name,
                                "已打开站点授权登录页",
                                true,
                                JSONObject()
                                    .put("opened", true)
                                    .put("url", url)
                                    .put("hint", "已为用户打开内嵌登录页。请提示用户完成登录后点击右上角「授权保存登录态」，完成后再继续读取私有数据。")
                                    .toString(),
                                mutating = true,
                            )
                        }
                        "revoke" -> {
                            val domain = args.str("url").trim().ifBlank { args.str("domain").trim() }
                            require(domain.isNotEmpty()) { "缺少要撤销授权的域名" }
                            val removed = WhaleSiteVault.revoke(app, domain)
                            ToolOutcome(name, if (removed) "已撤销站点登录态：$domain" else "未找到对应授权：$domain", removed, JSONObject().put("revoked", removed).put("domain", domain).toString(), mutating = true)
                        }
                        else -> {
                            val domains = WhaleSiteVault.listDomains(app)
                            ToolOutcome(name, "查看已授权站点列表", true, JSONObject().put("count", domains.length()).put("domains", domains).toString())
                        }
                    }
                }
                "whale_worker_ask" -> {
                    val question = args.str("question").trim()
                    require(question.isNotEmpty()) { "缺少要向用户确认的问题 question" }
                    val workerId = callerSessionId
                        .removePrefix("whale-worker-")
                        .replace(Regex("""-s\d+$"""), "")
                        .ifBlank { callerSessionId }
                    val res = WorkerRuntimeWake.askUserAndPause(app, workerId, question)
                    ToolOutcome(name, "已向用户发起确认并挂起", true, res.toString(), mutating = true)
                }
                "whale_worker_board" -> {
                    when (args.str("action", "list").trim().lowercase()) {
                        "put" -> {
                            val key = args.str("key").trim()
                            val value = args.str("value")
                            val res = WhaleWorkerBoard.put(app, key, value, callerSessionId)
                            ToolOutcome(name, "写入共享黑板：$key", true, res.toString(), mutating = true)
                        }
                        "get" -> {
                            val key = args.str("key").trim()
                            val hit = WhaleWorkerBoard.get(app, key)
                            if (hit == null) {
                                ToolOutcome(name, "黑板未找到键：$key", false, JSONObject().put("found", false).put("key", key).toString())
                            } else {
                                ToolOutcome(name, "读取共享黑板：$key", true, hit.toString())
                            }
                        }
                        "remove" -> {
                            val key = args.str("key").trim()
                            val removed = WhaleWorkerBoard.remove(app, key)
                            ToolOutcome(name, if (removed) "已删除黑板键：$key" else "未找到黑板键：$key", removed, JSONObject().put("removed", removed).put("key", key).toString(), mutating = true)
                        }
                        else -> {
                            val prefix = args.str("prefix").trim()
                            val items = WhaleWorkerBoard.list(app, prefix)
                            ToolOutcome(name, "列出共享黑板键", true, JSONObject().put("count", items.length()).put("items", items).toString())
                        }
                    }
                }
                "whale_remote_exec" -> {
                    val cmd = args.str("command").trim()
                    val lang = args.str("language", "shell").trim()
                    val timeout = args.str("timeoutSec", "45").toIntOrNull() ?: 45
                    val res = WhaleRemoteBridge.execRemote(app, cmd, lang, timeout)
                    ToolOutcome(name, "远程节点执行（$lang）", res.optBoolean("ok", false), res.toString(), mutating = true)
                }
                "whale_remote_mcp" -> {
                    val act = args.str("action", "list").trim().lowercase()
                    val server = args.str("server").trim()
                    val res = if (act == "call") {
                        val tool = args.str("tool").trim()
                        val argObj = args.optJSONObject("arguments") ?: runCatching { JSONObject(args.str("arguments", "{}")) }.getOrElse { JSONObject() }
                        WhaleRemoteBridge.callMcp(app, server, tool, argObj)
                    } else {
                        WhaleRemoteBridge.listMcp(app, server)
                    }
                    ToolOutcome(name, if (act == "call") "调用远程 MCP：$server" else "查询远程 MCP", res.optBoolean("ok", false), res.toString(), mutating = act == "call")
                }
                "whale_file_export" -> {
                    val project = args.str("project").trim().ifBlank { "scratch" }
                    val relPath = args.str("path").trim()
                    require(relPath.isNotEmpty()) { "缺少待导出文件的相对路径 path" }
                    val shareNow = args.str("share", "false").trim().lowercase() == "true"
                    val res = WhaleCodeRunner.exportProjectFile(app, project, relPath, shareNow)
                    ToolOutcome(name, "导出文件：${res.optString("fileName")}", true, res.toString(), mutating = true)
                }
                "whale_py_install" -> {
                    val pkg = args.str("package").trim()
                    require(pkg.isNotEmpty()) { "缺少要安装的 Python 包名 package" }
                    val mirror = args.str("mirrorUrl").trim()
                    val res = WhaleCodeRunner.installPurePythonPackage(app, pkg, mirror)
                    val ok = res.optBoolean("ok", false)
                    ToolOutcome(name, if (ok) "已安装 Python 包：$pkg" else "安装 Python 包失败：$pkg", ok, res.toString(), mutating = true)
                }
                "whale_code_run" -> {
                    val project = args.str("project").trim().ifBlank { "scratch" }
                    val lang = args.str("lang", "python").trim().lowercase()
                    val relFile = args.str("file").trim()
                    val rawCode = args.str("code")
                    val safeProj = project.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(48).ifBlank { "scratch" }
                    val projectDir = File(File(app.filesDir, "whale-projects"), safeProj).apply { mkdirs() }
                    val scriptCode = if (relFile.isNotEmpty()) {
                        val target = File(projectDir, relFile).canonicalFile
                        require(target.path.startsWith(projectDir.canonicalPath)) { "文件路径越界" }
                        require(target.isFile) { "未找到脚本文件：$relFile" }
                        target.readText(Charsets.UTF_8)
                    } else {
                        rawCode
                    }
                    require(scriptCode.isNotBlank()) { "请提供 code 或 file 参数" }
                    val timeoutSec = args.optLong("timeoutSec", 45L).coerceIn(3L, 180L)
                    val result = if (lang == "sh" || lang == "shell") {
                        WhaleCodeRunner.runShell(projectDir, scriptCode, timeoutSec = timeoutSec.coerceAtMost(120L))
                    } else {
                        val ptcGuard = WhaleHarnessCore.RepeatToolGuard(thresholds = setOf(3, 5), hardStopThreshold = 6)
                        val ptcLedger = UnifiedExecutionLedger(app)
                        val ptcBridge = WhaleCodeRunner.ToolBridge { subTool, subArgsJson ->
                            val decision = ptcGuard.observe(subTool, subArgsJson)
                            if (decision.hardBlock) {
                                return@ToolBridge JSONObject()
                                    .put("ok", false)
                                    .put("error", "PTC_REPEAT_LOOP_ABORTED")
                                    .put("hint", "Python PTC 中连续重复调用 $subTool 已达 ${decision.count} 次，已自动熔断。")
                                    .toString()
                            }
                            val mut = isMutating(subTool)
                            val opId = "ptc-${UUID.randomUUID().toString().take(10)}"
                            if (mut) {
                                ptcLedger.recordIntent(opId, "ptc-$safeProj", "python-ptc", 0L, subTool)
                                ptcLedger.transition(opId, UnifiedExecutionLedger.State.DISPATCHING)
                            }
                            val subOutcome = execute(subTool, subArgsJson)
                            if (mut) {
                                ptcLedger.transition(
                                    opId,
                                    if (subOutcome.ok) UnifiedExecutionLedger.State.COMPLETED else UnifiedExecutionLedger.State.FAILED,
                                    subOutcome.label,
                                )
                            }
                            JSONObject()
                                .put("ok", subOutcome.ok)
                                .put("label", subOutcome.label)
                                .put("payload", runCatching { JSONObject(subOutcome.payload) }.getOrElse { subOutcome.payload })
                                .toString()
                        }
                        val replSession = args.str("sessionId").trim().ifBlank { "proj-$safeProj" }
                        val resetRepl = args.str("resetSession", "false").trim().lowercase() == "true"
                        WhaleCodeRunner.runPython(
                            app,
                            projectDir,
                            scriptCode,
                            timeoutSec = timeoutSec,
                            toolBridge = ptcBridge,
                            sessionId = replSession,
                            resetSession = resetRepl,
                        )
                    }
                    val ok = result.optBoolean("ok", false)
                    ToolOutcome(name, if (lang == "sh" || lang == "shell") "运行 Shell：$safeProj" else "运行 Python：$safeProj", ok, result.toString(), mutating = true)
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
                        ?: run {
                            if (WhaleAutoHealBridge.isShizukuReady()) WhaleAutoHealBridge.autoHeal(app, relaxWhitelist = false)
                            DeepSeekAccessibilityService.current()
                        }
                        ?: throw IllegalStateException("无障碍服务未开启或未连上，可调用 whale_automation_heal 或在「我的 → 自动化设置」一键修复")
                    val res = withPhoneControlNotice("正在读取屏幕…") { service.fastControl("fastTree", "") }
                    ToolOutcome(name, "极速读取精简控件树", res.ok, res.text)
                }
                "whale_phone_action" -> {
                    val service = DeepSeekAccessibilityService.current()
                        ?: run {
                            if (WhaleAutoHealBridge.isShizukuReady()) WhaleAutoHealBridge.autoHeal(app, relaxWhitelist = false)
                            DeepSeekAccessibilityService.current()
                        }
                        ?: throw IllegalStateException("无障碍服务未开启或未连上，可调用 whale_automation_heal 或在「我的 → 自动化设置」一键修复")
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
                    val dashboard = WorkerDashboardStore(app)
                    val poolIds = WhaleNativeWorkerPool.activeIds()
                    val dashIds = dashboard.activeIds()
                    val mergedActiveIds = poolIds + dashIds
                    val dashPage = runCatching { dashboard.page("all", "", 0, 20, true) }.getOrElse { JSONObject() }
                    val dashItems = dashPage.optJSONArray("items") ?: JSONArray()
                    val badgeCount = dashPage.optInt("attention", 0).let { att ->
                        if (att > 0) att else dashPage.optInt("running", mergedActiveIds.size)
                    }
                    val phase = io.deepseekharness.mobile.DeviceControlState.current.phase
                    val briefings = JSONArray().apply {
                        runCatching { WorkerReportInbox(app).briefings().takeLast(6) }.getOrNull().orEmpty().forEach { put(it) }
                    }
                    val payload = JSONObject()
                        .put("activeWorkers", mergedActiveIds.size)
                        .put("badgeCount", badgeCount)
                        .put("nativeWorkers", WhaleNativeWorkerPool.snapshot())
                        .put("dashboardWorkers", dashItems)
                        .put("recentBriefings", briefings)
                        .put("deviceControlPhase", phase)
                    if (targetId.isNotEmpty()) {
                        WhaleNativeWorkerPool.get(targetId)?.let { payload.put("targetWorker", it) }
                            ?: runCatching { dashboard.detail(targetId, 0, 12) }.getOrNull()?.let { payload.put("targetWorker", it) }
                    }
                    ToolOutcome(name, "检查后台任务、实时进度与徽标状态", true, payload.toString())
                }
                "whale_worker_submit" -> {
                    val task = args.str("task").trim()
                    require(task.isNotEmpty()) { "缺少后台任务说明 task" }
                    val title = args.str("title").trim().takeIf { it.isNotEmpty() } ?: task.take(36)
                    WhaleSuperagentCore.MandateGuard.validateBriefText(title, "title", maxChars = 80)?.let { err ->
                        return ToolOutcome(name, "任务标题过长", false, JSONObject().put("error", err).toString())
                    }
                    val mode = args.str("mode", "create").trim().takeIf { it in setOf("read", "device", "create") } ?: "create"
                    val inheritContext = args.str("inheritContext", "false").trim().lowercase() == "true"
                    val forkPrefix = if (inheritContext) WhaleHarnessCore.ContextForkHelper.buildForkPrefix(app, maxTurns = 4, maxChars = 600) else ""
                    val cleanTask = task.take(1850)
                    val fullTask = if (forkPrefix.isNotBlank() && forkPrefix.length + cleanTask.length + 24 <= 1980) {
                        "$forkPrefix\n\n[本后台任务目标]\n$cleanTask"
                    } else {
                        cleanTask
                    }
                    val dependsOn = args.str("dependsOn", "")
                        .split(',', '，', ';')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .take(6)
                    val receipt = WorkerRuntimeWake.submit(
                        context = app,
                        task = fullTask,
                        mode = mode,
                        title = title,
                        needs = "none",
                        sourceSession = null,
                        dependsOn = dependsOn,
                    )
                    val workerId = receipt.optString("commandId")
                    ToolOutcome(
                        name,
                        "已派发独立后台 Worker：$title",
                        true,
                        receipt
                            .put("id", workerId)
                            .put("title", title)
                            .put("mode", mode)
                            .put("isolated", true)
                            .put("hint", "独立后台 Worker 已在专属线程异步启动（任务 ID：$workerId）。请立即简短告知用户已派发并结束本轮回复，切勿在主会话中重复执行该任务！")
                            .toString(),
                        mutating = true,
                    )
                }
                "whale_worker_cancel" -> {
                    val id = args.str("id").trim()
                    require(id.isNotEmpty()) { "缺少 Worker ID" }
                    val cancelled = WhaleNativeWorkerPool.cancel(id)
                    WhaleLongTaskStore.markTerminal(app, id, "cancelled")
                    ToolOutcome(name, if (cancelled) "已取消后台 Worker" else "未找到运行中的后台 Worker", cancelled, JSONObject().put("id", id).put("cancelled", cancelled).toString(), mutating = true)
                }
                "whale_worker_followup" -> {
                    val id = args.str("id").trim()
                    val msg = args.str("message").trim()
                    require(id.isNotEmpty()) { "缺少目标 Worker ID" }
                    WhaleSuperagentCore.MandateGuard.validateBriefText(msg, "message", 2000)?.let { err ->
                        return ToolOutcome(name, "追问参数超限", false, JSONObject().put("error", err).toString())
                    }
                    val refs = args.str("refs").trim()
                    val enriched = if (refs.isNotEmpty()) "$msg\n\n[附带参考资源指针：$refs]" else msg
                    val res = WorkerRuntimeWake.followupOrResume(app, id, enriched)
                    val label = if (res.optBoolean("resumed", false)) "已唤醒 Worker 原地续跑" else "已实时透传追问给运行中 Worker"
                    ToolOutcome(name, label, true, res.toString(), mutating = true)
                }
                "whale_read_resource" -> {
                    val resource = args.str("resource").trim()
                    val offset = args.optInt("offset", 0)
                    val limit = args.optInt("limit", 2000)
                    require(resource.isNotEmpty()) { "缺少 resource 参数" }
                    val fullText: String? = when {
                        resource.startsWith("spill/") -> WhaleHarnessCore.SpillAndPruneManager.readSpill(app, resource.removePrefix("spill/"))
                        resource.startsWith("worker/") -> {
                            val wid = resource.removePrefix("worker/")
                            WhaleNativeWorkerPool.readFullResult(wid)
                                ?: runCatching { WorkerDashboardStore(app).detail(wid, 0, 12).optString("summary").takeIf(String::isNotBlank) }.getOrNull()
                        }
                        resource.startsWith("checkpoint/") -> WhaleLongTaskStore.read(app, resource.removePrefix("checkpoint/"))?.progressDigest
                        resource.startsWith("knowledge/") -> runCatching {
                            CompanionKnowledgeStore(app).findById(resource.removePrefix("knowledge/"))?.optString("text")
                        }.getOrNull()
                        else -> null
                    }
                    if (fullText == null) {
                        ToolOutcome(name, "未找到指定资源", false, JSONObject().put("error", "NOT_FOUND").put("resource", resource).toString())
                    } else {
                        val page = WhaleSuperagentCore.ResourceTruncator.readPage(fullText, offset, limit)
                            .put("resource", resource)
                        ToolOutcome(name, "分页读取资源 $resource", true, page.toString())
                    }
                }
                "whale_todo_write" -> {
                    val rawTodos = args.opt("todos")
                    val arr = when (rawTodos) {
                        is JSONArray -> rawTodos
                        is String -> JSONArray(rawTodos.trim().ifBlank { "[]" })
                        else -> JSONArray()
                    }
                    val res = WhaleHarnessCore.SessionTodoStore.write("whale-main", arr, app)
                    ToolOutcome(name, "更新任务进度清单", true, res.toString(), mutating = true)
                }
                "whale_proactive_mode" -> {
                    when (args.str("action", "status").trim().lowercase()) {
                        "set_mode" -> {
                            val mode = WhaleSuperagentCore.ProactiveObserveStore.setMode(app, args.str("mode", "observe").trim().lowercase())
                            ToolOutcome(name, "已切换主动模式为 $mode", true, JSONObject().put("mode", mode).toString(), mutating = true)
                        }
                        "list_drafts" -> {
                            val drafts = WhaleSuperagentCore.ProactiveObserveStore.listDrafts(app)
                            ToolOutcome(name, "查看主动观察草稿箱", true, JSONObject().put("mode", WhaleSuperagentCore.ProactiveObserveStore.getMode(app)).put("drafts", drafts).toString())
                        }
                        "record_draft" -> {
                            val title = args.str("title").trim().ifBlank { "主动建议草稿" }
                            val draftAction = args.str("draftAction").trim()
                            require(draftAction.isNotEmpty()) { "缺少草稿内容 draftAction" }
                            val reason = args.str("reason", "规则观察命中").trim()
                            val entry = WhaleSuperagentCore.ProactiveObserveStore.recordSpeculativeDraft(app, "assistant", title, draftAction, reason)
                            ToolOutcome(name, "已记录主动观察草稿（未自动执行）", true, entry.toString(), mutating = true)
                        }
                        "dismiss_draft" -> {
                            val id = args.str("id").trim()
                            val removed = WhaleSuperagentCore.ProactiveObserveStore.dismissDraft(app, id)
                            ToolOutcome(name, if (removed) "已忽略该观察草稿" else "未找到该草稿", removed, JSONObject().put("id", id).put("removed", removed).toString(), mutating = true)
                        }
                        else -> {
                            val mode = WhaleSuperagentCore.ProactiveObserveStore.getMode(app)
                            val drafts = WhaleSuperagentCore.ProactiveObserveStore.listDrafts(app, 6)
                            ToolOutcome(name, "读取主动观察模式状态", true, JSONObject().put("mode", mode).put("draftCount", drafts.length()).put("drafts", drafts).toString())
                        }
                    }
                }
                "whale_web_search" -> {
                    val q = args.str("query").trim()
                    require(q.isNotEmpty()) { "缺少搜索关键词 query" }
                    val limit = args.optInt("limit", 5).coerceIn(1, 8)
                    val platform = args.str("platform", "auto").trim()
                    val res = WhaleWebFetcher.search(q, limit, platform, app)
                    ToolOutcome(name, "联网搜索：$q", res.optInt("count", 0) > 0 || res.has("xhsSearchUrl"), res.toString())
                }
                "whale_image_search" -> {
                    val q = args.str("query", "").trim()
                    val imgPath = args.str("imagePath", "").trim()
                    val lim = args.optInt("limit", 6)
                    val res = WhaleWebFetcher.searchImages(query = q, imagePath = imgPath, limit = lim)
                    val cnt = res.optInt("count", 0)
                    ToolOutcome(name, if (cnt > 0) "搜到 $cnt 张相关图片" else "已完成以图搜图查询", res.optBoolean("ok", false), res.toString())
                }
                "whale_web_render" -> {
                    val rawUrl = args.str("url").trim()
                    require(rawUrl.isNotEmpty()) { "缺少要渲染的网页 url" }
                    val waitMs = args.optLong("waitMs", 2600L).coerceIn(1000L, 8000L)
                    val maxChars = args.optInt("maxChars", 6000).coerceIn(500, 12_000)
                    val res = WhaleWebRenderer.renderAndExtract(app, rawUrl, waitMs, maxChars)
                    ToolOutcome(name, "浏览器渲染读取网页", res.optBoolean("ok", false), res.toString())
                }
                "whale_web_fetch" -> {
                    val rawUrl = args.str("url").trim()
                    val maxChars = args.optInt("maxChars", 6000).coerceIn(500, 12_000)
                    val fetched = WhaleWebFetcher.fetch(rawUrl, maxChars, app)
                    ToolOutcome(
                        name,
                        if (fetched.title.isNotBlank()) "抓取网页：${fetched.title.take(28)}" else "抓取网页内容",
                        fetched.ok,
                        JSONObject()
                            .put("url", fetched.finalUrl)
                            .put("status", fetched.status)
                            .put("title", fetched.title)
                            .put("truncatedBytes", fetched.truncatedBytes)
                            .put("content", fetched.content)
                            .toString(),
                    )
                }
                "whale_memory_delete" -> {
                    val id = args.str("id").trim()
                    require(id.isNotEmpty()) { "缺少记忆 UUID" }
                    val res = CompanionMemoryStore(app).execute(JSONObject().put("op", "delete").put("id", id).toString(), fromPetTurn = true)
                    ToolOutcome(name, "删除长期记忆", true, res, mutating = true)
                }
                "whale_clipboard" -> {
                    val action = args.str("action", "get").trim().lowercase()
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var outText = ""
                    var errMsg: String? = null
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            if (action == "set") {
                                val value = args.str("text")
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("whale", value))
                                outText = value
                            } else {
                                outText = cm.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString().orEmpty()
                            }
                        } catch (t: Throwable) {
                            errMsg = t.message ?: t.javaClass.simpleName
                        } finally {
                            latch.countDown()
                        }
                    }
                    latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                    if (errMsg != null) throw IllegalStateException(errMsg)
                    ToolOutcome(name, if (action == "set") "写入剪贴板" else "读取剪贴板", true, JSONObject().put("action", action).put("text", outText.take(4000)).toString(), mutating = action == "set")
                }
                "whale_device_media" -> {
                    val action = args.str("action").trim().lowercase()
                    val am = app.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    when (action) {
                        "volume_up" -> am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, 0)
                        "volume_down" -> am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, 0)
                        "mute" -> am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_MUTE, 0)
                        "set_volume" -> {
                            val pct = args.optInt("percent", 50).coerceIn(0, 100)
                            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (max * pct / 100), 0)
                        }
                        "play_pause", "next", "prev" -> {
                            val key = when (action) {
                                "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                                "prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                                else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                            }
                            val now = android.os.SystemClock.uptimeMillis()
                            am.dispatchMediaKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, key, 0))
                            am.dispatchMediaKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, key, 0))
                        }
                        else -> throw IllegalArgumentException("不支持的媒体控制动作：$action")
                    }
                    val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    ToolOutcome(name, "媒体与音量控制：$action", true, JSONObject().put("action", action).put("volumePercent", cur * 100 / max).toString(), mutating = true)
                }
                "whale_calendar_read" -> {
                    val snap = CompanionCalendar.read(app, days = 7)
                    if (snap.state == CompanionCalendar.State.NO_PERMISSION) {
                        throw IllegalStateException("未授予系统日历读取权限，请先在系统权限中开启日历权限")
                    }
                    val arr = JSONArray()
                    snap.events.take(20).forEach { ev ->
                        arr.put(JSONObject().put("title", ev.title).put("startMs", ev.start).put("endMs", ev.end).put("allDay", ev.allDay))
                    }
                    ToolOutcome(name, "读取未来 7 天系统日程", snap.state == CompanionCalendar.State.OK, JSONObject().put("count", arr.length()).put("events", arr).toString())
                }
                "whale_calendar_create" -> {
                    val title = args.str("title", "").trim()
                    val startRaw = args.str("startIso", "").trim()
                    require(title.isNotEmpty() && startRaw.isNotEmpty()) { "创建日程需要 title 和 startIso" }
                    val durationMin = args.optInt("durationMinutes", 60).coerceIn(5, 1440)
                    val location = args.str("location", "").trim()
                    val description = args.str("description", "").trim()
                    val startMs = startRaw.toLongOrNull() ?: run {
                        val zone = java.time.ZoneId.systemDefault()
                        runCatching { java.time.Instant.parse(startRaw).toEpochMilli() }
                            .recoverCatching { java.time.OffsetDateTime.parse(startRaw).toInstant().toEpochMilli() }
                            .recoverCatching { java.time.LocalDateTime.parse(startRaw.replace(" ", "T")).atZone(zone).toInstant().toEpochMilli() }
                            .getOrThrow()
                    }
                    val endMs = startMs + durationMin.toLong() * 60_000L
                    val hasWritePerm = androidx.core.content.ContextCompat.checkSelfPermission(
                        app,
                        android.Manifest.permission.WRITE_CALENDAR,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    var directInsertedId: Long? = null
                    if (hasWritePerm) {
                        var calId = 1L
                        runCatching {
                            app.contentResolver.query(
                                android.provider.CalendarContract.Calendars.CONTENT_URI,
                                arrayOf(android.provider.CalendarContract.Calendars._ID),
                                "${android.provider.CalendarContract.Calendars.VISIBLE} = 1",
                                null,
                                null,
                            )?.use { c -> if (c.moveToFirst()) calId = c.getLong(0) }
                        }
                        val values = android.content.ContentValues().apply {
                            put(android.provider.CalendarContract.Events.CALENDAR_ID, calId)
                            put(android.provider.CalendarContract.Events.TITLE, title)
                            put(android.provider.CalendarContract.Events.DTSTART, startMs)
                            put(android.provider.CalendarContract.Events.DTEND, endMs)
                            put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                            if (location.isNotEmpty()) put(android.provider.CalendarContract.Events.EVENT_LOCATION, location)
                            if (description.isNotEmpty()) put(android.provider.CalendarContract.Events.DESCRIPTION, description)
                        }
                        val uri = runCatching {
                            app.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
                        }.getOrNull()
                        directInsertedId = uri?.lastPathSegment?.toLongOrNull()
                    }
                    if (directInsertedId != null) {
                        ToolOutcome(
                            name,
                            "写入系统日历：$title",
                            true,
                            JSONObject().put("mode", "direct_insert").put("eventId", directInsertedId).put("title", title).put("startMs", startMs).put("endMs", endMs).toString(),
                            mutating = true,
                        )
                    } else {
                        val intent = android.content.Intent(android.content.Intent.ACTION_INSERT).apply {
                            data = android.provider.CalendarContract.Events.CONTENT_URI
                            putExtra(android.provider.CalendarContract.Events.TITLE, title)
                            putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                            putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                            if (location.isNotEmpty()) putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, location)
                            if (description.isNotEmpty()) putExtra(android.provider.CalendarContract.Events.DESCRIPTION, description)
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        app.startActivity(intent)
                        ToolOutcome(
                            name,
                            "拉起系统日历创建：$title",
                            true,
                            JSONObject().put("mode", "intent_prefill").put("title", title).put("startMs", startMs).put("endMs", endMs).toString(),
                            mutating = true,
                        )
                    }
                }
                "whale_alarm_set" -> {
                    val hour = args.optInt("hour", -1)
                    val minute = args.optInt("minute", -1)
                    require(hour in 0..23 && minute in 0..59) { "闹钟时间无效，需 hour(0~23) 与 minute(0~59)" }
                    val message = args.str("message", "鲸鱼助手闹钟").trim().ifEmpty { "鲸鱼助手闹钟" }
                    val skipUi = args.optBoolean("skipUi", true)
                    val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                        putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, message)
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, skipUi)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    app.startActivity(intent)
                    ToolOutcome(
                        name,
                        String.format(java.util.Locale.US, "设置系统闹钟 %02d:%02d", hour, minute),
                        true,
                        JSONObject().put("hour", hour).put("minute", minute).put("message", message).put("skipUi", skipUi).toString(),
                        mutating = true,
                    )
                }
                "whale_timer_set" -> {
                    val seconds = args.optInt("seconds", -1)
                    require(seconds in 1..86400) { "倒计时秒数需在 1~86400 之间" }
                    val message = args.str("message", "鲸鱼助手倒计时").trim().ifEmpty { "鲸鱼助手倒计时" }
                    val skipUi = args.optBoolean("skipUi", true)
                    val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, message)
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, skipUi)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    app.startActivity(intent)
                    ToolOutcome(
                        name,
                        "启动系统倒计时（${seconds}秒）",
                        true,
                        JSONObject().put("seconds", seconds).put("message", message).put("skipUi", skipUi).toString(),
                        mutating = true,
                    )
                }
                "whale_notifications_read" -> {
                    val store = CompanionStore(app)
                    val authorized = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)
                    val req = JSONObject()
                        .put("query", args.str("query", "").trim())
                        .put("limit", args.optInt("limit", 8).coerceIn(1, 10))
                        .toString()
                    val res = CompanionNotificationModel.execute(
                        enabled = true,
                        param = req,
                        now = System.currentTimeMillis(),
                        capture = store.notificationCapture,
                        authorized = authorized,
                        connected = CompanionNotificationService.isConnected(),
                        excluded = store.excludedPackages(),
                    ) { store.read() }
                    ToolOutcome(name, "读取本机通知摘要", true, res)
                }
                "whale_schedule_create" -> {
                    val task = args.str("task").trim()
                    require(task.isNotEmpty()) { "缺少定时任务内容 task" }
                    val delayMin = args.optLong("delayMinutes", 15L)
                    val title = args.str("title").trim().ifBlank { task.take(32) }
                    val repeatDaily = args.optBoolean("repeatDaily", false)
                    val created = WhaleScheduledTaskManager.create(app, title, task, delayMin, repeatDaily)
                    ToolOutcome(name, "已设定定时任务：$title（${delayMin}分钟后）", true, created.toString(), mutating = true)
                }
                "whale_schedule_list" -> {
                    val items = WhaleScheduledTaskManager.list(app)
                    ToolOutcome(name, "查询定时任务列表", true, JSONObject().put("count", items.length()).put("items", items).toString())
                }
                "whale_schedule_cancel" -> {
                    val id = args.str("id").trim()
                    require(id.isNotEmpty()) { "缺少定时任务 ID" }
                    val removed = WhaleScheduledTaskManager.cancel(app, id)
                    ToolOutcome(name, if (removed) "已取消定时任务" else "未找到该定时任务", removed, JSONObject().put("id", id).put("cancelled", removed).toString(), mutating = true)
                }
                "whale_device_status" -> {
                    val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val am = app.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    val volCur = am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
                    val volMax = (am?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 1).coerceAtLeast(1)
                    val meter = WhaleTokenMeter.snapshot(app)
                    val payload = JSONObject()
                        .put("batteryPercent", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
                        .put("charging", bm?.isCharging == true)
                        .put("volumePercent", volCur * 100 / volMax)
                        .put("accessibilityConnected", DeepSeekAccessibilityService.current() != null)
                        .put("activeWorkers", (WorkerDashboardStore(app).activeIds() + WhaleNativeWorkerPool.activeIds()).size)
                        .put("scheduledTasks", WhaleScheduledTaskManager.list(app).length())
                        .put("todayTokens", meter.totalTokens)
                        .put("todayEstimatedYuan", String.format(java.util.Locale.US, "%.4f", meter.estimatedYuan))
                        .toString()
                    ToolOutcome(name, "读取手机综合状态", true, payload)
                }
                "whale_open_url" -> {
                    val rawUrl = args.str("url").trim()
                    require(rawUrl.startsWith("https://") || rawUrl.startsWith("http://")) { "仅支持 http:// 或 https:// 网址" }
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(rawUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    app.startActivity(intent)
                    ToolOutcome(name, "打开网页链接", true, JSONObject().put("opened", rawUrl).toString(), mutating = true)
                }
                "whale_share_text" -> {
                    val text = args.str("text").trim()
                    require(text.isNotEmpty()) { "缺少要分享的文本 text" }
                    val title = args.str("title").trim().ifBlank { "分享内容" }
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                        putExtra(Intent.EXTRA_SUBJECT, title)
                    }
                    val chooser = Intent.createChooser(sendIntent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    app.startActivity(chooser)
                    ToolOutcome(name, "唤起系统分享", true, JSONObject().put("sharedChars", text.length).toString(), mutating = true)
                }
                "whale_phone_vision" -> {
                    val service = DeepSeekAccessibilityService.current()
                        ?: throw IllegalStateException("无障碍服务未开启，请先在「我的 → 手机能力」中开启鲸鱼助手无障碍服务")
                    val question = args.str("question", "").trim()
                    val cap = withPhoneControlNotice("正在看图识屏…") { service.captureScreenJpeg(annotateRefs = true) }
                    if (!cap.ok || cap.jpeg == null) {
                        ToolOutcome(name, "看图识屏受限", false, JSONObject().put("error", cap.error ?: "截屏失败").toString())
                    } else {
                        val analysis = WhaleVisionScreenAnalyzer.analyzeScreen(
                            context = app,
                            jpeg = cap.jpeg,
                            screenWidth = cap.width,
                            screenHeight = cap.height,
                            packageName = cap.packageName,
                            question = question,
                            locateTarget = false,
                        )
                        ToolOutcome(
                            name,
                            if (analysis.ok) "多模态看图识屏完成" else "视觉识屏失败",
                            analysis.ok,
                            JSONObject()
                                .put("packageName", cap.packageName)
                                .put("screenWidth", cap.width)
                                .put("screenHeight", cap.height)
                                .put("provider", analysis.provider)
                                .put("model", analysis.model)
                                .put("analysis", analysis.text)
                                .toString(),
                        )
                    }
                }
                "whale_phone_vision_click" -> {
                    val service = DeepSeekAccessibilityService.current()
                        ?: throw IllegalStateException("无障碍服务未开启，请先在「我的 → 手机能力」中开启鲸鱼助手无障碍服务")
                    val target = args.str("target").trim()
                    require(target.isNotEmpty()) { "缺少要视觉定位点击的目标 target" }
                    val cap = withPhoneControlNotice("视觉定位「${target.take(10)}」…") { service.captureScreenJpeg(annotateRefs = true) }
                    if (!cap.ok || cap.jpeg == null) {
                        ToolOutcome(name, "视觉截屏受限", false, JSONObject().put("error", cap.error ?: "截屏失败").toString(), mutating = true)
                    } else {
                        val loc = WhaleVisionScreenAnalyzer.analyzeScreen(
                            context = app,
                            jpeg = cap.jpeg,
                            screenWidth = cap.width,
                            screenHeight = cap.height,
                            packageName = cap.packageName,
                            question = target,
                            locateTarget = true,
                        )
                        if (!loc.ok || (loc.ref == null && (loc.x == null || loc.y == null))) {
                            ToolOutcome(name, "未能在画面中找到目标", false, JSONObject().put("error", loc.text).put("provider", loc.provider).put("model", loc.model).toString(), mutating = true)
                        } else {
                            val tapJson = JSONObject().put("action", "click")
                            if (loc.ref != null) tapJson.put("ref", loc.ref)
                            if (loc.x != null && loc.y != null) tapJson.put("x", loc.x).put("y", loc.y)
                            val tapRes = withPhoneControlNotice("视觉点击「${loc.text.take(10)}」") { service.fastControl("fastAct", tapJson.toString()) }
                            val posDesc = if (loc.ref != null) "#${loc.ref}" else "(${loc.x},${loc.y})"
                            ToolOutcome(
                                name,
                                "视觉点击「${loc.text}」$posDesc",
                                tapRes.ok,
                                JSONObject()
                                    .put("clicked", tapRes.ok)
                                    .put("label", loc.text)
                                    .put("ref", loc.ref ?: JSONObject.NULL)
                                    .put("x", loc.x ?: JSONObject.NULL)
                                    .put("y", loc.y ?: JSONObject.NULL)
                                    .put("detail", tapRes.text)
                                    .toString(),
                                mutating = true,
                            )
                        }
                    }
                }
                "whale_worker_manage" -> {
                    val action = args.str("action", "clear_completed").trim().lowercase()
                    val dashboard = WorkerDashboardStore(app)
                    val out = when (action) {
                        "delete" -> {
                            val id = args.str("id").trim()
                            require(id.isNotEmpty()) { "缺少要删除的 Worker ID" }
                            val poolRemoved = WhaleNativeWorkerPool.remove(id)
                            val dashRemoved = runCatching { dashboard.deleteWorker(id) }.getOrDefault(false)
                            JSONObject().put("action", action).put("id", id).put("removed", poolRemoved || dashRemoved)
                        }
                        "clear_badge" -> {
                            val inbox = WorkerReportInbox(app)
                            val unseenIds = inbox.unseen().mapNotNull { it.optString("id").takeIf(String::isNotBlank) }
                            if (unseenIds.isNotEmpty()) inbox.markSeen(unseenIds)
                            val cleared = dashboard.clearStaleAndFinished()
                            JSONObject().put("action", action).put("clearedCount", cleared + unseenIds.size)
                        }
                        else -> {
                            val poolCleared = WhaleNativeWorkerPool.clearFinished()
                            val dashCleared = dashboard.clearStaleAndFinished()
                            JSONObject().put("action", "clear_completed").put("clearedCount", poolCleared + dashCleared)
                        }
                    }
                    ToolOutcome(name, "清理后台任务与桌宠徽标", true, out.toString(), mutating = true)
                }
                "whale_ui_refresh" -> {
                    val dashboard = WorkerDashboardStore(app)
                    val inbox = WorkerReportInbox(app)
                    val unseenIds = inbox.unseen().mapNotNull { it.optString("id").takeIf(String::isNotBlank) }
                    if (unseenIds.isNotEmpty()) inbox.markSeen(unseenIds)
                    val page = runCatching { dashboard.page("all", "", 0, 20, true) }.getOrElse { JSONObject() }
                    WhaleAppControlBus.emit(JSONObject().put("action", "refresh"))
                    ToolOutcome(name, "已刷新应用与桌宠状态视图", true, JSONObject().put("refreshed", true).put("attention", page.optInt("attention", 0)).put("running", page.optInt("running", 0)).toString(), mutating = true)
                }
                "whale_virtual_screen_control" -> {
                    val action = args.str("action", "state").trim().lowercase()
                    when (action) {
                        "state" -> {
                            val st = VirtualScreenService.current?.state()
                                ?: JSONObject().put("active", false).put("starting", false).put("error", VirtualScreenService.lastError)
                            ToolOutcome(name, "查询目标应用副屏状态", true, st.toString())
                        }
                        "stop" -> {
                            val service = VirtualScreenService.current
                            if (service == null) {
                                app.stopService(Intent(app, VirtualScreenService::class.java))
                                ToolOutcome(name, "副屏已停止", true, JSONObject().put("active", false).toString(), mutating = true)
                            } else {
                                val released = runCatching { service.requestStop().get(15, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
                                ToolOutcome(name, if (released) "已停止目标应用副屏" else "停止副屏超时", released, JSONObject().put("active", !released).toString(), mutating = true)
                            }
                        }
                        "start" -> {
                            if (android.os.Build.VERSION.SDK_INT < 29) {
                                throw IllegalStateException("目标应用副屏需要 Android 10（API 29）及以上系统")
                            }
                            if (VirtualScreenService.current != null) {
                                val cur = VirtualScreenService.current?.state() ?: JSONObject().put("active", true)
                                ToolOutcome(name, "目标应用副屏已在运行", true, cur.toString(), mutating = true)
                            } else {
                                val rawTarget = args.str("target").trim()
                                require(rawTarget.isNotEmpty()) { "启动副屏需要传入 target（应用名、包名或组件名）" }
                                val target = WhaleAutoHealBridge.resolveAppPackage(app, rawTarget)
                                val component = when (VirtualScreenPreferences.targetKind(target)) {
                                    VirtualScreenTargetKind.COMPONENT -> VirtualScreenPolicy.component(target)
                                    VirtualScreenTargetKind.PACKAGE -> {
                                        val launch = app.packageManager.getLaunchIntentForPackage(target)
                                            ?: throw IllegalStateException("未找到 $target 的可启动入口")
                                        val flat = launch.component?.flattenToShortString()
                                            ?: throw IllegalStateException("未找到 $target 的组件入口")
                                        VirtualScreenPolicy.component(flat)
                                    }
                                    VirtualScreenTargetKind.INVALID -> throw IllegalArgumentException("目标应用包名或组件无效：$target")
                                }
                                val startReq = VirtualScreenPreferences.parseStart(JSONObject().put("target", target))
                                VirtualScreenPreferences.apply(app, VirtualScreenPreferences.updateFromStart(startReq))
                                val settings = VirtualScreenPreferences.read(app)
                                val metrics = android.util.DisplayMetrics()
                                val dm = app.getSystemService(android.hardware.display.DisplayManager::class.java)
                                runCatching { dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics) }
                                val (sw, sh, sd) = if (metrics.widthPixels > 0 && metrics.heightPixels > 0 && metrics.density > 0f) {
                                    Triple(metrics.widthPixels, metrics.heightPixels, metrics.density)
                                } else {
                                    val fb = app.resources.displayMetrics
                                    Triple(fb.widthPixels, fb.heightPixels, fb.density)
                                }
                                val spec = VirtualScreenStart.resolve(startReq, settings, sw, sh, sd)
                                val orientation = startReq.orientation ?: settings.orientation
                                val adaptive = startReq.adaptive ?: settings.adaptive
                                androidx.core.content.ContextCompat.startForegroundService(
                                    app,
                                    Intent(app, VirtualScreenService::class.java)
                                        .putExtra("component", component)
                                        .putVirtualScreenStart(spec, adaptive, orientation),
                                )
                                var latestState = VirtualScreenService.current?.state()
                                for (step in 0 until 12) {
                                    if (latestState?.optString("sessionId")?.isNotBlank() == true) break
                                    android.os.SystemClock.sleep(250L)
                                    latestState = VirtualScreenService.current?.state()
                                }
                                val payload = latestState ?: JSONObject().put("starting", true).put("component", component)
                                ToolOutcome(name, "已启动目标应用副屏：$target", true, payload.toString(), mutating = true)
                            }
                        }
                        else -> throw IllegalArgumentException("不支持的副屏操作：$action")
                    }
                }
                "whale_macro_manage" -> {
                    val action = args.str("action", "list").trim().lowercase()
                    when (action) {
                        "list" -> {
                            val list = WhaleMacroEngine.list(app)
                            val arr = JSONArray()
                            list.forEach { arr.put(it.json()) }
                            ToolOutcome(name, "查看录制宏（${list.size} 个）", true, JSONObject().put("count", list.size).put("recording", WhaleMacroEngine.isRecording()).put("macros", arr).toString())
                        }
                        "start_record" -> {
                            val res = WhaleMacroEngine.startRecording(args.str("name", "自定义动作"))
                            ToolOutcome(name, "开始录制操作宏", true, res.toString(), mutating = true)
                        }
                        "stop_record" -> {
                            val res = WhaleMacroEngine.stopRecording(app)
                            val ok = res.optBoolean("ok", false)
                            ToolOutcome(name, if (ok) "保存录制宏" else "结束录制", ok, res.toString(), mutating = ok)
                        }
                        "replay" -> {
                            val target = args.str("idOrName", args.str("name", ""))
                            val startIdx = args.optInt("startIndex", 0)
                            val confirmed = args.optBoolean("confirmedHighRisk", false)
                            val res = WhaleMacroEngine.replay(app, target, startIdx, confirmed)
                            val ok = res.optBoolean("ok", false)
                            ToolOutcome(name, if (ok) "0 Token 回放宏完成" else "回放宏暂停/需自愈", ok, res.toString(), mutating = true)
                        }
                        "delete" -> {
                            val target = args.str("idOrName", args.str("name", ""))
                            val ok = WhaleMacroEngine.delete(app, target)
                            ToolOutcome(name, if (ok) "已删除录制宏" else "未找到录制宏", ok, JSONObject().put("ok", ok).toString(), mutating = true)
                        }
                        else -> throw IllegalArgumentException("不支持的宏操作：$action")
                    }
                }
                "whale_automation_heal" -> {
                    val relax = args.str("relaxWhitelist", "true").trim().lowercase() != "false"
                    val res = WhaleAutoHealBridge.autoHeal(app, relax)
                    ToolOutcome(name, "诊断与修复无障碍和副屏", res.optBoolean("ok", true), res.toString(), mutating = true)
                }
                "whale_virtual_screen_action" -> {
                    val service = VirtualScreenService.current
                        ?: throw IllegalStateException("目标应用副屏未启动，请先调用 whale_virtual_screen_control(action='start') 启动")
                    val sessionId = args.str("sessionId").trim().ifBlank { service.state().optString("sessionId").trim() }
                    require(sessionId.isNotEmpty()) { "副屏会话尚未就绪，请稍候重试" }
                    val verb = args.str("action").trim().ifBlank { args.str("type").trim() }
                    require(verb.isNotEmpty()) { "缺少副屏动作参数 action" }
                    val targetText = args.str("text").trim()
                    val hasCoords = args.has("x") && args.has("y")
                    val res = if ((verb == "tap" || verb == "click") && targetText.isNotEmpty() && !hasCoords) {
                        WhaleAutoHealBridge.smartVirtualScreenTapByText(sessionId, targetText)
                    } else {
                        val req = JSONObject(args.toString()).put("sessionId", sessionId).put("action", if (verb == "click") "tap" else verb)
                        VirtualScreenCommands.execute("virtualScreenAction", req.toString())
                    }
                    ToolOutcome(name, "执行副屏操控：$verb", res.ok, res.text, mutating = verb != "tree")
                }
                "whale_virtual_screen_vision" -> {
                    val service = VirtualScreenService.current
                        ?: throw IllegalStateException("目标应用副屏未启动，请先调用 whale_virtual_screen_control(action='start') 启动")
                    val state = service.state()
                    val sessionId = args.str("sessionId").trim().ifBlank { state.optString("sessionId").trim() }
                    require(sessionId.isNotEmpty()) { "副屏会话尚未就绪，请稍候重试" }
                    val res = VirtualScreenCommands.execute("virtualScreenCapture", JSONObject().put("sessionId", sessionId).toString())
                    if (!res.ok) {
                        ToolOutcome(name, "获取副屏画面失败", false, res.text)
                    } else {
                        val env = JSONObject(res.text)
                        val b64 = env.optString("imageBase64").ifBlank { env.optString("dataBase64") }.ifBlank { env.optString("jpegBase64") }
                        if (b64.isBlank()) {
                            ToolOutcome(name, "读取副屏状态", true, res.text)
                        } else {
                            val jpeg = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                            val w = state.optInt("widthPx", 720).coerceAtLeast(1)
                            val h = state.optInt("heightPx", 1280).coerceAtLeast(1)
                            val analysis = WhaleVisionScreenAnalyzer.analyzeScreen(
                                context = app,
                                jpeg = jpeg,
                                screenWidth = w,
                                screenHeight = h,
                                packageName = state.optString("component", "virtual-screen"),
                                question = args.str("question", "").trim(),
                                locateTarget = false,
                            )
                            ToolOutcome(name, "多模态识屏（副屏）", analysis.ok, JSONObject().put("sessionId", sessionId).put("analysis", analysis.text).toString())
                        }
                    }
                }
                "whale_voice_speak" -> {
                    val text = args.str("text").trim()
                    require(text.isNotEmpty()) { "缺少要朗读的文本 text" }
                    val voice = args.str("voice").trim().takeIf { it.isNotEmpty() }
                    if (WhaleVoiceEngine.readConfig(app).apiKey.isBlank()) {
                        throw IllegalStateException("尚未配置硅基流动 API Key，请先在设置中填写语音 Key")
                    }
                    WhaleVoiceEngine.speakText(app, text, voiceOverride = voice)
                    ToolOutcome(name, "桌宠开始语音播报", true, JSONObject().put("speaking", true).put("chars", text.length).toString(), mutating = true)
                }
                "whale_voice_config" -> {
                    val autoSpeak = if (args.has("autoSpeakPet") && !args.isNull("autoSpeakPet")) args.optBoolean("autoSpeakPet") else null
                    val voice = args.str("voice").trim().takeIf { it.isNotEmpty() }
                    val speed = if (args.has("speed") && !args.isNull("speed")) args.optDouble("speed", 1.05).toFloat() else null
                    WhaleVoiceEngine.saveConfig(app, voice = voice, speed = speed, autoSpeakPet = autoSpeak)
                    if (autoSpeak == false) WhaleVoiceEngine.stopSpeaking()
                    ToolOutcome(name, "已更新桌宠语音设置", true, WhaleVoiceEngine.snapshotJson(app).toString(), mutating = true)
                }
                "whale_self_capabilities" -> {
                    val customTools = JSONArray()
                    runCatching { WhaleCustomToolEngine.loadAll(WhaleCustomToolEngine.storeFile(app)) }.getOrDefault(emptyList()).forEach { t ->
                        customTools.put(JSONObject().put("name", t.optString("name")).put("kind", t.optString("kind")).put("description", t.optString("description")))
                    }
                    val workshopApps = JSONArray()
                    runCatching { io.deepseekharness.mobile.features.WhaleFeatureStore(app).activeTools() }.getOrDefault(emptyList()).forEach { a ->
                        workshopApps.put(JSONObject().put("id", a.id).put("title", a.title))
                    }
                    val macros = JSONArray()
                    runCatching { WhaleMacroEngine.list(app) }.getOrDefault(emptyList()).forEach { m ->
                        macros.put(JSONObject().put("name", m.name).put("steps", m.steps.size))
                    }
                    val routines = JSONArray()
                    runCatching { WhaleRoutineEngine.list(app).filter { it.enabled } }.getOrDefault(emptyList()).forEach { r ->
                        routines.put(JSONObject().put("name", r.name).put("actionKind", r.actionKind))
                    }
                    val envDiag = runCatching { WhaleAutoHealBridge.health(app) }.getOrElse { JSONObject() }
                    val domains = JSONObject()
                        .put("code", "内置 CPython 3.11 代码运行、文件读写、图表/文档生成、功能工坊离线小软件开发")
                        .put("phone", "无障碍屏控、副屏后台操作、截屏识图、0-Token 录制宏与自愈、自动化自检修复")
                        .put("device", "电量、通知、剪贴板、定时任务、应用跳转、语音朗读")
                        .put("settings", "主题外观、桌宠控制、角色人设增量修正、自治模式")
                        .put("extensions", "Skill 技能安装管理、MCP 外部工具服务器、远程算力桥")
                        .put("self_authoring", "whale_tool_create 可自主创造 python/composite/http_json/js 持久化 custom_* 工具并立刻热加载调用")
                    val out = JSONObject()
                        .put("ok", true)
                        .put("toolDomains", domains)
                        .put("selfAuthoredTools", customTools)
                        .put("workshopMiniApps", workshopApps)
                        .put("savedMacros", macros)
                        .put("activeRoutines", routines)
                        .put("environment", envDiag)
                        .put("contextCompression", WhaleContextBudgeter.lastTurnStats.json())
                    ToolOutcome(name, "自检自身能力与环境状态", true, out.toString())
                }
                "whale_tools_expand" -> {
                    val cat = args.str("category", "all").trim().lowercase().ifEmpty { "all" }
                    val names = args.str("names").split(",", "，", " ").map { it.trim() }.filter { it.isNotEmpty() }
                    val label = if (names.isNotEmpty()) "热展开工具：${names.take(3).joinToString(",")}" else "热展开工具组：$cat"
                    ToolOutcome(
                        name,
                        label,
                        true,
                        JSONObject()
                            .put("expanded", cat)
                            .put("names", JSONArray(names))
                            .put("hint", "已热加载对应工具，下一轮可直接调用。")
                            .toString(),
                    )
                }
                "whale_tool_create" -> {
                    val toolName = args.str("name").trim()
                    val desc = args.str("description").trim()
                    val kind = args.str("kind").trim().lowercase()
                    val paramsObj = when (val raw = args.opt("paramsJson") ?: args.opt("params")) {
                        is JSONObject -> raw
                        is String -> if (raw.trim().isEmpty()) JSONObject() else JSONObject(raw.trim())
                        else -> JSONObject()
                    }
                    val reqList = when (val raw = args.opt("requiredJson") ?: args.opt("required")) {
                        is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it).takeIf(String::isNotBlank) }
                        is String -> {
                            val trimmed = raw.trim()
                            if (trimmed.isEmpty()) emptyList()
                            else if (trimmed.startsWith("[")) {
                                val arr = JSONArray(trimmed)
                                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                            } else trimmed.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        }
                        else -> emptyList()
                    }
                    val defObj = when (val raw = args.opt("definitionJson") ?: args.opt("definition")) {
                        is JSONObject -> raw
                        is String -> JSONObject(raw.trim())
                        else -> throw IllegalArgumentException("缺少 definitionJson 工具定义")
                    }
                    val res = WhaleCustomToolEngine.createOrUpdate(
                        file = WhaleCustomToolEngine.storeFile(app),
                        name = toolName,
                        description = desc,
                        kind = kind,
                        params = paramsObj,
                        required = reqList,
                        definition = defObj,
                    )
                    ToolOutcome(name, "创建自造工具：$toolName", true, res.toString(), mutating = true)
                }
                "whale_tool_list" -> {
                    val res = WhaleCustomToolEngine.listSummary(WhaleCustomToolEngine.storeFile(app))
                    ToolOutcome(name, "列出自造工具", true, res.toString())
                }
                "whale_tool_delete" -> {
                    val toolName = args.str("name").trim()
                    val res = WhaleCustomToolEngine.delete(WhaleCustomToolEngine.storeFile(app), toolName)
                    ToolOutcome(name, "删除自造工具：$toolName", res.optBoolean("removed"), res.toString(), mutating = true)
                }
                "whale_skill_list" -> {
                    val list = WhaleSkillStore.list(app)
                    ToolOutcome(name, "列出已安装技能", true, JSONObject().put("count", list.length()).put("skills", list).toString())
                }
                "whale_skill_read" -> {
                    val skillName = args.str("name").trim()
                    val res = WhaleSkillStore.read(app, skillName)
                    ToolOutcome(name, "读取技能：$skillName", true, res.toString())
                }
                "whale_skill_install" -> {
                    val url = args.str("url").trim()
                    val skillName = args.str("name").trim()
                    val desc = args.str("description").trim()
                    val content = args.str("content").trim()
                    val res = if (url.isNotEmpty()) {
                        WhaleSkillStore.installFromUrl(app, url, skillName)
                    } else {
                        WhaleSkillStore.saveLocal(app, skillName, desc, content)
                    }
                    ToolOutcome(name, "安装技能：${res.optString("name")}", true, res.toString(), mutating = true)
                }
                "whale_skill_manage" -> {
                    val action = args.str("action").trim().lowercase()
                    val skillName = args.str("name").trim()
                    val res = when (action) {
                        "enable" -> WhaleSkillStore.setEnabled(app, skillName, true)
                        "disable" -> WhaleSkillStore.setEnabled(app, skillName, false)
                        "delete", "remove" -> WhaleSkillStore.delete(app, skillName)
                        else -> throw IllegalArgumentException("不支持的 Skill 操作：$action")
                    }
                    ToolOutcome(name, "管理技能：$skillName", true, res.toString(), mutating = true)
                }
                "whale_mcp_list" -> {
                    val servers = WhaleMcpManager.listServers(app)
                    ToolOutcome(name, "列出 MCP 服务器", true, JSONObject().put("count", servers.length()).put("servers", servers).toString())
                }
                "whale_mcp_add" -> {
                    val url = args.str("url").trim()
                    val id = args.str("id").trim()
                    val displayName = args.str("name").trim()
                    val authHeader = args.str("authHeader").trim()
                    val res = WhaleMcpManager.addOrUpdateServer(app, id, displayName, url, authHeader)
                    ToolOutcome(name, "接入 MCP：${res.optString("name")}", true, res.toString(), mutating = true)
                }
                "whale_mcp_call" -> {
                    val server = args.str("server").trim()
                    val tool = args.str("tool").trim()
                    val toolArgs = when (val raw = args.opt("argumentsJson") ?: args.opt("arguments")) {
                        is JSONObject -> raw
                        is String -> if (raw.isBlank()) JSONObject() else JSONObject(raw.trim())
                        else -> JSONObject()
                    }
                    val res = WhaleMcpManager.callTool(app, server, tool, toolArgs)
                    ToolOutcome(name, "调用 MCP：$server.$tool", res.optBoolean("ok", true), res.toString(), mutating = true)
                }
                "whale_mcp_manage" -> {
                    val action = args.str("action").trim().lowercase()
                    val id = args.str("id").trim()
                    val res = when (action) {
                        "refresh" -> WhaleMcpManager.refreshServer(app, id)
                        "enable" -> WhaleMcpManager.setEnabled(app, id, true)
                        "disable" -> WhaleMcpManager.setEnabled(app, id, false)
                        "remove", "delete" -> WhaleMcpManager.removeServer(app, id)
                        else -> throw IllegalArgumentException("不支持的 MCP 操作：$action")
                    }
                    ToolOutcome(name, "管理 MCP：$id", true, res.toString(), mutating = true)
                }
                else -> {
                    val mcpOutcome = WhaleMcpManager.tryExecuteDynamic(app, name, args)
                    if (mcpOutcome != null) return mcpOutcome
                    val customOutcome = WhaleCustomToolEngine.execute(app, name, args) { stepTool, stepArgs ->
                        val stepRes = execute(stepTool, stepArgs.toString())
                        stepRes.ok to stepRes.payload
                    }
                    if (customOutcome != null) {
                        ToolOutcome(name, customOutcome.label, customOutcome.ok, customOutcome.payload, mutating = true)
                    } else {
                        ToolOutcome(name, "未知工具", false, JSONObject().put("error", "UNKNOWN_TOOL").toString())
                    }
                }
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
