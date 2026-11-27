package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 鲸鱼助手极致上下文与 Token 压缩引擎（零功能损失、KV 缓存友好）。
 *
 * 五层联合压缩：
 * 1. [minifyToolSchemas]：无损精简 Tool JSON Schema（裁剪冗长描述、移除空 required、按名确定性排序保 KV Cache）；
 * 2. [selectRelevantDynamicTools]：面对大量自造 `custom_*` 与外部 `mcp__*` 工具时，仅注入与当前意图最相关的 Top-K 完整 Schema，其余压缩为单行索引，配合 `whale_tools_expand` 随时热展开；
 * 3. [assembleDynamicContext]：为动态记忆/档案/待办/目标/RAG 设立分级硬预算（P0/P1/P2）+ 跨段语义去重，彻底消除重复注入；
 * 4. [compactToolPayload]：工具结果入队前一次性做无损结构化 JSON 压缩（剔除 null/空串/空数组、压缩空白、折叠超长数组），减少首轮体积并避免多轮原地修改破坏 KV Prefix Cache；
 * 5. [compressHistory]：历史对话按角色与时间衰减智能裁剪，自动剥离 `<!--whale_next:...-->` 协议尾注与远期大代码块。
 */
internal object WhaleContextBudgeter {

    data class TurnCompressionStats(
        val rawSchemaChars: Int = 0,
        val compactSchemaChars: Int = 0,
        val totalDynamicToolsAvailable: Int = 0,
        val injectedDynamicTools: Int = 0,
        val rawDynamicCtxChars: Int = 0,
        val compactDynamicCtxChars: Int = 0,
        val rawHistoryChars: Int = 0,
        val compactHistoryChars: Int = 0,
        val toolPayloadRawChars: Int = 0,
        val toolPayloadCompactChars: Int = 0,
        val ragSkippedByGate: Boolean = false,
        val timestampMs: Long = System.currentTimeMillis(),
    ) {
        val rawTotalChars: Int
            get() = rawSchemaChars + rawDynamicCtxChars + rawHistoryChars + toolPayloadRawChars

        val compactTotalChars: Int
            get() = compactSchemaChars + compactDynamicCtxChars + compactHistoryChars + toolPayloadCompactChars

        val totalSavedChars: Int
            get() = (rawTotalChars - compactTotalChars).coerceAtLeast(0)

        val compressionRatio: Float
            get() = if (rawTotalChars <= 0) 0f else (totalSavedChars.toFloat() / rawTotalChars.toFloat()).coerceIn(0f, 1f)

        val estimatedSavedTokens: Int
            get() = (totalSavedChars / 2.2).toInt().coerceAtLeast(0)

        fun json(): JSONObject = JSONObject()
            .put("rawSchemaChars", rawSchemaChars)
            .put("compactSchemaChars", compactSchemaChars)
            .put("dynamicToolsInjected", "$injectedDynamicTools/$totalDynamicToolsAvailable")
            .put("rawDynamicCtxChars", rawDynamicCtxChars)
            .put("compactDynamicCtxChars", compactDynamicCtxChars)
            .put("rawHistoryChars", rawHistoryChars)
            .put("compactHistoryChars", compactHistoryChars)
            .put("toolPayloadRawChars", toolPayloadRawChars)
            .put("toolPayloadCompactChars", toolPayloadCompactChars)
            .put("rawTotalChars", rawTotalChars)
            .put("compactTotalChars", compactTotalChars)
            .put("compressionRatio", compressionRatio.toDouble())
            .put("ragSkippedByGate", ragSkippedByGate)
            .put("totalSavedChars", totalSavedChars)
            .put("estimatedSavedTokens", estimatedSavedTokens)
    }

    @Volatile
    var lastTurnStats: TurnCompressionStats = TurnCompressionStats()

    // KV Cache 高水位阈值：上下文未达此水位前，严禁原地篡改历史 tool 消息，最大化 DeepSeek/OpenAI 前缀缓存命中率
    const val KV_OBSERVATION_MASK_HIGH_WATER_CHARS = 36_000
    const val TOOL_INLINE_MAX_CHARS = 6_400
    const val TOOL_INLINE_HEAD_POINTS = 3_200
    const val TOOL_INLINE_TAIL_POINTS = 2_400

