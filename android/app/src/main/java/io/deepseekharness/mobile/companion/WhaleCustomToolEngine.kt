package io.deepseekharness.mobile.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Self-Authored Callable Tool Engine for Whale Assistant.
 *
 * Allows the Agent to create, persist, list, delete, and invoke its own custom tools
 * (prefixed with `custom_`) across three 100%-native sandboxed execution engines:
 *   1. `composite` — multi-step pipeline over built-in `whale_*` tools (no recursion)
 *   2. `http_json` — HTTPS JSON/text API calls with strict SSRF protection & dot-path extraction
 *   3. `js`        — isolated no-network, no-file WebView JavaScript sandbox (5s timeout)
 */
internal object WhaleCustomToolEngine {
    private const val STORE_FILENAME = "whale_custom_tools.json"
    private const val MAX_TOOLS = 64
    private const val MAX_STEPS = 40
    private const val MAX_JS_CHARS = 24_000
    private const val MAX_HTTP_BYTES = 262_144
    private const val HTTP_TIMEOUT_MS = 25_000
    private const val JS_TIMEOUT_MS = 15_000L

    private val NAME_REGEX = Regex("^custom_[a-z][a-z0-9_]{1,40}$")
    private val TEMPLATE_REGEX = Regex("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*\\}\\}")
    private val VALID_KINDS = setOf("composite", "http_json", "js", "python")
    private val FORBIDDEN_COMPOSITE_TOOLS = setOf("whale_tool_create", "whale_tool_delete")
    private val lock = Any()

    data class ExecutionResult(
        val ok: Boolean,
        val label: String,
        val payload: String,
    )

    fun storeFile(context: Context): File = File(context.filesDir, STORE_FILENAME)

    fun isValidToolName(name: String): Boolean = NAME_REGEX.matches(name)

    fun isHostBlocked(rawHost: String): Boolean {
        val host = rawHost.trim().lowercase().trimEnd('.')
        if (host.isEmpty()) return true
        if (host == "localhost" || host == "::1" || host == "[::1]" || host == "0.0.0.0") return true
        if (host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".lan")) return true

