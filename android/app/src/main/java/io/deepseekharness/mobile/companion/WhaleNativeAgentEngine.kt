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

    data class ChatResult(
        val reply: String,
        val steps: List<StepRecord>,
        val model: String,
        val elapsedMs: Long,
        val mode: String = "native_direct_sse",
    ) {
        fun json(): JSONObject {
            val stepsArr = JSONArray()
            steps.forEach { stepsArr.put(it.json()) }
            return JSONObject()
                .put("reply", reply)
                .put("steps", stepsArr)
                .put("model", model)
                .put("elapsedMs", elapsedMs)
                .put("mode", mode)
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
    ): ChatResult {
        val startedAt = System.currentTimeMillis()
        val trimmed = userMessage.trim()
        require(trimmed.isNotEmpty()) { "请输入内容后再发送" }

        val store = RuntimeStore(app)
        val settings = store.settings()
        val endpoint = resolveEndpoint(store, settings)
        val toolSchemas = tools.toolSchemas(trimmed)

        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", buildSystemPrompt(trimmed)),
        )
        history.takeLast(32).forEach { (role, text) ->
            if (role in setOf("user", "assistant") && text.isNotBlank()) {
                messages.put(JSONObject().put("role", role).put("content", text.take(12_000)))
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", trimmed.take(32_000)))

        val steps = ArrayList<StepRecord>()
        var finalReply = ""

        for (round in 0 until MAX_TOOL_ROUNDS) {
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
            val turn = callChatCompletionsWithRetry(
                endpoint = endpoint,
                messages = messages,
                toolsArray = if (toolSchemas.length() > 0) toolSchemas else null,
                listener = streamBridge,
            )
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

            for (i in 0 until toolCalls.length()) {
                val call = toolCalls.optJSONObject(i) ?: continue
                val callId = call.optNonNullString("id", UUID.randomUUID().toString())
                val fn = call.optJSONObject("function") ?: continue
                val fnName = fn.optNonNullString("name", "")
                val fnArgs = fn.optNonNullString("arguments", "{}")
                val opId = "native-${sessionId.take(16)}-$round-$callId"
                val mutating = tools.isMutating(fnName)
                listener?.onPhase("tool:$fnName")
                if (mutating) {
                    ledger.recordIntent(opId, sessionId, "native-router", round.toLong(), fnName)
                    ledger.transition(opId, UnifiedExecutionLedger.State.DISPATCHING)
                }
                val t0 = System.currentTimeMillis()
                onProgress?.invoke(round + 1, "正在执行工具：$fnName", finalReply)
                val outcome = tools.execute(fnName, fnArgs)
                val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                if (mutating) {
                    ledger.transition(
                        opId,
                        if (outcome.ok) UnifiedExecutionLedger.State.COMPLETED else UnifiedExecutionLedger.State.FAILED,
                        outcome.label,
                    )
                }
                val step = StepRecord(fnName, outcome.label, outcome.ok, dt)
                steps += step
                listener?.onStep(step)
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", outcome.payload.take(24_000)),
                )
            }
        }

        if (finalReply.isBlank()) {
            finalReply = "已执行完相关步骤，但模型未生成总结，请再试一次。"
        }
        return ChatResult(
            reply = finalReply,
            steps = steps,
            model = endpoint.model,
            elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L),
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

    private fun buildSystemPrompt(userQuery: String): String = buildString {
        append("你是 Whale Assistant（鲸鱼助手），运行在用户手机上的全局总管 Router 与 100% 原生个人 Super Agent。无论从主界面还是悬浮桌宠唤起，你都总管所有任务并拥有完全权限：简单问答/单步工具立刻执行；复杂长耗时任务主动通过 whale_worker_submit 派发给后台 Worker；手机操作直接调用 whale_phone_* 原生屏控。\n")
        append("风格要求：简洁、自然、直接、有温度，像一个靠谱的私人助理，不堆砌机械术语或多余确认。\n")
        append("诚实与精确原则：遇到不确定或超出训练截止时间的新信息，不要凭印象否定用户或把「不知道」说成「不存在」，先调用 whale_web_search 查证；回答自己有哪些工具或当前任务数时，以本轮真实工具列表与 whale_worker_status 返回为准，严禁凭记忆编造或前后矛盾。\n")
        append("你对整个鲸鱼助手软件拥有完全的控制权：可以直接切换页面、调整极简/鲸鱼娘双风格与深浅主题、切换语言、修改任意伴侣与自治设置、管理人设卡片、启停悬浮桌宠、读写记忆/资料/待办、在本地编写多文件代码、在工坊创作并立即启用打开小工具、派发或取消后台 Worker 长耗时任务（桌宠与主界面拥有完全相同的权限），以及通过无障碍操控手机。收到指令后直接调用原生工具执行，无需让用户手动去设置里找。\n")
        runCatching {
            val persona = CompanionPersonaStore(app).active()
            val personaPrompt = CompanionBubbleChatPolicy.personaContext(persona)
            if (personaPrompt.isNotBlank()) append("\n").append(personaPrompt.take(1500)).append("\n")
        }
        runCatching {
            val owner = OwnerContext(app).read()
            val prompt = OwnerContext.prompt(owner)
            if (prompt.isNotBlank()) append("\n").append(prompt)
        }
        runCatching {
            val inboxPrompt = WorkerReportInbox(app).prompt()
            if (inboxPrompt.isNotBlank()) append(inboxPrompt.take(2400)).append("\n")
            val poolPrompt = WhaleNativeWorkerPool.recentSummaries(4)
            if (poolPrompt.isNotBlank()) append(poolPrompt.take(2000)).append("\n")
        }
        runCatching {
            val rag = WhaleNativeRagRetriever.retrieveContext(app, userQuery)
            if (rag.isNotBlank()) append(rag)
        }
    }

    private fun callChatCompletionsWithRetry(
        endpoint: Endpoint,
        messages: JSONArray,
        toolsArray: JSONArray?,
        listener: StreamListener?,
    ): AssistantTurn {
        var lastError: Throwable? = null
        for (attempt in 0..1) {
            try {
                return callChatCompletions(endpoint, messages, toolsArray, listener)
            } catch (error: TransientHttpException) {
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
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (code !in 200..299) {
                val errText = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
                if (code == 400 && wantStream && includeUsageOption && errText.contains("stream_options", ignoreCase = true)) {
                    return callChatCompletions(endpoint, messages, toolsArray, listener, includeUsageOption = false)
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
                val cacheHit = usageObj.optLong("prompt_cache_hit_tokens", 0L)
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
                if (usageObj != null) {
                    val pTok = usageObj.optLong("prompt_tokens", 0L)
                    val cTok = usageObj.optLong("completion_tokens", 0L)
                    val cacheHit = usageObj.optLong("prompt_cache_hit_tokens", 0L)
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
        private const val MAX_TOOL_ROUNDS = 14
    }
}
