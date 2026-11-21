package io.deepseekharness.mobile.companion

import android.content.Context
import io.deepseekharness.mobile.runtime.ModelProvider
import io.deepseekharness.mobile.runtime.RuntimeStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Callable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Whale 原生 Super Agent 引擎（彻底脱离 DeepSeek Harness / PRoot / Node）。
 *
 * 直接在 Android 原生层执行 OpenAI 兼容多轮 Function Calling 循环：
 * - 原生 SSE（Server-Sent Events）逐字流式输出，降低首字延迟；
 * - 完整支持流式 tool_calls 增量拼接与非流式 JSON 自动回退；
 * - 429 / 5xx 瞬时故障自动退避重试一次；
 * - 写入/创作类工具调用写入 [UnifiedExecutionLedger]（SQLite WAL）追踪状态。
 */
internal class WhaleNativeAgentEngine(private val context: Context) {
    interface StreamListener {
        fun onDelta(chunk: String) {}
        fun onStep(step: StepRecord) {}
        fun onPhase(phase: String) {}
    }

    data class StepRecord(
        val tool: String,
        val label: String,
        val ok: Boolean,
        val durationMs: Long,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("tool", tool)
            .put("label", label)
            .put("ok", ok)
            .put("durationMs", durationMs)
    }

    /** 运行中实时控制句柄：支持随时中途插话/修正（steer）与立即打断停止（cancel）。 */
    class RunControl {
        val cancelled = AtomicBoolean(false)
        val steerInterrupt = AtomicBoolean(false)
        private val inToolPhase = AtomicBoolean(false)
        private val steerQueue = ConcurrentLinkedQueue<String>()
        private val activeConn = AtomicReference<HttpURLConnection?>(null)

        fun setToolPhase(active: Boolean) { inToolPhase.set(active) }
        fun attachConnection(conn: HttpURLConnection?) { activeConn.set(conn) }
        fun clearConnection(conn: HttpURLConnection?) { activeConn.compareAndSet(conn, null) }

        fun cancel() {
            cancelled.set(true)
            runCatching { activeConn.getAndSet(null)?.disconnect() }
        }

        fun steer(note: String) {
            val clean = note.trim()
            if (clean.isEmpty() || cancelled.get()) return
            steerQueue.add(clean)
            if (!inToolPhase.get()) {
                steerInterrupt.set(true)
                runCatching { activeConn.getAndSet(null)?.disconnect() }
            }
        }

        fun hasPendingSteer(): Boolean = !steerQueue.isEmpty()

        fun drainSteers(): List<String> {
            val out = ArrayList<String>()
            while (true) {
                val item = steerQueue.poll() ?: break
                out.add(item)
            }
            return out
        }
    }

    data class ChatResult(
        val reply: String,
        val steps: List<StepRecord>,
        val model: String,
        val elapsedMs: Long,
        val mode: String = "native_direct_sse",
        val suggestions: List<String> = emptyList(),
        val cancelled: Boolean = false,
        val hitRoundLimit: Boolean = false,
    ) {
        fun json(): JSONObject {
            val stepsArr = JSONArray()
            steps.forEach { stepsArr.put(it.json()) }
            val sugArr = JSONArray()
            suggestions.forEach { sugArr.put(it) }
            return JSONObject()
                .put("reply", reply)
                .put("steps", stepsArr)
                .put("model", model)
                .put("elapsedMs", elapsedMs)
                .put("mode", mode)
                .put("suggestions", sugArr)
                .put("cancelled", cancelled)
        }
    }

    private data class AssistantTurn(
        val content: String,
        val toolCalls: JSONArray?,
    )

    private val app = context.applicationContext
    private val tools = WhaleNativeToolRegistry(app)
    private val ledger = UnifiedExecutionLedger(app)