        // Literal IPv4 private / loopback / link-local checks
        val ipv4 = host.split(".")
        if (ipv4.size == 4 && ipv4.all { it.toIntOrNull() in 0..255 }) {
            val a = ipv4[0].toInt()
            val b = ipv4[1].toInt()
            if (a == 0 || a == 10 || a == 127) return true
            if (a == 169 && b == 254) return true
            if (a == 172 && b in 16..31) return true
            if (a == 192 && b == 168) return true
            if (a == 100 && b in 64..127) return true
        }
        // IPv6 private / link-local literals
        val stripped = host.removePrefix("[").removeSuffix("]")
        if (stripped.contains(":")) {
            if (stripped == "::1" || stripped.startsWith("fe80:") || stripped.startsWith("fc") || stripped.startsWith("fd") || stripped == "::") {
                return true
            }
        }
        return false
    }

    fun validateHttpsUrl(rawUrl: String): URI {
        val trimmed = rawUrl.trim()
        require(trimmed.startsWith("https://", ignoreCase = true)) { "http_json 工具仅允许使用 https:// 地址" }
        // Replace template placeholders with a harmless token before URI parsing
        val sample = TEMPLATE_REGEX.replace(trimmed, "sample")
        val uri = runCatching { URI(sample) }.getOrElse {
            throw IllegalArgumentException("URL 格式无效：$trimmed")
        }
        val host = uri.host.orEmpty()
        require(host.isNotEmpty()) { "URL 缺少合法主机名" }
        require(!isHostBlocked(host)) { "禁止访问本机或内网地址：$host" }
        return uri
    }

    fun renderTemplate(template: String, args: JSONObject, prevOutput: String = "", urlEncode: Boolean = false): String {
        return TEMPLATE_REGEX.replace(template) { match ->
            val key = match.groupValues[1]
            val raw = if (key == "prev") {
                prevOutput
            } else if (args.has(key) && !args.isNull(key)) {
                args.opt(key)?.toString().orEmpty()
            } else {
                ""
            }
            if (urlEncode) URLEncoder.encode(raw, "UTF-8") else raw
        }
    }

    fun renderJsonTemplates(source: JSONObject, args: JSONObject, prevOutput: String): JSONObject {
        val out = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = source.opt(k)
            when (v) {
                is String -> out.put(k, renderTemplate(v, args, prevOutput, urlEncode = false))
                is JSONObject -> out.put(k, renderJsonTemplates(v, args, prevOutput))
                is JSONArray -> {
                    val arr = JSONArray()
                    for (i in 0 until v.length()) {
                        val elem = v.opt(i)
                        arr.put(
                            when (elem) {
                                is String -> renderTemplate(elem, args, prevOutput, urlEncode = false)
                                is JSONObject -> renderJsonTemplates(elem, args, prevOutput)
                                else -> elem
                            },
                        )
                    }
                    out.put(k, arr)
                }
                else -> out.put(k, v)
            }
        }
        return out
    }

    fun extractJsonPath(root: Any?, path: String): Any? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return root
        var cur: Any? = root
        for (segment in trimmed.split(".")) {
            if (cur == null) return null
            val seg = segment.trim()
            if (seg.isEmpty()) continue
            cur = when (cur) {
                is JSONObject -> if (cur.has(seg) && !cur.isNull(seg)) cur.opt(seg) else null
                is JSONArray -> {
                    val idx = seg.toIntOrNull()
                    if (idx != null && idx in 0 until cur.length()) cur.opt(idx) else null
                }
                else -> null
            }
        }
        return cur
    }

    fun unwrapWebViewJson(raw: String?): JSONObject {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null") {
            return JSONObject().put("ok", false).put("error", "JS 沙箱未返回结果")
        }
        val first = runCatching { JSONTokener(text).nextValue() }.getOrNull()
        return when (first) {
            is JSONObject -> first
            is String -> runCatching { JSONObject(first) }.getOrElse {
                JSONObject().put("ok", true).put("result", first)
            }
            else -> JSONObject().put("ok", true).put("result", first ?: text)
        }
    }

    fun validateDefinition(kind: String, definition: JSONObject) {
        require(kind in VALID_KINDS) { "不支持的工具类型：$kind（仅支持 composite / http_json / js / python）" }
        when (kind) {
            "composite" -> {
                val steps = definition.optJSONArray("steps")
                require(steps != null && steps.length() in 1..MAX_STEPS) {
                    "composite 工具需要 1~$MAX_STEPS 个 steps 步骤"
                }
                for (i in 0 until steps.length()) {
                    val step = steps.optJSONObject(i)
                        ?: throw IllegalArgumentException("第 ${i + 1} 个步骤格式无效")
                    val toolName = step.optString("tool", "").trim()
                    require(toolName.startsWith("whale_")) {
                        "composite 步骤只能调用内置 whale_* 工具，禁止递归调用自定义工具：$toolName"
                    }
                    require(toolName !in FORBIDDEN_COMPOSITE_TOOLS) {
                        "composite 步骤不允许调用 $toolName"
                    }
                }
            }
            "http_json" -> {
                val url = definition.optString("url", "").trim()
                validateHttpsUrl(url)
                val method = definition.optString("method", "GET").trim().uppercase()
                require(method in setOf("GET", "POST")) { "http_json 仅支持 GET 或 POST 方法" }
            }
            "js" -> {
                val code = definition.optString("code", "").trim()
                require(code.isNotEmpty() && code.length <= MAX_JS_CHARS) {
                    "js 工具 code 长度需在 1~$MAX_JS_CHARS 字符之间"
                }
            }
            "python" -> {
                val code = definition.optString("code", "").trim()
                require(code.isNotEmpty() && code.length <= 16_000) {
                    "python 工具 code 长度需在 1~16000 字符之间"
                }
            }
        }
    }

    fun loadAll(file: File): MutableList<JSONObject> = synchronized(lock) {
        if (!file.isFile) return@synchronized mutableListOf()
        val parsed = runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return@synchronized mutableListOf()
        val list = mutableListOf<JSONObject>()
        for (i in 0 until parsed.length()) {
            val obj = parsed.optJSONObject(i) ?: continue
            if (isValidToolName(obj.optString("name", ""))) list.add(obj)
        }
        list
    }

    private fun saveAll(file: File, items: List<JSONObject>) = synchronized(lock) {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(arr.toString(2), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString(2), Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun createOrUpdate(
        file: File,
        name: String,
        description: String,
        kind: String,
        params: JSONObject,
        required: List<String>,
        definition: JSONObject,
    ): JSONObject {
        val cleanName = name.trim()
        val cleanDesc = description.trim()
        val cleanKind = kind.trim().lowercase()
        require(isValidToolName(cleanName)) {
            "自造工具名称必须以 custom_ 开头且仅含小写字母/数字/下划线（总长 9~48 位），例如 custom_weather"
        }
        require(cleanDesc.length in 2..200) { "工具描述长度需在 2~200 字之间" }
        validateDefinition(cleanKind, definition)

        val validParamKeys = mutableSetOf<String>()
        val cleanParams = JSONObject()
        val pKeys = params.keys()
        while (pKeys.hasNext()) {
            val k = pKeys.next().trim()
            if (k.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]{0,31}$"))) {
                validParamKeys.add(k)
                cleanParams.put(k, params.optString(k, k).trim().take(120))
            }
        }
        val cleanRequired = JSONArray()
        required.map { it.trim() }.filter { it in validParamKeys }.distinct().forEach { cleanRequired.put(it) }

        val record = JSONObject()
            .put("name", cleanName)
            .put("description", cleanDesc)
            .put("kind", cleanKind)
            .put("params", cleanParams)
            .put("required", cleanRequired)
            .put("definition", definition)
            .put("updatedAt", System.currentTimeMillis())

        val items = loadAll(file)
        val existingIdx = items.indexOfFirst { it.optString("name") == cleanName }
        if (existingIdx >= 0) {
            items[existingIdx] = record
        } else {
            require(items.size < MAX_TOOLS) { "自定义工具数量已达上限（$MAX_TOOLS 个），请先删除不再使用的工具" }
            items.add(record)
        }
        saveAll(file, items)
        return JSONObject()
            .put("saved", true)
            .put("updated", existingIdx >= 0)
            .put("name", cleanName)
            .put("kind", cleanKind)
            .put("totalCustomTools", items.size)
    }

    fun listSummary(file: File): JSONObject {
        val items = loadAll(file)
        val arr = JSONArray()
        for (item in items) {
            arr.put(
                JSONObject()
                    .put("name", item.optString("name"))
                    .put("kind", item.optString("kind"))
                    .put("description", item.optString("description"))
                    .put("params", item.optJSONObject("params") ?: JSONObject())
                    .put("updatedAt", item.optLong("updatedAt")),
            )
        }
        return JSONObject().put("count", arr.length()).put("tools", arr)
    }

    fun delete(file: File, name: String): JSONObject {
        val cleanName = name.trim()
        val items = loadAll(file)
        val before = items.size
        items.removeAll { it.optString("name") == cleanName }
        val removed = items.size < before
        if (removed) saveAll(file, items)
        return JSONObject()
            .put("name", cleanName)
            .put("removed", removed)
            .put("remainingCustomTools", items.size)
    }

    fun toOpenAiSchema(record: JSONObject): JSONObject {
        val props = JSONObject()
        val params = record.optJSONObject("params") ?: JSONObject()
        val pKeys = params.keys()
        while (pKeys.hasNext()) {
            val k = pKeys.next()
            props.put(k, JSONObject().put("type", "string").put("description", params.optString(k, k)))
        }
        val required = record.optJSONArray("required") ?: JSONArray()
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", record.optString("name"))
                    .put("description", "[自造工具/${record.optString("kind")}] ${record.optString("description")}")
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", props)
                            .put("required", required),
                    ),
            )
    }

    fun customToolSchemas(context: Context): List<JSONObject> =
        runCatching { loadAll(storeFile(context)).map(::toOpenAiSchema) }.getOrDefault(emptyList())

    fun findTool(file: File, name: String): JSONObject? =
        loadAll(file).firstOrNull { it.optString("name") == name }

    fun executeComposite(
        record: JSONObject,
        args: JSONObject,
        stepExecutor: (toolName: String, stepArgs: JSONObject) -> Pair<Boolean, String>,
    ): ExecutionResult {
        val name = record.optString("name")
        val def = record.optJSONObject("definition") ?: JSONObject()
        val steps = def.optJSONArray("steps") ?: JSONArray()
        val results = JSONArray()
        var prevOutput = ""
        var allOk = true

        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            val toolName = step.optString("tool", "").trim()
            if (!toolName.startsWith("whale_") || toolName in FORBIDDEN_COMPOSITE_TOOLS) {
                allOk = false
                results.put(JSONObject().put("step", i + 1).put("tool", toolName).put("ok", false).put("error", "禁止的步骤工具：$toolName"))
                break
            }
            val rawArgs = step.optJSONObject("args") ?: JSONObject()
            val resolvedArgs = renderJsonTemplates(rawArgs, args, prevOutput.take(4000))
            val (ok, payload) = stepExecutor(toolName, resolvedArgs)
            prevOutput = payload
            results.put(
                JSONObject()
                    .put("step", i + 1)
                    .put("tool", toolName)
                    .put("ok", ok)
                    .put("output", payload.take(4000)),
            )
            if (!ok) {
                allOk = false
                if (step.optBoolean("stopOnError", true)) break
            }
        }
        val out = JSONObject()
            .put("tool", name)
            .put("ok", allOk)
            .put("steps", results)
        return ExecutionResult(allOk, "执行自造工作流：$name", out.toString())
    }

    fun executeHttpJson(record: JSONObject, args: JSONObject): ExecutionResult {
        val name = record.optString("name")
        val def = record.optJSONObject("definition") ?: JSONObject()
        val urlTemplate = def.optString("url", "").trim()
        val resolvedUrl = renderTemplate(urlTemplate, args, urlEncode = true)
        val uri = validateHttpsUrl(resolvedUrl)

        // DNS-level SSRF check before opening socket
        val resolvedAddrs = runCatching { InetAddress.getAllByName(uri.host) }.getOrNull()
        if (resolvedAddrs != null) {
            for (addr in resolvedAddrs) {
                val ip = addr.hostAddress.orEmpty()
                if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress || isHostBlocked(ip)) {
                    throw SecurityException("目标域名解析到受限内网地址，已拦截：$ip")
                }
            }
        }

        val method = def.optString("method", "GET").trim().uppercase()
        val conn = (URL(resolvedUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            setRequestProperty("Accept", "application/json, text/plain;q=0.9, */*;q=0.5")
            setRequestProperty("User-Agent", "WhaleAssistant/1.0")
            val headers = def.optJSONObject("headers")
            if (headers != null) {
                val hKeys = headers.keys()
                while (hKeys.hasNext()) {
                    val hk = hKeys.next()
                    if (!hk.equals("Host", ignoreCase = true)) {
                        setRequestProperty(hk, renderTemplate(headers.optString(hk), args))
                    }
                }
            }
            if (method == "POST") {
                doOutput = true
                if (getRequestProperty("Content-Type") == null) {
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
        }
        try {
            if (method == "POST") {
                val body = renderTemplate(def.optString("body", "{}"), args)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val rawBytes = stream?.use { input ->
                val buf = ByteArray(MAX_HTTP_BYTES)
                var total = 0
                while (total < buf.size) {
                    val read = input.read(buf, total, buf.size - total)
                    if (read <= 0) break
                    total += read
                }
                buf.copyOf(total)
            } ?: ByteArray(0)
            val bodyText = String(rawBytes, Charsets.UTF_8)
            val extractPath = def.optString("extractPath", "").trim()
            val parsedJson = runCatching { JSONTokener(bodyText).nextValue() }.getOrNull()
            val data: Any = if (extractPath.isNotEmpty() && parsedJson != null) {
                extractJsonPath(parsedJson, extractPath) ?: JSONObject.NULL
            } else {
                parsedJson ?: bodyText.take(12_000)
            }
            val ok = status in 200..299
            val out = JSONObject()
                .put("ok", ok)
                .put("status", status)
                .put("data", data)
            return ExecutionResult(ok, "调用自造接口：$name", out.toString())
        } finally {
            conn.disconnect()
        }
    }

    fun executeJs(context: Context, record: JSONObject, args: JSONObject): ExecutionResult {
        val name = record.optString("name")
        val def = record.optJSONObject("definition") ?: JSONObject()
        val code = def.optString("code", "").trim()
        require(code.isNotEmpty() && code.length <= MAX_JS_CHARS) { "js 工具代码无效" }
        require(Looper.myLooper() != Looper.getMainLooper()) { "js 沙箱必须在后台线程调用" }

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<String?>(null)
        val webViewRef = AtomicReference<WebView?>(null)
        val mainHandler = Handler(Looper.getMainLooper())

        val wrappedJs = buildString {
            append("(function(args){try{var __fn=function(args){\n")
            append(code)
            append("\n};var __r=__fn(args);return JSON.stringify({ok:true,result:__r===undefined?null:__r});}")
            append("catch(e){return JSON.stringify({ok:false,error:String(e&&e.message||e)});}})( ")
            append(args.toString())
            append(" );")
        }

        mainHandler.post {
            runCatching {
                val wv = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.blockNetworkImage = true
                    settings.domStorageEnabled = false
                    settings.databaseEnabled = false
                }
                webViewRef.set(wv)
                val evaluated = AtomicBoolean(false)
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!evaluated.compareAndSet(false, true)) return
                        view?.evaluateJavascript(wrappedJs) { value ->
                            resultRef.set(value)
                            latch.countDown()
                        }
                    }
                }
                wv.loadDataWithBaseURL("about:blank", "<!doctype html><html><body></body></html>", "text/html", "utf-8", null)
            }.onFailure { err ->
                resultRef.set(JSONObject().put("ok", false).put("error", err.message ?: "WebView 沙箱初始化失败").toString())
                latch.countDown()
            }
        }

        val completed = latch.await(JS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        mainHandler.post {
            runCatching {
                webViewRef.getAndSet(null)?.apply {
                    stopLoading()
                    destroy()
                }
            }
        }

        if (!completed) {
            return ExecutionResult(
                false,
                "执行自造脚本超时：$name",
                JSONObject().put("ok", false).put("error", "JS 沙箱执行超时（>${JS_TIMEOUT_MS / 1000}秒）").toString(),
            )
        }
        val unwrapped = unwrapWebViewJson(resultRef.get())
        val ok = unwrapped.optBoolean("ok", true)
        return ExecutionResult(ok, "执行自造脚本：$name", unwrapped.toString())
    }

    fun execute(
        context: Context,
        name: String,
        args: JSONObject,
        stepExecutor: (toolName: String, stepArgs: JSONObject) -> Pair<Boolean, String>,
    ): ExecutionResult? {
        if (!name.startsWith("custom_")) return null
        val record = findTool(storeFile(context), name) ?: return null
        return when (record.optString("kind")) {
            "composite" -> executeComposite(record, args, stepExecutor)
            "http_json" -> executeHttpJson(record, args)
            "js" -> executeJs(context, record, args)
            "python" -> executePython(context, record, args)
            else -> ExecutionResult(false, "未知自造工具类型", JSONObject().put("error", "UNSUPPORTED_KIND").toString())
        }
    }

    private fun executePython(context: Context, record: JSONObject, args: JSONObject): ExecutionResult {
        val toolName = record.optString("name", "custom_python")
        val code = record.optJSONObject("definition")?.optString("code", "").orEmpty()
        if (code.isBlank()) {
            return ExecutionResult(false, "自造 Python 工具代码为空", JSONObject().put("error", "EMPTY_CODE").toString())
        }
        val dir = File(File(context.applicationContext.filesDir, "whale-projects"), "custom-tools").apply { mkdirs() }
        val argsLiteral = JSONObject.quote(args.toString())
        val wrapped = buildString {
            append("import json as _whale_json\n")
            append("args = _whale_json.loads(").append(argsLiteral).append(")\n")
            append(code).append("\n")
        }
        val res = WhaleCodeRunner.runPython(context = context, projectDir = dir, code = wrapped, timeoutSec = 45L)
        val ok = res.optBoolean("ok", false)
        return ExecutionResult(ok, if (ok) "执行自造 Python 工具：$toolName" else "自造 Python 工具报错：$toolName", res.toString())
    }
}
