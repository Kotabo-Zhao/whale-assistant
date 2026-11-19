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
 * - 秒级启动、零容器开销；
 * - 按意图动态裁剪工具 Schema；
 * - 写入/创作类工具调用写入 [UnifiedExecutionLedger]（SQLite WAL）追踪状态。
 */
internal class WhaleNativeAgentEngine(private val context: Context) {
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
        val mode: String = "native_direct",
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

    private val app = context.applicationContext
    private val tools = WhaleNativeToolRegistry(app)
    private val ledger = UnifiedExecutionLedger(app)

    fun send(
        userMessage: String,
        history: List<Pair<String, String>> = emptyList(),
        sessionId: String = "whale-main",
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
                .put("content", buildSystemPrompt()),
        )
        history.takeLast(12).forEach { (role, text) ->
            if (role in setOf("user", "assistant") && text.isNotBlank()) {
                messages.put(JSONObject().put("role", role).put("content", text.take(6000)))
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", trimmed.take(8000)))

        val steps = ArrayList<StepRecord>()
        var finalReply = ""

        for (round in 0 until MAX_TOOL_ROUNDS) {
            val response = callChatCompletions(endpoint, messages, if (toolSchemas.length() > 0) toolSchemas else null)
            val choice = response.optJSONArray("choices")?.optJSONObject(0)
                ?: throw IllegalStateException("模型服务未返回有效内容")
            val message = choice.optJSONObject("message")
                ?: throw IllegalStateException("模型响应缺少 message 字段")
            val content = message.optString("content", "").trim()
            val toolCalls = message.optJSONArray("tool_calls")

            if (toolCalls == null || toolCalls.length() == 0) {
                finalReply = content.ifBlank { "已完成处理。" }
                break
            }

            messages.put(message)
            for (i in 0 until toolCalls.length()) {
                val call = toolCalls.optJSONObject(i) ?: continue
                val callId = call.optString("id", UUID.randomUUID().toString())
                val fn = call.optJSONObject("function") ?: continue
                val fnName = fn.optString("name", "")
                val fnArgs = fn.optString("arguments", "{}")
                val opId = "native-${sessionId.take(16)}-$round-$callId"
                val mutating = tools.isMutating(fnName)
                if (mutating) {
                    ledger.recordIntent(opId, sessionId, "native-router", round.toLong(), fnName)
                    ledger.transition(opId, UnifiedExecutionLedger.State.DISPATCHING)
                }
                val t0 = System.currentTimeMillis()
                val outcome = tools.execute(fnName, fnArgs)
                val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                if (mutating) {
                    ledger.transition(
                        opId,
                        if (outcome.ok) UnifiedExecutionLedger.State.COMPLETED else UnifiedExecutionLedger.State.FAILED,
                        outcome.label,
                    )
                }
                steps += StepRecord(fnName, outcome.label, outcome.ok, dt)
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", outcome.payload.take(12_000)),
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
        val deepSeekKey = store.readModelCredential("deepseek")?.takeIf(String::isNotBlank)
        if (deepSeekKey != null) {
            val model = settings.defaultModels[ModelProvider.DEEPSEEK]?.takeIf(String::isNotBlank) ?: "deepseek-chat"
            return Endpoint("https://api.deepseek.com/v1/chat/completions", deepSeekKey, model)
        }
        val openAiKey = store.readModelCredential("openai")?.takeIf(String::isNotBlank)
        if (openAiKey != null) {
            val model = settings.defaultModels[ModelProvider.OPENAI]?.takeIf(String::isNotBlank) ?: "gpt-4o-mini"
            return Endpoint("https://api.openai.com/v1/chat/completions", openAiKey, model)
        }
        for (custom in settings.customProviders) {
            val key = store.readModelCredential(custom.id)?.takeIf(String::isNotBlank) ?: continue
            val model = custom.models.firstOrNull()?.id?.takeIf(String::isNotBlank) ?: continue
            val base = custom.baseUrl.trimEnd('/')
            val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            return Endpoint(url, key, model)
        }
        throw IllegalStateException("请先在「我的 → 模型与密钥」中配置 API Key")
    }

    private fun buildSystemPrompt(): String = buildString {
        append("你是 Whale Assistant（鲸鱼助手），运行在用户手机上的个人 Super Agent。\n")
        append("风格要求：简洁、自然、直接、有温度，像一个靠谱的私人助理，不堆砌机械术语或多余确认。\n")
        append("当需要查询记忆、主人档案、后台任务或创作工坊离线小工具时，直接调用已提供的工具并基于真实结果作答。\n")
        runCatching {
            val owner = OwnerContext(app).read()
            val prompt = OwnerContext.prompt(owner)
            if (prompt.isNotBlank()) append("\n").append(prompt)
        }
    }

    private fun callChatCompletions(endpoint: Endpoint, messages: JSONArray, toolsArray: JSONArray?): JSONObject {
        val body = JSONObject()
            .put("model", endpoint.model)
            .put("messages", messages)
            .put("stream", false)
        if (toolsArray != null && toolsArray.length() > 0) {
            body.put("tools", toolsArray)
            body.put("tool_choice", "auto")
        }

        val conn = (URL(endpoint.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${endpoint.apiKey}")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("模型接口返回 HTTP $code：${text.take(240)}")
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 5
    }
}
