package io.deepseekharness.mobile.companion

import android.content.Context
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
import java.util.UUID

/**
 * 原生安全网页抓取与多源社区搜索工具（基于内置 Jsoup，零外部运行时依赖）。
 *
 * - SSRF 防护：复用 [WhaleCustomToolEngine.isHostBlocked] 并校验 DNS 解析结果，
 *   手动追踪最多 4 跳重定向且每跳均重新校验；
 * - 体积上限：单次最多读取 256 KiB 原始响应体；
 * - 中文社区优先多源搜索：
 *   1. B 站原生直搜通道（自动带临时 buvid3 + 复用 [WhaleSiteVault] 登录态，直出 UP 主/播放量/时长/BV 号）；
 *   2. 小红书/知乎/GitHub 站内定向检索（cn.bing.com + 搜狗双引擎聚合，并提示可配合 `whale_web_render` 读取动态正文）；
 *   3. 中文查询默认优先 Bing 中文 + 搜狗，英文/海外查询再回退 DuckDuckGo HTML。
 */
internal object WhaleWebFetcher {
    private const val MAX_REDIRECTS = 4
    private const val MAX_RESPONSE_BYTES = 256 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

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
    fun fetch(rawUrl: String, maxChars: Int = 6_000, context: Context? = null): FetchResult {
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

    /** 多源联网搜索：支持按 platform 定向直搜 B 站、小红书、知乎、GitHub，中文优先 Bing CN + 搜狗。 */
    fun search(query: String, limit: Int = 5, platform: String = "auto", context: Context? = null): JSONObject {
        val q = query.trim()
        require(q.isNotEmpty()) { "缺少搜索关键词 query" }
        val safeLimit = limit.coerceIn(1, 8)
        val normPlatform = detectPlatform(q, platform)

        // 1. B 站原生直搜通道（返回 UP 主 / 播放量 / 时长 / BV 链接）
        if (normPlatform == "bilibili") {
            val cleanBiliQuery = q.replace(Regex("(?i)(b站|bilibili|哔哩哔哩|site:bilibili\\.com\\S*)"), "").trim().ifBlank { q }
            val biliItems = runCatching { searchBilibiliNative(cleanBiliQuery, safeLimit, context) }.getOrDefault(JSONArray())
            if (biliItems.length() > 0) {
                return JSONObject()
                    .put("query", q)
                    .put("platform", "bilibili")
                    .put("provider", "bilibili_api")
                    .put("count", biliItems.length())
                    .put("items", biliItems)
            }
            val scoped = searchChineseWeb("site:bilibili.com/video $cleanBiliQuery", safeLimit)
            return JSONObject()
                .put("query", q)
                .put("platform", "bilibili")
                .put("provider", scoped.first)
                .put("count", scoped.second.length())
                .put("items", scoped.second)
        }

        // 2. 小红书站内定向检索（双中文引擎 + 给出小红书站内搜索页供 whale_web_render 深入读取）
        if (normPlatform == "xiaohongshu") {
            val cleanXhs = q.replace(Regex("(?i)(小红书|xhs|site:xiaohongshu\\.com\\S*)"), "").trim().ifBlank { q }
            val scoped = searchChineseWeb("site:xiaohongshu.com $cleanXhs", safeLimit)
            val xhsSearchUrl = "https://www.xiaohongshu.com/search_result?keyword=${URLEncoder.encode(cleanXhs, "UTF-8")}"
            return JSONObject()
                .put("query", q)
                .put("platform", "xiaohongshu")
                .put("provider", scoped.first)
                .put("count", scoped.second.length())
                .put("items", scoped.second)
                .put("xhsSearchUrl", xhsSearchUrl)
                .put("hint", "如需查看小红书笔记正文或站内实时结果，请调用 whale_web_render 渲染上述链接或 xhsSearchUrl。")
        }

        // 3. 知乎 / GitHub 定向检索
        val effectiveQuery = when (normPlatform) {
            "zhihu" -> "site:zhihu.com ${q.replace(Regex("(?i)(知乎|site:zhihu\\.com\\S*)"), "").trim().ifBlank { q }}"
            "github" -> "site:github.com ${q.replace(Regex("(?i)(github|site:github\\.com\\S*)"), "").trim().ifBlank { q }}"
            else -> q
        }

        val hasCjk = Regex("[\\u4e00-\\u9fa5]").containsMatchIn(effectiveQuery)
        if (hasCjk || normPlatform == "zhihu") {
            val (provider, items) = searchChineseWeb(effectiveQuery, safeLimit)
            if (items.length() > 0) {
                return JSONObject()
                    .put("query", q)
                    .put("platform", normPlatform)
                    .put("provider", provider)
                    .put("count", items.length())
                    .put("items", items)
            }
        }

        // 英文/海外或中文双源均为空时回退 DuckDuckGo -> Bing 国际
        val ddgItems = runCatching { searchDuckDuckGo(effectiveQuery, safeLimit) }.getOrDefault(JSONArray())
        if (ddgItems.length() > 0) {
            return JSONObject()
                .put("query", q)
                .put("platform", normPlatform)
                .put("provider", "duckduckgo")
                .put("count", ddgItems.length())
                .put("items", ddgItems)
        }

        val bingItems = runCatching { searchBing(effectiveQuery, safeLimit, cn = false) }.getOrDefault(JSONArray())
        return JSONObject()
            .put("query", q)
            .put("platform", normPlatform)
            .put("provider", if (bingItems.length() > 0) "bing" else "none")
            .put("count", bingItems.length())
            .put("items", bingItems)
    }

    private fun detectPlatform(query: String, requested: String): String {
        val p = requested.trim().lowercase()
        if (p in setOf("bilibili", "xiaohongshu", "zhihu", "github")) return p
        val low = query.lowercase()
        return when {
            low.contains("小红书") || low.contains("xiaohongshu") || low.contains("xhs") -> "xiaohongshu"
            low.contains("b站") || low.contains("bilibili") || low.contains("哔哩哔哩") -> "bilibili"
            low.contains("知乎") || low.contains("zhihu") -> "zhihu"
            else -> "auto"
        }
    }

    private fun searchChineseWeb(query: String, limit: Int): Pair<String, JSONArray> {
        val cnBing = runCatching { searchBing(query, limit, cn = true) }.getOrDefault(JSONArray())
        if (cnBing.length() >= minOf(2, limit)) return "bing_cn" to cnBing
        val sogou = runCatching { searchSogou(query, limit) }.getOrDefault(JSONArray())
        if (cnBing.length() == 0 && sogou.length() > 0) return "sogou" to sogou
        if (cnBing.length() > 0 && sogou.length() > 0) {
            val merged = JSONArray()
            val seen = mutableSetOf<String>()
            for (arr in listOf(cnBing, sogou)) {
                for (i in 0 until arr.length()) {
                    if (merged.length() >= limit) break
                    val obj = arr.optJSONObject(i) ?: continue
                    val u = obj.optString("url")
                    if (seen.add(u)) merged.put(obj)
                }
            }
            return "bing_cn+sogou" to merged
        }
        return (if (cnBing.length() > 0) "bing_cn" else "none") to cnBing
    }

    private fun searchBilibiliNative(keyword: String, limit: Int, context: Context?): JSONArray {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val apiUrl = "https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=$encoded&page=1&page_size=$limit"
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", DESKTOP_UA)
            setRequestProperty("Referer", "https://search.bilibili.com/")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            val savedCookie = context?.let { WhaleSiteVault.cookieForUrl(it, apiUrl) }.orEmpty()
            val buvid = "buvid3=${UUID.randomUUID().toString().uppercase()}infoc"
            setRequestProperty("Cookie", if (savedCookie.isNotBlank()) "$savedCookie; $buvid" else buvid)
        }
        val raw = try {
            if (conn.responseCode !in 200..299) return JSONArray()
            val (bytes, _) = readBoundedBytes(conn.inputStream, MAX_RESPONSE_BYTES)
            String(bytes, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 0) return JSONArray()
        val list = root.optJSONObject("data")?.optJSONArray("result") ?: return JSONArray()
        val out = JSONArray()
        for (i in 0 until minOf(limit, list.length())) {
            val row = list.optJSONObject(i) ?: continue
            val bvid = row.optString("bvid").trim()
            val title = row.optString("title").replace(Regex("<[^>]+>"), "").trim()
            val author = row.optString("author").trim()
            val play = row.optLong("play", 0L)
            val duration = row.optString("duration").trim()
            val desc = row.optString("description").replace(Regex("\\s+"), " ").trim().take(120)
            if (bvid.isNotEmpty() && title.isNotEmpty()) {
                val url = "https://www.bilibili.com/video/$bvid"
                val snippet = "UP主：$author · 播放：$play · 时长：$duration${if (desc.isNotEmpty()) " · $desc" else ""}"
                out.put(
                    JSONObject()
                        .put("title", title)
                        .put("url", url)
                        .put("bvid", bvid)
                        .put("author", author)
                        .put("play", play)
                        .put("duration", duration)
                        .put("snippet", snippet.take(260)),
                )
            }
        }
        return out
    }

    private fun searchSogou(query: String, limit: Int): JSONArray {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = fetchRawHtml("https://www.sogou.com/web?query=$encoded")
        if (html.isBlank()) return JSONArray()
        val doc = Jsoup.parse(html)
        val results = doc.select("div.vrwrap, div.rb")
        val items = JSONArray()
        for (el in results) {
            if (items.length() >= limit) break
            val a = el.selectFirst("h3 a") ?: continue
            var href = a.attr("href").trim()
            if (href.startsWith("/")) href = "https://www.sogou.com$href"
            val title = a.text().trim()
            val snippet = el.selectFirst(".str-text-info, .text-layout, p")?.text()?.trim().orEmpty()
            if (title.isNotEmpty() && href.startsWith("http")) {
                items.put(JSONObject().put("title", title).put("url", href).put("snippet", snippet.take(260)))
            }
        }
        return items
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

    private fun searchBing(query: String, limit: Int, cn: Boolean = true): JSONArray {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val host = if (cn) "https://cn.bing.com" else "https://www.bing.com"
        val html = fetchRawHtml("$host/search?q=$encoded&setlang=zh-Hans&mkt=zh-CN")
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
            setRequestProperty("User-Agent", DESKTOP_UA)
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
