package io.deepseekharness.mobile.companion

import java.util.Locale

/**
 * 动态意图能力速查引擎（Zero-KV-Break Capability Primer + 模糊自愈纠错）。
 *
 * 解决「静态 10 工具保 KV 缓存，但模型在二级 `whale_call` 里容易忘工具或猜错参数」的痛点：
 * - 不触碰静态 System Prompt（保持 95%+ KV 前缀缓存命中）；
 * - 仅在最新用户消息前的动态上下文里，按意图注入 2~4 行精确 `whale_call` 工具名与必填参数模板；
 * - 当模型调用了不存在的工具名时，通过 [suggestClosestTools] 返回 Top-3 近似真实工具名助力 1 轮自愈。
 */
internal object WhaleCapabilityPrimer {

    private data class DomainRule(
        val id: String,
        val cues: List<String>,
        val snippet: String,
    )

    private val rules = listOf(
        DomainRule(
            id = "phone",
            cues = listOf("手机", "屏幕", "截屏", "截图", "打开", "点一下", "点击", "滑动", "输入", "微信", "美团", "淘宝", "抖音", "小红书", "B站", "副屏", "无障碍", "录宏", "宏"),
            snippet = "- 手机屏控（优先先看屏再动作，优先用 #ref，图标/Canvas 用视觉定位）：`whale_call(tool=\"whale_phone_screen\", argsJson=\"{}\")`、`whale_call(tool=\"whale_phone_click\", argsJson=\"{\\\"ref\\\":12}\")`、`whale_call(tool=\"whale_phone_vision_click\", argsJson=\"{\\\"target\\\":\\\"按钮描述\\\"}\")`、`whale_call(tool=\"whale_phone_batch\", argsJson=\"{\\\"steps\\\":[...]}\")`。",
        ),
        DomainRule(
            id = "python_docs",
            cues = listOf("python", "脚本", "代码", "运行", "计算", "算一下", "画图", "图表", "柱状图", "折线图", "pdf", "word", "docx", "excel", "xlsx", "ppt", "pptx", "表格", "二维码", "公式", "csv", "json"),
            snippet = "- 内置 Python 3.11 沙盒（直接跑脚本/做表/画图/生成 PDF·Word·PPT·Excel）：`whale_call(tool=\"whale_code_run\", argsJson=\"{\\\"code\\\":\\\"...\\\"}\")`；缺纯 Python 库直接在代码里调 `pip_install(\\\"包名\\\")`。",
        ),
        DomainRule(
            id = "app_settings",
            cues = listOf("主题", "深色", "浅色", "外观", "乌萨奇", "鲸鱼娘", "极简", "语言", "自治", "桌宠", "灵动岛", "悬浮", "设置", "字体", "省电"),
            snippet = "- 软件自控与外观切换：`whale_call(tool=\"whale_app_settings_get\", argsJson=\"{}\")`、`whale_call(tool=\"whale_app_control\", argsJson=\"{\\\"action\\\":\\\"appearance\\\",\\\"style\\\":\\\"cute|simple|usagi\\\",\\\"theme\\\":\\\"light|dark|system\\\"}\")`。",
        ),
        DomainRule(
            id = "workshop_create",
            cues = listOf("做个工具", "小工具", "小软件", "工坊", "自造", "造一个", "mcp", "插件", "自动化规则"),
            snippet = "- 造工具与离线工坊：`whale_call(tool=\"whale_tool_create\", argsJson=\"{\\\"name\\\":\\\"custom_xxx\\\",\\\"description\\\":\\\"...\\\",\\\"kind\\\":\\\"python\\\",\\\"code\\\":\\\"...\\\"}\")`、`whale_call(tool=\"whale_workshop_list\", argsJson=\"{}\")`。",
        ),
        DomainRule(
            id = "site_auth",
            cues = listOf("登录", "cookie", "收藏夹", "我的b站", "我的小红书", "我的知乎", "账号数据"),
            snippet = "- 网站安全登录态（绝不索要明文 Cookie）：`whale_call(tool=\"whale_site_auth\", argsJson=\"{\\\"action\\\":\\\"open_login\\\",\\\"url\\\":\\\"https://...\\\"}\")`。",
        ),
        DomainRule(
            id = "emotion_persona",
            cues = listOf("性格", "心情", "情绪", "羁绊", "好感", "人设", "角色卡", "称呼", "口癖", "温柔一点", "直接一点", "活泼一点"),
            snippet = "- 角色卡与情感养成：`whale_call(tool=\"whale_emotion_state\", argsJson=\"{\\\"action\\\":\\\"get\\\"}\")`、`whale_call(tool=\"whale_emotion_state\", argsJson=\"{\\\"action\\\":\\\"note_growth\\\",\\\"note\\\":\\\"...\\\"}\")`、`whale_call(tool=\"whale_persona_manage\", argsJson=\"{\\\"action\\\":\\\"refine\\\",\\\"appendPersonality\\\":\\\"...\\\"}\")`。",
        ),
        DomainRule(
            id = "context_ledger",
            cues = listOf("刚才说过", "前面提过", "历史原文", "翻一下之前", "回溯", "上次聊", "之前发的"),
            snippet = "- 长对话原文回溯：`whale_call(tool=\"whale_context_search\", argsJson=\"{\\\"query\\\":\\\"关键词\\\"}\")`、`whale_call(tool=\"whale_context_expand\", argsJson=\"{\\\"rangeId\\\":\\\"...\\\"}\")`（每轮最多展开 1 段）。",
        ),
        DomainRule(
            id = "knowledge_crud",
            cues = listOf("资料库", "知识库", "knowledge", "索引", "fts", "清理资料", "删除资料", "修改资料", "笔记"),
            snippet = "- 本地资料库原生读写与索引维护（严禁手写 sqlite3 破坏 FTS4 同步）：`whale_call(tool=\"whale_knowledge_list\", argsJson=\"{\\\"offset\\\":0,\\\"limit\\\":20}\")`、`whale_call(tool=\"whale_knowledge_read\", argsJson=\"{\\\"id\\\":\\\"...\\\"}\")`、`whale_call(tool=\"whale_knowledge_edit\", argsJson=\"{\\\"id\\\":\\\"...\\\",\\\"text\\\":\\\"...\\\"}\")`、`whale_call(tool=\"whale_knowledge_delete\", argsJson=\"{\\\"id\\\":\\\"...\\\"}\")`、`whale_call(tool=\"whale_knowledge_rebuild_index\", argsJson=\"{}\")`。",
        ),
        DomainRule(
            id = "memory_proactive",
            cues = listOf("记住", "记忆", "画像", "偏好", "关注项", "别忘了", "提醒我", "我喜欢", "我平时"),
            snippet = "- 结构化记忆与主动关注：`whale_call(tool=\"whale_memory_digest\", argsJson=\"{}\")`、`whale_call(tool=\"whale_memory_search\", argsJson=\"{\\\"query\\\":\\\"...\\\",\\\"category\\\":\\\"project|preference|profile|people|routine|constraint\\\"}\")`、`whale_call(tool=\"whale_proactive_watchlist\", argsJson=\"{\\\"action\\\":\\\"list\\\"}\")`。",
        ),
        DomainRule(
            id = "background_worker",
            cues = listOf("后台跑", "异步", "深度调研", "长任务", "取消任务", "清掉徽标", "任务进度"),
            snippet = "- 后台长任务编排：`whale_call(tool=\"whale_worker_submit\", argsJson=\"{\\\"title\\\":\\\"...\\\",\\\"task\\\":\\\"...\\\"}\")`、`whale_call(tool=\"whale_worker_manage\", argsJson=\"{\\\"action\\\":\\\"list\\\"}\")`、`whale_call(tool=\"whale_worker_cancel\", argsJson=\"{\\\"id\\\":\\\"...\\\"}\")`。",
        ),
    )

