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
    private data class VoiceHandoff(val text: String, val page: String, val gate: HarnessPageLoadGate, val at: Long, val shared: Boolean)
    private var pendingVoice: VoiceHandoff? = null
    private var speaker: TextToSpeech? = null
    private var speechPending = false
    private var speechGeneration = 0L
    private var retries = 0
    private var entry: String? = null
    private var returnAfterManage = false
    private val single = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> deliver(listOfNotNull(uri)) }
    private val multiple = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> deliver(uris) }
    private val voice = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val expected = voiceGate
        val page = voicePage
        voiceGate = null
        voicePage = null
        val text = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        val samePage = expected != null && page != null && current(expected) && visible() && web?.url == page
        when (HarnessVoiceResultPolicy.decide(result.resultCode == Activity.RESULT_OK, text, samePage)) {
            HarnessVoiceResultPolicy.Decision.CANCELLED -> Unit
            HarnessVoiceResultPolicy.Decision.INVALID -> voiceUnavailable()
            HarnessVoiceResultPolicy.Decision.REVIEW -> reviewVoice(text, checkNotNull(expected), checkNotNull(page))
            HarnessVoiceResultPolicy.Decision.COPY -> if (!activity.isFinishing && !activity.isDestroyed) voiceCopyFallback(text)
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
        if (web != null && authorized()) { container?.visibility = View.VISIBLE; returnAfterManage = false }
    }

    /** Management was opened from chat: return only to the still-owned view, without reloading. */
    fun returnFromManage(): Boolean {
        val restore = HarnessManageReturnPolicy.shouldRestore(returnAfterManage, web != null, authorized(), visible())
        returnAfterManage = false
        if (!restore) return false
        container?.visibility = View.VISIBLE
        return true
    }

    /** 放弃当前视图但不撤销授权，以便调用者回退到原对话 Activity。 */
    fun discard() { destroyView() }

    fun resume() {
        claimIfUnowned()
        if (web != null && !AppAuthenticationState.ownsHarnessAccess(owner)) {
            val pending = pendingVoice
            destroyView()
            if (pending != null) handoffFallback(pending)
        } else {
            web?.let(HarnessThemeBridge::apply)
            deliverPendingVoice()
        }
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
        root.findViewById<View>(R.id.harness_manage).setOnClickListener {
            hide(returnToConversation = true)
            Toast.makeText(activity, R.string.harness_manage_return_hint, Toast.LENGTH_SHORT).show()
        }
        activity.addContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        HarnessThemeBridge.attach(view)
        return view
    }

    private fun current(expected: HarnessPageLoadGate) = gate === expected && authorized()
    private fun authorized() = !activity.isFinishing && !activity.isDestroyed && AppAuthenticationState.ownsHarnessAccess(owner)
    private fun hide(returnToConversation: Boolean = false) {
        stopSpeech(); returnAfterManage = returnToConversation; container?.visibility = View.GONE
    }

    private fun destroyView() {
        stopSpeech()
        pendingVoice = null
        returnAfterManage = false
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
        val edit = android.widget.EditText(activity).apply { setText(text); setSelection(length()); minLines = 2; maxLines = 8 }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.harness_voice_review).setMessage(R.string.harness_voice_notice)
            .setView(edit).setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.harness_voice_copy, null).setPositiveButton(R.string.harness_voice_insert, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = edit.text.toString()
                if (!HarnessVoiceResultPolicy.canInsert(value)) { edit.error = activity.getString(R.string.harness_voice_draft_limit); return@setOnClickListener }
                dialog.dismiss()
                insertVoice(value, expected, expectedPage)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val value = edit.text.toString()
                if (!HarnessVoiceResultPolicy.canCopy(value)) { edit.error = activity.getString(R.string.harness_voice_copy_limit); return@setOnClickListener }
                val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
                if (clipboard == null) { edit.error = activity.getString(R.string.harness_voice_copy_unavailable); return@setOnClickListener }
                val copied = runCatching { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("语音输入", value)) }.isSuccess
                if (copied) dialog.dismiss() else edit.error = activity.getString(R.string.harness_voice_copy_unavailable)
            }
        }
        dialog.show()
    }

    private fun insertVoice(message: String, expected: HarnessPageLoadGate, expectedPage: String, fallback: (String) -> Unit = ::voiceCopyFallback) {
        val target = web
        val currentOrigin = origin
        val page = target?.url?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (!current(expected) || !visible() || target == null || target.url != expectedPage || currentOrigin == null || page == null ||
            !currentOrigin.allows(page) || !HarnessVoiceResultPolicy.canInsert(message)) {
            if (HarnessVoiceResultPolicy.canCopy(message)) fallback(message) else voiceUnavailable()
            return
        }
        val template = runCatching { activity.assets.open("support/harness-voice-insert.js").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
        val marker = "/*__VOICE_INPUT__*/null"
        val pageMarker = "/*__VOICE_PAGE__*/null"
        if (template == null || template.indexOf(marker) < 0 || template.indexOf(marker) != template.lastIndexOf(marker) ||
            template.indexOf(pageMarker) < template.indexOf(marker) + marker.length || template.indexOf(pageMarker) != template.lastIndexOf(pageMarker)) {
            fallback(message)
            return
        }
        runCatching {
            val textAt = template.indexOf(marker)
            val pageAt = template.indexOf(pageMarker)
            val script = template.substring(0, textAt) + JSONObject.quote(message) +
                template.substring(textAt + marker.length, pageAt) + JSONObject.quote(expectedPage) +
                template.substring(pageAt + pageMarker.length)
            target.evaluateJavascript(script) { result ->
                if (result != "\"inserted\"" && !activity.isFinishing && !activity.isDestroyed) fallback(message)
            }
        }.onFailure { if (!activity.isFinishing && !activity.isDestroyed) fallback(message) }
    }

    /** Caller has explicitly reviewed shared text; never submits the draft. */
    internal fun insertSharedText(message: String): Boolean {
        val page = web?.url ?: return false
        val currentOrigin = origin ?: return false
        val parsed = runCatching { Uri.parse(page) }.getOrNull() ?: return false
        if (!SharedTextImport.canInsertDraft(message) || !visible() || !authorized() || !currentOrigin.allows(parsed)) return false
        insertVoice(message, gate, page, ::sharedTextCopyFallback)
        return true
    }

    internal fun sharedTextCopyFallback(text: String) {
        AlertDialog.Builder(activity).setTitle("无法放入对话草稿")
            .setMessage("对话未就绪、已切换或输入框不可用。你可以自行复制后粘贴；内容不会自动发送。\n\n$text")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("复制") { _, _ ->
                activity.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("分享文字", text))
            }.show()
    }

    private fun stageVoice(text: String, shared: Boolean = false): Boolean {
        val page = web?.url ?: return false
        val targetOrigin = origin ?: return false
        val parsed = runCatching { Uri.parse(page) }.getOrNull() ?: return false
        if (!visible() || !authorized() || !targetOrigin.allows(parsed) || text.isBlank() || text.length > 2000 || pendingVoice != null) return false
        pendingVoice = VoiceHandoff(text, page, gate, android.os.SystemClock.elapsedRealtime(), shared)
        return true
    }

    private fun deliverPendingVoice() {
        val pending = pendingVoice ?: return
        pendingVoice = null
        val age = android.os.SystemClock.elapsedRealtime() - pending.at
        if (!HarnessVoiceHandoffPolicy.canDeliver(visible(), authorized(), gate === pending.gate, web?.url == pending.page, age)) {
            handoffFallback(pending)
            return
        }
        insertVoice(pending.text, pending.gate, pending.page, if (pending.shared) ::sharedTextCopyFallback else ::voiceCopyFallback)
    }

    private fun handoffFallback(pending: VoiceHandoff) {
        if (pending.shared) sharedTextCopyFallback(pending.text) else voiceCopyFallback(pending.text)
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
        val readRequest = HarnessSpeechReadRequest(generation)
        speechPending = true
        setSpeechButton(true)
        handler.postDelayed({ if (readRequest.claim(speechGeneration)) { stopSpeech(); speakUnavailable() } }, 10_000)
        runCatching {
            target.evaluateJavascript(template.replace(marker, JSONObject.quote(page))) { result ->
                if (!readRequest.claim(speechGeneration)) return@evaluateJavascript
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
                    .setNegativeButton(android.R.string.cancel) { _, _ -> if (generation == speechGeneration) stopSpeech() }
                    .setPositiveButton(R.string.harness_speak) { _, _ -> speak(text, expected, page, generation) }
                    .setOnCancelListener { if (generation == speechGeneration) stopSpeech() }.show()
            }
        }.onFailure { stopSpeech(); speakUnavailable() }
    }

    private fun speak(text: String, expected: HarnessPageLoadGate, page: String, generation: Long) {
        if (generation != speechGeneration) return
        if (!current(expected) || !visible() || web?.url != page) { stopSpeech(); return }
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
        internal fun stageCompanionVoice(text: String): Boolean = active?.get()?.stageVoice(text) == true
        internal fun stageCompanionKnowledge(text: String): Boolean = active?.get()?.stageVoice(text, shared = true) == true
        internal fun cancelCompanionVoice() { active?.get()?.pendingVoice = null }
        fun hasOwnedSurface(): Boolean = active?.get()?.let {
            val access = it.boundAccess
            it.web != null && (it.authorized() || (access != null && AppAuthenticationState.isUnownedHarnessAccess(access)))
        } == true
    }
}
