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

        // 1. 100% 静态 System Prompt：跨轮/跨对话完全稳定，最大化前缀缓存命中率（缓存输入便宜 10~50 倍）
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", buildStaticSystemPrompt()),
        )

        // 2. 滑动历史窗口：保留最近 12 条消息，近 4 条保留完整度，更早消息自动压缩，避免上下文雪球
        val recentHistory = history.takeLast(12)
        val totalHist = recentHistory.size
        recentHistory.forEachIndexed { idx, (role, text) ->
            if (role in setOf("user", "assistant") && text.isNotBlank()) {
                val limit = if (idx >= totalHist - 4) 4_000 else 1_000
                val clipped = if (text.length > limit) text.take(limit) + "…[历史已精简]" else text
                messages.put(JSONObject().put("role", role).put("content", clipped))
            }
        }

        // 3. 动态本轮上下文（档案/Worker/RAG）放在最新用户消息前，绝不污染静态 System Prompt 缓存前缀
        val dynamicCtx = buildDynamicTurnContext(trimmed)
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
                    if (p.fnName == "whale_tool_create" || p.fnName == "whale_tool_delete") {
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
        if (finalReply.isBlank()) {
            finalReply = if (wasCancelled) "已停止生成。" else "已执行完相关步骤，但模型未生成总结，请再试一次。"
        }
        val (cleanReply, suggestions) = extractSuggestions(finalReply)
        return ChatResult(
            reply = cleanReply,
            steps = steps,
            model = endpoint.model,
            elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L),
            suggestions = if (wasCancelled) emptyList() else suggestions,
            cancelled = wasCancelled,
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

    private fun buildStaticSystemPrompt(): String = buildString {
        append("你是 Whale Assistant（鲸鱼助手），运行在用户手机上的主动式私人幕僚与 100% 原生 Super Agent。简单问答与只读核查立即一步到位执行；复杂长耗时任务主动用 whale_worker_submit 派发给后台 Worker。\n")
        append("主动幕僚原则：不要被动等待追问。看到用户的目标、待办或日程线索时，主动串联所需只读查询并给出完整建议与下一步落地选项；若收到 [用户中途补充/修正]，立即以最新补充为准调整思路与方案。\n")
        append("风格：简洁、自然、直接、有温度，像靠谱的私人助理，不堆砌术语或多余确认。\n")
        append("精确原则：不确定的新信息先调用 whale_web_search 查证，绝不编造；若需要手机屏控、代码工坊、应用设置或设备定时等专域工具而当前列表未展开，先调用 whale_tools_expand 热加载即可立即使用。\n")
        append("工具效率：多个互不依赖的只读查询尽量在同一轮并行发出 tool_calls，避免重复调用相同参数。\n")
        append("下一步行动提案：在每次最终回复的最末尾，另起一行输出 <!--whale_next:[\"简短建议1\",\"简短建议2\",\"简短建议3\"]-->，给出 2~3 条贴合本轮上下文、用户点一下就能直接执行的中文短建议（每条不超过 16 字，绝不写空泛废话）。")
        runCatching {
            val persona = CompanionPersonaStore(app).active()
            val personaPrompt = CompanionBubbleChatPolicy.personaContext(persona)
            if (personaPrompt.isNotBlank()) append("\n").append(personaPrompt.take(1200))
        }
    }

    private fun buildDynamicTurnContext(userQuery: String): String = buildString {
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
            val rag = WhaleNativeRagRetriever.retrieveContext(app, userQuery)
            if (rag.isNotBlank()) append(rag.take(2400))
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
        private const val MAX_TOOL_ROUNDS = 14
        private val parallelToolPool = Executors.newFixedThreadPool(4)
    }
}