    private val nextTagRegex = Regex("<!--\\s*whale_next:[^>]*-->", RegexOption.IGNORE_CASE)
    private val fencedCodeRegex = Regex("```[a-zA-Z0-9_-]*\\n[\\s\\S]*?```")
    private val whitespaceRegex = Regex("\\s+")
    private val uiOrControlOnlyRegex = Regex(
        "^(继续|继续吧|好的|行|可以|算了|停|停止|取消|重试|再试一次|再来一次|换一个|下一步|打开.+|关闭.+|切到.+|切换.+|改成.+|把.+改成.+|调大.+|调小.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val memoryOrKnowledgeCueRegex = Regex(
        "(记得|记忆|之前|上次|以前|笔记|资料|文档|偏好|习惯|档案|目标|待办|日程|项目|我的|帮我查|帮我找|整理|总结|分析|对比|方案|为什么|怎么|如何|推荐|查询)",
        RegexOption.IGNORE_CASE,
    )

    // -------------------------------------------------------------------------
    // 1. Tool Schema 极致瘦身与确定性排序（保 KV Prefix Cache）
    // -------------------------------------------------------------------------

    /**
     * 对即将发往模型的 `tools` JSONArray 做无损瘦身：
     * - 合并空白、精简过长的工具与参数描述（保留枚举值与关键格式）；
     * - 删除空 `required: []` 字段；
     * - 按 `function.name` 字典序稳定排序，防止同一批工具因顺序抖动击穿 KV Cache。
     */
    fun minifyToolSchemas(
        rawTools: JSONArray,
        maxToolDescChars: Int = 78,
        maxParamDescChars: Int = 48,
    ): JSONArray {
        val list = ArrayList<Pair<String, JSONObject>>(rawTools.length())
        val seenNames = HashSet<String>(rawTools.length())
        for (i in 0 until rawTools.length()) {
            val item = rawTools.optJSONObject(i) ?: continue
            val fn = item.optJSONObject("function") ?: continue
            val name = fn.optString("name", "").trim()
            if (name.isEmpty() || !seenNames.add(name)) continue

            val slimFn = JSONObject().put("name", name)
            val desc = compactSentence(fn.optString("description", ""), maxToolDescChars)
            if (desc.isNotEmpty()) slimFn.put("description", desc)

            val params = fn.optJSONObject("parameters")
            if (params != null) {
                val slimParams = JSONObject().put("type", params.optString("type", "object"))
                val props = params.optJSONObject("properties")
                val slimProps = JSONObject()
                if (props != null) {
                    val propKeys = ArrayList<String>()
                    val it = props.keys()
                    while (it.hasNext()) propKeys.add(it.next())
                    propKeys.sort()
                    for (k in propKeys) {
                        val pObj = props.optJSONObject(k) ?: continue
                        val slimP = JSONObject()
                        val pType = pObj.optString("type", "string")
                        slimP.put("type", pType)
                        val pDesc = compactSentence(pObj.optString("description", ""), maxParamDescChars)
                        if (pDesc.isNotEmpty()) slimP.put("description", pDesc)
                        pObj.optJSONArray("enum")?.let { if (it.length() > 0) slimP.put("enum", it) }
                        pObj.optJSONObject("items")?.let { itemsObj ->
                            val slimItems = JSONObject()
                            val itType = itemsObj.optString("type", "")
                            if (itType.isNotEmpty()) slimItems.put("type", itType)
                            val itDesc = compactSentence(itemsObj.optString("description", ""), 32)
                            if (itDesc.isNotEmpty()) slimItems.put("description", itDesc)
                            itemsObj.optJSONArray("enum")?.let { if (it.length() > 0) slimItems.put("enum", it) }
                            itemsObj.optJSONObject("properties")?.let { slimItems.put("properties", it) }
                            slimP.put("items", if (slimItems.length() > 0) slimItems else itemsObj)
                        }
                        slimProps.put(k, slimP)
                    }
                }
                slimParams.put("properties", slimProps)
                val req = params.optJSONArray("required")
                if (req != null && req.length() > 0) {
                    slimParams.put("required", req)
                }
                slimFn.put("parameters", slimParams)
            }

            list.add(name to JSONObject().put("type", "function").put("function", slimFn))
        }
        list.sortBy { it.first }
        return JSONArray().apply { list.forEach { put(it.second) } }
    }

