package io.deepseekharness.mobile

import android.annotation.SuppressLint
import android.webkit.WebSettings

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
