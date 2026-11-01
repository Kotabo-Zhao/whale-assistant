package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.ModelProvider
import io.deepseekharness.mobile.runtime.RuntimeStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Independent, streaming character dialogue. Agent tools stay on the Harness path. */
internal class PetDirectChat(private val context: android.content.Context) {
    fun reply(card: JSONObject, history: List<CompanionBubbleChat.Message>, onPartial: (String) -> Unit): String {
        require(history.isNotEmpty() && history.last().role == "user")
        val store = RuntimeStore(context)
        val key = store.readModelCredential("deepseek")?.takeIf { it.isNotBlank() }
            ?: error("模型直连需要在设置中配置 DeepSeek API Key；也可点击顶部切换 Agent 模式。")
        val model = store.settings().defaultModels[ModelProvider.DEEPSEEK] ?: "deepseek-flash"
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", buildString {
            append("你是小说角色扮演引擎，与用户进行自然的角色对话。严格忠于角色设定和说话方式，保持场景连贯，不替用户决定言行，不要写成任务汇报。\n")
            append(PetRoleplayPrompt.character(card, history))
        }))
        history.forEachIndexed { index, entry ->
            require(entry.role == "user" || entry.role == "assistant")
            var content = CompanionBubbleChatPolicy.validateMessage(entry.text)
            if (index == history.lastIndex) PetRoleplayPrompt.afterHistory(card).takeIf(String::isNotBlank)?.let { content += "\n\n$it" }
            messages.put(JSONObject().put("role", entry.role).put("content", content))
        }
        val body = JSONObject().put("model", model).put("messages", messages).put("stream", true)
            .put("max_tokens", 400).put("temperature", 0.85).put("thinking", JSONObject().put("type", "disabled"))
        val connection = URL("https://api.deepseek.com/chat/completions").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000; connection.readTimeout = 120_000
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) error("模型直连请求失败（HTTP $status），请检查配置或稍后重试。")
            val answer = StringBuilder()
            var finish = ""
            var done = false
            connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (!line.startsWith("data:")) return@forEach
                    val data = line.substringAfter(':').trim()
                    if (data == "[DONE]") { done = true; return@forEach }
                    if (data.isEmpty()) return@forEach
                    val choice = JSONObject(data).optJSONArray("choices")?.optJSONObject(0) ?: return@forEach
                    val token = choice.optJSONObject("delta")?.optString("content").orEmpty()
                    if (token.isNotEmpty()) { answer.append(token); onPartial(answer.toString()) }
                    choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }?.let { finish = it }
                }
            }
            if (finish == "content_filter") error("模型服务未返回完整回复（内容过滤）。")
            if (!done && finish.isBlank()) error("模型流式连接中断，请重试。")
            return answer.toString().takeIf(String::isNotBlank) ?: error("模型服务没有返回文本，请重试。")
        } finally { connection.disconnect() }
    }
}
