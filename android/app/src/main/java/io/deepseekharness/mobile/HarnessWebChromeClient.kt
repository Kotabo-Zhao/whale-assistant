package io.deepseekharness.mobile

import android.content.Context
import android.net.Uri
import android.os.Message
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/** 对话页面共用的上传与 OAuth 弹窗；弹窗不加载网络资源，也不获得本机会话凭据。 */
internal class HarnessWebChromeClient(
    private val context: Context,
    private val currentView: () -> WebView?,
    private val canOpen: () -> Boolean,
    private val onAuthorization: (Uri) -> Boolean,
    private val onChooseFile: (ValueCallback<Array<Uri>>, FileChooserParams?) -> Boolean,
) : WebChromeClient() {
    private var popup: WebView? = null

    override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
        if (view == null || view !== currentView() || !isUserGesture || resultMsg == null || !canOpen()) return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        closePopup()
        val next = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = false
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                safeBrowsingEnabled = true
            }
            webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView?) { if (window === popup) closePopup() }
            }
            webViewClient = PopupClient()
        }
        popup = next
        transport.webView = next
        resultMsg.sendToTarget()
        return true
    }

    override fun onCloseWindow(window: WebView?) { if (window === popup) closePopup() }

    override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
        if (filePathCallback == null) return false
        return onChooseFile(filePathCallback, fileChooserParams)
    }

    fun closePopup() {
        val old = popup ?: return
        popup = null
        old.stopLoading()
        old.webChromeClient = null
        old.webViewClient = WebViewClient()
        old.destroy()
    }

    private inner class PopupClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return true
            if (uri.toString() == "about:blank") return false
            if (isDeepSeekAuthorizationNavigation(uri.toString(), request.method, request.isForMainFrame)) onAuthorization(uri)
            return true
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
            if (request?.url?.toString() == "about:blank") null else blockedResponse()

        override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
            handler?.cancel()
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            if (view === popup) closePopup()
            return true
        }
    }
}