    /** 按本轮用户消息匹配最多 2 个最相关域，生成极简精确参数速查卡。 */
    fun buildPrimer(userQuery: String): String {
        val lower = userQuery.trim().lowercase(Locale.US)
        if (lower.isEmpty()) return ""
        val matched = rules.filter { rule -> rule.cues.any { cue -> lower.contains(cue) } }.take(3)
        if (matched.isEmpty()) return ""
        val ids = matched.map { it.id }.toSet()
        return buildString {
            append("[本轮精准能力速查（直接经 whale_call 调用，无需盲猜参数）]\n")
            matched.forEach { append(it.snippet).append("\n") }
            if ("phone" in ids && "python_docs" in ids) {
                append("- 跨步联动提示：可先用 `whale_phone_vision` 读取屏幕信息，再把提取的数据交给 `whale_code_run` 生成表格/文档或图表。\n")
            }
        }.trim()
    }

    /** 对调用错误的未知工具名做模糊匹配，返回 Top-K 最接近的真实工具名用于 1 轮自愈。 */
    fun suggestClosestTools(unknownName: String, candidates: Collection<String>, limit: Int = 3): List<String> {
        val target = unknownName.trim().lowercase(Locale.US)
        if (target.isEmpty() || candidates.isEmpty()) return emptyList()
        val targetTokens = target.split('_', '-', '.', ' ').filter { it.isNotEmpty() }.toSet()
        return candidates
            .distinct()
            .map { cand ->
                val lc = cand.lowercase(Locale.US)
                val candTokens = lc.split('_', '-', '.', ' ').filter { it.isNotEmpty() }.toSet()
                val overlap = if (targetTokens.isEmpty() || candTokens.isEmpty()) 0.0
                else targetTokens.intersect(candTokens).size.toDouble() / targetTokens.union(candTokens).size.toDouble()
                val substrBonus = if (lc.contains(target) || target.contains(lc)) 0.45 else 0.0
                val dist = levenshtein(target, lc)
                val editSim = 1.0 - (dist.toDouble() / maxOf(target.length, lc.length).coerceAtLeast(1))
                cand to (overlap * 0.55 + editSim * 0.45 + substrBonus)
            }
            .filter { it.second >= 0.25 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }
}
