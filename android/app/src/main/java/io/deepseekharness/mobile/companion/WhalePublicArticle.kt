package io.deepseekharness.mobile.companion

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Fetch only on an explicit library action. No account session, scripts or embedded media. */
internal object WhalePublicArticle {
    data class Article(val title: String, val text: String)
    private const val MAX_BYTES = 2 * 1024 * 1024
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
        .dns(object : Dns {
            override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname).also { addresses -> require(addresses.all(::publicAddress)) }
        })
        .callTimeout(12, TimeUnit.SECONDS).build()

    internal fun publicAddress(ip: InetAddress): Boolean {
        if (ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress || ip.isMulticastAddress) return false
        val b = ip.address
        val first = b[0].toInt() and 255
        if (ip !is Inet4Address) return first in 0x20..0x3f &&
            !(first == 0x20 && b[1].toInt() == 2) && !(first == 0x20 && b[1].toInt() == 1 && b[2].toInt() == 0 && b[3].toInt() == 0)
        val second = b[1].toInt() and 255
        return first in 1..223 && first != 127 && !(first == 100 && second in 64..127) &&
            !(first == 198 && second in 18..19) && !(first == 192 && second == 0)
    }
    private fun checked(url: HttpUrl): HttpUrl {
        val secure = if (url.scheme == "http" && url.port == 80) url.newBuilder().scheme("https").port(443).build() else url
        require(secure.isHttps && secure.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            '.' in url.host && !url.host.endsWith(".local") && !url.host.endsWith(".localhost"))
        return secure
    }
    fun fetch(value: String): Article {
        var url = checked(value.toHttpUrl())
        repeat(5) {
            client.newCall(Request.Builder().url(url).header("Accept", "text/html").build()).execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    url = checked(requireNotNull(url.resolve(requireNotNull(response.header("Location")))))
                    return@repeat
                }
                check(response.isSuccessful)
                val body = requireNotNull(response.body)
                val type = requireNotNull(body.contentType())
                require(type.type == "text" && type.subtype == "html" && body.contentLength() <= MAX_BYTES)
                val source = body.source()
                require(!source.request(MAX_BYTES + 1L)) { "页面超出读取范围" }
                return parse(source.readByteArray(), type.charset()?.name())
            }
        }
        error("重定向过多")
    }
    internal fun parse(bytes: ByteArray, charset: String?): Article {
        require(bytes.size in 1..MAX_BYTES)
        val doc = Jsoup.parse(bytes.inputStream(), charset, "")
        doc.select("script,style,form,nav,footer,aside,[hidden]").remove()
        val article = doc.selectFirst("#js_content, #detail-desc, .note-text, [itemprop=articleBody], article, main")
            ?: error("网页没有提供可读取的公开正文")
        val text = article.wholeText().replace(Regex("[\\t\\x0B\\f\\r ]+"), " ").replace(Regex("\n{3,}"), "\n\n").trim()
        require(text.length in 20..CompanionKnowledgeData.MAX_DOCUMENT_TEXT && '\u0000' !in text)
        val title = (doc.selectFirst("h1")?.text()?.takeIf(String::isNotBlank) ?: doc.title()).trim().take(160)
        return Article(title.ifEmpty { "分享网页" }, text)
    }
}
