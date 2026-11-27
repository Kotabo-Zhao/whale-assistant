package io.deepseekharness.mobile

import android.annotation.SuppressLint
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import java.io.ByteArrayInputStream
import java.net.URI

/** Harness 对话容器共享的 WebView 设置；导航和认证仍由受限客户端单独执行。 */
internal object HarnessWebViewPolicy {
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(settings: WebSettings, fontScale: Float) = with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        // 系统文件选择器只返回用户选取的 content URI；页面自行发起的请求由受限客户端拦截。
        allowContentAccess = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        // OAuth 使用单独拦截的临时窗口，不能直接在主 WebView 内打开外部页面。
        setSupportMultipleWindows(true)
        mediaPlaybackRequiresUserGesture = true
        builtInZoomControls = false
        displayZoomControls = false
        safeBrowsingEnabled = true
        textZoom = AppTextScale.percentOf(fontScale)
    }
}

internal fun blockedResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)),
)

internal fun isDeepSeekAuthorizationNavigation(raw: String, method: String, isMainFrame: Boolean): Boolean =
    isMainFrame && method == "GET" && isDeepSeekAuthorizationUrl(raw)

internal fun isDeepSeekAuthorizationUrl(raw: String): Boolean {
    if (raw.length > 8192 || raw.any { it.isISOControl() || it == '\\' }) return false
    return try {
        val uri = URI(raw)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("platform.deepseek.com", ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawUserInfo == null &&
            uri.rawPath == "/dsh/authorize" &&
            uri.rawFragment == null &&
            !uri.rawQuery.isNullOrBlank()
    } catch (_: Exception) {
        false
    }
}
