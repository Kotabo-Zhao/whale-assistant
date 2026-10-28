package io.deepseekharness.mobile

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import io.deepseekharness.mobile.runtime.HarnessAccess
import io.deepseekharness.mobile.runtime.RuntimeStore
import io.deepseekharness.mobile.runtime.WorkspaceFileSharing
import java.util.concurrent.Executors
import java.lang.ref.WeakReference

/** MainActivity 内的独立受限 WebView；不把 Harness 凭据暴露给 Capacitor 页面。 */
internal class MainHarnessSurface(private val activity: MainActivity) {
    private val owner = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val shareExecutor = Executors.newSingleThreadExecutor()
    private var container: View? = null
    private var web: WebView? = null
    private var chrome: HarnessWebChromeClient? = null
    private var origin: HarnessOrigin? = null
    private var gate = HarnessPageLoadGate()
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var retries = 0
    private var entry: String? = null
    private val single = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> deliver(listOfNotNull(uri)) }
    private val multiple = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> deliver(uris) }

    fun visible(): Boolean = container?.visibility == View.VISIBLE

    /** 只在主线程调用；失败由调用者保留原独立页面作为回退。 */
    fun open(access: HarnessAccess, sessionId: String?): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        val targetOrigin = HarnessOrigin.parse(access.url) ?: return false
        val cookie = runCatching { HarnessSessionCookie.authenticated(access.password) }.getOrNull() ?: return false
        val target = runCatching {
            HarnessPageUrl.withVersions(targetOrigin.initialUrl, BuildConfig.VERSION_NAME,
                RuntimeStore(activity).installedManifest()?.version, sessionId)
        }.getOrNull() ?: return false
        if (!AppAuthenticationState.claimHarnessAccess(owner, access)) return false
        active = WeakReference(this)
        val view = ensureView()
        gate.cancel()
        val nextGate = HarnessPageLoadGate()
        gate = nextGate
        handler.removeCallbacksAndMessages(null)
        deliver(emptyList())
        chrome?.closePopup()
        view.stopLoading()
        view.clearHistory()
        origin = targetOrigin
        entry = target
        retries = 0
        var clearHistoryOnLoad = true
        view.settings.cacheMode = if (HarnessPageCache.modeFor(target) == HarnessCacheMode.NORMAL) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
        view.webViewClient = HarnessRestrictedWebViewClient(
            targetOrigin, access.username, access.password,
            onDeepSeekAuthorization = ::authorize,
            onMainFrameFailure = { if (current(nextGate)) retryOrReport() },
            onRendererGone = { gone ->
                if (gone === web) {
                    destroyView()
                    Toast.makeText(activity, R.string.harness_page_failed, Toast.LENGTH_SHORT).show()
                }
            },
            onMainFrameLoaded = { loaded ->
                if (current(nextGate)) {
                    if (clearHistoryOnLoad && loaded.url == target) {
                        clearHistoryOnLoad = false
                        loaded.clearHistory()
                    }
                    HarnessThemeBridge.apply(loaded)
                    HarnessPolyfill.apply(loaded)
                    HarnessWorkspaceShare.apply(loaded)
                }
            },
            onWorkspaceShare = ::share,
        )
        clearAuthCache()
        container?.visibility = View.VISIBLE
        val cookies = CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(view, false) }
        cookies.setCookie(HarnessSessionCookie.origin(targetOrigin.port), cookie) { accepted ->
            handler.post {
                if (!current(nextGate)) return@post
                when (nextGate.onCookieStored(accepted)) {
                    CookieLoadDecision.LOAD -> { cookies.flush(); view.loadUrl(target) }
                    CookieLoadDecision.REJECT -> { hide(); Toast.makeText(activity, R.string.harness_session_failed, Toast.LENGTH_SHORT).show() }
                    CookieLoadDecision.IGNORE -> Unit
                }
            }
        }
        return true
    }

    fun onBack(): Boolean {
        if (!visible()) return false
        val view = web
        if (view != null && view.canGoBack()) view.goBack() else hide()
        return true
    }

    fun showIfOwned() {
        if (web != null && authorized()) container?.visibility = View.VISIBLE
    }

    /** 放弃当前视图但不撤销授权，以便调用者回退到原对话 Activity。 */
    fun discard() { destroyView() }

    fun resume() {
        if (web != null && !AppAuthenticationState.ownsHarnessAccess(owner)) destroyView()
        else web?.let(HarnessThemeBridge::apply)
    }

    fun destroy() {
        val oldOrigin = origin
        destroyView()
        if (!activity.isChangingConfigurations && AppAuthenticationState.revokeHarness(owner) && oldOrigin != null) {
            CookieManager.getInstance().setCookie(HarnessSessionCookie.origin(oldOrigin.port), HarnessSessionCookie.expired()) { CookieManager.getInstance().flush() }
        }
        clearAuthCache()
        shareExecutor.shutdown()
    }

    private fun ensureView(): WebView {
        web?.let { return it }
        val root = LayoutInflater.from(activity).inflate(R.layout.activity_harness, null, false)
        val view = root.findViewById<WebView>(R.id.harness_web_view)
        HarnessWebViewPolicy.configure(view.settings, activity.resources.configuration.fontScale)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) WebView.startSafeBrowsing(activity.applicationContext, null)
        val nextChrome = HarnessWebChromeClient(activity, { web }, ::authorized, ::authorize, ::chooseFile)
        chrome = nextChrome
        view.webChromeClient = nextChrome
        web = view
        container = root
        root.findViewById<View>(R.id.harness_companion).setOnClickListener {
            activity.startActivity(Intent(activity, io.deepseekharness.mobile.companion.CompanionActivity::class.java))
        }
        root.findViewById<View>(R.id.harness_manage).setOnClickListener { hide() }
        activity.addContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        HarnessThemeBridge.attach(view)
        return view
    }

    private fun current(expected: HarnessPageLoadGate) = gate === expected && authorized()
    private fun authorized() = !activity.isFinishing && !activity.isDestroyed && AppAuthenticationState.ownsHarnessAccess(owner)
    private fun hide() { container?.visibility = View.GONE }

    private fun destroyView() {
        if (active?.get() === this) active = null
        gate.cancel()
        handler.removeCallbacksAndMessages(null)
        deliver(emptyList())
        chrome?.closePopup()
        chrome = null
        val old = web
        web = null
        container?.let { (it.parent as? ViewGroup)?.removeView(it) }
        container = null
        if (old != null) {
            HarnessThemeBridge.detach(old)
            old.stopLoading()
            old.webChromeClient = null
            old.webViewClient = WebViewClient()
            old.removeAllViews()
            old.destroy()
        }
    }

    private fun chooseFile(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams?): Boolean {
        if (!authorized()) return false
        deliver(emptyList())
        chooser = callback
        val types = params?.acceptTypes?.filter { it.contains('/') }?.toTypedArray()?.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
        return try {
            if (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) multiple.launch(types) else single.launch(types)
            true
        } catch (_: Throwable) { deliver(emptyList()); false }
    }

    private fun deliver(uris: List<Uri>) {
        val callback = chooser ?: return
        chooser = null
        val accepted = uris.filter { it.scheme == ContentResolver.SCHEME_CONTENT }
        runCatching { callback.onReceiveValue(accepted.takeIf { it.isNotEmpty() }?.toTypedArray()) }
    }

    private fun authorize(uri: Uri): Boolean {
        if (!authorized() || !isDeepSeekAuthorizationUrl(uri.toString())) return false
        return try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            chrome?.closePopup()
            true
        } catch (_: Exception) { false }
    }

    private fun retryOrReport() {
        if (retries >= 2) {
            Toast.makeText(activity, R.string.harness_page_failed, Toast.LENGTH_LONG).show()
            return
        }
        val expected = gate
        val target = entry ?: return
        retries++
        handler.postDelayed({ if (current(expected)) web?.loadUrl(target) }, 300L * retries)
    }

    private fun share(raw: String) {
        if (!authorized()) return
        val candidates = WorkspaceShareAddress.relativePaths(raw) ?: return
        shareExecutor.execute {
            val intent = runCatching { WorkspaceFileSharing.chooser(activity, RuntimeStore(activity), activity.cacheDir, candidates, open = false) }.getOrNull()
            activity.runOnUiThread {
                if (!authorized()) return@runOnUiThread
                if (intent == null || runCatching { activity.startActivity(intent) }.isFailure) {
                    Toast.makeText(activity, R.string.harness_file_action_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun clearAuthCache() {
        val app = activity.applicationContext
        authCacheExecutor.execute { runCatching { WebViewDatabase.getInstance(app).clearHttpAuthUsernamePassword() } }
    }

    companion object {
        @Volatile private var active: WeakReference<MainHarnessSurface>? = null
        private val authCacheExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dsh-embedded-auth-db").apply { isDaemon = true }
        }
        fun hasOwnedSurface(): Boolean = active?.get()?.let { it.web != null && it.authorized() } == true
    }
}