    fun send(
        userMessage: String,
        history: List<Pair<String, String>> = emptyList(),
        sessionId: String = "whale-main",
        listener: StreamListener? = null,
        onProgress: ((round: Int, step: String, preview: String) -> Unit)? = null,
        control: RunControl? = null,
        images: List<String> = emptyList(),
    ): ChatResult {
        val startedAt = System.currentTimeMillis()
        val trimmed = userMessage.trim().ifEmpty { if (images.isNotEmpty()) "请帮我看看这张图片并给出实用建议。" else "" }
        require(trimmed.isNotEmpty()) { "请输入内容后再发送" }

        val store = RuntimeStore(app)
        val settings = store.settings()
        val rawEndpoint = resolveEndpoint(store, settings)
        val budgetProgress = runCatching { WhaleTokenMeter.snapshot(app).progress }.getOrDefault(0f)
        val routeDecision = WhaleModelRouter.route(rawEndpoint.model, trimmed, images.isNotEmpty(), budgetProgress)
        val endpoint = rawEndpoint.copy(model = routeDecision.model)
        val expandedCategories = mutableSetOf<String>()
        var toolSchemas = tools.toolSchemas(trimmed, expandedCategories)

        // 1. 滑动历史窗口准备（用于角色世界书关键词触发与历史回放）
        val recentHistory = history.takeLast(12)
        val activePersona = runCatching { CompanionPersonaStore(app).active() }.getOrNull()

        // 2. System Prompt：优先以当前激活角色人设领衔，完整保留角色设定与工具规则
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", buildStaticSystemPrompt(activePersona)),
        )
        val totalHist = recentHistory.size
        recentHistory.forEachIndexed { idx, (role, text) ->
            if (role in setOf("user", "assistant") && text.isNotBlank()) {
                val limit = if (idx >= totalHist - 4) 4_000 else 1_000
                val clipped = if (text.length > limit) text.take(limit) + "…[历史已精简]" else text
                messages.put(JSONObject().put("role", role).put("content", clipped))
            }
        }