    // -------------------------------------------------------------------------
    // 2. 自造工具 / MCP 工具按相关性 Top-K 懒注入（避免工具增多导致每轮爆炸）
    // -------------------------------------------------------------------------

    data class DynamicToolSelection(
        val selected: List<JSONObject>,
        val deferredNames: List<String>,
    )

    /**
     * 当 `custom_*` 或 `mcp__*` 工具数量较多时：
     * - 若已显式展开对应分组或强制全量，则全部注入；
     * - 否则按与用户本轮文本的词法相关性打分，仅注入得分 > 0 的前 [maxAutoInject] 个完整 Schema；
     * - 其余工具返回名称列表，以极短单行索引告知模型，模型随时可用 `whale_tools_expand(category="custom"|"mcp"|toolName)` 零损唤醒。
     */
    fun selectRelevantDynamicTools(
        allSchemas: List<JSONObject>,
        userQuery: String,
        expandAll: Boolean,
        explicitNames: Set<String> = emptySet(),
        maxAutoInject: Int = 3,
    ): DynamicToolSelection {
        if (allSchemas.isEmpty()) return DynamicToolSelection(emptyList(), emptyList())
        if (expandAll || allSchemas.size <= 2) {
            return DynamicToolSelection(allSchemas, emptyList())
        }
        val queryTokens = extractTokens(userQuery)
        val qLower = userQuery.lowercase(Locale.ROOT)
        val explicit = explicitNames.map { it.lowercase(Locale.ROOT) }.toSet()

        data class Scored(val schema: JSONObject, val name: String, val score: Int, val force: Boolean)
        val scored = allSchemas.mapNotNull { schema ->
            val fn = schema.optJSONObject("function") ?: return@mapNotNull null
            val name = fn.optString("name", "").trim()
            if (name.isEmpty()) return@mapNotNull null
            val desc = fn.optString("description", "")
            val nameLower = name.lowercase(Locale.ROOT)
            val force = nameLower in explicit || (nameLower.length >= 6 && qLower.contains(nameLower))
            var score = if (force) 100 else 0
            val toolTokens = extractTokens("$name $desc")
            for (tok in queryTokens) {
                if (tok in toolTokens) score += if (tok.length >= 3) 4 else 2
            }
            Scored(schema, name, score, force)
        }

        val chosen = ArrayList<Scored>()
        val chosenNames = HashSet<String>()
        // 先放显式点名的工具
        scored.filter { it.force }.forEach {
            if (chosenNames.add(it.name)) chosen.add(it)
        }
        // 再按相关度补齐 Top-K
        scored.filter { !it.force && it.score >= 4 }
            .sortedByDescending { it.score }
            .take(maxAutoInject)
            .forEach {
                if (chosenNames.add(it.name)) chosen.add(it)
            }

        val deferred = scored.filter { it.name !in chosenNames }.map { it.name }
        return DynamicToolSelection(chosen.map { it.schema }, deferred)
    }

    // -------------------------------------------------------------------------
    // 3. 动态上下文硬预算装配 + 跨段去重 + RAG 语义门控
    // -------------------------------------------------------------------------

    data class ContextSection(
        val id: String,
        val priority: Int, // 0 = P0 必留, 1 = P1 高优, 2 = P2 按预算填充
        val maxChars: Int,
        val content: String,
    )

    /** 判断本轮用户输入是否值得触发自动 RAG（纯控制短指令/接续词直接跳过，省 500–1200 字符噪音）。 */
    fun shouldTriggerAutoRag(userQuery: String): Boolean {
        val q = userQuery.trim()
        if (q.length < 3) return false
        if (WhaleNativeRagRetriever.isGreetingOnly(q)) return false
        if (uiOrControlOnlyRegex.matches(q)) return false
        return true
    }

    /** 根据用户提问长度与历史轮数动态分配本轮上下文字符预算，保持充裕上下文不轻易失忆。 */
    fun dynamicContextBudget(userQuery: String, hasCheckpoint: Boolean, hasActiveTodo: Boolean): Int {
        val qLen = userQuery.trim().length
        return when {
            qLen <= 14 && !hasCheckpoint && !hasActiveTodo -> 1_200
            qLen <= 60 && !hasCheckpoint -> 2_400
            hasCheckpoint || hasActiveTodo || qLen > 120 -> 4_500
            else -> 3_200
        }
    }

