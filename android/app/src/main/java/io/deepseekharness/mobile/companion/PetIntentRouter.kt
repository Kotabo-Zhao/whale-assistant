package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Classifies the user's intent using the same model and recent conversational context. */
internal object PetIntentRouter {
    enum class Intent { CHAT, KNOWLEDGE, AGENT }
    fun parse(content: String): Intent = when (JSONObject(content).getString("intent")) {
        "chat" -> Intent.CHAT
        "knowledge" -> Intent.KNOWLEDGE
        "agent" -> Intent.AGENT
        else -> error("模型返回的对话意图无效，请重试。")
    }
    fun classify(key: String, model: String, history: List<CompanionBubbleChat.Message>): Intent {
        val context = JSONArray().apply { history.takeLast(6).forEach { put(JSONObject().put("role", it.role).put("text", it.text)) } }
        val system = "判断最后一条用户消息的真实意图，结合前文指代。只输出 JSON 对象，字段 intent 取值：" +
            "knowledge：要求查询、读取、核对或根据用户保存的资料、文件、知识库回答，包括隐含指代；" +
            "agent：要求使用设备、工具、外部服务或查询/管理记忆、事项，或修改资料；" +
            "chat：普通角色聊天、创作、解释问题或明确要求不要使用工具。不要把虚构情节当设备操作。" +
            "如果最后一条是回答前文为完成某项任务而提出的问题，应延续原任务归类：资料检索选 knowledge，其他工具任务选 agent，不要把简短回答误判为闲聊。"
        val body = JSONObject().put("model", model).put("thinking", JSONObject().put("type", "disabled"))
            .put("temperature", 0).put("max_tokens", 80).put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", context.toString())))
        val connection = URL("https://api.deepseek.com/chat/completions").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false; connection.connectTimeout = 20_000; connection.readTimeout = 60_000
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) error("模型意图识别失败（HTTP $status），请重试。")
            val response = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            return parse(response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        } finally { connection.disconnect() }
    }
}
