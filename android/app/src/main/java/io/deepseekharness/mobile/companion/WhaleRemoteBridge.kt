package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 可选远程算力 / 桌面 stdio MCP 桥接客户端：
 * 让手机端在需要桌面级工具链（uv/npx/Node.js/stdio MCP 如 B站 MCP、FFmpeg 或重型脚本）时，
 * 安全调用用户自建的远程桥接网关（scripts/whale-remote-bridge.py）。
 * Token 仅保存在私有 SharedPreferences 中，永不向大模型回显明文。
 */
internal object WhaleRemoteBridge {
    private const val PREF_NAME = "whale_remote_bridge"
    private const val KEY_URL = "url"
    private const val KEY_TOKEN = "token"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getConfig(context: Context): JSONObject {
        val p = prefs(context)
        val url = p.getString(KEY_URL, "").orEmpty()
        val hasToken = !p.getString(KEY_TOKEN, "").isNullOrBlank()
        val enabled = p.getBoolean(KEY_ENABLED, false)
        return JSONObject()
            .put("ok", true)
            .put("enabled", enabled)
            .put("url", url)
            .put("hasToken", hasToken)
    }

    fun saveConfig(context: Context, url: String, token: String?, enabled: Boolean): JSONObject {
        val cleanUrl = url.trim().trimEnd('/')
        if (cleanUrl.isNotEmpty() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return JSONObject().put("ok", false).put("error", "远程桥接地址必须以 http:// 或 https:// 开头")
        }
        val editor = prefs(context).edit()
            .putString(KEY_URL, cleanUrl)
            .putBoolean(KEY_ENABLED, enabled && cleanUrl.isNotEmpty())
        if (token != null && token.isNotBlank()) {
            editor.putString(KEY_TOKEN, token.trim())
        }
        editor.apply()
        return getConfig(context)
    }

    private fun request(context: Context, path: String, method: String, body: JSONObject? = null, timeoutMs: Int = 30_000): JSONObject {
        val p = prefs(context)
        val enabled = p.getBoolean(KEY_ENABLED, false)
        val baseUrl = p.getString(KEY_URL, "").orEmpty().trimEnd('/')
        val token = p.getString(KEY_TOKEN, "").orEmpty()
        if (!enabled || baseUrl.isBlank()) {
            return JSONObject().put("ok", false).put("error", "未配置或未启用远程算力/MCP 桥接节点，请先在设置中填写桥接地址与 Token。")
        }
        return runCatching {
            val conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8_000
                readTimeout = timeoutMs.coerceIn(5_000, 180_000)
                setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            conn.disconnect()
            runCatching { JSONObject(text) }.getOrElse {
                JSONObject().put("ok", code in 200..299).put("httpStatus", code).put("raw", text.take(4000))
            }
        }.getOrElse { err ->
            JSONObject().put("ok", false).put("error", "连接远程桥接节点失败：${err.message ?: err.javaClass.simpleName}")
        }
    }

    fun testConnection(context: Context): JSONObject = request(context, "/health", "GET", null, 10_000)

    fun execRemote(context: Context, command: String, language: String = "shell", timeoutSec: Int = 45): JSONObject {
        val clean = command.trim()
        if (clean.isEmpty()) return JSONObject().put("ok", false).put("error", "缺少远程执行命令 command")
        val payload = JSONObject()
            .put("command", clean)
            .put("language", language.ifBlank { "shell" })
            .put("timeoutSec", timeoutSec.coerceIn(3, 180))
        return request(context, "/exec", "POST", payload, (timeoutSec.coerceIn(3, 180) + 5) * 1000)
    }

    fun listMcp(context: Context, server: String = ""): JSONObject {
        val payload = JSONObject().put("server", server.trim())
        return request(context, "/mcp/list", "POST", payload, 35_000)
    }

    fun callMcp(context: Context, server: String, tool: String, arguments: JSONObject = JSONObject()): JSONObject {
        if (server.isBlank() || tool.isBlank()) {
            return JSONObject().put("ok", false).put("error", "调用远程 MCP 需要提供 server 和 tool")
        }
        val payload = JSONObject()
            .put("server", server.trim())
            .put("tool", tool.trim())
            .put("arguments", arguments)
        return request(context, "/mcp/call", "POST", payload, 45_000)
    }
}
