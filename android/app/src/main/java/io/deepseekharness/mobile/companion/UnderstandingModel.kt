package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A model may propose observations only; these are not confirmed memories or reminders. */
internal object UnderstandingModelContract {
    fun validate(raw: String, eventId: String) {
        require(raw.toByteArray(Charsets.UTF_8).size <= 32_000)
        val root = JSONObject(raw)
        require(root.opt("version") == 1)
        val claims = root.getJSONArray("claims")
        require(claims.length() <= 20)
        for (i in 0 until claims.length()) {
            val claim = claims.getJSONObject(i)
            val text = claim.opt("text") as? String ?: error("invalid claim")
            require(text.isNotBlank() && text.length <= 600 && !CompanionStore.sensitive(text))
            val evidence = claim.getJSONArray("evidence_ids")
            require(evidence.length() == 1 && evidence.opt(0) == eventId) { "invalid evidence" }
        }
        if (root.has("items")) {
            val items = root.getJSONArray("items")
            require(items.length() <= 20)
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val title = item.opt("title") as? String ?: error("invalid item")
                require(title.isNotBlank() && title.length <= 200 && !CompanionStore.sensitive(title))
                val evidence = item.getJSONArray("evidence_ids")
                require(evidence.length() == 1 && evidence.opt(0) == eventId)
            }
        }
    }
}

internal fun interface UnderstandingModel {
    fun analyze(eventId: String, body: String): String
}

internal class UnderstandingRequestException(val code: String, message: String) : IllegalStateException(message)

/** Configured OpenAI-compatible client; never follows redirects or logs content or credentials. */
internal class DeepSeekUnderstandingModel(
    private val key: String,
    private val model: String,
    private val endpoint: String = "https://api.deepseek.com/chat/completions",
    private val allowCustomEndpoint: Boolean = false,
) : UnderstandingModel {
    override fun analyze(eventId: String, body: String): String {
        require(key.isNotBlank() && !key.contains('\n') && !key.contains('\r'))
        require(model.isNotBlank() && model.length <= 200 && model.none { it.isISOControl() })
        val prompt = UnderstandingPrompt.forEvent(eventId, body)
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", prompt.system))
            .put(JSONObject().put("role", "user").put("content", prompt.user))
        val payload = JSONObject().put("model", model).put("messages", messages).put("temperature", 0)
            .put("max_tokens", 1200).put("response_format", JSONObject().put("type", "json_object"))
        val target = java.net.URI(endpoint)
        // DeepSeek defaults to thinking mode, which can consume this small extraction
        // budget before producing JSON. Classification needs the final answer instead.
        if (target.host == "api.deepseek.com") payload.put("thinking", JSONObject().put("type", "disabled"))
        val builtInHosts = setOf("api.deepseek.com", "api.openai.com", "openrouter.ai", "api.groq.com", "api.x.ai", "api.mistral.ai")
        require(target.scheme == "https" && target.userInfo == null && target.host != null &&
            (allowCustomEndpoint || target.host in builtInHosts))
        val connection = target.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000; connection.readTimeout = 30_000
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw UnderstandingRequestException(when (status) {
                401, 403 -> "credentials"
                429 -> "rate_limited"
                in 500..599 -> "service_unavailable"
                else -> "request_rejected"
            }, "后台模型请求未成功")
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    check(output.size() + n <= 64_000) { "model response too large" }
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            val response = JSONObject(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString())
            val content = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            UnderstandingModelContract.validate(content, eventId)
            return content
        } finally { connection.disconnect() }
    }
}

/** Called by an explicitly authorized worker, never by notification capture itself. */
internal class UnderstandingProcessor(private val queue: UnderstandingQueue, private val model: UnderstandingModel) {
    fun processOne(now: Long, retryAt: Long): Boolean {
        require(retryAt >= now)
        val claim = queue.claim(now) ?: return false
        try {
            val event = queue.eventFor(claim) ?: error("event unavailable or permission revoked")
            val output = model.analyze(event.first, event.second)
            UnderstandingModelContract.validate(output, event.first)
            return queue.commitResult(claim, event.first, output)
        } catch (e: Exception) {
            queue.retry(claim, retryAt)
            throw e
        }
    }
}
