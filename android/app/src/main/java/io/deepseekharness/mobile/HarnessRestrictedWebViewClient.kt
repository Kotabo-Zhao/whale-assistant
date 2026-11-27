package io.deepseekharness.mobile

import android.net.Uri
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.deepseekharness.mobile.runtime.HarnessAccess

internal class HarnessOrigin(val scheme: String, val host: String, val port: Int, val initialUrl: String) {
    fun allows(uri: Uri): Boolean =
        uri.scheme == scheme && uri.host == host && uri.port == port && uri.userInfo == null

    companion object {
        fun parse(raw: String?): HarnessOrigin? {
            val uri = HarnessPageUrl.parseEntryUrl(raw) ?: return null
            return HarnessOrigin(uri.scheme, uri.host, uri.port, uri.toASCIIString())
        }
    }
}

internal class HarnessRestrictedWebViewClient(
    private val origin: HarnessOrigin,
    private val username: String,
    private val password: String,
    private val onDeepSeekAuthorization: (Uri) -> Boolean = { false },
    private val onMainFrameFailure: () -> Unit,
    private val onRendererGone: (WebView?) -> Unit,
    private val onMainFrameLoaded: (WebView) -> Unit = {},
    private val onWorkspaceShare: (String) -> Unit = {},
) : WebViewClient() {
    override fun onPageFinished(view: WebView?, url: String?) {
        if (view != null) onMainFrameLoaded(view)
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView?,
        handler: HttpAuthHandler?,
        host: String?,
        realm: String?,
    ) {
        if (host == origin.host && realm == HarnessAccess.REALM) {
            handler?.proceed(username, password)
        } else {
            handler?.cancel()
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return true
        // 预览面板的分享按钮：只认带用户手势的主框架 GET，且 scheme 必须是 dsh-share。
        // 任何情况下都不让页面真的去加载这个地址（WebView 没有对应协议处理器）。
        if (WorkspaceShareAddress.SCHEME.equals(uri.scheme, ignoreCase = true)) {
            if (request.hasGesture() && request.method == "GET" && request.isForMainFrame) {
                onWorkspaceShare(uri.toString())
            }
            return true
        }
        if (origin.allows(uri)) return false
        // A page must not be able to launch the external account chooser from a timer,
        // redirect, or injected script.  Popup transport gets its own one-shot allowance
        // from onCreateWindow after WebView has confirmed a real user gesture.
        // Kotlin 不为 hasXxx() 生成合成属性（只有 get/is 前缀才生成），这里必须显式调用。
        if (request.hasGesture() &&
            isDeepSeekAuthorizationNavigation(uri.toString(), request.method, request.isForMainFrame)
        ) {
            onDeepSeekAuthorization(uri)
        }
        return true
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val uri = request?.url ?: return blockedResponse()
        // 市场目录和缩略图由插件直接请求 HTTPS 资源站；交给 WebView 执行正常的 TLS/CORS 校验。
        // 不添加本机 Authorization 或 Cookie，也不允许资源站替换会话主页面。
        return if (origin.allows(uri) || HarnessPluginResources.allows(
                uri.toString(), request.method, request.isForMainFrame,
            )) null else blockedResponse()
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
        handler?.cancel()
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        if (request?.isForMainFrame == true) onMainFrameFailure()
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        onRendererGone(view)
        return true
    }
}