    /**
     * 按 P0 → P1 → P2 优先级严格在 [totalBudgetChars] 内装配动态上下文，
     * 同时对行级内容做 bigram 相似度去重（避免主人档案、长期目标、RAG 记忆反复重复同一事实）。
     */
    fun assembleDynamicContext(sections: List<ContextSection>, totalBudgetChars: Int): String {
        val ordered = sections.filter { it.content.isNotBlank() }.sortedBy { it.priority }
        if (ordered.isEmpty()) return ""
        val acceptedBigrams = ArrayList<Set<String>>()
        val out = StringBuilder()

        for (sec in ordered) {
            val remaining = totalBudgetChars - out.length
            if (remaining < 48) break
            val secCap = minOf(sec.maxChars, remaining)
            val deduped = deduplicateLines(sec.content, acceptedBigrams, skipDedup = sec.priority == 0)
            if (deduped.isBlank()) continue
            val clipped = if (deduped.length > secCap) {
                deduped.take((secCap - 1).coerceAtLeast(16)).trimEnd() + "…"
            } else {
                deduped
            }
            if (out.isNotEmpty()) out.append('\n')
            out.append(clipped)
        }
        return out.toString().trim()
    }

    private fun deduplicateLines(
        text: String,
        seenBigrams: MutableList<Set<String>>,
        skipDedup: Boolean,
    ): String {
        val kept = ArrayList<String>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            // 标题行直接保留
            if (line.startsWith("[") && line.endsWith("]")) {
                kept.add(line)
                continue
            }
            val bg = charBigrams(line)
            if (!skipDedup && bg.size >= 6) {
                val duplicate = seenBigrams.any { prev ->
                    if (prev.size < 6) false else jaccard(bg, prev) >= 0.62
                }
                if (duplicate) continue
            }
            if (bg.size >= 6) seenBigrams.add(bg)
            kept.add(line)
        }
        // 若去重后只剩一个孤立段标题，丢弃整个空段
        if (kept.size == 1 && kept[0].startsWith("[") && kept[0].endsWith("]")) return ""
        return kept.joinToString("\n")
    }

    // -------------------------------------------------------------------------
    // 4. 工具返回载荷入队前一次性结构化瘦身（保 KV Cache，零关键信息损失）
    // -------------------------------------------------------------------------

    /**
     * 对工具返回的 JSON/文本做无损结构化压缩：
     * - JSON：移除 `null`、空字符串 `""`、空数组 `[]`、空对象 `{}`（保留 `ok`/`error`/`status` 等语义键）；
     *   对超长数组（>12 项）保留前 10 项 + 末 2 项并附加 `_omittedItems` 计数；
     * - 纯文本：折叠连续空行与多余空格。
     */
    private val EXPLORATORY_TOOLS = setOf(
        "whale_tool_catalog",
        "whale_tools_expand",
        "whale_phone_screen",
        "whale_phone_vision",
        "whale_web_search",
        "whale_web_fetch",
        "whale_self_capabilities",
        "whale_workspace_list",
        "whale_knowledge_search",
        "whale_memory_search",
        "whale_apps_list",
        "whale_worker_list",
        "whale_skill_list",
        "whale_mcp_list",
    )

    private val DEFAULT_FALSE_NOISE_KEYS = setOf(
        "truncated",
        "hasMore",
        "isError",
        "cached",
        "stale",
    )

    private val PRESERVE_WHITESPACE_KEYS = setOf(
        "content",
        "code",
        "diff",
        "stdout",
        "stderr",
        "patch",
    )

    /**
     * 源自 ActKV (arXiv:2609.31395) 的「动作导向探索输出折叠」：
     * 一次性探索类工具（目录检索/旧截屏/搜索列表）在下一轮即可激进折叠为 1 行摘要，
     * 保留关键代码产物与资料正文，大幅压缩 48–64 轮长轨迹 Token。
     */
    fun pruneExploratoryToolMessage(toolName: String, content: String): String? {
        if (content.length <= 360) return null
        val isExploratory = toolName in EXPLORATORY_TOOLS || content.contains("UNKNOWN_TOOL")
        if (!isExploratory) return null
        val head = content.replace(whitespaceRegex, " ").take(150).trim()
        return "[ActKV 折叠旧探索输出 · $toolName · 原长 ${content.length} 字] $head…"
    }

    fun compactToolPayload(rawPayload: String): String {
        val trimmed = rawPayload.trim()
        if (trimmed.length < 180) return trimmed
        if (trimmed.startsWith("{")) {
            val compacted = runCatching { compactJsonObject(JSONObject(trimmed), depth = 0).toString() }.getOrNull()
            if (!compacted.isNullOrBlank() && compacted.length < trimmed.length) return compacted
        } else if (trimmed.startsWith("[")) {
            val compacted = runCatching { compactJsonArray(JSONArray(trimmed), depth = 0).toString() }.getOrNull()
            if (!compacted.isNullOrBlank() && compacted.length < trimmed.length) return compacted
        }
        return trimmed.replace(Regex("\n{3,}"), "\n\n")
    }

    private fun compactJsonObject(obj: JSONObject, depth: Int): JSONObject {
        val out = JSONObject()
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            val v = obj.opt(k)
            if (v == null || v === JSONObject.NULL) continue
            when (v) {
                is Boolean -> {
                    if (!v && k in DEFAULT_FALSE_NOISE_KEYS) continue
                    out.put(k, v)
                }
                is String -> {
                    var s = v.trim()
                    if (s.isEmpty() && k !in setOf("error", "status", "message")) continue
                    if (s.length > 200 && k !in PRESERVE_WHITESPACE_KEYS) {
                        s = s.replace(Regex("[ \t]{2,}"), " ")
                    }
                    out.put(k, s)
                }
                is JSONObject -> {
                    val child = if (depth < 4) compactJsonObject(v, depth + 1) else v
                    if (child.length() > 0) out.put(k, child)
                }
                is JSONArray -> {
                    val child = if (depth < 4) compactJsonArray(v, depth + 1) else v
                    if (child.length() > 0) out.put(k, child)
                }
                else -> out.put(k, v)
            }
        }
        return out
    }

    private fun compactJsonArray(arr: JSONArray, depth: Int): JSONArray {
        val n = arr.length()
        val out = JSONArray()
        val keepLimit = if (depth == 0) 14 else 10
        if (n <= keepLimit) {
            for (i in 0 until n) {
                appendCompactedElement(out, arr.opt(i), depth)
            }
            return out
        }
        val headCount = keepLimit - 2
        for (i in 0 until headCount) {
            appendCompactedElement(out, arr.opt(i), depth)
        }
        out.put(JSONObject().put("_omittedItems", n - keepLimit).put("_totalItems", n))
        for (i in (n - 2) until n) {
            appendCompactedElement(out, arr.opt(i), depth)
        }
        return out
    }

    private fun appendCompactedElement(out: JSONArray, v: Any?, depth: Int) {
        if (v == null || v === JSONObject.NULL) return
        when (v) {
            is JSONObject -> out.put(compactJsonObject(v, depth + 1))
            is JSONArray -> out.put(compactJsonArray(v, depth + 1))
            is String -> {
                val s = v.trim()
                if (s.isNotEmpty()) out.put(s)
            }
            else -> out.put(v)
        }
    }

    // -------------------------------------------------------------------------
    // 5. 历史消息智能预算压缩（剥离协议尾注 + 远期大代码块折叠）
    // -------------------------------------------------------------------------

    /**
     * 对历史对话按时间梯度与总字符预算 [maxTotalChars] 压缩：
     * - 自动剥离助手历史回复里的 `<!--whale_next:...-->` 快捷建议标签；
     * - 倒数 2 条以外的历史消息将长代码块折叠为单行占位；
     * - 从最新往最旧逆向分配预算，确保最近语境最清晰、总历史严格不超预算。
     */
    fun compressHistory(
        history: List<Pair<String, String>>,
        maxTurns: Int = 10,
        maxTotalChars: Int = 4_200,
    ): List<Pair<String, String>> {
        val valid = history.takeLast(maxTurns).filter { (role, text) ->
            role in setOf("user", "assistant") && text.isNotBlank()
        }
        if (valid.isEmpty()) return emptyList()
        val total = valid.size
        val selectedReversed = ArrayList<Pair<String, String>>(total)
        var used = 0

        for (i in (total - 1) downTo 0) {
            val (role, raw) = valid[i]
            val distFromEnd = total - 1 - i
            var cleaned = if (role == "assistant") {
                nextTagRegex.replace(raw, "").trim()
            } else {
                raw.trim()
            }
            if (cleaned.isEmpty()) continue
            if (distFromEnd >= 2) {
                cleaned = fencedCodeRegex.replace(cleaned) { m ->
                    "[已折叠历史代码块(${m.value.length}字)]"
                }
            }
            val perMsgLimit = when {
                distFromEnd <= 1 -> 1_600
                distFromEnd <= 4 -> 520
                else -> 240
            }
            val remaining = maxTotalChars - used
            if (remaining < 80) break
            val cap = minOf(perMsgLimit, remaining)
            val clipped = if (cleaned.length > cap) {
                cleaned.take((cap - 8).coerceAtLeast(32)).trimEnd() + "…[已精简]"
            } else {
                cleaned
            }
            selectedReversed.add(role to clipped)
            used += clipped.length
        }
        return selectedReversed.reversed()
    }

    /**
     * KV Prefix Cache 锁死型 Append-Only 历史窗口（Manus / DeepSeek / Nadir 2026 最佳实践）：
     * - 每条历史消息仅按自身内容做确定性清洗（去 `<!--whale_next:...-->` + 固定上限 [perMessageCap]），
     *   **绝不**因距离末尾变远而二次改写或折叠代码块，确保第 1..N 条消息在下一轮保持字节级完全一致；
     * - 仅当历史条数超过 [maxTurns] 时按 [blockSlide] 整块向前滑动，让相邻多轮稳定命中 85%~95% 跨轮前缀缓存。
     */
    fun normalizeHistoryImmutable(
        history: List<Pair<String, String>>,
        maxTurns: Int = 16,
        blockSlide: Int = 8,
        perMessageCap: Int = 1_600,
    ): List<Pair<String, String>> {
        val valid = history.filter { (role, text) ->
            role in setOf("user", "assistant") && text.isNotBlank()
        }
        if (valid.isEmpty()) return emptyList()
        val windowStart = if (valid.size <= maxTurns) {
            0
        } else {
            val excess = valid.size - maxTurns
            val blocks = ((excess + blockSlide - 1) / blockSlide) * blockSlide
            blocks.coerceAtMost((valid.size - 2).coerceAtLeast(0))
        }
        val slice = valid.subList(windowStart, valid.size)
        return slice.mapNotNull { (role, raw) ->
            val cleaned = if (role == "assistant") {
                nextTagRegex.replace(raw, "").trim()
            } else {
                raw.trim()
            }
            if (cleaned.isEmpty()) return@mapNotNull null
            val capped = if (cleaned.length > perMessageCap) {
                cleaned.take((perMessageCap - 8).coerceAtLeast(32)).trimEnd() + "…[已精简]"
            } else {
                cleaned
            }
            role to capped
        }
    }

    // -------------------------------------------------------------------------
    // 内部工具函数
    // -------------------------------------------------------------------------

    internal fun compactSentence(text: String, maxChars: Int): String {
        val normalized = whitespaceRegex.replace(text.trim(), " ")
        if (normalized.length <= maxChars) return normalized
        return normalized.take((maxChars - 1).coerceAtLeast(8)).trimEnd() + "…"
    }

    private fun extractTokens(text: String): Set<String> {
        val lower = text.lowercase(Locale.ROOT)
        val tokens = HashSet<String>()
        // 英文/数字词元（按 _ / - / 空格切分）
        Regex("[a-z0-9]{2,}").findAll(lower).forEach { m ->
            val w = m.value
            if (w !in setOf("custom", "mcp", "whale", "tool", "the", "and", "for", "with")) {
                tokens.add(w)
            }
        }
        // 中文 2-gram 词元
        val cjk = lower.filter { it in '\u4e00'..'\u9fff' }
        for (i in 0 until cjk.length - 1) {
            tokens.add(cjk.substring(i, i + 2))
        }
        return tokens
    }

    private fun charBigrams(text: String): Set<String> {
        val clean = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it in '\u4e00'..'\u9fff' }
        if (clean.length < 2) return emptySet()
        val set = HashSet<String>(clean.length)
        for (i in 0 until clean.length - 1) {
            set.add(clean.substring(i, i + 2))
        }
        return set
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val small = if (a.size <= b.size) a else b
        val large = if (a.size <= b.size) b else a
        var inter = 0
        for (x in small) if (x in large) inter += 1
        val union = a.size + b.size - inter
        return if (union <= 0) 0.0 else inter.toDouble() / union.toDouble()
    }
}
