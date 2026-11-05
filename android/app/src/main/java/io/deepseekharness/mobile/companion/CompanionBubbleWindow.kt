package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.deepseekharness.mobile.R
import androidx.core.widget.doAfterTextChanged

/** Native floating chat; recent completed messages are restored across service restarts. */
internal class CompanionBubbleWindow(private val context: Context, private val wm: WindowManager, private val onState: (String) -> Unit = {}, private val openSession: (String) -> Unit = {}, private val onWorkerCounts: (Int, Int, Int) -> Unit = { _, _, _ -> }, private val autoOpenOnDelivery: Boolean = true, private val onBackgroundReport: ((String) -> Unit)? = null, private val historyStore: CompanionBubbleHistory = CompanionBubbleHistory(context),
    private val replyRunner: (CompanionBubbleChat, List<CompanionBubbleChat.Message>, String, Boolean, Boolean, (String) -> Unit) -> CompanionBubbleChat.Reply = { chat, history, scope, memory, knowledge, partial -> chat.fusedReply(history, scope, memory, knowledge, partial) },
    private val classifyWaiting: (List<CompanionBubbleChat.Message>) -> PetIntentRouter.Decision? = { messages ->
        val runtime = io.deepseekharness.mobile.runtime.RuntimeStore(context)
        runtime.readModelCredential("deepseek")?.takeIf { it.isNotBlank() }?.let { key ->
            val model = runtime.settings().defaultModels[io.deepseekharness.mobile.runtime.ModelProvider.DEEPSEEK] ?: "deepseek-flash"
            PetIntentRouter.classify(key, model, messages, whileWaiting = true)
        }
    },
    private val receiveTask: ((String, String, String, String, String) -> org.json.JSONObject)? = null,
    private val petBounds: () -> IntArray) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val anchorStore = CompanionSessionAnchorStore(context)
    private val restored = historyStore.load()
    private val history = restored?.messages?.toMutableList() ?: mutableListOf()
    private val chat = CompanionBubbleChat(context, restored, historyStore, ::dispatchTask)
    private var root: LinearLayout? = null
    private var previewDialog: android.app.AlertDialog? = null
    private var recoveryDialog: android.app.AlertDialog? = null
    private var questionDialog: android.app.AlertDialog? = null
    private var pendingQuestion: org.json.JSONObject? = null
    private var mainQuestion: String? = null
    private var liveText = ""
    private var renderLive: ((String) -> Unit)? = null
    @Volatile private var showLivePartials = false
    private var flushPartial: (() -> Unit)? = null
    private var request = 0L
    private var busy = false
    private var activeReply: PetReplyOperation? = null
    private class Followup(val text: String, val id: String, var deciding: Boolean = false, var receipt: String? = null) {
        val classification = PetReplyOperation(30_000)
        var handingOff = false
        var parked = false
    }
    private val intakeLock = Any()
    private val journal = historyStore.followups
    private val recovery = runCatching { journal.entries() }
    private val restoredRequestId = restored?.pending?.let { org.json.JSONObject(it).optString("requestId") }
    private val recovered = recovery.getOrDefault(emptyList()).filterNot { it.id == restoredRequestId && it.stage in setOf("queued", "reply") }.toMutableList()
    private var failedFollowup: Followup? = recovery.getOrNull()?.firstOrNull {
        it.id == restoredRequestId && it.stage in setOf("queued", "reply")
    }?.let { Followup(it.text, it.id) }
    private val recoveringDispatch = mutableSetOf<String>()
    private val followups = ArrayDeque<Followup>()
    private val intake = java.util.concurrent.ThreadPoolExecutor(0, 1, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue())
    private var sendNext: (() -> Unit)? = null
    private var renderFollowups: (() -> Unit)? = null
    @Volatile private var closed = false
    private var draft = chat.pendingUser.orEmpty()
    private var lastError: String? = if (chat.pendingDirect) "上次回复中断，点此重新生成。" else null
    private var inFlightUser: String? = null
    private var failedUser: String? = null
    private var captureDialog: android.app.AlertDialog? = null
    private var positionUpdater: (() -> Unit)? = null
    private val workerPanel = CompanionWorkerPanel(context, { id -> hide(); openSession(id) }, onWorkerCounts, ::hide)
    private var renderWorkerReport: ((org.json.JSONObject) -> Unit)? = null
    private val workerReportListener: (org.json.JSONObject) -> Unit = { report ->
        handler.post {
            if (!closed) {
                when (report.optString("state")) {
                    "completed" -> onState("taskDone")
                    "waiting" -> onState("taskWaiting")
                    "failed", "cancelled", "blocked" -> onState("taskFailed")
                }
                if (visible) renderWorkerReport?.let { render ->
                    render(report); WorkerReportInbox(context).markSeen(listOf(report.optString("id")))
                } else if (onBackgroundReport != null) {
                    onBackgroundReport.invoke(report.optString("state"))
                } else if (autoOpenOnDelivery) show()
            }
        }
    }
    init { WorkerReportInbox.subscribe(workerReportListener) }
    private val richViews = mutableListOf<android.webkit.WebView>()
    /** 后台投递回合（Worker 回执等）的本地收件箱；不参与对话历史与导出。 */
    private val deliveryInbox = CompanionDeliveryInbox(context)
    private var deliveryWatcher: CompanionDeliveryWatcher? = null
    val visible: Boolean get() = root != null

    private val toolWindows = PetToolWindows(context, wm)
    fun openTool(tool: io.deepseekharness.mobile.features.WhaleFeatureStore.Tool) = toolWindows.open(tool)
    fun toggle() { if (visible) hide() else show() }
    private fun dispatchTask(task: String, mode: String, id: String, title: String, needs: String): org.json.JSONObject =
        receiveTask?.invoke(task, mode, id, title, needs) ?: workerPanel.submit(task, mode, id, title, needs)
    fun composeTask() { if (!closed) workerPanel.compose() }
    fun openTaskInbox() { if (!closed) workerPanel.open() }
    fun refreshWorkerStatus() { workerPanel.refreshStatus() }

    /** Classify independently from the foreground transcript and its pending request. */
    private fun classifyFollowup(item: Followup, messages: List<CompanionBubbleChat.Message>) {
        val sourceSession = chat.handoffSessionId()
        item.deciding = true
        intake.execute {
            var receipt: String? = null
            runCatching {
                if (closed) return@runCatching
                val decision = item.classification.run { classifyWaiting(messages) } ?: return@runCatching

                val mode = when (decision.intent) {
                    PetIntentRouter.Intent.BACKGROUND_READ -> "read"
                    PetIntentRouter.Intent.BACKGROUND_CREATE -> "create"
                    else -> return@runCatching
                }
                if (closed || !decision.independent || sourceSession != chat.handoffSessionId()) return@runCatching
                sourceSession?.let { BubbleSessionRegistry(context).record(it) }
                val title = decision.title(item.text)
                val id = item.id
                synchronized(intakeLock) {
                    if (item.parked) return@runCatching
                    item.handingOff = true
                }
                val plan = org.json.JSONObject().put("mode",mode).put("needs",decision.needs).put("title",title)
                sourceSession?.let { plan.put("sourceSession",it) }
                journal.advance(id,"dispatch",plan = plan)
                try { receipt = deliverPlan(id, item.text, plan) }
                catch (error: Exception) {
                    handler.post {
                        if (!closed) {
                            followups.remove(item)
                            recovered.add(PetFollowupJournal.Entry(id,item.text,"dispatch","","",plan))
                            if (visible) { hide(); show() }
                        }
                    }
                    throw error
                }
            }.onFailure { android.util.Log.d("whale-intake", "intake unavailable: ${it.javaClass.simpleName}") }
            handler.post {
                if (!closed) {
                    item.receipt = receipt
                    item.deciding = false
                    renderFollowups?.invoke()
                    sendNext?.invoke()
                }
            }
        }
    }

    private fun deliverPlan(id: String, text: String, plan: org.json.JSONObject): String {
        val mode = plan.getString("mode"); val needs = plan.getString("needs"); val title = plan.getString("title")
        val source = plan.optString("sourceSession").takeIf(String::isNotBlank)
        val prefs = CompanionStore(context)
        val denial = chat.deniedBackground(mode, needs, prefs.bubbleMemoryAccess && CompanionMemoryStore(context).enabled, prefs.knowledgeModelAccess)
        val dashboard = WorkerDashboardStore(context)
        val receipt = if (denial != null) dashboard.hold(id,text,mode,needs,title,denial,source)
        else if (dashboard.isHeld(id)) "「$title」正在等待授权确认，可在后台任务中继续。"
        else {
            val accepted = if (source == null) dispatchTask(text,mode,id,title,needs)
                else WorkerRuntimeWake.submit(context,text,mode,commandId=id,title=title,needs=needs,sourceSession=source)
            check(accepted.getString("commandId") == id)
            "「$title」已交给后台处理，进度可在后台任务中查看。"
        }
        journal.advance(id,"handoff",receipt)
        return receipt
    }
    /**
     * Start the pet UI on a main-workspace session selected by the user.  Its local bubble
     * history is archived before switching; the main Harness transcript is never read here.
     */
    fun resumeSession(sessionId: String): Boolean {
        if (closed || busy || followups.isNotEmpty() || !BubbleSessionRegistry.valid(sessionId)) return false
        busy = true
        val generation = ++request
        deliveryWatcher?.stop(); deliveryWatcher = null
        hide()
        executor.execute {
            val result = runCatching { historyStore.archiveAndClear(); deliveryInbox.clear(); chat.resumeSession(sessionId) }
            handler.post {
                if (closed || request != generation) return@post
                busy = false
                result.onSuccess {
                    history.clear(); draft = ""; PetInitiative.clearOpening(context); initiativeQuestion = null; lastError = null; failedUser = null
                    show("已接续主对话会话；主对话正文不会复制到桌宠。现在可以继续发送消息。")
                }.onFailure {
                    lastError = "无法切换到主对话会话，原桌宠记录已保留。"
                    show(lastError)
                }
            }
        }
        return true
    }
    private var initiativeQuestion: String? = null
    private var initiativeUsesMemory = false
    val canReceiveInitiative: Boolean get() = !closed && !visible && !busy && draft.isBlank() &&
        chat.pendingUser == null && followups.isEmpty() && failedUser == null

    fun show(opening: String? = null) {
        if (closed || visible) return
        val resumeInitiativeDraft = initiativeQuestion != null
        initiativeQuestion = null
        val density = context.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val ui = PetUi(context)
        val palette = ui.palette
        val foreground = ui.ink
        val secondary = ui.muted
        val accent = ui.accent
        fun bar() = ui.shape(ui.surface, 12)
        val metrics = context.resources.displayMetrics
        val fontScale = context.resources.configuration.fontScale
        val sizeStore = CompanionStore(context)
        var widthMode = sizeStore.chatWidthMode
        var heightMode = sizeStore.chatHeightMode
        fun windowWidth(screen: Int): Int {
            val available = (screen - dp(16)).coerceAtLeast(1)
            return when (widthMode) {
                "small" -> minOf(dp(340), (available * .82f).toInt())
                "large" -> available
                else -> minOf(dp(620), (available * .94f).toInt())
            }.coerceAtLeast(1)
        }
        var width = windowWidth(metrics.widthPixels)
        val pet = petBounds()
        var petOnRight = pet[0] + pet[2] / 2 >= metrics.widthPixels / 2
        // This estimate only selects the order before measuring; the final coordinates use actual height.
        var showAbove = pet[1] >= dp(175) || pet[1] + pet[3] + dp(175) > metrics.heightPixels
        val panel = object : LinearLayout(context) {
            override fun onConfigurationChanged(config: android.content.res.Configuration) {
                super.onConfigurationChanged(config)
                post { if (root === this) positionUpdater?.invoke() }
            }
            override fun onScreenStateChanged(screenState: Int) {
                super.onScreenStateChanged(screenState)
                if (root === this) {
                    showLivePartials = screenState == android.view.View.SCREEN_STATE_ON
                    if (showLivePartials) flushPartial?.invoke()
                }
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = ui.shape(ui.surface, 20, ui.line)
            clipToOutline = true
            setPadding(dp(1), dp(1), dp(1), dp(1))
        }
        val knowledgeAllowed = CompanionStore(context).knowledgeModelAccess
        val accessBadge = TextView(context).apply {
            text = if (knowledgeAllowed) "角色对话 · 资料已授权，按需调用工具" else "角色对话 · 资料未授权，点击打开设置"
            textSize = 13f; setTextColor(secondary); gravity = Gravity.CENTER_VERTICAL; minHeight = dp(44)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            contentDescription = text
            setOnClickListener {
                context.startActivity(Intent(context, CompanionActivity::class.java)
                    .putExtra(CompanionActivity.OPEN_PAGE, CompanionPreferencePage.LIBRARY.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }
        val sessionActions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            background = bar(); addView(accessBadge)
        }
        val workerBadge = ui.text("后台任务", 12f, accent, true).apply {
            gravity = Gravity.CENTER; minHeight = dp(44)
            maxWidth = minOf((width * 0.43f).toInt(),dp((134 * fontScale.coerceAtLeast(1f)).toInt()))
            maxLines = if (fontScale >= 1.4f) 2 else 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(9), 0, dp(9), 0); contentDescription = "后台任务"
            background = ui.action(ui.soft, 12); setOnClickListener { workerPanel.open() }
        }
        val headingEnd = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; addView(workerBadge); addView(ui.close(::hide)) }
        val subtitle = ui.string(R.string.pet_ui_chat_title).takeIf { fontScale < 1.4f }
        panel.addView(ui.header(ui.string(R.string.pet_ui_name), subtitle, headingEnd))
        val shortcuts = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(2), dp(8), 0)
        }
        val toolShelf = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        lateinit var controlsScroll: ScrollView
        fun syncControls() {
            controlsScroll.visibility = if (sessionActions.visibility == android.view.View.VISIBLE || toolShelf.childCount > 0)
                android.view.View.VISIBLE else android.view.View.GONE
        }
        fun sizeRow(label: String, choices: List<String>, selected: String, change: (String) -> Unit) {
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                addView(ui.text(label, 13f, secondary))
            }
            val modes = listOf("small", "auto", "large")
            val buttons = choices.mapIndexed { index, title ->
                ui.text(title, 13f, foreground).apply {
                    gravity = Gravity.CENTER; minHeight = dp(44); isFocusable = true
                    contentDescription = "$label$title"
                    row.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
                }
            }
            fun select(mode: String) {
                buttons.forEachIndexed { index, button ->
                    button.isSelected = modes[index] == mode
                    button.background = ui.action(if (button.isSelected) ui.soft else ui.surface, 10)
                    button.setTextColor(if (button.isSelected) accent else foreground)
                }
            }
            select(selected)
            buttons.forEachIndexed { index, button -> button.setOnClickListener {
                val mode = modes[index]
                change(mode)
                select(mode)
                positionUpdater?.invoke()
            } }
            sessionActions.addView(row)
        }
        sizeRow("宽度", listOf("窄", "自动", "宽"), widthMode) { widthMode = it; sizeStore.chatWidthMode = it }
        sizeRow("高度", listOf("低", "自动", "高"), heightMode) { heightMode = it; sizeStore.chatHeightMode = it }
        fun shortcut(label: String, icon: Int, description: String, click: () -> Unit) {
            shortcuts.addView(ui.text(label, 13f, foreground).apply {
                gravity = Gravity.CENTER; minHeight = dp(44); contentDescription = description
                setPadding(dp(5), 0, dp(5), 0); background = ui.action(ui.surface, 10)
                ui.leadingIcon(this, icon, 16); isFocusable = true; setOnClickListener { click() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        sessionActions.addView(ui.text(ui.string(R.string.pet_manual_assign), 14f, accent).apply {
            minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0); ui.leadingIcon(this, R.drawable.ic_pet_letter)
            contentDescription = "交办独立后台任务"; setOnClickListener {
                sessionActions.visibility = android.view.View.GONE
                syncControls()
                panel.post { positionUpdater?.invoke() }
                composeTask()
            }
        })
        shortcut(ui.string(R.string.pet_menu_capture), R.drawable.ic_pet_shell, "存入本地资料库") { openCapture() }
        shortcut(ui.string(R.string.pet_menu_tools), R.drawable.ic_whale_nav_plugins, ui.string(R.string.pet_menu_tools)) {
            sessionActions.visibility = android.view.View.GONE
            if (toolShelf.childCount > 0) toolShelf.removeAllViews()
            else toolShelf.addView(WhalePetToolShelf(context, { panel.post { positionUpdater?.invoke() } }, ::openTool, ::hide))
            syncControls()
            panel.post { positionUpdater?.invoke() }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
            gravity = if (showAbove) Gravity.BOTTOM else Gravity.TOP
        }
        val scroll = ScrollView(context).apply { addView(content); isVerticalScrollBarEnabled = false; setBackgroundColor(ui.canvas) }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, dp(120)))
        val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var controlLimit = dp(180)
        controlsScroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) controlLimit
                    else minOf(controlLimit,MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(cap,MeasureSpec.AT_MOST))
            }
        }
        controls.addView(toolShelf); controls.addView(sessionActions)
        controlsScroll.addView(controls)
        syncControls()
        panel.addView(shortcuts); panel.addView(controlsScroll)
        content.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) panel.post { if (root === panel) positionUpdater?.invoke() }
        }
        fun styleBubble(view: android.view.View, isUser: Boolean) {
            view.setPadding(dp(12), dp(8), dp(12), dp(8))
            view.background = ui.shape(if (isUser) ui.soft else ui.answer, 14, if (isUser) null else ui.line).apply {
                val round = dp(15).toFloat(); val tip = dp(5).toFloat()
                cornerRadii = if (isUser) floatArrayOf(round,round,tip,tip,round,round,round,round)
                    else floatArrayOf(tip,tip,round,round,round,round,round,round)
            }
        }
        fun atBottom(): Boolean = scroll.getChildAt(0)?.let { child ->
            child.bottom - (scroll.scrollY + scroll.height) <= dp(48)
        } ?: true
        fun bubble(message: String, isUser: Boolean): TextView {
            val shouldFollow = isUser || atBottom()
            val view = TextView(context).apply {
                text = CompanionRichText.render(message, ui); textSize = 15f; setTextColor(ui.ink); setLinkTextColor(ui.accent); tag = isUser; maxWidth = width - dp(45)
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                styleBubble(this, isUser)
            }
            content.addView(view, LinearLayout.LayoutParams(-2, -2).apply { gravity = if (isUser) Gravity.END else Gravity.START; bottomMargin = dp(6) })
            if (shouldFollow) scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
            return view
        }
        fun renderCompleted(view: TextView, message: String, isUser: Boolean) {
            // Ordinary short bubbles remain lightweight native text. Formulae and tables need
            // a proper offline renderer; streaming continues in the native TextView until done.
            if (!CompanionRichText.requiresRichRenderer(message)) return
            val web = android.webkit.WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
                settings.javaScriptEnabled = true
                settings.blockNetworkLoads = true
                settings.allowContentAccess = false
                settings.allowFileAccess = true
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                styleBubble(this, isUser)
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: android.webkit.WebView, request: android.webkit.WebResourceRequest): Boolean {
                        if (request.isForMainFrame && request.url.scheme in setOf("http", "https")) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                        return true
                    }
                    override fun onPageFinished(v: android.webkit.WebView, url: String) {
                        val quoted = org.json.JSONObject.quote(message).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
                        val bodyColor = String.format(java.util.Locale.ROOT, "#%06x", ui.ink and 0xffffff)
                        val quietColor = String.format(java.util.Locale.ROOT, "#%06x", ui.muted and 0xffffff)
                        val linkColor = String.format(java.util.Locale.ROOT, "#%06x", ui.accent and 0xffffff)
                        val codeColor = String.format(java.util.Locale.ROOT, "#%06x", ui.soft and 0xffffff)
                        v.evaluateJavascript("document.body.style.cssText='--ink:$bodyColor;--muted:$quietColor;--link:$linkColor;--code:$codeColor';renderMessage($quoted)") { value ->
                            val height = value.toIntOrNull() ?: return@evaluateJavascript
                            if (root !== panel || height <= 0) return@evaluateJavascript
                            v.layoutParams = v.layoutParams.apply { this.height = dp(height + 22) }
                            view.visibility = android.view.View.GONE
                            v.visibility = android.view.View.VISIBLE
                            if (atBottom()) scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
                            panel.post { positionUpdater?.invoke() }
                        }
                    }
                }
                visibility = android.view.View.INVISIBLE
                addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
                    if (v.visibility == android.view.View.VISIBLE && right - left != oldRight - oldLeft) {
                        evaluateJavascript("Math.ceil(document.getElementById('message').getBoundingClientRect().height)") { raw ->
                            val pixels = raw.toIntOrNull()?.takeIf { it > 0 } ?: return@evaluateJavascript
                            if (root === panel) layoutParams = layoutParams.apply { height = dp(pixels + 22) }
                        }
                    }
                }
            }
            richViews.add(web)
            content.addView(web, content.indexOfChild(view) + 1, LinearLayout.LayoutParams(width - dp(45), dp(1)).apply {
                gravity = if (isUser) Gravity.END else Gravity.START; bottomMargin = dp(6)
            })
            web.post { if (root === panel) web.loadUrl("file:///android_asset/rich-chat/index.html") }
        }
        fun imageBubble(id: String) {
            if (!id.matches(Regex("sha256:[a-f0-9]{64}"))) return
            val hash = id.removePrefix("sha256:")
            val imageRoot = java.io.File(context.noBackupFilesDir, "dsh-runtime/current/root/.dsh/attachments/v1/objects").canonicalFile
            val file = java.io.File(imageRoot, "${hash.take(2)}/$hash").canonicalFile
            if (!file.path.startsWith(imageRoot.path + java.io.File.separator) || !file.isFile || file.length() !in 1..8_000_000L) {
                val unavailable = bubble("图片暂不可用，点击重试", false)
                unavailable.setOnClickListener { content.removeView(unavailable); imageBubble(id) }
                return
            }
            val shouldFollow = atBottom()
            val imageWidth = width - dp(45)
            val image = android.widget.ImageView(context).apply { adjustViewBounds = true; maxWidth = imageWidth; maxHeight = dp(300); contentDescription = "对话图片" }
            content.addView(image, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
            executor.execute {
                val bitmap = runCatching {
                    val bytes = file.readBytes()
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                    require(digest == hash)
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000)
                    var sample = 1
                    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 1024) sample *= 2
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull()
                handler.post {
                    if (root === panel && bitmap != null) {
                        val stillFollowing = atBottom()
                        image.setImageBitmap(bitmap)
                        image.setOnClickListener {
                            val preview = android.widget.ImageView(context).apply {
                                setImageDrawable(image.drawable); adjustViewBounds = true; contentDescription = "图片预览"
                            }
                            previewDialog?.dismiss()
                            previewDialog = runCatching {
                                android.app.AlertDialog.Builder(context).setTitle("图片预览").setView(preview).setPositiveButton("关闭", null).create().apply {
                                    window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                                    setOnDismissListener { previewDialog = null }
                                    show()
                                }
                            }.getOrElse {
                                android.widget.Toast.makeText(context, "图片预览暂不可用", android.widget.Toast.LENGTH_SHORT).show()
                                null
                            }
                        }
                        if (shouldFollow && stillFollowing) scroll.fullScroll(android.view.View.FOCUS_DOWN)
                    } else {
                        bitmap?.recycle()
                        if (root === panel) {
                            content.removeView(image)
                            val unavailable = bubble("图片暂不可用，点击重试", false)
                            unavailable.setOnClickListener { content.removeView(unavailable); imageBubble(id) }
                        }
                    }
                }
            }
        }
        fun citationCard(citation: CompanionBubbleChat.Citation) {
            val shouldFollow = atBottom()
            // One compact card per source document; segment and parsing metadata remain available
            // after opening the card, rather than crowding every reply.
            val coverage = ""
            val segment = ""
            val view = TextView(context).apply {
                text = "资料来源 · ${citation.displayName}$segment$coverage"
                textSize = 12f; setTextColor(ui.accent); maxWidth = width - dp(45)
                contentDescription = "回答资料来源：${citation.displayName}$segment$coverage"
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = ui.action(ui.soft, 12, ui.line)
                isClickable = true
                setOnClickListener {
                    runCatching {
                        context.startActivity(Intent(context, CompanionActivity::class.java)
                            .putExtra(CompanionActivity.OPEN_KNOWLEDGE_ID, citation.id)
                            .putExtra(CompanionActivity.OPEN_KNOWLEDGE_REVISION, citation.revision)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    }.onFailure { android.widget.Toast.makeText(context, "无法打开资料来源，请稍后重试。", android.widget.Toast.LENGTH_LONG).show() }
                }
            }
            content.addView(view, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.START; bottomMargin = dp(6) })
            if (shouldFollow) scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
        if (history.isEmpty()) bubble(opening ?: CompanionPersonaStore(context).active().optString("greeting").ifBlank { "嗨，想聊什么？" }, false)
        else history.takeLast(8).forEach {
            val parsed = if (it.role == "assistant") CompanionBubbleChatPolicy.parseInlineImages(it.text) else CompanionBubbleChatPolicy.InlineImages(it.text, emptyList())
            val user = it.role == "user"
            bubble(parsed.text, user).also { view -> renderCompleted(view, parsed.text, user) }; (it.images + parsed.images).distinct().take(6).forEach(::imageBubble); if (!user) CompanionBubbleChatPolicy.citedSources(parsed.text, it.citations).forEach(::citationCard)
        }
        if (history.isNotEmpty() && opening != null) bubble(opening, false)
        if (initiativeQuestion == null && opening == null && !busy && chat.pendingUser == null && (draft.isBlank() || resumeInitiativeDraft) &&
            CompanionStore(context).modelInitiative) {
            executor.execute {
                val pending = runCatching { PetInitiative.pendingOpening(context) }.getOrNull()
                handler.post {
                    if (pending != null && !closed && root === panel && !busy && (draft.isBlank() || resumeInitiativeDraft) &&
                        CompanionStore(context).modelInitiative && (!pending.usesMemory || CompanionStore(context).bubbleMemoryAccess)) {
                        initiativeQuestion = pending.text
                        initiativeUsesMemory = pending.usesMemory
                        bubble(pending.text, false)
                        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
                    }
                }
            }
        }
        fun workerReport(report: org.json.JSONObject) {
            val state = when (report.optString("state")) {
                "completed" -> "已完成"
                "waiting" -> "需要你回答"
                "blocked" -> "遇到阻碍"
                "failed" -> "未完成"
                "cancelled" -> "已取消"
                else -> "状态更新"
            }
            val text = "后台任务 · ${report.optString("title")} · $state\n${report.optString("text")}".trim()
            bubble(text, false).also { view -> renderCompleted(view, text, false) }
        }
        val workerInbox = WorkerReportInbox(context)
        val unseenReports = workerInbox.unseen()
        unseenReports.forEach(::workerReport)
        renderWorkerReport = ::workerReport
        // 后台任务结果：用户那一轮已经结束，回执是在同一会话里新起的一轮，历史里没有它。
        // 单独从收件箱追加，既不改对话历史（历史要求用户/助手严格交替），也不会被误当成新提问。
        val deliveries = deliveryInbox.unseen()
        if (deliveries.isNotEmpty()) {
            bubble("后台任务有结果了（不是新的提问，可以继续说）", false)
            deliveries.forEach { item ->
                val parsed = CompanionBubbleChatPolicy.parseInlineImages(item.text)
                bubble(parsed.text, false).also { view -> renderCompleted(view, parsed.text, false) }
                parsed.images.forEach(::imageBubble)
            }
            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
        if (busy) {
            (inFlightUser ?: chat.pendingUser)?.let { bubble(it, true) }
            val live = bubble(mainQuestion ?: liveText.ifBlank { "正在回复…" }, false)
            renderLive = { text -> live.text = CompanionRichText.render(text, ui); panel.post { positionUpdater?.invoke() } }
        }
        val input = EditText(context).apply {
            hint = ui.string(R.string.pet_ui_input); setTextColor(foreground); setHintTextColor(secondary)
            imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
            background = null; setPadding(dp(10), dp(8), dp(2), dp(8)); minHeight = dp(44); textSize = 15f; maxLines = 3; filters = arrayOf(android.text.InputFilter.LengthFilter(2000))
            setText(draft); setSelection(text.length)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { draft = s?.toString().orEmpty(); panel.post { positionUpdater?.invoke() } }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        val send = ui.text(ui.string(R.string.pet_ui_send), 14f, ui.onPrimary, true).apply {
            gravity = Gravity.CENTER; minWidth = dp(54); minHeight = dp(44)
            background = ui.action(ui.primary, 12); isEnabled = !busy || inFlightUser != null
        }
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2)); background = ui.shape(ui.canvas, 14, ui.line)
        }

        val continueButton = TextView(context).apply {
            text = "主对话"; setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(secondary, accent))); textSize = 13f; setPadding(dp(4), dp(12), dp(4), dp(12))
            contentDescription = "在主对话继续桌宠会话"
            isEnabled = chat.handoffSessionId() != null
            setOnClickListener {
                chat.handoffSessionId()?.let { id -> hide(); openSession(id) }
            }
        }
        val newTopic = TextView(context).apply {
            text = "新话题"; setTextColor(continueButton.textColors); textSize = 13f; setPadding(dp(4), dp(12), dp(4), dp(12))
            contentDescription = "开始新话题并保留之前的聊天记录"
            isEnabled = !busy && (history.isNotEmpty() || lastError != null)
            setOnClickListener {
                if (busy || (history.isEmpty() && lastError == null)) return@setOnClickListener
                val canPark = synchronized(intakeLock) {
                    if (followups.any { it.deciding && it.handingOff } || recoveringDispatch.isNotEmpty()) false
                    else { followups.forEach { it.parked = true }; true }
                }
                if (!canPark) {
                    android.widget.Toast.makeText(context, "正在保存交办回执，请稍后再试。", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                followups.filter { it.deciding }.forEach { it.classification.cancel() }
                val unsentDraft = draft.takeUnless { it == chat.pendingUser || it == failedUser }.orEmpty()
                busy = true; send.isEnabled = false; input.isEnabled = false; isEnabled = false; continueButton.isEnabled = false
                val generation = ++request
                executor.execute {
                    val result = runCatching {
                        // Preserve queued inputs for explicit review, never replay them into a different topic.
                        val retained = journal.entries()
                        historyStore.archiveAndClear(); deliveryInbox.clear(); chat.startNewTopic()
                        retained
                    }
                    handler.post {
                        if (closed || request != generation) return@post
                        busy = false
                        result.onSuccess { retained ->
                            history.clear(); draft = unsentDraft; lastError = null; failedUser = null; failedFollowup = null
                            followups.clear(); recovered.clear(); recovered.addAll(retained)
                            PetInitiative.clearOpening(context); initiativeQuestion = null; pendingQuestion = null; mainQuestion = null
                        }
                            .onFailure { lastError = "无法开启新话题，原聊天记录已保留" }
                        if (root != null) { hide(); show() }
                    }
                }
            }
        }
        sessionActions.addView(LinearLayout(context).apply {
            for (action in listOf(continueButton,newTopic)) {
                action.gravity = Gravity.CENTER; action.minHeight = dp(44)
                addView(action,LinearLayout.LayoutParams(0,-2,1f))
            }
        })
        footer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        shortcut(ui.string(R.string.pet_menu_session), R.drawable.ic_whale_nav_chat, ui.string(R.string.pet_menu_session)) {
            toolShelf.removeAllViews()
            sessionActions.visibility = if (sessionActions.visibility == android.view.View.GONE) android.view.View.VISIBLE else android.view.View.GONE
            syncControls()
            controlsScroll.scrollTo(0,0)
            panel.post { positionUpdater?.invoke() }
        }
        footer.addView(send)
        val status = ui.text("",12f,secondary).apply { setPadding(dp(12),dp(4),dp(12),dp(4)) }
        controls.addView(status)
        val endWait = ui.text("结束等待", 13f, accent).apply {
            minHeight=dp(44); gravity=Gravity.CENTER
            contentDescription="结束等待并保留原消息；已提交的后台任务不会取消"
            visibility=if (busy && activeReply != null) android.view.View.VISIBLE else android.view.View.GONE
            background=ui.action(ui.soft,12)
            setOnClickListener {
                activeReply?.cancel()
                text="正在结束等待…"; isEnabled=false
            }
        }
        controls.addView(endWait)
        val progress = ui.text("", 12f, secondary).apply {
            setPadding(dp(12), dp(4), dp(12), dp(5))
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        controls.addView(progress)
        val recoveryChip = ui.text("", 13f, accent).apply { minHeight = dp(44); gravity = Gravity.CENTER }
        controls.addView(recoveryChip)
        fun recoveryLabel() {
            recoveryChip.text = if (recovery.isFailure) "待处理消息暂时无法读取" else "待处理消息 · ${recovered.size}"
            recoveryChip.visibility = if (recovered.isEmpty() && recovery.isSuccess) android.view.View.GONE else android.view.View.VISIBLE
        }
        recoveryLabel()
        recoveryChip.setOnClickListener {
            val entry = recovered.firstOrNull() ?: return@setOnClickListener
            val note = when (entry.stage) {
                "dispatch" -> "上次交办回执尚未保存，可以使用原任务编号恢复交办。"
                "queued" -> "这条消息尚未接取，可继续放回对话。"
                "handoff" -> entry.receipt.ifBlank { "已开始交办，请到后台任务核对接取状态。" }
                else -> "已开始回复，可在对话记录中核对。"
            }
            fun forget() {
                if (runCatching { journal.remove(entry.id) }.isSuccess) { recovered.remove(entry); recoveryLabel() }
                else android.widget.Toast.makeText(context,"暂时无法更新记录",android.widget.Toast.LENGTH_SHORT).show()
            }
            val builder = android.app.AlertDialog.Builder(context).setTitle("待处理消息")
                .setMessage(listOfNotNull(entry.after.takeIf(String::isNotBlank)?.let { "接在这句话之后：$it" }, entry.text, note).joinToString("\n\n"))
                .setNegativeButton(if (recovered.size > 1) "查看下一条" else "稍后") { _,_ ->
                    if (recovered.size > 1) {
                        recovered.remove(entry); recovered.add(entry)
                        handler.post { if (root === panel) recoveryChip.performClick() }
                    }
                }
            if (entry.stage == "dispatch" && entry.plan != null) {
                builder.setPositiveButton("恢复交办") { _,_ ->
                    if (!recoveringDispatch.add(entry.id)) return@setPositiveButton
                    intake.execute {
                        val result = runCatching { deliverPlan(entry.id,entry.text,entry.plan) }
                        handler.post {
                            recoveringDispatch.remove(entry.id)
                            if (!closed) {
                                result.onSuccess { receipt ->
                                    recovered.remove(entry); recovered.add(entry.copy(stage="handoff",receipt=receipt))
                                }.onFailure { android.widget.Toast.makeText(context,"交办尚未确认，原任务仍可恢复。",android.widget.Toast.LENGTH_SHORT).show() }
                                if (visible) { hide(); show() }
                            }
                        }
                    }
                }
            } else if (entry.stage == "queued") {
                builder.setPositiveButton("继续这条消息") { _,_ ->
                    recovered.remove(entry); recoveryLabel()
                    followups.addLast(Followup(entry.text,entry.id))
                    renderFollowups?.invoke(); sendNext?.invoke()
                }.setNeutralButton("移除消息") { _,_ -> forget() }
            } else {
                builder.setPositiveButton("已核对") { _,_ -> forget() }
                if (entry.stage == "handoff") builder.setNeutralButton("后台任务") { _,_ -> openTaskInbox() }
            }
            recoveryDialog = builder.create().apply { window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY); show() }
        }
        val progressDetails = ui.text("", 12f, secondary).apply { setPadding(dp(12),0,dp(12),dp(6)) }
        controls.addView(progressDetails)
        var expanded = false
        renderFollowups = {
            val deciding = followups.count { it.deciding }
            val received = followups.count { it.receipt != null }
            val waiting = followups.size - deciding - received
            val counts = listOfNotNull(
                "判断中 $deciding".takeIf { deciding > 0 }, "等待对话 $waiting".takeIf { waiting > 0 },
                "已收到回执 $received".takeIf { received > 0 }
            ).joinToString(" · ")
            if (followups.isEmpty()) expanded = false
            val latest = followups.lastOrNull { it.receipt != null }?.receipt
            progress.text = listOfNotNull(counts, latest).joinToString("\n")
            progress.visibility = if (counts.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            progress.contentDescription = "$counts，查看消息状态"
            progressDetails.text = if (expanded) followups.joinToString("\n") { entry ->
                val state = entry.receipt ?: if (entry.deciding) "正在判断" else "等待对话"
                "${entry.text.take(80)} · $state"
            } else ""
            progressDetails.visibility = if (expanded) android.view.View.VISIBLE else android.view.View.GONE
            panel.post { positionUpdater?.invoke() }
        }
        progress.setOnClickListener { expanded = !expanded; renderFollowups?.invoke() }
        renderFollowups?.invoke()
        val answerQuestion = TextView(context).apply {
            text = "回答助手的问题"; textSize = 15f; setPadding(dp(12), dp(10), dp(12), dp(10)); setTextColor(accent); background = bar()
            visibility = if (pendingQuestion != null) android.view.View.VISIBLE else android.view.View.GONE
            setOnClickListener { showQuestion() }
        }
        controls.addView(answerQuestion)
        panel.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(4), dp(10), dp(9)) })
        // Message bubbles always precede the input, regardless of the pet's screen position.
        status.text = if (busy) "正在回复…" else lastError.orEmpty()
        status.visibility = if (busy || lastError != null) android.view.View.VISIBLE else android.view.View.GONE
        fun sendLabel() {
            send.text = if (busy && inFlightUser != null) {
                "继续发送"
            } else ui.string(R.string.pet_ui_send)
        }
        sendLabel()
        if (busy) {
            status.text = when {
                pendingQuestion != null -> "需要你回答；回答后会继续原任务。"
                mainQuestion != null -> "需要你回答，请点“主对话”继续。"
                else -> "正在回复…"
            }
            input.isEnabled = inFlightUser != null
        }
        var failedMessage: TextView? = null
        var retryText: String? = null
        var queued: Followup? = null
        sendNext = {
            if (!busy && !closed && chat.pendingUser == null && failedUser == null && followups.firstOrNull()?.deciding == false) {
                queued = followups.removeFirst()
                renderFollowups?.invoke()
                send.performClick()
            }
        }
        status.setOnClickListener {
            if (!busy) (chat.pendingUser ?: failedUser)?.let { retryText = it; send.performClick() }
        }
        send.setOnClickListener {
            // A recoverable turn owns its transcript slot, but independent work can still be dispatched.
            val pendingTurn = inFlightUser ?: chat.pendingUser ?: failedUser
            val newInput = queued == null && retryText == null && input.text.toString().trim() != pendingTurn
            if (busy || (newInput && (pendingTurn != null || followups.isNotEmpty()))) {
                if (busy && inFlightUser == null) return@setOnClickListener
                val next = runCatching { CompanionBubbleChatPolicy.validateMessage(input.text.toString()) }.getOrNull() ?: return@setOnClickListener
                val id = runCatching { journal.accept(next,followups.lastOrNull()?.text ?: pendingTurn.orEmpty()) }.getOrElse {
                    android.widget.Toast.makeText(context,it.message?.takeIf { note -> note.startsWith("待处理消息已满") } ?: "无法保存待处理消息，输入已保留",android.widget.Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val item = Followup(next,id)
                if (pendingTurn != null) {
                    val outstanding = (listOf(pendingTurn) + followups.map { it.text } + next).takeLast(6)
                    classifyFollowup(item, outstanding.map { CompanionBubbleChat.Message("user", it) })
                }
                followups.addLast(item)
                renderFollowups?.invoke()
                input.text.clear()
                sendLabel()
                sendNext?.invoke()
                return@setOnClickListener
            }
            val item = queued ?: failedFollowup?.takeIf { it.text == (retryText ?: input.text.toString().trim()) }
            val fromQueue = queued != null
            val typed = input.text.toString().trim()
            val replyingTo = initiativeQuestion?.takeIf { item == null && retryText == null &&
                CompanionStore(context).modelInitiative && (!initiativeUsesMemory ||
                    (CompanionStore(context).bubbleMemoryAccess && CompanionMemoryStore(context).enabled)) }
            val raw = item?.text ?: retryText ?: if (replyingTo != null && typed.isNotEmpty())
                "鲸鱼娘问：「$replyingTo」\n我的回答：$typed" else typed
            val message = runCatching { CompanionBubbleChatPolicy.validateMessage(raw) }.getOrElse {
                if (raw.length > 2000) input.error = "带上提问后回答过长，请缩短一些。"
                return@setOnClickListener
            }
            queued = null
            retryText = null
            if (chat.pendingUser?.let { it != message } == true) {
                status.text = "上一条消息需要恢复，点此重试。"; status.visibility = android.view.View.VISIBLE
                return@setOnClickListener
            }
            if (item?.receipt == null && runCatching {
                chat.preserveDirectInput(history,message,item?.id)
                if (item != null) journal.advance(item.id,"reply")
            }.isFailure) {
                failedFollowup = item; failedUser = message
                lastError = "对话记录写入失败，请检查存储空间。输入已保留，点此重试。"
                status.text = lastError
                status.visibility = android.view.View.VISIBLE
                return@setOnClickListener
            }
            inFlightUser = message; failedUser = null; failedFollowup = null
            if (initiativeQuestion != null) PetInitiative.clearOpening(context)
            initiativeQuestion = null
            if (!fromQueue && (typed == message || replyingTo != null)) input.text.clear()
            failedMessage?.let { content.removeView(it) }; failedMessage = null
            lastError = null
            val sentBubble = bubble(message, true)
            renderCompleted(sentBubble, message, true)
            val streamingBubble = bubble("正在输入…", false)
            liveText = ""
            renderLive = { text -> streamingBubble.text = CompanionRichText.render(text, ui); panel.post { positionUpdater?.invoke() } }
            mainQuestion = null
            busy = true; onState("thinking"); newTopic.isEnabled = false; continueButton.isEnabled = chat.handoffSessionId() != null; sendLabel(); status.text = "正在回复…"; status.visibility = android.view.View.VISIBLE; panel.post { positionUpdater?.invoke() }
            val generation = ++request
            chat.onInteractionNeeded = { question -> handler.post {
                if (!closed && request == generation) {
                    mainQuestion = question
                    if (root !== panel) {
                        if (visible) { hide(); show() }
                        return@post
                    }
                    streamingBubble.text = CompanionRichText.render(question, ui)
                    status.text = "需要你回答，请点“主对话”继续。"
                    status.visibility = android.view.View.VISIBLE
                    continueButton.isEnabled = chat.handoffSessionId() != null
                    panel.post { positionUpdater?.invoke() }
                }
            } }
            chat.onQuestion = { question -> handler.post {
                if (!closed && request == generation) {
                    pendingQuestion = question
                    mainQuestion = null
                    if (root === panel) {
                        answerQuestion.visibility = android.view.View.VISIBLE
                        status.text = "需要你回答；回答后会继续原任务。"
                        continueButton.isEnabled = true
                        showQuestion()
                    } else if (root != null) { hide(); show() }
                }
            } }
            val prior = (history + CompanionBubbleChat.Message("user", message)).takeLast(19)
            val latestPartial = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val partialScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
            val acceptingPartials = java.util.concurrent.atomic.AtomicBoolean(true)
            fun renderPartial() {
                partialScheduled.set(false)
                if (!showLivePartials || closed || request != generation || !acceptingPartials.get()) return
                val partial = latestPartial.getAndSet(null)
                if (partial != null) {
                    liveText = partial
                    renderLive?.invoke(partial)
                    if (root === panel) {
                        if (pendingQuestion == null) status.text = "正在流式输出…"
                        if (atBottom()) scroll.fullScroll(android.view.View.FOCUS_DOWN)
                    }
                }
            }
            fun schedulePartial() {
                if (showLivePartials && latestPartial.get() != null && acceptingPartials.get() && partialScheduled.compareAndSet(false,true)) {
                    handler.postDelayed({ renderPartial() },32)
                }
            }
            flushPartial = ::schedulePartial
            val operation = PetReplyOperation()
            activeReply = operation
            endWait.visibility=android.view.View.VISIBLE; endWait.isEnabled=true; endWait.text="结束等待"
            executor.execute {
                val result = runCatching { operation.run {
                    val settings = CompanionStore(context)
                    val memoryStore = CompanionMemoryStore(context)
                    val memoryAllowed = settings.bubbleMemoryAccess && memoryStore.enabled
                    val knowledgeAllowed = settings.knowledgeModelAccess
                    // Content changes do not reset a live conversation; authorization/capability changes do.
                    val scope = CompanionBubbleChatPolicy.toolScope(
                        memoryAllowed, knowledgeAllowed,
                        memoryAllowed && SemanticControl(context).memoryEnabled,
                        knowledgeAllowed && SemanticControl(context).enabled,
                    )
                    val onPartial: (String) -> Unit = { partial ->
                        if (acceptingPartials.get()) {
                            latestPartial.set(partial)
                            schedulePartial()
                        }
                    }
                    val answer = item?.receipt?.let { receipt ->
                        val finished = requireNotNull(chat.snapshot((prior + CompanionBubbleChat.Message("assistant",receipt)).takeLast(18)))
                        PetReplyOperation.commitReply { historyStore.save(finished,requireWritable=true) }
                        CompanionBubbleChat.Reply(receipt, emptyList(), emptyList())
                    } ?: replyRunner(chat, prior, scope, memoryAllowed, knowledgeAllowed, onPartial)
                    // A failed cleanup leaves a reviewable entry. The completed turn must not be retried.
                    if (item != null) runCatching { journal.remove(item.id) }
                    answer
                } }
                handler.post {
                    if (closed || request != generation) return@post
                    if (activeReply === operation) activeReply=null
                    endWait.visibility=android.view.View.GONE
                    acceptingPartials.set(false); latestPartial.set(null)
                    flushPartial = null
                    val samePanel = root === panel
                    mainQuestion = null; inFlightUser = null; busy = false; send.isEnabled = true; input.isEnabled = true
                    result.onSuccess { answer ->
                        pendingQuestion = null; questionDialog?.dismiss(); questionDialog = null; answerQuestion.visibility = android.view.View.GONE
                        onState("reply")
                        history.add(CompanionBubbleChat.Message("user", message)); history.add(CompanionBubbleChat.Message("assistant", answer.text, answer.images, answer.citations))
                        while (history.size > 18) { history.removeAt(0); history.removeAt(0) }
                        chat.snapshot(history)?.let { snapshot -> executor.execute {
                            runCatching {
                                // The reply was persisted before the input became available for another turn.
                                // The anchor is intentionally lossy and is never sent back into Harness automatically.
                                fun summary(value: String) = value.replace(Regex("\\s+"), " ").trim().take(160)
                                anchorStore.append(snapshot.sessionId, "user", System.currentTimeMillis(), summary(message))
                                anchorStore.append(snapshot.sessionId, "assistant", System.currentTimeMillis(), summary(answer.text), answer.citations.map {
                                    CompanionSessionAnchorStore.Citation(it.id, it.revision, it.displayName, it.chunkIndex, it.chunkCount, it.parsingCoverage)
                                })
                            }
                        } }
                        // 本轮调用过 mobile_workers、或这个会话名下有未结束的任务：父会话里还会有一轮
                        // 由 Worker 回执触发的新回复，那不是用户的请求，气泡的流折叠看不到它 —— 交给投递监察器推回来。
                        if (answer.tools.contains("mobile_workers") || hasActiveWorkers()) armDeliveryWatcher(answer.turn)
                        if (samePanel) { streamingBubble.text = CompanionRichText.render(answer.text, ui); renderCompleted(streamingBubble, answer.text, false); answer.images.forEach(::imageBubble); answer.citations.take(2).forEach(::citationCard) }
                        status.text = ""; status.visibility = android.view.View.GONE
                    }.onFailure { error ->
                        onState("idle")
                        failedMessage = sentBubble; failedUser = message; failedFollowup = item
                        if (draft.isEmpty()) {
                            draft = message
                            if (samePanel) { input.setText(message); input.setSelection(input.length()) }
                        }
                        if (samePanel) content.removeView(streamingBubble)
                        status.text = error.message?.takeIf { it.startsWith("本机") || it.startsWith("对话") || it.startsWith("回复") || it.startsWith("模型") }
                            ?: "发送失败，请检查本机服务和网络；输入已保留。"
                        status.append(" · 点此重试")
                        lastError = status.text.toString()
                        status.visibility = android.view.View.VISIBLE
                    }
                    if (samePanel) { continueButton.isEnabled = chat.handoffSessionId() != null; newTopic.isEnabled = history.isNotEmpty() || lastError != null }
                    if (!samePanel && root != null) { hide(); show() }
                    sendLabel()
                    if (result.isSuccess) sendNext?.invoke()
                    if (root == null && !busy && followups.isEmpty()) sendNext = null
                    panel.post { positionUpdater?.invoke() }
                }
            }
        }
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val updatePosition = {
            val metrics = context.resources.displayMetrics
            val currentPet = petBounds()
            val inset = panel.rootWindowInsets
            val keyboard = if (android.os.Build.VERSION.SDK_INT >= 30 && inset?.isVisible(android.view.WindowInsets.Type.ime()) == true)
                inset.getInsets(android.view.WindowInsets.Type.ime()).bottom else 0
            val frame = android.graphics.Rect().also { panel.getWindowVisibleDisplayFrame(it) }
            val screenWidth = if (panel.isAttachedToWindow && frame.width() > 0)
                minOf(metrics.widthPixels, frame.width()) else metrics.widthPixels
            val nextWidth = windowWidth(screenWidth)
            val resized = nextWidth != width
            if (resized) {
                width = nextWidth
                params.width = width
                workerBadge.maxWidth = minOf((width * .43f).toInt(), dp((134 * fontScale.coerceAtLeast(1f)).toInt()))
                val bodyWidth = (width - dp(45)).coerceAtLeast(1)
                for (index in 0 until content.childCount) {
                    when (val child = content.getChildAt(index)) {
                        is TextView -> child.maxWidth = bodyWidth
                        is android.widget.ImageView -> child.maxWidth = bodyWidth
                        is android.webkit.WebView -> child.layoutParams = child.layoutParams.apply { this.width = bodyWidth }
                    }
                }
            }
            val frameBottom = if (frame.bottom > 0) minOf(metrics.heightPixels, frame.bottom) else metrics.heightPixels
            val usable = minOf(frameBottom, metrics.heightPixels - keyboard)
            val gap = dp(8)
            val hasSideRoom = maxOf(currentPet[0], screenWidth - currentPet[0] - currentPet[2]) >= width + gap
            val availableHeight = if (hasSideRoom) usable else minOf(usable, maxOf(currentPet[1], usable - currentPet[1] - currentPet[3]) - gap)
            val nextLimit = (usable / 3).coerceIn(dp(48),dp(208))
            if (controlLimit != nextLimit) { controlLimit = nextLimit; controlsScroll.requestLayout() }
            val measureWidth = android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY)
            panel.measure(measureWidth, android.view.View.MeasureSpec.UNSPECIFIED)
            // WRAP_CONTENT can still report the expanded menu's cached height during collapse.
            var chrome = panel.paddingTop + panel.paddingBottom
            for (i in 0 until panel.childCount) {
                val child = panel.getChildAt(i)
                if (child !== scroll && child.visibility != android.view.View.GONE) chrome += child.measuredHeight
            }
            val natural = content.measuredHeight.takeIf { it > 0 } ?: dp(112)
            val available = (usable - frame.top.coerceAtLeast(0) - dp(8)).coerceAtLeast(1)
            val targetHeight = when (heightMode) {
                "small" -> (available * .42f).toInt()
                "large" -> (available * .86f).toInt()
                else -> minOf(availableHeight, (available * .76f).toInt(), chrome + natural.coerceAtLeast(dp(72)))
            }
            val desiredScroll = (targetHeight - chrome).coerceIn(minOf(dp(44), (available - chrome).coerceAtLeast(1)), (available - chrome).coerceAtLeast(1))
            val scrollParams = scroll.layoutParams as LinearLayout.LayoutParams
            if (scrollParams.height != desiredScroll) { scrollParams.height = desiredScroll; scroll.layoutParams = scrollParams }
            panel.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, android.view.View.MeasureSpec.AT_MOST))
            val actualHeight = panel.measuredHeight.coerceIn(1, metrics.heightPixels)
            val point = io.deepseekharness.mobile.overlay.CompanionHintLayout.place(screenWidth, usable.coerceAtLeast(actualHeight),
                currentPet[0], currentPet[1], currentPet[2], currentPet[3], width, actualHeight, dp(2), gap)
            val placedAbove = point.y + actualHeight <= currentPet[1]
            val right = currentPet[0] + currentPet[2] / 2 >= metrics.widthPixels / 2
            if (placedAbove != showAbove || right != petOnRight) {
                val changeOrder = placedAbove != showAbove
                showAbove = placedAbove; petOnRight = right
                if (changeOrder) {
                    content.gravity = if (showAbove) Gravity.BOTTOM else Gravity.TOP
                }
                for (i in 0 until content.childCount) {
                    val child = content.getChildAt(i) as? TextView ?: continue
                    val isUser = child.tag == true
                    styleBubble(child, isUser)
                    (child.layoutParams as LinearLayout.LayoutParams).gravity = if (isUser) Gravity.END else Gravity.START
                }
                content.requestLayout()
            }
            val moved = params.x != point.x || params.y != point.y
            params.x = point.x; params.y = point.y
            if (root === panel && (moved || resized)) runCatching { wm.updateViewLayout(panel, params) }
            Unit
        }
        panel.setOnApplyWindowInsetsListener { view, insets ->
            view.post { if (root === panel) positionUpdater?.invoke() }
            view.onApplyWindowInsets(insets)
        }
        updatePosition()
        if (runCatching { wm.addView(panel, params) }.isSuccess) {
            root = panel; positionUpdater = updatePosition
            showLivePartials = context.getSystemService(android.os.PowerManager::class.java).isInteractive
            if (showLivePartials) flushPartial?.invoke()
            workerInbox.markSeen(unseenReports.map { it.optString("id") })
            deliveryInbox.markSeen(deliveries)
            workerPanel.attach(workerBadge)
            executor.execute { chat.prewarm() }
            armDeliveryWatcherIfNeeded()
            if (!busy && !chat.pendingDirect && chat.pendingUser != null) handler.post { if (root === panel && !busy) { retryText = chat.pendingUser; send.performClick() } }
        }
    }
    /** Reply to the original tool call, without another chat prompt. */
    private fun showQuestion() {
        val question = pendingQuestion ?: return
        if (questionDialog != null) return
        val form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        val readAnswers = question.optJSONArray("questions")?.objects().orEmpty().map { row ->
            form.addView(TextView(context).apply { text = row.optString("question"); textSize = 16f })
            row.optString("detail").takeIf { it.isNotBlank() }?.let { detail -> form.addView(TextView(context).apply { text = CompanionRichText.render(detail) }) }
            val options = row.optJSONArray("options")?.objects().orEmpty()
            val choices = options.map { option -> android.widget.CheckBox(context).apply {
                text = option.getString("label") + option.optString("description").takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                form.addView(this)
            } }
            if (!row.optBoolean("multiSelect")) choices.forEach { choice -> choice.setOnCheckedChangeListener { _, checked -> if (checked) choices.filter { it !== choice }.forEach { it.isChecked = false } } }
            val custom = EditText(context).apply { hint = "也可以直接输入回答"; maxLines = 5 }
            form.addView(custom)
            val read: () -> org.json.JSONObject = {
                org.json.JSONObject().put("id", row.getString("id"))
                    .put("selected", org.json.JSONArray(options.indices.filter { choices[it].isChecked }.map { options[it].getString("label") }))
                    .apply { custom.text.toString().trim().takeIf { it.isNotEmpty() }?.let { put("custom", it) } }
            }
            read
        }
        val scroll = ScrollView(context).apply { addView(form) }
        questionDialog = android.app.AlertDialog.Builder(context).setTitle("助手需要你的回答").setView(scroll)
            .setNegativeButton("稍后回答", null).setPositiveButton("提交回答", null).create().apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                setOnDismissListener { questionDialog = null }
                show()
                getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val answers = readAnswers.map { it() }
                    if (answers.any { it.getJSONArray("selected").length() == 0 && it.optString("custom").isBlank() }) {
                        android.widget.Toast.makeText(context, "请回答每个问题", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                        // The conversation executor is waiting for this answer.
                        Thread({
                            val result = runCatching { chat.answerQuestion(question.getString("callId"), org.json.JSONArray(answers)) }
                            handler.post {
                                result.onSuccess { if (pendingQuestion?.optString("callId") == question.optString("callId")) pendingQuestion = null; dismiss() }
                                    .onFailure { getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true; android.widget.Toast.makeText(context, it.message ?: "回答提交失败，请重试", android.widget.Toast.LENGTH_LONG).show() }
                            }
                        }, "pet-question-answer").start()
                    }
                }
            }
    }
    /**
     * 盯住本会话后续的「后台投递回合」（Worker 回执 / 屏幕陪看唤醒）。
     *
     * 只在**本轮确实调用过 mobile_workers** 时布防：没有派工就没有回执，不必白跑。
     * 同一个窗口只允许一个监察器；换会话、开新话题、窗口销毁都会停掉。
     */
    private fun armDeliveryWatcher(turn: Int?) {
        deliveryWatcher?.stop(); deliveryWatcher = null
        val session = chat.currentSessionId() ?: return
        // 从「上次已经看到过的回合」和「本轮回合」里取较大的一个：重开气泡时不会把旧回执再推一遍，
        // 而进程重启后也能接着上次的位置继续盯。
        val startTurn = maxOf(turn ?: -1, deliveryInbox.lastSeenTurn())
        deliveryWatcher = CompanionDeliveryWatcher(
            context, chat, session, startTurn,
            onDelivery = { delivery -> handleDelivery(delivery) },
            onDone = { deliveryWatcher = null },
        ).also { it.start() }
    }

    /** 这个会话名下是否还有没结束的 Worker：有就说明后续一定会有回执要推回气泡。 */
    private fun hasActiveWorkers(): Boolean {
        val session = chat.currentSessionId() ?: return false
        return runCatching { WorkerDashboardStore(context).activeFor(session) }.getOrDefault(false)
    }

    /** 打开气泡时补一次布防：用户可能在派工后才重新打开气泡（或进程被杀后重开）。 */
    private fun armDeliveryWatcherIfNeeded() {
        if (deliveryWatcher != null) return
        if (hasActiveWorkers()) armDeliveryWatcher(null)
    }

    /** 结果到达：先落收件箱（进程重启后仍能看到），再按窗口状态刷新或弹出。 */
    private fun handleDelivery(delivery: Delivery) {
        val session = chat.currentSessionId() ?: return
        deliveryInbox.add(CompanionDeliveryInbox.Item(delivery.turn, delivery.text, System.currentTimeMillis(), session))
        deliveryInbox.rememberTurn(delivery.turn)
        handler.post {
            if (closed) return@post
            onState("reply")
            when {
                root != null -> { hide(); show() }
                autoOpenOnDelivery -> show()
            }
        }
    }

    fun openCapture() {
        if (closed || captureDialog != null) return
        fun dp(n: Int) = (n * context.resources.displayMetrics.density + .5f).toInt()
        val ui = PetUi(context)
        val counter = ui.text("", 12f, ui.muted).apply { gravity = Gravity.END }
        val field = ui.editor(EditText(context)).apply {
            hint = ui.string(R.string.pet_ui_note)
            minLines = 2; maxLines = 4
            doAfterTextChanged {
                val size = it?.length ?: 0
                counter.text = "$size / 8000"
                if (error != null && size in 1..CompanionKnowledgeData.MAX_RECORD_TEXT && !it.isNullOrBlank() && '\u0000' !in it) error = null
            }
            setText(draft); setSelection(length())
        }
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(2))
            addView(field)
            counter.setPadding(0, dp(5), 0, 0); addView(counter)
        }
        val dialog = ui.dialogBuilder("存入本地资料库", ui.string(R.string.pet_ui_capture_hint), R.drawable.ic_whale_nav_memory)
            .setView(ScrollView(context).apply { addView(form) }).setPositiveButton("保存文字", null).setNeutralButton("选文件", null)
            .setNegativeButton("取消", null).create()
        fun submit(pick: Boolean) {
            val value = field.text.toString()
            if (value.length > CompanionKnowledgeData.MAX_RECORD_TEXT || '\u0000' in value || (!pick && value.isBlank())) {
                field.error = "请填写有效文字，最多 8000 字。"; return
            }
            val result = runCatching {
                context.startActivity(Intent(context, WhaleMemoryShareActivity::class.java).apply {
                    action = if (pick) WhaleMemoryShareActivity.PICK else WhaleMemoryShareActivity.FROM_PET
                    if (value.isNotBlank()) putExtra(Intent.EXTRA_TEXT, value)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            if (result.isSuccess) dialog.dismiss()
            else field.error = "未能打开保存入口，请重试。"
        }
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.setOnDismissListener { if (captureDialog === dialog) captureDialog = null }
        captureDialog = dialog
        dialog.show()
        ui.finishDialog(dialog)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit(false) }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { submit(true) }
    }

    fun followPet() { positionUpdater?.invoke() }
    fun hide() { showLivePartials = false; recoveryDialog?.dismiss(); recoveryDialog = null; renderFollowups = null; if (!busy && followups.isEmpty()) sendNext = null; captureDialog?.dismiss(); captureDialog = null; renderLive = null; renderWorkerReport = null; workerPanel.detach(); questionDialog?.dismiss(); questionDialog = null; previewDialog?.dismiss(); previewDialog = null; if (!busy) onState("idle"); positionUpdater = null; root?.let { runCatching { wm.removeView(it) } }; root = null; richViews.forEach { (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() }; richViews.clear() }
    fun destroy() { toolWindows.destroy(); activeReply?.cancel(); activeReply=null; closed = true; flushPartial = null; WorkerReportInbox.unsubscribe(workerReportListener); request++; busy = false; deliveryWatcher?.stop(); deliveryWatcher = null; hide(); workerPanel.destroy(); intake.shutdownNow(); executor.shutdownNow() }
}
