package io.deepseekharness.mobile

import android.content.ContentResolver
import android.content.Intent
import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import androidx.appcompat.app.AlertDialog
import io.deepseekharness.mobile.runtime.HarnessAccess
import io.deepseekharness.mobile.runtime.RuntimeStore
import io.deepseekharness.mobile.runtime.WorkspaceFileSharing
import java.util.concurrent.Executors
import java.lang.ref.WeakReference
import org.json.JSONObject
import org.json.JSONTokener

/** MainActivity 内的独立受限 WebView；不把 Harness 凭据暴露给 Capacitor 页面。 */
internal class MainHarnessSurface(private val activity: MainActivity) {
    private val owner = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val shareExecutor = Executors.newSingleThreadExecutor()
    private var container: View? = null
    private var web: WebView? = null
    private var chrome: HarnessWebChromeClient? = null
    private var origin: HarnessOrigin? = null
    private var boundAccess: HarnessAccess? = null
    private var gate = HarnessPageLoadGate()
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var voiceGate: HarnessPageLoadGate? = null
    private var voicePage: String? = null
    private var speaker: TextToSpeech? = null
    private var speechPending = false
    private var speechGeneration = 0L
    private var retries = 0
    private var entry: String? = null
    private val single = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> deliver(listOfNotNull(uri)) }
    private val multiple = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> deliver(uris) }
    private val voice = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val expected = voiceGate
        val page = voicePage
        voiceGate = null
        voicePage = null
        if (result.resultCode == Activity.RESULT_OK && expected != null && page != null && current(expected) && visible() && web?.url == page) {
            val text = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (text.isNotBlank() && text.length <= 2000) reviewVoice(text, expected, page) else voiceUnavailable()
        }
    }

    fun visible(): Boolean = container?.visibility == View.VISIBLE

    /** 只在主线程调用；失败由调用者保留原独立页面作为回退。 */
    fun open(access: HarnessAccess, sessionId: String?): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        stopSpeech()
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
        boundAccess = access
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
        stopSpeech()
        val view = web
        if (view != null && view.canGoBack()) view.goBack() else hide()
        return true
    }

    fun showIfOwned() {
        claimIfUnowned()
        if (web != null && authorized()) container?.visibility = View.VISIBLE
    }

    /** 放弃当前视图但不撤销授权，以便调用者回退到原对话 Activity。 */
    fun discard() { destroyView() }

    fun resume() {
        claimIfUnowned()
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

    fun pauseAudio() { stopSpeech() }

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
        root.findViewById<View>(R.id.harness_voice).setOnClickListener { startVoice() }
        root.findViewById<View>(R.id.harness_speak).setOnClickListener { readAloud() }
        root.findViewById<View>(R.id.harness_manage).setOnClickListener { hide() }
        activity.addContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        HarnessThemeBridge.attach(view)
        return view
    }

    private fun current(expected: HarnessPageLoadGate) = gate === expected && authorized()
    private fun authorized() = !activity.isFinishing && !activity.isDestroyed && AppAuthenticationState.ownsHarnessAccess(owner)
    private fun hide() { stopSpeech(); container?.visibility = View.GONE }

    private fun destroyView() {
        stopSpeech()
        if (active?.get() === this) active = null
        gate.cancel()
        voiceGate = null
        voicePage = null
        handler.removeCallbacksAndMessages(null)
        deliver(emptyList())
        chrome?.closePopup()
        chrome = null
        val old = web
        web = null
        container?.let { (it.parent as? ViewGroup)?.removeView(it) }
        container = null
        boundAccess = null
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

    private fun startVoice() {
        if (!visible() || !authorized()) return
        stopSpeech()
        voiceGate = gate
        voicePage = web?.url
        if (voicePage == null) { voiceGate = null; voiceUnavailable(); return }
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, activity.getString(R.string.harness_voice_prompt))
        runCatching { voice.launch(intent) }.onFailure { voiceGate = null; voicePage = null; voiceUnavailable() }
    }

    private fun reviewVoice(text: String, expected: HarnessPageLoadGate, expectedPage: String) {
        val edit = android.widget.EditText(activity).apply { setText(text); setSelection(length()) }
        AlertDialog.Builder(activity).setTitle(R.string.harness_voice_review).setMessage(R.string.harness_voice_notice)
            .setView(edit).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.harness_voice_insert) { _, _ ->
                val message = edit.text.toString()
                val target = web
                val currentOrigin = origin
                val page = target?.url?.let { runCatching { Uri.parse(it) }.getOrNull() }
                if (!current(expected) || !visible() || target == null || target.url != expectedPage || currentOrigin == null || page == null ||
                    !currentOrigin.allows(page) || message.isBlank() || message.length > 2000) {
                    voiceUnavailable()
                } else {
                    val template = runCatching { activity.assets.open("support/harness-voice-insert.js").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
                    val marker = "/*__VOICE_INPUT__*/null"
                    val pageMarker = "/*__VOICE_PAGE__*/null"
                    if (template == null || template.indexOf(marker) < 0 || template.indexOf(marker) != template.lastIndexOf(marker) ||
                        template.indexOf(pageMarker) < template.indexOf(marker) + marker.length || template.indexOf(pageMarker) != template.lastIndexOf(pageMarker)) {
                        voiceCopyFallback(message)
                    } else {
                        runCatching {
                            val textAt = template.indexOf(marker)
                            val pageAt = template.indexOf(pageMarker)
                            val script = template.substring(0, textAt) + JSONObject.quote(message) +
                                template.substring(textAt + marker.length, pageAt) + JSONObject.quote(expectedPage) +
                                template.substring(pageAt + pageMarker.length)
                            target.evaluateJavascript(script) { result ->
                                if (current(expected) && visible() && result != "\"inserted\"") voiceCopyFallback(message)
                            }
                        }.onFailure { if (current(expected) && visible()) voiceCopyFallback(message) }
                    }
                }
            }.show()
    }

    private fun voiceUnavailable() = Toast.makeText(activity, R.string.harness_voice_unavailable, Toast.LENGTH_LONG).show()

    private fun readAloud() {
        if (speaker != null || speechPending) { stopSpeech(); return }
        val target = web ?: return
        val page = target.url ?: return
        val currentOrigin = origin ?: return
        if (!visible() || !authorized() || !currentOrigin.allows(Uri.parse(page))) return
        val template = runCatching { activity.assets.open("support/harness-voice-read.js").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
        val marker = "/*__VOICE_PAGE__*/null"
        if (template == null || template.indexOf(marker) < 0 || template.indexOf(marker) != template.lastIndexOf(marker)) { speakUnavailable(); return }
        val expected = gate
        val generation = ++speechGeneration
        speechPending = true
        setSpeechButton(true)
        runCatching {
            target.evaluateJavascript(template.replace(marker, JSONObject.quote(page))) { result ->
                if (generation != speechGeneration) return@evaluateJavascript
                if (!current(expected) || !visible() || web?.url != page) { stopSpeech(); return@evaluateJavascript }
                val payload = runCatching { JSONObject(JSONTokener(result ?: "").nextValue() as String) }.getOrNull()
                val text = payload?.optString("text").orEmpty()
                if (payload?.optString("status") != "ok" || text.isBlank() || text.length > 2000) {
                    stopSpeech(); speakUnavailable(); return@evaluateJavascript
                }
                val prefix = activity.getString(R.string.harness_speak_notice) +
                    if (payload.optBoolean("truncated")) "\n" + activity.getString(R.string.harness_speak_truncated) else ""
                AlertDialog.Builder(activity).setTitle(R.string.harness_speak_preview)
                    .setMessage(prefix + "\n\n" + text)
                    .setNegativeButton(android.R.string.cancel) { _, _ -> stopSpeech() }
                    .setPositiveButton(R.string.harness_speak) { _, _ -> speak(text, expected, page, generation) }
                    .setOnCancelListener { stopSpeech() }.show()
            }
        }.onFailure { stopSpeech(); speakUnavailable() }
    }

    private fun speak(text: String, expected: HarnessPageLoadGate, page: String, generation: Long) {
        if (generation != speechGeneration || !current(expected) || !visible() || web?.url != page) { stopSpeech(); return }
        try {
            val engine = TextToSpeech(activity.applicationContext) { status ->
                handler.post {
                    val active = speaker
                    if (generation != speechGeneration) return@post
                    if (!current(expected) || !visible() || web?.url != page) { stopSpeech(); return@post }
                    if (status != TextToSpeech.SUCCESS || active == null || text.length > TextToSpeech.getMaxSpeechInputLength()) {
                        stopSpeech(); speakUnavailable(); return@post
                    }
                    speechPending = false
                    if (active.speak(text, TextToSpeech.QUEUE_FLUSH, null, generation.toString()) != TextToSpeech.SUCCESS) {
                        stopSpeech(); speakUnavailable()
                    }
                }
            }
            speaker = engine
            val listenerResult = engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { handler.post { if (generation == speechGeneration) stopSpeech() } }
                @Deprecated("Deprecated in Android")
                override fun onError(utteranceId: String?) { handler.post { if (generation == speechGeneration) { stopSpeech(); speakUnavailable() } } }
                override fun onError(utteranceId: String?, errorCode: Int) { handler.post { if (generation == speechGeneration) { stopSpeech(); speakUnavailable() } } }
            })
            if (listenerResult != TextToSpeech.SUCCESS) { stopSpeech(); speakUnavailable(); return }
            handler.postDelayed({ if (generation == speechGeneration && speechPending) { stopSpeech(); speakUnavailable() } }, 10_000)
        } catch (_: Exception) { stopSpeech(); speakUnavailable() }
    }

    private fun stopSpeech() {
        speechGeneration++
        speechPending = false
        speaker?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
        speaker = null
        setSpeechButton(false)
    }

    private fun setSpeechButton(active: Boolean) {
        (container?.findViewById<View>(R.id.harness_speak) as? android.widget.Button)
            ?.setText(if (active) R.string.harness_speak_stop else R.string.harness_speak)
    }

    private fun speakUnavailable() = Toast.makeText(activity, R.string.harness_speak_unavailable, Toast.LENGTH_LONG).show()

    private fun voiceCopyFallback(text: String) {
        AlertDialog.Builder(activity).setTitle(R.string.harness_voice_review)
            .setMessage(activity.getString(R.string.harness_voice_copy_notice) + "\n\n" + text)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.harness_voice_copy) { _, _ ->
                activity.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("语音输入", text))
            }.show()
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

    private fun claimIfUnowned() {
        val access = boundAccess ?: return
        if (web != null && !activity.isFinishing && !activity.isDestroyed) {
            AppAuthenticationState.claimUnownedHarnessAccess(owner, access)
        }
    }

    companion object {
        @Volatile private var active: WeakReference<MainHarnessSurface>? = null
        private val authCacheExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dsh-embedded-auth-db").apply { isDaemon = true }
        }
        fun hasOwnedSurface(): Boolean = active?.get()?.let {
            val access = it.boundAccess
            it.web != null && (it.authorized() || (access != null && AppAuthenticationState.isUnownedHarnessAccess(access)))
        } == true
    }
}
