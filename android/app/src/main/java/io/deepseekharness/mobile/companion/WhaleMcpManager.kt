package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whale 原生 MCP（Model Context Protocol）客户端与服务器注册表。
 *
 * 完整支持标准 Streamable HTTP / JSON-RPC 2.0 MCP 协议：
 * - initialize 握手 + Mcp-Session-Id 会话保持 + JSON / SSE（text/event-stream）双格式响应解析；
 * - tools/list 自动发现远端工具清单并缓存；
 * - tools/call 直调远端工具并解包 text/image/resource 返回；
 * - 自动将已启用 MCP 服务器的工具动态映射为 mcp__<serverId>__<toolName> 注入 Agent 工具列表。
 */
internal object WhaleMcpManager {
    private const val PREFS_NAME = "whale_mcp_servers_v1"
    private const val KEY_SERVERS = "servers"
    private const val MAX_SERVERS = 16
    private const val MAX_DYNAMIC_TOOLS = 24
    private val rpcId = AtomicInteger(1)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun sanitizeId(raw: String): String = raw.trim().lowercase()
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')
        .take(28)
        .ifEmpty { "mcp_${System.currentTimeMillis() % 10000}" }

    fun validateUrl(rawUrl: String): String {
        val url = rawUrl.trim()
        val parsed = URL(url)
        val scheme = parsed.protocol.lowercase()
        val host = parsed.host.lowercase()
        val isLocal = host == "127.0.0.1" || host == "localhost" || host.startsWith("192.168.") || host.startsWith("10.")
        require(scheme == "https" || (scheme == "http" && isLocal)) {
            "MCP 地址必须为 https://（本机或局域网 127.0.0.1 / 192.168.x.x 允许 http://）"
        }
        return url
    }

    /** 解析 JSON-RPC 2.0 响应体（兼容 application/json 与 text/event-stream SSE 帧）。 */
    fun parseRpcBody(rawBody: String): JSONObject {
        val trimmed = rawBody.trim()
        if (trimmed.startsWith("{")) return JSONObject(trimmed)
        var lastJson: JSONObject? = null
        for (line in trimmed.lines()) {
            val clean = line.trim()
            if (clean.startsWith("data:")) {
                val payload = clean.removePrefix("data:").trim()
                if (payload.startsWith("{")) {
                    runCatching { JSONObject(payload) }.getOrNull()?.let { lastJson = it }
                }
            }
        }
        return lastJson ?: throw IllegalStateException("MCP 返回了无法解析的响应：${trimmed.take(200)}")
    }

    private fun loadServers(context: Context): JSONArray {
        val raw = prefs(context).getString(KEY_SERVERS, null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun saveServers(context: Context, arr: JSONArray) {
        prefs(context).edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    private data class RpcResponse(val json: JSONObject, val sessionId: String?)

    private fun postRpc(
        url: String,
        authHeader: String,
        sessionId: String?,
        method: String,
        params: JSONObject,
        isNotification: Boolean = false,
    ): RpcResponse {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
        if (!isNotification) body.put("id", rpcId.getAndIncrement())

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 25_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("User-Agent", "WhaleAssistant/0.4 (Android Native MCP Client)")
            if (authHeader.isNotBlank()) setRequestProperty("Authorization", authHeader)
            if (!sessionId.isNullOrBlank()) setRequestProperty("Mcp-Session-Id", sessionId)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val newSession = conn.getHeaderField("Mcp-Session-Id")?.takeIf(String::isNotBlank) ?: sessionId
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            if (isNotification) return RpcResponse(JSONObject(), newSession)
            if (code !in 200..299) {
                throw IllegalStateException("MCP HTTP $code：${text.take(240)}")
            }
            val parsed = parseRpcBody(text)
            parsed.optJSONObject("error")?.let { err ->
                throw IllegalStateException("MCP 错误 ${err.optInt("code")}: ${err.optString("message")}")
            }
            return RpcResponse(parsed, newSession)
        } finally {
            conn.disconnect()
        }
    }

    private fun initializeSession(url: String, authHeader: String): String? {
        val res = postRpc(
            url = url,
            authHeader = authHeader,
            sessionId = null,
            method = "initialize",
            params = JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "whale-assistant").put("version", "0.4.0")),
        )
        runCatching {
            postRpc(url, authHeader, res.sessionId, "notifications/initialized", JSONObject(), isNotification = true)
        }
        return res.sessionId
    }

