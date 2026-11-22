package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 原生安全网页抓取与多源搜索工具（基于项目内置 Jsoup，零外部运行时依赖）。
 *
 * - SSRF 防护：复用 [WhaleCustomToolEngine.isHostBlocked] 并校验 DNS 解析结果，
 *   手动追踪最多 4 跳重定向且每跳均重新校验，杜绝经由 302 跳转访问本机/内网地址；
 * - 体积上限：单次最多读取 256 KiB 原始响应体，防止超大页面引发移动端 OOM；
 * - 多源搜索回退：优先 DuckDuckGo HTML，失败或无结果时自动回退至 Bing HTML（Jsoup 解析）。
 */
internal object WhaleWebFetcher {
    private const val MAX_REDIRECTS = 4
    private const val MAX_RESPONSE_BYTES = 256 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    data class FetchResult(
        val ok: Boolean,
        val status: Int,
        val finalUrl: String,
        val title: String,
        val content: String,
        val truncatedBytes: Boolean = false,
    )

    /** 校验 URL 协议与目标主机（静态规则 + DNS 私有地址拦截）。 */
    fun validatePublicUrl(rawUrl: String): URI {
        val trimmed = rawUrl.trim()
        require(trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
            "仅支持 http:// 或 https:// 网址"
        }
        val uri = runCatching { URI(trimmed) }.getOrElse {
            throw IllegalArgumentException("URL 格式无效：$trimmed")
        }
        val host = uri.host?.trim().orEmpty()
        require(host.isNotEmpty()) { "URL 缺少合法主机名" }
        require(!WhaleCustomToolEngine.isHostBlocked(host)) { "禁止访问本机或内网地址：$host" }
        val resolved = runCatching { InetAddress.getAllByName(host) }.getOrNull().orEmpty()
        for (addr in resolved) {
            if (isPrivateAddress(addr)) {
                throw IllegalArgumentException("目标域名解析到内网或受保护地址，已拒绝访问：$host")
            }
        }
        return uri
    }

    internal fun isPrivateAddress(addr: InetAddress): Boolean =
        addr.isAnyLocalAddress ||
            addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress ||
            WhaleCustomToolEngine.isHostBlocked(addr.hostAddress.orEmpty())

