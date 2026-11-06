package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A model may propose observations only; these are not confirmed memories or reminders. */
internal object UnderstandingModelContract {
    fun actionableItems(root: JSONObject): List<JSONObject> {
        val items = root.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).map(items::getJSONObject).filter {
            root.optInt("version") == 1 || (it.optString("owner") == "user" && it.optString("status") == "required")
        }
    }

    fun validate(raw: String, eventId: String, source: String? = null) {
        require(raw.toByteArray(Charsets.UTF_8).size <= 32_000)
        val root = JSONObject(raw)
        require(root.opt("version") in setOf(1, 2))
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
                if (root.optInt("version") == 2) {
                    require(item.optString("owner") in setOf("user", "other", "unknown"))
                    require(item.optString("status") in setOf("required", "completed", "informational"))
                    for (key in listOf("action", "reason", "evidence_quote")) {
                        val value = item.getString(key)
                        require(value.isNotBlank() && value.length <= 600 && !CompanionStore.sensitive(value))
                    }
                    require(source == null || source.contains(item.getString("evidence_quote"))) { "item evidence is absent from source" }
                }
            }
        }
    }
}

internal fun interface UnderstandingModel {
    fun analyze(eventId: String, body: String): String
}

internal interface CompanionJsonModel : UnderstandingModel {
    fun complete(prompt: UnderstandingPrompt.Value, tokenLimit: Int): String

    override fun analyze(eventId: String, body: String): String {
        val raw = complete(UnderstandingPrompt.forEvent(eventId, body), 4096)
        UnderstandingModelContract.validate(raw, eventId, body)
        require(JSONObject(raw).optInt("version") == 2) { "model assessment version missing" }
        return raw
    }
}

internal class UnderstandingRequestException(val code: String, message: String) : IllegalStateException(message)

/** Configured OpenAI-compatible client; never follows redirects or logs content or credentials. */
internal class DeepSeekUnderstandingModel(
    private val key: String,
    private val model: String,
    private val endpoint: String = "https://api.deepseek.com/chat/completions",
    private val allowCustomEndpoint: Boolean = false,
) : CompanionJsonModel {
    override fun complete(prompt: UnderstandingPrompt.Value, tokenLimit: Int): String {
        require(key.isNotBlank() && !key.contains('\n') && !key.contains('\r'))
        require(model.isNotBlank() && model.length <= 200 && model.none { it.isISOControl() })
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", prompt.system))
            .put(JSONObject().put("role", "user").put("content", prompt.user))
        val payload = JSONObject().put("model", model).put("messages", messages)
            .put("max_tokens", tokenLimit).put("response_format", JSONObject().put("type", "json_object"))
        val target = java.net.URI(endpoint)
        // DeepSeek defaults to thinking mode, which can consume this small extraction
        // budget before producing JSON. Classification needs the final answer instead.
        if (target.host == "api.deepseek.com") payload.put("thinking", JSONObject().put("type", "disabled"))
        val builtInHosts = setOf("api.deepseek.com", "api.openai.com", "openrouter.ai", "api.groq.com", "api.x.ai", "api.mistral.ai")
        require(target.scheme == "https" && target.userInfo == null && target.host != null &&
            (allowCustomEndpoint || target.host in builtInHosts))
        val connection = target.toURL().openConnection() as HttpURLConnection
        val cancellation=PetReplyOperation.bind(connection::disconnect)
        try {
            PetReplyOperation.checkpoint()
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
            return content
        } finally { cancellation.close(); connection.disconnect() }
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
