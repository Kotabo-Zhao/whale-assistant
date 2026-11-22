package io.deepseekharness.mobile.companion

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 离屏原生 WebView 动态渲染抓取器（Layer 2 搜索与网页读取强化）：
 * 专治小红书、B站动态页、SPA 前端渲染网站等普通 HTTP/Jsoup 只能拿到空壳 HTML 的页面。
 * 自动复用用户通过 [WhaleSiteVault] 授权登录过的站点 Cookie，并严格复用 [WhaleWebFetcher.validatePublicUrl] SSRF 防护。
 */
internal object WhaleWebRenderer {
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun renderAndExtract(
        context: Context,
        rawUrl: String,
        waitMs: Long = 2600L,
        maxChars: Int = 6000,
    ): JSONObject {
        val app = context.applicationContext
        val uri = WhaleWebFetcher.validatePublicUrl(rawUrl)
        val targetUrl = uri.toString()
        val safeWait = waitMs.coerceIn(1000L, 8000L)
        val safeMaxChars = maxChars.coerceIn(500, 12_000)

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return JSONObject().put("ok", false).put("error", "WebView 渲染工具必须在后台线程调用")
        }

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<JSONObject>()
        val finished = AtomicBoolean(false)
        val mainHandler = Handler(Looper.getMainLooper())
        val webViewRef = AtomicReference<WebView?>()

        mainHandler.post {
            try {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                WhaleSiteVault.cookieForUrl(app, targetUrl)?.let { cookieStr ->
                    cookieStr.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { pair ->
                        cm.setCookie(targetUrl, pair)
                    }
                    cm.flush()
                }

                val webView = WebView(app).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = false
                    settings.blockNetworkImage = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.userAgentString = DESKTOP_UA
                    layout(0, 0, 1080, 1920)
                }
                webViewRef.set(webView)

                fun extractNow() {
                    if (!finished.compareAndSet(false, true)) return
                    val js = """
                        (function() {
                          try {
                            var title = (document.title || '').trim();
                            var url = location.href || '';
                            var clone = document.body ? document.body.cloneNode(true) : null;
                            if (clone) {
                              var rm = clone.querySelectorAll('script,style,noscript,svg,iframe');
                              for (var i = 0; i < rm.length; i++) rm[i].remove();
                            }
                            var text = (clone ? (clone.innerText || clone.textContent || '') : '')
                              .replace(/\s+/g, ' ').trim().slice(0, $safeMaxChars);
                            var links = [];
                            var anchors = document.querySelectorAll('a[href]');
                            var seen = {};
                            for (var j = 0; j < anchors.length && links.length < 18; j++) {
                              var a = anchors[j];
                              var href = a.href || '';
                              var t = (a.innerText || a.getAttribute('title') || '').replace(/\s+/g, ' ').trim();
                              if (href.indexOf('http') === 0 && t.length >= 4 && !seen[href]) {
                                seen[href] = 1;
                                links.push({ title: t.slice(0, 90), url: href });
                              }
                            }
                            return JSON.stringify({ ok: true, title: title.slice(0, 180), finalUrl: url, content: text, links: links });
                          } catch (e) {
                            return JSON.stringify({ ok: false, error: String(e) });
                          }
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(js) { rawJson ->
                        runCatching {
                            val unquoted = if (rawJson != null && rawJson.startsWith("\"")) {
                                JSONObject("{\"v\":$rawJson}").optString("v", "")
                            } else {
                                rawJson.orEmpty()
                            }
                            val parsed = JSONObject(unquoted)
                            resultRef.set(parsed)
                        }.onFailure { err ->
                            resultRef.set(JSONObject().put("ok", false).put("error", "解析页面渲染结果失败：${err.message}"))
                        }
                        runCatching {
                            webView.stopLoading()
                            webView.destroy()
                        }
                        webViewRef.set(null)
                        latch.countDown()
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        mainHandler.postDelayed({ extractNow() }, safeWait)
                    }
                }

                // 兜底硬超时，防止页面一直卡 loading
                mainHandler.postDelayed({ extractNow() }, safeWait + 9_000L)
                webView.loadUrl(targetUrl)
            } catch (e: Exception) {
                resultRef.set(JSONObject().put("ok", false).put("error", "启动离屏浏览器失败：${e.message}"))
                latch.countDown()
            }
        }

        val completed = latch.await(safeWait + 12_000L, TimeUnit.MILLISECONDS)
        if (!completed) {
            mainHandler.post {
                runCatching {
                    webViewRef.getAndSet(null)?.let {
                        it.stopLoading()
                        it.destroy()
                    }
                }
            }
            return JSONObject().put("ok", false).put("error", "离屏浏览器渲染超时：$targetUrl")
        }

        val out = resultRef.get() ?: JSONObject().put("ok", false).put("error", "未获取到页面渲染内容")
        if (!out.has("links")) out.put("links", JSONArray())
        return out
    }
}
