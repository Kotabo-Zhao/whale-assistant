package io.deepseekharness.mobile.companion

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 通用站点授权登录页：用户在内嵌 WebView 中自行登录目标站点后，
 * 点击「授权保存登录态」即可将当前站点 Cookie 经 Keystore 加密存入 [WhaleSiteVault]。
 */
class WhaleSiteLoginActivity : AppCompatActivity() {
    private var webView: WebView? = null
    private var titleView: TextView? = null
    private var currentUrl: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startUrl = intent?.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        val label = intent?.getStringExtra(EXTRA_LABEL)?.trim().orEmpty()
        if (!startUrl.startsWith("https://", ignoreCase = true)) {
            Toast.makeText(this, "仅支持 https:// 站点授权登录", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentUrl = startUrl

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 28, 20, 20)
            setBackgroundColor(Color.parseColor("#142136"))
        }

        val closeBtn = Button(this).apply {
            text = "关闭"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }

        titleView = TextView(this).apply {
            text = label.ifBlank { "站点登录授权" }
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 12
                marginEnd = 12
            }
        }

        val saveBtn = Button(this).apply {
            text = "授权保存登录态"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2F8AF5"))
            setPadding(24, 10, 24, 10)
            setOnClickListener { saveCurrentSiteCookies(label) }
        }

        bar.addView(closeBtn)
        bar.addView(titleView)
        bar.addView(saveBtn)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val u = request?.url?.toString().orEmpty()
                    return !(u.startsWith("https://", ignoreCase = true) || u.startsWith("http://", ignoreCase = true))
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrBlank()) currentUrl = url
                    val pageTitle = view?.title?.trim().orEmpty()
                    if (pageTitle.isNotEmpty()) titleView?.text = pageTitle.take(24)
                }
            }
            loadUrl(startUrl)
        }
        webView = wv
        root.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun saveCurrentSiteCookies(label: String) {
        val target = currentUrl.ifBlank { intent?.getStringExtra(EXTRA_URL).orEmpty() }
        val cm = CookieManager.getInstance()
        cm.flush()
        val cookie = cm.getCookie(target)?.trim().orEmpty()
        if (cookie.isEmpty()) {
            Toast.makeText(this, "尚未检测到登录态 Cookie，请先完成登录后再点保存", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            val saved = WhaleSiteVault.saveCookie(this, target, cookie, label)
            Toast.makeText(this, "已加密保存 ${saved.optString("domain")} 的登录态", Toast.LENGTH_SHORT).show()
            finish()
        }.onFailure { err ->
            Toast.makeText(this, "保存失败：${err.message ?: err.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        webView?.let { wv ->
            wv.stopLoading()
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_LABEL = "label"

        fun launch(context: Context, url: String, label: String = "") {
            val intent = Intent(context.applicationContext, WhaleSiteLoginActivity::class.java).apply {
                putExtra(EXTRA_URL, url.trim())
                putExtra(EXTRA_LABEL, label.trim())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.applicationContext.startActivity(intent)
        }
    }
}