        // 3. 动态本轮上下文（档案/Worker/RAG）放在最新用户消息前，绝不污染静态 System Prompt 缓存前缀
        val dynamicCtx = buildDynamicTurnContext(trimmed, activePersona, recentHistory)
        val userPayload = if (dynamicCtx.isNotBlank()) {
            "$dynamicCtx\n\n用户消息：${trimmed.take(24_000)}"
        } else {
            trimmed.take(24_000)
        }
        if (images.isNotEmpty()) {
            val parts = JSONArray()
            parts.put(JSONObject().put("type", "text").put("text", userPayload))
            images.take(3).forEach { dataUrl ->
                if (dataUrl.startsWith("data:image/") || dataUrl.startsWith("https://")) {
                    parts.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", dataUrl)),
                    )
                }
            }
            messages.put(JSONObject().put("role", "user").put("content", parts))
        } else {
            messages.put(JSONObject().put("role", "user").put("content", userPayload))
        }

        val steps = ArrayList<StepRecord>()
        val seenCalls = HashSet<String>()
        val toolMsgIndicesByRound = ArrayList<List<Int>>()
        var finalReply = ""
        var hitRoundLimit = false

        for (round in 0 until MAX_TOOL_ROUNDS) {
            // 4. 滚动折叠两轮以前的冗长工具输出，防止多轮推理时输入 Token 指数级膨胀
            if (round >= 2 && round - 2 < toolMsgIndicesByRound.size) {
                for (msgIdx in toolMsgIndicesByRound[round - 2]) {
                    val m = messages.optJSONObject(msgIdx) ?: continue
                    val raw = m.optString("content", "")
                    if (raw.length > 520) {
                        m.put("content", raw.take(460) + "\n…[已折叠历史工具输出，原长 ${raw.length} 字符]")
                    }
                }
            }

            listener?.onPhase(if (round == 0) "thinking" else "synthesizing")
            onProgress?.invoke(round + 1, "第 ${round + 1} 轮推理中…", finalReply)
            val streamBridge = if (listener != null || onProgress != null) {
                object : StreamListener {
                    private val liveBuf = StringBuilder()
                    override fun onDelta(chunk: String) {
                        listener?.onDelta(chunk)
                        if (onProgress != null) {
                            liveBuf.append(chunk)
                            if (liveBuf.length % 24 < chunk.length || liveBuf.length <= 24) {
                                onProgress.invoke(round + 1, "第 ${round + 1} 轮生成回复中…", liveBuf.toString().takeLast(280))
                            }
                        }
                    }
                    override fun onStep(step: StepRecord) { listener?.onStep(step) }
                    override fun onPhase(phase: String) { listener?.onPhase(phase) }
                }
            } else {
                null
            }
            if (control?.cancelled?.get() == true) break
            var partialStream = ""
            val turn = try {
                callChatCompletionsWithRetry(
                    endpoint = endpoint,
                    messages = messages,
                    toolsArray = if (toolSchemas.length() > 0) toolSchemas else null,
                    listener = if (streamBridge != null) object : StreamListener {
                        override fun onDelta(chunk: String) {
                            partialStream += chunk
                            streamBridge.onDelta(chunk)
                        }
                        override fun onStep(step: StepRecord) { streamBridge.onStep(step) }
                        override fun onPhase(phase: String) { streamBridge.onPhase(phase) }
                    } else null,
                    control = control,
                )
            } catch (err: Throwable) {
                if (control?.cancelled?.get() == true) {
                    finalReply = partialStream.trim()
                    break
                }
                if (control?.steerInterrupt?.getAndSet(false) == true && control.hasPendingSteer()) {
                    val notes = control.drainSteers()
                    messages.put(JSONObject().put("role", "user").put("content", "[用户中途补充/修正] ${notes.joinToString("\n")}"))
                    listener?.onPhase("restarting")
                    continue
                }
                throw err
            }
            if (control?.cancelled?.get() == true) {
                finalReply = turn.content.trim().ifBlank { partialStream.trim() }
                break
            }
            if (control?.steerInterrupt?.getAndSet(false) == true || control?.hasPendingSteer() == true) {
                val notes = control.drainSteers()
                if (notes.isNotEmpty() && (turn.toolCalls == null || turn.toolCalls.length() == 0)) {
                    messages.put(JSONObject().put("role", "user").put("content", "[用户中途补充/修正] ${notes.joinToString("\n")}"))
                    listener?.onPhase("restarting")
                    continue
                }
            }
            val content = turn.content.trim()
            val toolCalls = turn.toolCalls

            if (toolCalls == null || toolCalls.length() == 0) {
                finalReply = content.ifBlank { "已完成处理。" }
                break
            }

            val assistantMsg = JSONObject()
                .put("role", "assistant")
                .put("content", if (content.isEmpty()) JSONObject.NULL else content)
                .put("tool_calls", toolCalls)
            messages.put(assistantMsg)

            // 5. 解析本轮全部工具调用：若全部为只读工具且数量 >= 2，则 4 线程并行执行，速度提升 2~3 倍
            data class PlannedCall(val callId: String, val fnName: String, val fnArgs: String, val mutating: Boolean)
            val planned = ArrayList<PlannedCall>(toolCalls.length())
            for (i in 0 until toolCalls.length()) {
                val call = toolCalls.optJSONObject(i) ?: continue
                val callId = call.optNonNullString("id", UUID.randomUUID().toString())
                val fn = call.optJSONObject("function") ?: continue
                val fnName = fn.optNonNullString("name", "")
                val fnArgs = fn.optNonNullString("arguments", "{}")
                planned.add(PlannedCall(callId, fnName, fnArgs, tools.isMutating(fnName)))
            }

            val canParallel = planned.size >= 2 && planned.none { it.mutating }
            val roundToolIndices = ArrayList<Int>(planned.size)

            fun runSingle(p: PlannedCall): Pair<WhaleNativeToolRegistry.ToolOutcome, Long> {
                val callKey = "${p.fnName}:${p.fnArgs.trim()}"
                if (!seenCalls.add(callKey)) {
                    return WhaleNativeToolRegistry.ToolOutcome(
                        p.fnName,
                        "跳过重复调用",
                        false,
                        JSONObject().put("error", "DUPLICATE_CALL").put("hint", "相同参数的工具刚已调用过，请更换参数、换一种工具或直接汇总回答用户。").toString(),
                    ) to 1L
                }
                val opId = "native-${sessionId.take(16)}-$round-${p.callId}"
                if (p.mutating) {
                    ledger.recordIntent(opId, sessionId, "native-router", round.toLong(), p.fnName)
                    ledger.transition(opId, UnifiedExecutionLedger.State.DISPATCHING)
                }
                val t0 = System.currentTimeMillis()
                val outcome = tools.execute(p.fnName, p.fnArgs)
                val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                if (p.mutating) {
                    ledger.transition(
                        opId,
                        if (outcome.ok) UnifiedExecutionLedger.State.COMPLETED else UnifiedExecutionLedger.State.FAILED,
                        outcome.label,
                    )
                }
                return outcome to dt
            }

            control?.setToolPhase(true)
            val executed: List<Pair<WhaleNativeToolRegistry.ToolOutcome, Long>> = try { if (canParallel) {
                listener?.onPhase("tool:parallel(${planned.size})")
                onProgress?.invoke(round + 1, "正在并行执行 ${planned.size} 项只读工具…", finalReply)
                val futures = parallelToolPool.invokeAll(planned.map { p -> Callable { runSingle(p) } })
                futures.map { f ->
                    runCatching { f.get() }.getOrElse { err ->
                        WhaleNativeToolRegistry.ToolOutcome("parallel", "并行工具异常", false, JSONObject().put("error", err.message ?: "EXECUTION_ERROR").toString()) to 1L
                    }
                }
            } else {
                planned.map { p ->
                    listener?.onPhase("tool:${p.fnName}")
                    onProgress?.invoke(round + 1, "正在执行工具：${p.fnName}", finalReply)
                    runSingle(p)
                }
            } } finally { control?.setToolPhase(false) }

            var needSchemaRefresh = false
            for (idx in planned.indices) {
                val p = planned[idx]
                val (outcome, dt) = executed[idx]
                if (outcome.ok) {
                    if (p.fnName in setOf("whale_tool_create", "whale_tool_delete", "whale_mcp_add", "whale_mcp_manage")) {
                        needSchemaRefresh = true
                    } else if (p.fnName == "whale_tools_expand") {
                        val cat = runCatching { JSONObject(p.fnArgs).optString("category", "all") }.getOrDefault("all").lowercase()
                        expandedCategories.add(cat)
                        needSchemaRefresh = true
                    }
                }
                val step = StepRecord(p.fnName, outcome.label, outcome.ok, dt)
                steps += step
                listener?.onStep(step)
                roundToolIndices.add(messages.length())
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", p.callId)
                        .put("content", outcome.payload.take(8_000)),
                )
            }
            toolMsgIndicesByRound.add(roundToolIndices)
            if (needSchemaRefresh) {
                toolSchemas = tools.toolSchemas(trimmed, expandedCategories)
            }
            if (control?.cancelled?.get() == true) break
            if (control?.hasPendingSteer() == true) {
                val notes = control.drainSteers()
                if (notes.isNotEmpty()) {
                    messages.put(JSONObject().put("role", "user").put("content", "[用户中途补充/修正] ${notes.joinToString("\n")}"))
                    listener?.onPhase("steering")
                }
            }
        }

        val wasCancelled = control?.cancelled?.get() == true
        if (finalReply.isBlank() && !wasCancelled && steps.isNotEmpty()) {
            hitRoundLimit = true
            // 达到最大工具轮数后，强制补发一轮无 tools 的收尾总结请求，确保模型自然汇报进展而非吐出机械报错
            listener?.onPhase("synthesizing")
            messages.put(
                JSONObject()
                    .put("role", "user")
                    .put("content", "已达到单轮连续操作步数上限。请不再调用工具，直接用简洁自然的中文告诉我：刚才已经完成了哪几步、当前屏幕上看到了什么结果，以及接下来需要我做什么或要不要继续。"),
            )
            val summaryTurn = runCatching {
                callChatCompletionsWithRetry(
                    endpoint = endpoint,
                    messages = messages,
                    toolsArray = null,
                    listener = listener,
                    control = control,
                )
            }.getOrNull()
            finalReply = summaryTurn?.content?.trim().orEmpty()
        }
        if (finalReply.isBlank()) {
            finalReply = when {
                wasCancelled -> "已停止生成。"
                steps.isNotEmpty() -> {
                    val recent = steps.takeLast(3).joinToString(" → ") { it.label }
                    "我已经连续执行了 ${steps.size} 步操作（最近：$recent），目前已先暂停。你可以看看当前页面，告诉我接下来怎么继续～"
                }
                else -> "刚才模型没有返回文字内容，再发一次试试看～"
            }
        }
        val (cleanReply, parsedSuggestions) = extractSuggestions(finalReply)
        val suggestions = if (parsedSuggestions.isNotEmpty() || wasCancelled) parsedSuggestions else inferFallbackSuggestions(cleanReply)
        return ChatResult(
            reply = cleanReply,
            steps = steps,
            model = endpoint.model,
            elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L),
            suggestions = if (wasCancelled) emptyList() else suggestions,
            cancelled = wasCancelled,
            hitRoundLimit = hitRoundLimit,
        )
    }

    private data class Endpoint(val url: String, val apiKey: String, val model: String)

    private fun resolveEndpoint(
        store: RuntimeStore,
        settings: io.deepseekharness.mobile.runtime.RuntimeSettings,
    ): Endpoint {
        val builtin = listOf(
            Triple(ModelProvider.DEEPSEEK, "https://api.deepseek.com/v1/chat/completions", "deepseek-chat"),
            Triple(ModelProvider.OPENAI, "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
            Triple(ModelProvider.OPENROUTER, "https://openrouter.ai/api/v1/chat/completions", "deepseek/deepseek-chat"),
            Triple(ModelProvider.XAI, "https://api.x.ai/v1/chat/completions", "grok-2-latest"),
            Triple(ModelProvider.GROQ, "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile"),
            Triple(ModelProvider.MISTRAL, "https://api.mistral.ai/v1/chat/completions", "mistral-small-latest"),
        )
        for ((provider, url, fallbackModel) in builtin) {
            val key = store.readModelCredential(provider.wireValue)?.takeIf(String::isNotBlank) ?: continue
            val model = settings.defaultModels[provider]?.takeIf(String::isNotBlank) ?: fallbackModel
            return Endpoint(url, key, model)
        }
        for (custom in settings.customModelProviders) {
            val key = store.readModelCredential(custom.id)?.takeIf(String::isNotBlank) ?: continue
            val model = custom.models.firstOrNull()?.id?.takeIf(String::isNotBlank) ?: continue
            val base = custom.baseUrl.trimEnd('/')
            val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            return Endpoint(url, key, model)
        }
        throw IllegalStateException("请先在「我的 → 模型与密钥」中配置 API Key")
    }

    private fun buildStaticSystemPrompt(persona: JSONObject? = null): String = buildString {
        // 100% 静态前缀：不掺入每轮变化的历史文本，确保 DeepSeek/OpenAI 前缀缓存高命中
        val card = persona ?: runCatching { CompanionPersonaStore(app).active() }.getOrNull()
        val charName = card?.optString("name")?.takeIf(String::isNotBlank) ?: "鲸鱼娘"
        if (card != null) {
            val personaPrompt = runCatching { PetRoleplayPrompt.character(card, emptyList()) }.getOrDefault("")
            if (personaPrompt.isNotBlank()) {
                append(personaPrompt.take(4_500)).append("\n\n")
            }
        }
        append("身份与能力：始终保持「$charName」的人设与口吻回复，同时具备手机原生 Super Agent 全部工具能力；处理现实事务时结果准确且不切回客服腔。若用户在对话中约定新的称呼、口癖、语气或相处设定，主动调用 whale_persona_manage(action=\"refine\") 增量修正角色卡。\n")
        append("执行原则：只读核查一步到位，复杂耗时任务派发 whale_worker_submit；收到 [用户中途补充/修正] 立即调整；不确定信息先 whale_web_search；需专域工具（屏控/代码/设置/设备/MCP与Skill扩展）先 whale_tools_expand 热加载；互不依赖查询同轮并行；屏控遇无文字广告弹窗优先返回键或视觉定位。\n")
        append("快捷回复（强制）：每次最终回复末尾另起一行输出 <!--whale_next:[\"选项1\",\"选项2\",\"选项3\"]-->（≤14字）；若向用户提问或给选项，必须给出可直接点选的简短回答。")
    }

    private fun buildDynamicTurnContext(userQuery: String, persona: JSONObject? = null, recentHistory: List<Pair<String, String>> = emptyList()): String = buildString {
        runCatching {
            val owner = OwnerContext(app).read()
            val profile = owner.optJSONObject("profile")
            val fields = profile?.optJSONArray("fields")
            val confirmed = mutableListOf<String>()
            if (fields != null) {
                for (i in 0 until fields.length()) {
                    val f = fields.optJSONObject(i) ?: continue
                    if (f.optString("state") == "confirmed") {
                        confirmed.add("${f.optString("label")}=${f.optString("value")}")
                    }
                }
            }
            if (confirmed.isNotEmpty()) {
                append("[主人档案] ").append(confirmed.joinToString("；")).append("\n")
            }
            val nowStr = SimpleDateFormat("yyyy-MM-dd EEEE HH:mm", Locale.CHINA).format(Date())
            append("[当前时间] ").append(nowStr).append("\n")
            val openMatters = owner.optJSONArray("openMatters")
            if (openMatters != null && openMatters.length() > 0) {
                val top = mutableListOf<String>()
                for (i in 0 until minOf(5, openMatters.length())) {
                    val m = openMatters.optJSONObject(i) ?: continue
                    val title = m.optString("title").ifBlank { m.optString("text") }.trim()
                    val due = m.optString("dueAt").ifBlank { m.optString("when") }.trim()
                    if (title.isNotEmpty()) top.add(if (due.isNotEmpty()) "$title($due)" else title)
                }
                append("[未完成待办] 共 ").append(openMatters.length()).append(" 条")
                if (top.isNotEmpty()) append("：").append(top.joinToString("；"))
                append("\n")
            }
        }
        runCatching {
            val inboxPrompt = WorkerReportInbox(app).prompt()
            if (inboxPrompt.isNotBlank()) append(inboxPrompt.take(1600)).append("\n")
            val poolPrompt = WhaleNativeWorkerPool.recentSummaries(3)
            if (poolPrompt.isNotBlank()) append(poolPrompt.take(1200)).append("\n")
        }
        runCatching {
            val goals = WhaleGoalGraphStore.promptSummary(app)
            if (goals.isNotBlank()) append(goals.take(800)).append("\n")
            val playbooks = WhalePlaybookStore.promptSummary(app)
            if (playbooks.isNotBlank()) append(playbooks.take(800)).append("\n")
        }
        runCatching {
            val skillCatalog = WhaleSkillStore.catalogPrompt(app)
            if (skillCatalog.isNotBlank()) append(skillCatalog.take(1200)).append("\n")
            val mcpSummary = WhaleMcpManager.promptSummary(app)
            if (mcpSummary.isNotBlank()) append(mcpSummary.take(600)).append("\n")
        }
        runCatching {
            val rag = WhaleNativeRagRetriever.retrieveContext(app, userQuery)
            if (rag.isNotBlank()) append(rag.take(2400)).append("\n")
        }
        runCatching {
            val card = persona ?: CompanionPersonaStore(app).active()
            // 仅当角色卡含非恒定世界书条目时，按最近对话关键词动态触发（不破坏 System Prompt 缓存前缀）
            if (card.optString("worldBook").isNotBlank()) {
                val contextText = (recentHistory.takeLast(4).map { it.second } + userQuery).joinToString("\n")
                val book = runCatching { JSONObject(card.optString("worldBook")) }.getOrNull()
                val rawEntries = book?.opt("entries")
                val entries = when (rawEntries) {
                    is JSONArray -> rawEntries
                    is JSONObject -> JSONArray().apply { rawEntries.keys().forEach { put(rawEntries.opt(it)) } }
                    else -> null
                }
                if (entries != null) {
                    val charName = card.optString("name", "鲸鱼娘")
                    val matched = (0 until entries.length()).mapNotNull { i ->
                        val e = entries.optJSONObject(i) ?: return@mapNotNull null
                        if ((e.has("enabled") && !e.optBoolean("enabled")) || e.optBoolean("disable") || e.optBoolean("constant")) return@mapNotNull null
                        val keys = e.optJSONArray("keys") ?: e.optJSONArray("key") ?: return@mapNotNull null
                        val hit = (0 until keys.length()).any { k -> keys.optString(k).takeIf(String::isNotBlank)?.let { contextText.contains(it, ignoreCase = true) } == true }
                        if (!hit) null else e.optString("content").replace("{{char}}", charName, ignoreCase = true).replace("{{user}}", "用户", ignoreCase = true).takeIf(String::isNotBlank)
                    }.joinToString("\n").take(1200)
                    if (matched.isNotBlank()) append("[触发世界设定] ").append(matched).append("\n")
                }
            }
            val post = PetRoleplayPrompt.afterHistory(card)
            if (post.isNotBlank()) append("[角色持续指令] ").append(post.take(600))
        }
    }.trim()

    private fun callChatCompletionsWithRetry(
        endpoint: Endpoint,
        messages: JSONArray,
        toolsArray: JSONArray?,
        listener: StreamListener?,
        control: RunControl? = null,
    ): AssistantTurn {
        var lastError: Throwable? = null
        for (attempt in 0..1) {
            if (control?.cancelled?.get() == true) throw InterruptedException("cancelled")
            try {
                return callChatCompletions(endpoint, messages, toolsArray, listener, control = control)
            } catch (error: TransientHttpException) {
                if (control?.cancelled?.get() == true || control?.steerInterrupt?.get() == true) throw error
                lastError = error
                if (attempt == 0) {
                    runCatching { Thread.sleep(800L) }
                    continue
                }
                throw error
            }
        }
        throw lastError ?: IllegalStateException("模型请求失败")
    }

    private class TransientHttpException(message: String) : IllegalStateException(message)

    private fun callChatCompletions(
        endpoint: Endpoint,
        messages: JSONArray,
        toolsArray: JSONArray?,
        listener: StreamListener?,
        includeUsageOption: Boolean = true,
        control: RunControl? = null,
    ): AssistantTurn {
        val wantStream = listener != null
        val body = JSONObject()
            .put("model", endpoint.model)
            .put("messages", messages)
            .put("stream", wantStream)
        if (wantStream && includeUsageOption) {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }
        if (toolsArray != null && toolsArray.length() > 0) {
            body.put("tools", toolsArray)
            body.put("tool_choice", "auto")
        }

        val conn = (URL(endpoint.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${endpoint.apiKey}")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (wantStream) setRequestProperty("Accept", "text/event-stream, application/json")
        }
        control?.attachConnection(conn)
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (code !in 200..299) {
                val errText = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
                if (code == 400 && wantStream && includeUsageOption && errText.contains("stream_options", ignoreCase = true)) {
                    return callChatCompletions(endpoint, messages, toolsArray, listener, includeUsageOption = false, control = control)
                }
                val msg = "模型接口返回 HTTP $code：${errText.take(240)}"
                if (code == 429 || code in 502..504) throw TransientHttpException(msg)
                throw IllegalStateException(msg)
            }
            val contentType = conn.contentType.orEmpty().lowercase()
            if (wantStream && contentType.contains("text/event-stream")) {
                return parseSseResponse(BufferedReader(InputStreamReader(stream, Charsets.UTF_8)), listener, endpoint.model, messages.toString().length)
            }
            val text = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            val response = JSONObject(text)
            val usageObj = response.optJSONObject("usage")
            if (usageObj != null) {
                val pTok = usageObj.optLong("prompt_tokens", 0L)
                val cTok = usageObj.optLong("completion_tokens", 0L)
                val cacheHit = WhaleTokenMeter.extractCacheHitTokens(usageObj)
                if (pTok > 0L || cTok > 0L) {
                    runCatching { WhaleTokenMeter.record(app, pTok, cTok, cacheHit, endpoint.model) }
                }
            } else {
                runCatching { WhaleTokenMeter.record(app, messages.toString().length / 3L, text.length / 4L, 0L, endpoint.model) }
            }
            val choice = response.optJSONArray("choices")?.optJSONObject(0)
                ?: throw IllegalStateException("模型服务未返回有效内容")
            val message = choice.optJSONObject("message")
                ?: throw IllegalStateException("模型响应缺少 message 字段")
            val content = message.optNonNullString("content", "")
            if (content.isNotEmpty() && listener != null) {
                listener.onDelta(content)
            }
            return AssistantTurn(content, message.optJSONArray("tool_calls"))
        } finally {
            control?.clearConnection(conn)
            conn.disconnect()
        }
    }

    private fun parseSseResponse(
        reader: BufferedReader,
        listener: StreamListener?,
        model: String,
        promptCharsHint: Int,
    ): AssistantTurn {
        val contentBuilder = StringBuilder()
        val toolAccumulators = linkedMapOf<Int, MutableToolCall>()
        var recordedUsage = false

        reader.useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || !line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                val usageObj = chunk.optJSONObject("usage")
                if (usageObj != null && !recordedUsage) {
                    val pTok = usageObj.optLong("prompt_tokens", 0L)
                    val cTok = usageObj.optLong("completion_tokens", 0L)
                    val cacheHit = WhaleTokenMeter.extractCacheHitTokens(usageObj)
                    if (pTok > 0L || cTok > 0L) {
                        recordedUsage = true
                        runCatching { WhaleTokenMeter.record(app, pTok, cTok, cacheHit, model) }
                    }
                }
                val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue

                val deltaText = delta.optNonNullString("content", "")
                if (deltaText.isNotEmpty()) {
                    contentBuilder.append(deltaText)
                    listener?.onDelta(deltaText)
                }

                val deltaCalls = delta.optJSONArray("tool_calls")
                if (deltaCalls != null) {
                    for (i in 0 until deltaCalls.length()) {
                        val tc = deltaCalls.optJSONObject(i) ?: continue
                        val idx = tc.optInt("index", i)
                        val acc = toolAccumulators.getOrPut(idx) { MutableToolCall() }
                        val id = tc.optNonNullString("id", "")
                        if (id.isNotEmpty()) acc.id = id
                        val fn = tc.optJSONObject("function")
                        if (fn != null) {
                            val namePart = fn.optNonNullString("name", "")
                            if (namePart.isNotEmpty()) acc.name.append(namePart)
                            val argsPart = fn.optNonNullString("arguments", "")
                            if (argsPart.isNotEmpty()) acc.arguments.append(argsPart)
                        }
                    }
                }
            }
        }

        val toolCalls = if (toolAccumulators.isEmpty()) {
            null
        } else {
            JSONArray().apply {
                toolAccumulators.values.forEach { acc ->
                    put(
                        JSONObject()
                            .put("id", acc.id.ifBlank { UUID.randomUUID().toString() })
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", acc.name.toString())
                                    .put("arguments", acc.arguments.toString().ifBlank { "{}" }),
                            ),
                    )
                }
            }
        }
        if (!recordedUsage) {
            runCatching { WhaleTokenMeter.record(app, promptCharsHint / 3L, contentBuilder.length.coerceAtLeast(12) / 2L, 0L, model) }
        }
        return AssistantTurn(contentBuilder.toString(), toolCalls)
    }

    private class MutableToolCall {
        var id: String = ""
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    private fun JSONObject.optNonNullString(key: String, fallback: String = ""): String =
        if (!has(key) || isNull(key)) fallback else optString(key, fallback)

    companion object {
        private val NEXT_BLOCK_REGEX = Regex("<!--whale_next:(\\[[\\s\\S]*?\\])-->")

        internal fun extractSuggestions(raw: String): Pair<String, List<String>> {
            val match = NEXT_BLOCK_REGEX.find(raw) ?: return raw.trim() to emptyList()
            val clean = raw.replace(NEXT_BLOCK_REGEX, "").trim()
            val list = ArrayList<String>()
            runCatching {
                val arr = JSONArray(match.groupValues[1])
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i).trim()
                    if (item.isNotEmpty() && item.length <= 28 && list.size < 3) list.add(item)
                }
            }
            return clean to list
        }

        private val CHOICE_REGEX = Regex("([\u4e00-\u9fa5A-Za-z0-9]{2,8})还是([\u4e00-\u9fa5A-Za-z0-9]{2,8})")

        /** 当快速模型漏吐 <!--whale_next--> 但回复里向用户提了问题时，本地零成本兜底生成一键回答胶囊。 */
        internal fun inferFallbackSuggestions(reply: String): List<String> {
            val text = reply.trim()
            if (text.isEmpty()) return emptyList()
            val tail = text.takeLast(160)
            val asksUser = tail.contains('？') || tail.contains('?') ||
                listOf("要不要", "是否", "试试", "好不好", "需要吗", "继续吗", "告诉我").any { it in tail }
            if (!asksUser) return emptyList()
            val choice = CHOICE_REGEX.find(tail)
            if (choice != null) {
                val rawA = choice.groupValues[1].trim()
                val a = rawA.replace(Regex("^(?:你还没说|还没说|你是想|你想|你要|你打算|打算|准备|需要|应该|请问|先|是|选|要)"), "").ifEmpty { rawA }
                val b = choice.groupValues[2].trim()
                if (a.isNotEmpty() && b.isNotEmpty() && a != b) {
                    return listOf(a, b, "先不急")
                }
            }
            return when {
                listOf("要不要", "试试", "需要吗", "好不好", "是否", "帮我", "帮你", "先塞", "先加").any { it in tail } ->
                    listOf("好呀，直接做", "先不用了", "我有补充")
                listOf("继续", "哪一步", "怎么处理", "接下来").any { it in tail } ->
                    listOf("继续下一步", "先停在这里", "换个方式")
                else -> listOf("好的，按你建议做", "再详细说说", "先这样")
            }
        }
        private const val MAX_TOOL_ROUNDS = 20
        private val parallelToolPool = Executors.newFixedThreadPool(4)
    }
}