    /** 安全抓取网页并提取正文与标题（防 SSRF、防大文件 OOM、Jsoup 清洗）。 */
    fun fetch(rawUrl: String, maxChars: Int = 6_000, context: android.content.Context? = null): FetchResult {
        val safeMaxChars = maxChars.coerceIn(500, 12_000)
        var currentUri = validatePublicUrl(rawUrl)
        var statusCode = 0
        var rawHtml = ""
        var truncated = false
        var contentType = ""

        for (hop in 0..MAX_REDIRECTS) {
            val conn = (URL(currentUri.toString()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.8")
                if (context != null) {
                    WhaleSiteVault.cookieForUrl(context, currentUri.toString())?.let { cookie ->
                        setRequestProperty("Cookie", cookie)
                    }
                }
            }
            try {
                statusCode = conn.responseCode
                if (statusCode in setOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location")?.trim().orEmpty()
                    if (location.isEmpty() || hop >= MAX_REDIRECTS) break
                    val nextUrl = currentUri.resolve(location).toString()
                    currentUri = validatePublicUrl(nextUrl)
                    continue
                }
                contentType = conn.contentType.orEmpty().lowercase()
                val stream = if (statusCode in 200..299) conn.inputStream else conn.errorStream
                if (stream != null) {
                    val (bytes, hitCap) = readBoundedBytes(stream, MAX_RESPONSE_BYTES)
                    rawHtml = String(bytes, Charsets.UTF_8)
                    truncated = hitCap
                }
                break
            } finally {
                conn.disconnect()
            }
        }

        val (title, cleaned) = if (contentType.contains("json") || contentType.contains("text/plain")) {
            "" to rawHtml.replace(Regex("\\s+"), " ").trim().take(safeMaxChars)
        } else {
            extractReadableText(rawHtml, safeMaxChars)
        }

        return FetchResult(
            ok = statusCode in 200..299,
            status = statusCode,
            finalUrl = currentUri.toString(),
            title = title,
            content = cleaned,
            truncatedBytes = truncated,
        )
    }

    /** 多源联网搜索：优先 DuckDuckGo HTML，失败或空结果时自动回退至 Bing HTML。 */
    fun search(query: String, limit: Int = 5): JSONObject {
        val q = query.trim()
        require(q.isNotEmpty()) { "缺少搜索关键词 query" }
        val safeLimit = limit.coerceIn(1, 8)

        val ddgItems = runCatching { searchDuckDuckGo(q, safeLimit) }.getOrDefault(JSONArray())
        if (ddgItems.length() > 0) {
            return JSONObject()
                .put("query", q)
                .put("provider", "duckduckgo")
                .put("count", ddgItems.length())
                .put("items", ddgItems)
        }

        val bingItems = runCatching { searchBing(q, safeLimit) }.getOrDefault(JSONArray())
        return JSONObject()
            .put("query", q)
            .put("provider", if (bingItems.length() > 0) "bing" else "none")
            .put("count", bingItems.length())
            .put("items", bingItems)
    }

    private fun searchDuckDuckGo(query: String, limit: Int): JSONArray {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = fetchRawHtml("https://html.duckduckgo.com/html/?q=$encoded")
        if (html.isBlank()) return JSONArray()
        val doc = Jsoup.parse(html)
        val links = doc.select("a.result__a")
        val snips = doc.select(".result__snippet")
        val items = JSONArray()
        for (i in 0 until minOf(limit, links.size)) {
            val link = links[i]
            val rawHref = link.attr("href").trim()
            val uddg = Regex("""[?&]uddg=([^&]+)""").find(rawHref)?.groupValues?.get(1)
            val href = when {
                uddg != null -> runCatching { URLDecoder.decode(uddg, "UTF-8") }.getOrDefault(rawHref)
                rawHref.startsWith("//") -> "https:$rawHref"
                else -> rawHref
            }
            val title = link.text().trim()
            val snippet = snips.getOrNull(i)?.text()?.trim().orEmpty()
            if (title.isNotEmpty() && href.startsWith("http")) {
                items.put(JSONObject().put("title", title).put("url", href).put("snippet", snippet.take(260)))
            }
        }
        return items
    }

    private fun searchBing(query: String, limit: Int): JSONArray {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = fetchRawHtml("https://www.bing.com/search?q=$encoded&setlang=zh-Hans")
        if (html.isBlank()) return JSONArray()
        val doc = Jsoup.parse(html)
        val results = doc.select("li.b_algo")
        val items = JSONArray()
        for (el in results) {
            if (items.length() >= limit) break
            val a = el.selectFirst("h2 a") ?: continue
            val href = a.attr("href").trim()
            val title = a.text().trim()
            val snippet = el.selectFirst(".b_caption p, p")?.text()?.trim().orEmpty()
            if (title.isNotEmpty() && href.startsWith("http")) {
                items.put(JSONObject().put("title", title).put("url", href).put("snippet", snippet.take(260)))
            }
        }
        return items
    }

    private fun fetchRawHtml(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        }
        return try {
            if (conn.responseCode !in 200..299) return ""
            val (bytes, _) = readBoundedBytes(conn.inputStream, MAX_RESPONSE_BYTES)
            String(bytes, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private fun extractReadableText(rawHtml: String, maxChars: Int): Pair<String, String> {
        if (rawHtml.isBlank()) return "" to ""
        return runCatching {
            val doc = Jsoup.parse(rawHtml)
            val title = doc.title().trim().take(160)
            doc.select("script, style, noscript, svg, iframe, nav, footer").remove()
            val main = doc.selectFirst("article, main, [role=main]") ?: doc.body()
            val text = main?.text().orEmpty().replace(Regex("\\s+"), " ").trim().take(maxChars)
            title to text
        }.getOrElse {
            "" to rawHtml.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(maxChars)
        }
    }

    private fun readBoundedBytes(input: java.io.InputStream, maxBytes: Int): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        var truncated = false
        input.use { stream ->
            while (true) {
                val read = stream.read(buf)
                if (read <= 0) break
                val allowed = (maxBytes - total).coerceAtLeast(0)
                if (read > allowed) {
                    if (allowed > 0) out.write(buf, 0, allowed)
                    truncated = true
                    break
                }
                out.write(buf, 0, read)
                total += read
            }
        }
        return out.toByteArray() to truncated
    }
}