    private fun fetchTools(url: String, authHeader: String): JSONArray {
        val sessionId = runCatching { initializeSession(url, authHeader) }.getOrNull()
        val res = postRpc(url, authHeader, sessionId, "tools/list", JSONObject())
        return res.json.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
    }

    fun addOrUpdateServer(
        context: Context,
        id: String,
        name: String,
        rawUrl: String,
        authHeader: String = "",
    ): JSONObject {
        val url = validateUrl(rawUrl)
        val cleanId = sanitizeId(id.ifBlank { name.ifBlank { URL(url).host } })
        val cleanName = name.trim().ifEmpty { cleanId }.take(48)
        val discovered = fetchTools(url, authHeader.trim())
        val servers = loadServers(context)
        val next = JSONArray()
        var replaced = false
        val entry = JSONObject()
            .put("id", cleanId)
            .put("name", cleanName)
            .put("url", url)
            .put("authHeader", authHeader.trim())
            .put("enabled", true)
            .put("tools", discovered)
            .put("updatedAt", System.currentTimeMillis())
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            if (s.optString("id") == cleanId) {
                next.put(entry)
                replaced = true
            } else {
                next.put(s)
            }
        }
        if (!replaced) {
            require(next.length() < MAX_SERVERS) { "MCP 服务器数量达到上限（$MAX_SERVERS）" }
            next.put(entry)
        }
        saveServers(context, next)
        val toolNames = JSONArray()
        for (i in 0 until discovered.length()) {
            discovered.optJSONObject(i)?.optString("name")?.takeIf(String::isNotBlank)?.let(toolNames::put)
        }
        return JSONObject()
            .put("ok", true)
            .put("id", cleanId)
            .put("name", cleanName)
            .put("url", url)
            .put("toolCount", discovered.length())
            .put("tools", toolNames)
    }

    fun refreshServer(context: Context, id: String): JSONObject {
        val servers = loadServers(context)
        val targetId = sanitizeId(id)
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            if (s.optString("id") == targetId || s.optString("name").equals(id.trim(), ignoreCase = true)) {
                val discovered = fetchTools(s.getString("url"), s.optString("authHeader"))
                s.put("tools", discovered).put("updatedAt", System.currentTimeMillis())
                saveServers(context, servers)
                return JSONObject().put("ok", true).put("id", s.getString("id")).put("toolCount", discovered.length())
            }
        }
        throw IllegalArgumentException("未找到 MCP 服务器：$id")
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean): JSONObject {
        val servers = loadServers(context)
        val targetId = sanitizeId(id)
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            if (s.optString("id") == targetId || s.optString("name").equals(id.trim(), ignoreCase = true)) {
                s.put("enabled", enabled)
                saveServers(context, servers)
                return JSONObject().put("ok", true).put("id", s.getString("id")).put("enabled", enabled)
            }
        }
        throw IllegalArgumentException("未找到 MCP 服务器：$id")
    }

    fun removeServer(context: Context, id: String): JSONObject {
        val servers = loadServers(context)
        val targetId = sanitizeId(id)
        val next = JSONArray()
        var removed = false
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            if (s.optString("id") == targetId || s.optString("name").equals(id.trim(), ignoreCase = true)) {
                removed = true
            } else {
                next.put(s)
            }
        }
        saveServers(context, next)
        return JSONObject().put("removed", removed).put("id", targetId)
    }

    fun listServers(context: Context): JSONArray {
        val servers = loadServers(context)
        val out = JSONArray()
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            val tools = s.optJSONArray("tools") ?: JSONArray()
            val names = JSONArray()
            for (j in 0 until tools.length()) {
                tools.optJSONObject(j)?.optString("name")?.takeIf(String::isNotBlank)?.let(names::put)
            }
            out.put(
                JSONObject()
                    .put("id", s.optString("id"))
                    .put("name", s.optString("name"))
                    .put("url", s.optString("url"))
                    .put("enabled", s.optBoolean("enabled", true))
                    .put("toolCount", tools.length())
                    .put("tools", names),
            )
        }
        return out
    }

    fun callTool(context: Context, serverId: String, toolName: String, arguments: JSONObject): JSONObject {
        val servers = loadServers(context)
        val targetId = sanitizeId(serverId)
        var server: JSONObject? = null
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            if (s.optString("id") == targetId || s.optString("name").equals(serverId.trim(), ignoreCase = true)) {
                server = s
                break
            }
        }
        val target = server ?: throw IllegalArgumentException("未找到 MCP 服务器：$serverId")
        val url = target.getString("url")
        val auth = target.optString("authHeader")
        val sessionId = runCatching { initializeSession(url, auth) }.getOrNull()
        val res = postRpc(
            url = url,
            authHeader = auth,
            sessionId = sessionId,
            method = "tools/call",
            params = JSONObject().put("name", toolName).put("arguments", arguments),
        )
        val result = res.json.optJSONObject("result") ?: JSONObject()
        val isError = result.optBoolean("isError", false)
        val contentArr = result.optJSONArray("content")
        val textParts = mutableListOf<String>()
        if (contentArr != null) {
            for (i in 0 until contentArr.length()) {
                val part = contentArr.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "text" -> part.optString("text").takeIf(String::isNotBlank)?.let(textParts::add)
                    "resource" -> part.optJSONObject("resource")?.optString("text")?.takeIf(String::isNotBlank)?.let(textParts::add)
                    else -> textParts.add(part.toString().take(400))
                }
            }
        }
        return JSONObject()
            .put("ok", !isError)
            .put("server", target.optString("id"))
            .put("tool", toolName)
            .put("output", if (textParts.isNotEmpty()) textParts.joinToString("\n").take(10_000) else result.toString().take(10_000))
    }

    /** 将已启用的 MCP 远端工具动态导出为 OpenAI Function Calling Schema。 */
    fun mcpToolSchemas(context: Context): List<JSONObject> {
        val servers = loadServers(context)
        val out = mutableListOf<JSONObject>()
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            if (!s.optBoolean("enabled", true)) continue
            val serverId = s.optString("id")
            val serverName = s.optString("name", serverId)
            val tools = s.optJSONArray("tools") ?: continue
            for (j in 0 until tools.length()) {
                if (out.size >= MAX_DYNAMIC_TOOLS) return out
                val t = tools.optJSONObject(j) ?: continue
                val tName = t.optString("name").trim()
                if (tName.isEmpty()) continue
                val safeTool = tName.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(36)
                val fullName = "mcp__${serverId}__${safeTool}".take(64)
                val desc = "[MCP:$serverName] ${t.optString("description").ifBlank { tName }}".take(220)
                val inputSchema = t.optJSONObject("inputSchema")
                    ?: JSONObject().put("type", "object").put("properties", JSONObject())
                out.add(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", fullName)
                                .put("description", desc)
                                .put("parameters", inputSchema),
                        ),
                )
            }
        }
        return out
    }

    /** 若工具名为 mcp__<serverId>__<toolName>，直接路由至对应 MCP 服务器执行。 */
    fun tryExecuteDynamic(context: Context, fullName: String, args: JSONObject): WhaleNativeToolRegistry.ToolOutcome? {
        if (!fullName.startsWith("mcp__")) return null
        val rest = fullName.removePrefix("mcp__")
        val sep = rest.indexOf("__")
        if (sep <= 0) return null
        val serverId = rest.substring(0, sep)
        val toolSlug = rest.substring(sep + 2)
        // 还原原始工具名（若含特殊字符）
        val servers = loadServers(context)
        var actualToolName = toolSlug
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            if (s.optString("id") == serverId) {
                val tools = s.optJSONArray("tools") ?: break
                for (j in 0 until tools.length()) {
                    val tName = tools.optJSONObject(j)?.optString("name") ?: continue
                    val safe = tName.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(36)
                    if (tName == toolSlug || safe == toolSlug) {
                        actualToolName = tName
                        break
                    }
                }
                break
            }
        }
        val res = callTool(context, serverId, actualToolName, args)
        return WhaleNativeToolRegistry.ToolOutcome(
            tool = fullName,
            label = "MCP：$serverId.$actualToolName",
            ok = res.optBoolean("ok", true),
            payload = res.toString(),
            mutating = true,
        )
    }

    fun promptSummary(context: Context): String {
        val servers = loadServers(context)
        val lines = mutableListOf<String>()
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            if (!s.optBoolean("enabled", true)) continue
            val tools = s.optJSONArray("tools") ?: JSONArray()
            lines.add("- ${s.optString("name")} (id=${s.optString("id")}, ${tools.length()}个工具)")
        }
        if (lines.isEmpty()) return ""
        return "[已连接 MCP 服务器]\n" + lines.joinToString("\n")
    }
}
