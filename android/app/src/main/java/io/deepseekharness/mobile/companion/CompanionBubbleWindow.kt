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
            PetIntentRouter.classify(key, model, messages, whileWaiting = true, briefings = WorkerReportInbox(context).prompt())
        }
    },
    private val parallelReply: (List<CompanionBubbleChat.Message>, org.json.JSONObject?, (String) -> Unit) -> String = { messages, evidence, partial -> PetDirectChat(context).readOnly(messages,evidence,partial) },
    private val receiveTask: ((String, String, String, String, String) -> org.json.JSONObject)? = null,
    private val replyTimeoutMillis: Long = 180_000,
    private val petBounds: () -> IntArray) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val replyExecutor = java.util.concurrent.ThreadPoolExecutor(0, 4, 30, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue()) { job: Runnable -> Thread(job,"pet-reply").apply { isDaemon = true } }
    private val anchorStore = CompanionSessionAnchorStore(context)
    private val restored = historyStore.load()
    private val history = restored?.messages?.toMutableList() ?: mutableListOf()
    private var chat = CompanionBubbleChat(context, restored, historyStore, ::dispatchTask)
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
    @Volatile private var request = 0L
    private var busy = false
    private var activeReply: PetReplyOperation? = null
    private class Followup(val text: String, val id: String, var deciding: Boolean = false, var receipt: String? = null) {
        val classification = PetReplyOperation(30_000)
        val replyOperation = PetReplyOperation(60_000)
        var handingOff = false
        @Volatile var parked = false
        var parallel = false
        @Volatile var live = ""
    }
    private val intakeLock = Any()
    private val journal = historyStore.followups
    private val recovery = runCatching { journal.entries() }
    private val restoredRequestId = restored?.pending?.let { org.json.JSONObject(it) }?.let { it.optString("followupId",it.getString("requestId")) }
    private val queuedStages = setOf("queued","handoff","answered")
    private val pendingRows = recovery.getOrDefault(emptyList()).filter { it.id != restoredRequestId &&
        it.topic != null && it.topic == restored?.topicId && it.stage in queuedStages }
    private val recovered = recovery.getOrDefault(emptyList()).filterNot { it in pendingRows || it.id == restoredRequestId }.toMutableList()
    private var failedFollowup: Followup? = recovery.getOrNull()?.firstOrNull {
        it.id == restoredRequestId && it.stage in setOf("queued", "reply")
    }?.let { Followup(it.text, it.id,receipt=it.receipt.takeIf(String::isNotBlank)) }
    private val recoveringDispatch = mutableSetOf<String>()
    private val followups = ArrayDeque(pendingRows.map { Followup(it.text,it.id,receipt=it.receipt.takeIf(String::isNotBlank)) })
    private val intake = java.util.concurrent.ThreadPoolExecutor(0, 1, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue())
    private var sendNext: (() -> Unit)? = null
    private var renderFollowups: (() -> Unit)? = null
    private var renderParallel: ((Followup) -> Unit)? = null
    @Volatile private var closed = false
    private var draft = chat.pendingUser.orEmpty()
    private var lastError: String? = if (chat.pendingDirect || chat.pendingStopped) "上一条回复未完成，点此重试。" else null
    private var inFlightUser: String? = null
    private var failedUser: String? = chat.pendingUser.takeIf { lastError != null }
    @Volatile private var lastProgressAt = 0L
    private val checkBacklog = object : Runnable {
        override fun run() {
            val operation = activeReply ?: return
            if (followups.isEmpty()) return
            val remaining = 30_000L - (android.os.SystemClock.uptimeMillis() - lastProgressAt)
            if (remaining > 0) handler.postDelayed(this,remaining)
            else operation.cancel("回复长时间没有进展，正在处理后续消息。")
        }
    }
    private fun armBacklog() {
        handler.removeCallbacks(checkBacklog)
        if (activeReply != null && followups.isNotEmpty()) handler.postDelayed(checkBacklog,30_000)
    }
    private var captureDialog: android.app.AlertDialog? = null
    private var positionUpdater: (() -> Unit)? = null
    private val workerPanel = CompanionWorkerPanel(context, { id -> hide(); openSession(id) }, onWorkerCounts, ::hide)
    private val workerInbox = WorkerReportInbox(context)
    private var renderWorkerReport: ((org.json.JSONObject) -> Boolean)? = null
    private var showRecentBriefings = false
    private val workerReportListener: (org.json.JSONObject) -> Unit = { report ->
        handler.post {
            if (!closed && report.optString("kind") == "coordination" &&
                workerInbox.unseen().any { it.optString("id") == report.optString("id") }) {
                if (visible) {
                    if (renderWorkerReport?.invoke(report) == true) onState("reply")
                } else {
                    onState("reply")
                    if (onBackgroundReport != null) onBackgroundReport.invoke(report.optString("state"))
                    else if (autoOpenOnDelivery) show()
                }
            }
        }
    }
    init {
        // A process can stop between journaling the failed turn and releasing the history slot.
        val parked = recovery.getOrNull()?.firstOrNull { it.id == restoredRequestId && it.stage in setOf("draft","host","dispatch","review") }
        if (parked != null) {
            runCatching { chat.parkPending(history) }.onSuccess {
                recovered.add(it); failedUser=null; failedFollowup=null; lastError=null; draft=""
            }.onFailure { lastError="对话恢复记录暂时不可写，点此重试。" }
        }
        WorkerReportInbox.subscribe(workerReportListener)
    }
    private val richViews = mutableListOf<android.webkit.WebView>()
    /** 后台投递回合（Worker 回执等）的本地收件箱；不参与对话历史与导出。 */
    private val deliveryInbox = CompanionDeliveryInbox(context)
    private var deliveryWatcher: CompanionDeliveryWatcher? = null
    val visible: Boolean get() = root != null

    private var inlineTool: PetInlineTool? = null
    private var selectedTool: io.deepseekharness.mobile.features.WhaleFeatureStore.Tool? = null
    private var presentTool: ((io.deepseekharness.mobile.features.WhaleFeatureStore.Tool) -> Unit)? = null
    fun openTool(tool: io.deepseekharness.mobile.features.WhaleFeatureStore.Tool) {
        selectedTool = tool
        if (!visible) show()
        presentTool?.invoke(tool)
    }
    fun toggle() { if (visible) hide() else show() }
    private fun dispatchTask(task: String, mode: String, id: String, title: String, needs: String): org.json.JSONObject =
        receiveTask?.invoke(task, mode, id, title, needs) ?: workerPanel.submit(task, mode, id, title, needs)
    fun composeTask() { if (!closed) { if (draft.isBlank()) draft = "我想请你帮我"; hide(); show() } }
    fun openTaskInbox() { if (!closed) { showRecentBriefings = true; hide(); show() } }
    fun refreshWorkerStatus() { workerPanel.refreshStatus() }

    /** Classify independently from the foreground transcript and its pending request. */
    private fun classifyFollowup(item: Followup, messages: List<CompanionBubbleChat.Message>) {
        val sourceSession = chat.handoffSessionId()
        val sourceRequest = request
        val completedHistory = history.toList()
        val speculation = PetRecallSpeculation(context,item.text.replace(Regex("\\s+")," ").take(100).dropLastWhile(Char::isHighSurrogate))
        item.deciding = true
        intake.execute {
            var receipt: String? = null
            runCatching {
                if (closed || item.parked) return@runCatching
                val decision = item.classification.run { classifyWaiting(messages) } ?: return@runCatching
                if (closed || item.parked || !decision.independent || request != sourceRequest) return@runCatching
                if (decision.intent in setOf(PetIntentRouter.Intent.TASK_STATUS,PetIntentRouter.Intent.TASK_CANCEL)) {
                    synchronized(intakeLock) {
                        if (item.parked) return@runCatching
                        item.handingOff = true
                    }
                    receipt = PetQuickControl.reply(context,chat.handoffSessionId(),decision)
                    journal.advance(item.id,"answered",requireNotNull(receipt))
                    item.parallel = true
                    return@runCatching
                }
                if (decision.intent in setOf(PetIntentRouter.Intent.CHAT,PetIntentRouter.Intent.KNOWLEDGE)) {
                    val evidence = if (decision.intent == PetIntentRouter.Intent.KNOWLEDGE) speculation.consume()
                        ?: CompanionRecall(context).recall(item.text.replace(Regex("\\s+")," ").take(100).dropLastWhile(Char::isHighSurrogate)) else null
                    synchronized(intakeLock) { if (item.parked) return@runCatching; item.parallel = true }
                    handler.post { if (!closed && !item.parked) renderParallel?.invoke(item) }
                    var postedAt = 0L
                    val answer = item.replyOperation.run { parallelReply(completedHistory + CompanionBubbleChat.Message("user",item.text),evidence) { partial ->
                        item.live = partial
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now-postedAt >= 80) { postedAt=now; handler.post { if (!closed && !item.parked) renderParallel?.invoke(item) } }
                    } }
                    if (closed || item.parked) return@runCatching
                    if (evidence != null && !CompanionRecall(context).isCurrent(evidence)) {
                        item.live = "资料或权限已变化，刚才的候选回复已作废，消息保留待重新核对。"
                        return@runCatching
                    }
                    journal.advance(item.id,"answered",answer)
                    item.parallel = true
                    receipt = answer
                    return@runCatching
                }
                if (sourceSession != chat.handoffSessionId()) return@runCatching
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
            }.onFailure {
                item.live = "这条回复尚未完成，消息已保留。"
                android.util.Log.d("whale-intake", "intake unavailable: ${it.javaClass.simpleName}")
            }
            speculation.close()
            handler.post {
                if (!closed) {
                    if (item.parked) return@post
                    item.receipt = receipt
                    item.deciding = false
                    if (item.parallel) renderParallel?.invoke(item)
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
        val task = plan.optString("task").ifBlank { text }
        val command = plan.optString("requestId").ifBlank { id }
        val receipt = if (denial != null) dashboard.hold(command,task,mode,needs,title,denial,source)
        else if (dashboard.isHeld(command)) "「$title」还缺少所需权限，我会说明需要你处理的部分。"
        else {
            val accepted = if (source == null) dispatchTask(task,mode,command,title,needs)
                else WorkerRuntimeWake.submit(context,task,mode,commandId=command,title=title,needs=needs,sourceSession=source)
            check(accepted.getString("commandId") == command)
            "「$title」我来跟进，有结果或需要你决定时会告诉你。"
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
            val result = runCatching { historyStore.archiveAndClear(); deliveryInbox.clear(); chat.resumeSession(sessionId); workerInbox.startTopic(sessionId) }
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
    private var activeOpening: PetInitiative.Opening? = null
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
            val available = (screen - dp(6)).coerceAtLeast(1)
            return when (widthMode) {
                "small" -> minOf(dp(380), (available * .86f).toInt())
                "large" -> available
                else -> minOf(dp(680), available)
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
            background = ui.shape(ui.surface, 22, ui.line)
            clipToOutline = true
            setPadding(0, 0, 0, 0)
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
        val workerBadge = ui.text("", 12f, accent, true).apply {
            gravity = Gravity.CENTER; minWidth = dp(34); minHeight = dp(34)
            maxWidth = minOf((width * 0.40f).toInt(), dp((124 * fontScale.coerceAtLeast(1f)).toInt()))
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0); contentDescription = "和小鲸聊聊近况"
            androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_cute_notifications)?.mutate()?.let { d ->
                val sz = dp(20)
                d.setBounds(0, 0, sz, sz)
                setCompoundDrawablesRelative(d, null, null, null)
                compoundDrawablePadding = dp(3)
            }
            background = ui.action(ui.soft, 18); setOnClickListener { openTaskInbox() }
        }
        val expandButton = android.widget.ImageView(context).apply {
            setImageResource(if (heightMode == "large") R.drawable.ic_cute_collapse else R.drawable.ic_cute_expand)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(5)
            setPadding(pad, pad, pad, pad)
            minimumWidth = dp(34); minimumHeight = dp(34)
            contentDescription = "切换桌宠大窗模式"
            background = ui.action(Color.TRANSPARENT, 12)
            isFocusable = true; isClickable = true
            setOnClickListener {
                heightMode = if (heightMode == "large") "auto" else "large"
                sizeStore.chatHeightMode = heightMode
                setImageResource(if (heightMode == "large") R.drawable.ic_cute_collapse else R.drawable.ic_cute_expand)
                positionUpdater?.invoke()
            }
        }
        var quickNewChatAction: (() -> Unit)? = null
        val newChatHeaderBtn = TextView(context).apply {
            text = "+"
            textSize = 19f
            setTextColor(ui.ink)
            gravity = Gravity.CENTER
            contentDescription = "新建对话"
            background = ui.action(Color.TRANSPARENT, 12)
            isFocusable = true; isClickable = true
            setOnClickListener { quickNewChatAction?.invoke() }
        }
        val headingEnd = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(workerBadge)
            addView(newChatHeaderBtn, LinearLayout.LayoutParams(dp(32), dp(34)).apply { marginStart = dp(4) })
            addView(expandButton, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(4) })
            addView(ui.close(::hide))
        }
        val grabber = android.view.View(context).apply {
            background = ui.shape(ui.line, 99)
        }
        panel.addView(grabber, LinearLayout.LayoutParams(dp(34), dp(3)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
            bottomMargin = dp(2)
        })
        val subtitle: String? = null
        panel.addView(ui.header(if (ui.usagi) "乌萨奇" else ui.string(R.string.pet_ui_name), subtitle, headingEnd))
        panel.addView(ui.divider())
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
        fun shortcut(@Suppress("UNUSED_PARAMETER") label: String, icon: Int, description: String, click: () -> Unit) {
            shortcuts.addView(android.widget.ImageView(context).apply {
                setImageResource(icon)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                val pad = dp(6)
                setPadding(pad, pad, pad, pad)
                minimumHeight = dp(40)
                contentDescription = description
                background = ui.action(Color.TRANSPARENT, 14)
                isFocusable = true; isClickable = true
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(0, dp(40), 1f))
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
        shortcut(ui.string(R.string.pet_menu_capture), R.drawable.ic_cute_memory, "存入本地资料库") { openCapture() }
        shortcut(ui.string(R.string.pet_menu_tools), R.drawable.ic_cute_workshop, ui.string(R.string.pet_menu_tools)) {
            sessionActions.visibility = android.view.View.GONE
            if (toolShelf.childCount > 0) toolShelf.removeAllViews()
            else toolShelf.addView(WhalePetToolShelf(context, { panel.post { positionUpdater?.invoke() } }, ::openTool, ::hide))
            syncControls()
            panel.post { positionUpdater?.invoke() }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
            gravity = if (showAbove) Gravity.BOTTOM else Gravity.TOP
        }
        val scroll = ScrollView(context).apply { addView(content); isVerticalScrollBarEnabled = false; setBackgroundColor(ui.canvas) }
        val bodyHost = android.widget.FrameLayout(context).apply {
            addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        panel.addView(bodyHost, LinearLayout.LayoutParams(-1, dp(120)))
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
        panel.addView(ui.divider()); panel.addView(shortcuts); panel.addView(controlsScroll)
        content.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) panel.post { if (root === panel) positionUpdater?.invoke() }
        }
        fun styleBubble(view: android.view.View, isUser: Boolean) {
            val round = dp(16).toFloat()
            val tip = dp(5).toFloat()
            if (isUser) {
                view.setPadding(dp(13), dp(9), dp(13), dp(9))
                view.background = ui.shape(Color.parseColor("#2F8AF5"), 16, null).apply {
                    cornerRadii = floatArrayOf(round, round, tip, tip, round, round, round, round)
                }
                if (view is TextView) view.setTextColor(Color.WHITE)
            } else {
                view.setPadding(dp(11), dp(9), dp(11), dp(9))
                val bgCol = if (ui.usagi) Color.parseColor(if (ui.dark) "#281E14" else "#FFFDF4") else Color.parseColor(if (ui.dark) "#142136" else "#FFFFFF")
                val strokeCol = if (ui.usagi) Color.parseColor(if (ui.dark) "#574028" else "#F2D48B") else Color.parseColor(if (ui.dark) "#283A57" else "#CBD5E1")
                val inkCol = if (ui.usagi) Color.parseColor(if (ui.dark) "#FEF3C7" else "#3B2613") else Color.parseColor(if (ui.dark) "#E6EEFC" else "#0F172A")
                view.background = ui.shape(bgCol, 16, strokeCol).apply {
                    cornerRadii = floatArrayOf(tip, tip, round, round, round, round, round, round)
                }
                if (view is TextView) {
                    view.setTextColor(inkCol)
                    view.setLineSpacing(dp(3).toFloat(), 1.16f)
                }
            }
        }
        fun atBottom(): Boolean = scroll.getChildAt(0)?.let { child ->
            child.bottom - (scroll.scrollY + scroll.height) <= dp(48)
        } ?: true
        var liveMotion = false
        fun bubble(message: String, isUser: Boolean): TextView {
            val shouldFollow = isUser || atBottom()
            val view = TextView(context).apply {
                text = CompanionRichText.render(message, ui); textSize = 15f; setTextColor(ui.ink); setLinkTextColor(ui.accent); tag = isUser; maxWidth = if (isUser) width - dp(36) else width - dp(18)
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                styleBubble(this, isUser)
            }
            content.addView(view, LinearLayout.LayoutParams(if (isUser) -2 else -1, -2).apply { gravity = if (isUser) Gravity.END else Gravity.START; bottomMargin = dp(5) })
            if(liveMotion) io.deepseekharness.mobile.WhaleMotion.enter(view)
            if (shouldFollow) scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
            return view
        }
        fun renderCompleted(view: TextView, message: String, isUser: Boolean) {
            // Ordinary short bubbles remain lightweight native text. Formulae and tables need
            // a proper offline renderer; streaming continues in the native TextView until done.
            if (!CompanionRichText.requiresRichRenderer(message)) {
                if (!isUser) {
                    view.text = CompanionRichText.render(message)
                }
                return
            }
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
        fun exportedFileOrImageCard(fileName: String) {
            val clean = fileName.trim().substringAfterLast('/').substringAfterLast('\\')
            if (clean.isEmpty() || root !== panel) return
            val ext = clean.substringAfterLast('.', "").lowercase()
            val shareDir = java.io.File(context.applicationContext.cacheDir, "share")
            val cachedFile = java.io.File(shareDir, clean)
            val isImage = ext in setOf("png", "jpg", "jpeg", "webp")
            val shouldFollow = atBottom()
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val bgCol = if (ui.dark) Color.parseColor("#142136") else Color.parseColor("#F8FAFC")
                val strokeCol = if (ui.dark) Color.parseColor("#2B446B") else Color.parseColor("#CBD5E1")
                background = ui.shape(bgCol, 14, strokeCol)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            if (isImage && cachedFile.isFile && cachedFile.length() in 1..12_000_000L) {
                val imgView = android.widget.ImageView(context).apply {
                    adjustViewBounds = true
                    maxWidth = width - dp(56)
                    maxHeight = dp(260)
                    contentDescription = clean
                    setOnClickListener {
                        runCatching { WhaleCodeRunner.openOrShareExportedFile(context, clean, "open") }
                    }
                }
                card.addView(imgView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
                executor.execute {
                    val bmp = runCatching {
                        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(cachedFile.absolutePath, opts)
                        val maxDim = maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(1)
                        val sample = (maxDim / 960).coerceAtLeast(1)
                        android.graphics.BitmapFactory.decodeFile(
                            cachedFile.absolutePath,
                            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                        )
                    }.getOrNull()
                    if (bmp != null) {
                        handler.post {
                            if (root === panel) {
                                imgView.setImageBitmap(bmp)
                                if (shouldFollow) scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
                                panel.post { positionUpdater?.invoke() }
                            } else bmp.recycle()
                        }
                    }
                }
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleView = ui.text("📎 $clean", 13f, if (ui.dark) Color.parseColor("#E6EEFC") else Color.parseColor("#0F172A"), bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val openBtn = TextView(context).apply {
                text = "打开"
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = ui.action(Color.parseColor("#2F8AF5"), 12)
                setOnClickListener { runCatching { WhaleCodeRunner.openOrShareExportedFile(context, clean, "open") } }
            }
            val shareBtn = TextView(context).apply {
                text = "分享"
                textSize = 12f
                setTextColor(if (ui.dark) Color.parseColor("#93C5FD") else Color.parseColor("#0369A1"))
                setPadding(dp(9), dp(4), dp(9), dp(4))
                background = ui.action(Color.TRANSPARENT, 12, ui.line)
                setOnClickListener { runCatching { WhaleCodeRunner.openOrShareExportedFile(context, clean, "share") } }
            }
            row.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            row.addView(shareBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(5) })
            row.addView(openBtn, LinearLayout.LayoutParams(-2, -2))
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            if (shouldFollow) scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
        fun conversationMessage(it: CompanionBubbleChat.Message) {
            val parsed = if (it.role == "assistant") CompanionBubbleChatPolicy.parseInlineImages(it.text) else CompanionBubbleChatPolicy.InlineImages(it.text, emptyList())
            val user = it.role == "user"
            bubble(parsed.text, user).also { view -> renderCompleted(view, parsed.text, user) }; (it.images + parsed.images).distinct().take(6).forEach(::imageBubble); if (!user) CompanionBubbleChatPolicy.citedSources(parsed.text, it.citations).forEach(::citationCard)
        }
        val renderedReports = mutableSetOf<String>()
        fun workerReport(report: org.json.JSONObject): Boolean {
            if (report.optString("kind") != "coordination" || !renderedReports.add(report.getString("id"))) return false
            val text = report.getString("text").trim()
            bubble(text, false).also { view -> renderCompleted(view, text, false) }
            return true
        }
        val display = workerInbox.forConversation(chat.presentationSessionId(),showRecentBriefings,chat.currentSessionId())
        val deliveries = deliveryInbox.load().filter { it.sessionId == chat.currentSessionId() }
        val timeline = mutableListOf<Pair<Long, () -> Unit>>()
        val existingTexts = history.map { "${it.role}:${it.text.trim()}" }.toMutableSet()
        history.takeLast(10).forEach { message -> timeline.add(message.atMs to { conversationMessage(message) }) }
        val shared = runCatching { WhaleSharedChatStore.list(context) }.getOrDefault(org.json.JSONArray())
        val sharedStart = (shared.length() - 16).coerceAtLeast(0)
        for (si in sharedStart until shared.length()) {
            val row = shared.optJSONObject(si) ?: continue
            val role = row.optString("role", "")
            val text = row.optString("text", "").trim()
            if (text.isEmpty() || (role != "user" && role != "assistant")) continue
            if (!existingTexts.add("$role:$text")) continue
            val atMs = row.optLong("atMs", 0L)
            val syncedMsg = CompanionBubbleChat.Message(role, text, emptyList(), emptyList(), atMs)
            // 同时合入 history，确保桌宠下一次提问完整继承 App 主界面的最新上下文
            history.add(syncedMsg)
            timeline.add(atMs to { conversationMessage(syncedMsg) })
        }
        history.sortBy { it.atMs }
        while (history.size > 20) history.removeAt(0)
        display.rows.forEach { report -> timeline.add(report.optLong("at") to { workerReport(report); Unit }) }
        deliveries.forEach { item -> timeline.add(item.atMs to {
            val parsed = CompanionBubbleChatPolicy.parseInlineImages(item.text)
            bubble(parsed.text, false).also { view -> renderCompleted(view, parsed.text, false) }
            parsed.images.forEach(::imageBubble)
        }) }
        if (history.isEmpty()) bubble(opening ?: CompanionPersonaStore(context).active().optString("greeting").ifBlank { "嗨，想聊什么？" }, false)
        timeline.sortedBy { it.first }.forEach { it.second() }
        if (history.isNotEmpty() && opening != null) bubble(opening, false)
        if (initiativeQuestion == null && opening == null && !busy && chat.pendingUser == null && (draft.isBlank() || resumeInitiativeDraft) &&
            CompanionStore(context).let { it.modelInitiative || it.contextFollowup }) {
            executor.execute {
                val pending = runCatching { PetInitiative.pendingOpening(context) }.getOrNull()
                handler.post {
                    if (pending != null && !closed && root === panel && !busy && (draft.isBlank() || resumeInitiativeDraft) &&
                        pending.allowed(context)) {
                        initiativeQuestion = pending.text
                        activeOpening = pending
                        bubble(pending.text, false)
                        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
                    }
                }
            }
        }
        if (showRecentBriefings && display.rows.isEmpty()) bubble("想了解哪件事的进展，直接告诉我。", false)
        showRecentBriefings = false
        liveMotion = true
        if (busy) {
            (inFlightUser ?: chat.pendingUser)?.let { bubble(it, true) }
            val live = bubble(mainQuestion ?: liveText.ifBlank { "•••" }, false)
            renderLive = { text -> live.text = text; panel.post { positionUpdater?.invoke() } }
        }
        val input = object : EditText(context) {
            override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
                val connection = super.onCreateInputConnection(outAttrs)
                outAttrs.imeOptions = outAttrs.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION.inv()
                return connection
            }
        }.apply {
            hint = ui.string(R.string.pet_ui_input); setTextColor(foreground); setHintTextColor(secondary)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
            background = null; setPadding(dp(10), dp(8), dp(2), dp(8)); minHeight = dp(44); textSize = 15f; maxLines = 3; filters = arrayOf(android.text.InputFilter.LengthFilter(2000))
            setText(draft); setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            var lastLines = 1
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { draft = s?.toString().orEmpty() }
                override fun afterTextChanged(s: android.text.Editable?) {
                    val lines = lineCount.coerceAtLeast(1)
                    if (lines != lastLines) {
                        lastLines = lines
                        panel.post { positionUpdater?.invoke() }
                    }
                }
            })
        }
        val send = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_cute_send)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
            contentDescription = ui.string(R.string.pet_ui_send)
            background = ui.action(ui.soft, 19)
            isFocusable = true; isClickable = true
            isEnabled = !busy || inFlightUser != null
        }
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(5), dp(4)); background = ui.shape(ui.canvas, 24, ui.line)
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
            isEnabled = !busy && (history.isNotEmpty() || lastError != null || renderedReports.isNotEmpty())
            setOnClickListener {
                if (busy || (history.isEmpty() && lastError == null && renderedReports.isEmpty())) return@setOnClickListener
                val canPark = synchronized(intakeLock) {
                    if (followups.any { it.deciding && it.handingOff } || recoveringDispatch.isNotEmpty()) false
                    else { followups.forEach { it.parked = true }; true }
                }
                if (!canPark) {
                    android.widget.Toast.makeText(context, "正在保存交办回执，请稍后再试。", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                followups.filter { it.deciding }.forEach { it.classification.cancel(); it.replyOperation.cancel() }
                val unsentDraft = draft.takeUnless { it == chat.pendingUser || it == failedUser }.orEmpty()
                busy = true; send.isEnabled = false; input.isEnabled = false; isEnabled = false; continueButton.isEnabled = false
                val generation = ++request
                executor.execute {
                    val result = runCatching {
                        // Preserve queued inputs for explicit review, never replay them into a different topic.
                        val retained = journal.entries()
                        historyStore.archiveAndClear(); deliveryInbox.clear(); chat.startNewTopic(); workerInbox.startTopic(null)
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
        renderWorkerReport = { report ->
            val page = workerInbox.forConversation(chat.presentationSessionId(),ownerSession=chat.currentSessionId())
            val id = report.getString("id")
            val added = id in page.localIds && workerReport(report)
            if (added) { workerInbox.displayed(page,setOf(id)); newTopic.isEnabled = !busy }
            added
        }
        sessionActions.addView(LinearLayout(context).apply {
            for (action in listOf(continueButton,newTopic)) {
                action.gravity = Gravity.CENTER; action.minHeight = dp(44)
                addView(action,LinearLayout.LayoutParams(0,-2,1f))
            }
        })
        val ttsBtn = android.widget.ImageView(context).apply {
            setImageResource(if (WhaleVoiceEngine.readConfig(context).autoSpeakPet) R.drawable.ic_cute_voice_on else R.drawable.ic_cute_voice_off)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
            contentDescription = "切换桌宠自动朗读"
            background = ui.action(Color.TRANSPARENT, 18)
            isFocusable = true; isClickable = true
            setOnClickListener {
                val cur = WhaleVoiceEngine.readConfig(context).autoSpeakPet
                val next = !cur
                WhaleVoiceEngine.saveConfig(context, autoSpeakPet = next)
                if (!next) WhaleVoiceEngine.stopSpeaking()
                setImageResource(if (next) R.drawable.ic_cute_voice_on else R.drawable.ic_cute_voice_off)
            }
        }
        val micBtn = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_cute_mic)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
            contentDescription = "语音输入"
            background = ui.action(Color.TRANSPARENT, 18)
            isFocusable = true; isClickable = true
        }
        quickNewChatAction = {
            if (!busy) {
                if (history.isNotEmpty() || lastError != null || renderedReports.isNotEmpty()) {
                    newTopic.performClick()
                } else {
                    android.widget.Toast.makeText(context, "当前已经是新对话啦", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        val quickSlashBar = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = android.view.View.GONE
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(2), dp(10), dp(2))
            }
            val presets = listOf(
                "总结当前屏幕" to "帮我看看当前手机屏幕上的内容并提炼重点。",
                "记一条待办" to "帮我记一条待办：",
                "查记忆" to "查一下我之前让你记住的偏好和近期重要事项。",
                "查后台任务" to "帮我看看当前有哪些后台任务在跑、各自进度如何。",
            )
            presets.forEach { (label, promptText) ->
                val chip = TextView(context).apply {
                    text = label
                    textSize = 12f
                    setTextColor(if (ui.dark) Color.parseColor("#DBEAFE") else Color.parseColor("#17324D"))
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    background = ui.action(if (ui.dark) Color.parseColor("#1D324C") else Color.parseColor("#EAF6FF"), 14, ui.line)
                    setOnClickListener {
                        visibility = android.view.View.GONE
                        input.setText(promptText)
                        input.setSelection(promptText.length)
                        if (!promptText.endsWith("：")) {
                            send.performClick()
                        } else {
                            input.requestFocus()
                        }
                    }
                }
                row.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            }
            addView(row)
        }
        val slashBtn = TextView(context).apply {
            text = "/"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ui.muted)
            gravity = Gravity.CENTER
            contentDescription = "快捷指令"
            background = ui.action(Color.TRANSPARENT, 16)
            isFocusable = true; isClickable = true
            setOnClickListener {
                quickSlashBar.visibility = if (quickSlashBar.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
                panel.post { positionUpdater?.invoke() }
            }
        }
        footer.addView(slashBtn, LinearLayout.LayoutParams(dp(30), dp(38)))
                footer.addView(micBtn, LinearLayout.LayoutParams(dp(38), dp(38)))
        footer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val mediaBtn = android.widget.TextView(context).apply {
            text = "+"
            textSize = 20f
            setTextColor(ui.ink)
            gravity = Gravity.CENTER
            contentDescription = "发送图片或视频"
            background = ui.shape(ui.soft, 18, ui.line)
            setOnClickListener {
                runCatching {
                    val intent = android.content.Intent(context, WhaleMemoryShareActivity::class.java)
                        .setAction(WhaleMemoryShareActivity.PICK)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
        }
        footer.addView(mediaBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(4) })
        shortcut(ui.string(R.string.pet_menu_session), R.drawable.ic_cute_chat, ui.string(R.string.pet_menu_session)) {
            toolShelf.removeAllViews()
            sessionActions.visibility = if (sessionActions.visibility == android.view.View.GONE) android.view.View.VISIBLE else android.view.View.GONE
            syncControls()
            controlsScroll.scrollTo(0,0)
            panel.post { positionUpdater?.invoke() }
        }
        footer.addView(send, LinearLayout.LayoutParams(dp(38), dp(38)))
        input.setOnEditorActionListener { _, action, key ->
            val enter = key?.keyCode == android.view.KeyEvent.KEYCODE_ENTER || key?.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            if (enter && android.view.inputmethod.BaseInputConnection.getComposingSpanStart(input.text) >= 0) return@setOnEditorActionListener false
            val submit = key == null && action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                enter && key != null && !key.isShiftPressed && !key.isAltPressed
            if (submit && (key == null || key.action == android.view.KeyEvent.ACTION_DOWN && key.repeatCount == 0) &&
                input.text.isNotBlank() && send.isEnabled) send.performClick()
            submit
        }
        val sendState = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val stateRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        sendState.addView(stateRow)
        val status = ui.text("",12f,secondary).apply {
            minHeight=dp(36); gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),dp(2),dp(4),dp(2))
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        stateRow.addView(status,LinearLayout.LayoutParams(0,-2,1f))
        micBtn.setOnClickListener {
            if (WhaleVoiceEngine.isRecording) {
                micBtn.alpha = 0.5f
                micBtn.isEnabled = false
                status.text = "✨ •••"; status.visibility = android.view.View.VISIBLE
                executor.execute {
                    val res = WhaleVoiceEngine.stopAndTranscribe(context)
                    handler.post {
                        micBtn.setImageResource(R.drawable.ic_cute_mic); micBtn.alpha = 1f
                        micBtn.isEnabled = true
                        res.onSuccess { spoken ->
                            status.visibility = android.view.View.GONE
                            input.setText(spoken)
                            input.setSelection(spoken.length)
                            if (send.isEnabled) send.performClick()
                        }.onFailure { err ->
                            status.text = err.message ?: "语音识别失败"
                            status.visibility = android.view.View.VISIBLE
                        }
                    }
                }
            } else {
                val err = WhaleVoiceEngine.startRecording(context)
                if (err != null) {
                    status.text = err
                    status.visibility = android.view.View.VISIBLE
                } else {
                    micBtn.setImageResource(R.drawable.ic_cute_stop)
                    status.text = "🔴 •••"
                    status.visibility = android.view.View.VISIBLE
                }
            }
        }
        val endWait = ui.text("结束等待", 13f, accent).apply {
            minHeight=dp(44); gravity=Gravity.CENTER
            contentDescription="结束等待并保留原消息；已提交的后台任务不会取消"
            visibility=android.view.View.GONE
            background=ui.action(ui.soft,12)
            setOnClickListener {
                activeReply?.cancel()
                text="正在结束等待…"; isEnabled=false
            }
        }
        stateRow.addView(endWait)
        val progress = ui.text("", 12f, secondary).apply {
            setPadding(dp(12), dp(4), dp(12), dp(5))
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val queueRow = LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        sendState.addView(queueRow)
        queueRow.addView(progress,LinearLayout.LayoutParams(0,-2,1f))
        val recoveryChip = ui.text("", 13f, accent).apply { minHeight = dp(44); gravity = Gravity.CENTER }
        queueRow.addView(recoveryChip)
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
                "draft" -> "这条消息未完成，可以重新发送。"
                "host" -> "原请求可能仍在主对话中执行，可打开原会话核对。"
                "handoff" -> entry.receipt.ifBlank { "已开始交办，请到后台任务核对接取状态。" }
                "answered" -> entry.receipt
                "review" -> entry.receipt
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
            } else if (entry.stage == "host") {
                builder.setPositiveButton("查看原会话") { _,_ -> entry.plan?.getString("sourceSession")?.let { hide(); openSession(it) } }
                    .setNeutralButton("已核对") { _,_ -> forget() }
            } else if (entry.stage == "queued" || entry.stage == "draft") {
                builder.setPositiveButton("继续这条消息") { _,_ ->
                    // A terminal Host turn cannot run again with its old ID. A user-selected resend
                    // gets a fresh ID, atomically replacing the parked draft in the journal.
                    val id = runCatching { journal.requeue(entry,chat.presentationSessionId()) }.getOrElse {
                        android.widget.Toast.makeText(context,"暂时无法恢复消息，请重试。",android.widget.Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    recovered.remove(entry); recoveryLabel()
                    followups.addLast(Followup(entry.text,id))
                    renderFollowups?.invoke(); sendNext?.invoke()
                }.setNeutralButton("移除消息") { _,_ -> forget() }
            } else {
                builder.setPositiveButton("已核对") { _,_ -> forget() }
                if (entry.stage == "handoff") builder.setNeutralButton("后台任务") { _,_ -> openTaskInbox() }
            }
            recoveryDialog = builder.create().apply { window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY); show() }
        }
        val progressDetails = ui.text("", 12f, secondary).apply { setPadding(dp(12),0,dp(12),dp(6)) }
        progressDetails.maxLines=2
        sendState.addView(progressDetails)
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
            val latest = followups.lastOrNull { it.receipt != null && !it.parallel }?.receipt
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
        stateRow.addView(answerQuestion)
        panel.addView(sendState)
        panel.addView(quickSlashBar, LinearLayout.LayoutParams(-1, -2))
        panel.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(4), dp(10), dp(9)) })
        // Message bubbles always precede the input, regardless of the pet's screen position.
        status.text = lastError.orEmpty()
        status.visibility = if (lastError != null) android.view.View.VISIBLE else android.view.View.GONE
        fun sendLabel() {
            send.alpha = if (send.isEnabled) 1f else 0.45f
        }
        sendLabel()
        if (busy) {
            when {
                pendingQuestion != null -> { status.text = "需要你回答；回答后会继续原任务。"; status.visibility = android.view.View.VISIBLE }
                mainQuestion != null -> { status.text = "需要你回答，请点“主对话”继续。"; status.visibility = android.view.View.VISIBLE }
                else -> { status.text = ""; status.visibility = android.view.View.GONE }
            }
            input.isEnabled = inFlightUser != null
        }
        var failedMessage: TextView? = null
        var retryText: String? = null
        var queued: Followup? = null
        val parallelBubbles = mutableMapOf<String,Pair<TextView,TextView>>()
        renderParallel = { item ->
            val answer = item.receipt ?: item.live.ifBlank { "正在另行回复…" }
            val pair = parallelBubbles.getOrPut(item.id) {
                bubble(item.text,true).also { renderCompleted(it,item.text,true) } to bubble(answer,false)
            }
            pair.second.text = if (item.receipt == null) answer else CompanionRichText.render(answer,ui)
            panel.post { positionUpdater?.invoke(); if (atBottom()) scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
        followups.filter { it.parallel }.forEach { renderParallel?.invoke(it) }
        fun releaseFailed(): Boolean {
            val failed = failedUser ?: chat.pendingUser ?: return true
            return runCatching {
                if (chat.pendingUser == null) chat.preserveDirectInput(history,failed,failedFollowup?.id)
                val entry = chat.parkPending(history)
                recovered.removeAll { it.id == entry.id }
                recovered.add(entry)
                recoveryLabel()
                if (draft == failed) { draft = ""; if (root === panel) input.text.clear() }
                failedUser = null; failedFollowup = null; lastError = null
                true
            }.getOrElse {
                lastError = "对话记录暂时无法保存，后续消息已保留，点此重试。"
                status.text = lastError; status.visibility = android.view.View.VISIBLE
                false
            }
        }
        sendNext = {
            if (!busy && !closed && followups.isNotEmpty() && releaseFailed()) {
                // Once the foreground slot is free, unclaimed speculative classifications
                // must not delay ordered delivery. Already dispatched work keeps its own ID.
                val item = followups.firstOrNull { entry -> synchronized(intakeLock) {
                    if (entry.deciding && !entry.handingOff && !entry.parallel) {
                        entry.parked = true; entry.deciding = false; entry.classification.cancel()
                    }
                    !entry.deciding
                } }
                if (item != null) {
                    followups.remove(item); queued = item
                    renderFollowups?.invoke()
                    send.performClick()
                }
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
                // 无感中途补充：直接注入正在运行的原生会话，无需弹出额外排队或状态提醒。
                if (busy && chat.steerNative(next)) {
                    if (root === panel) bubble(next, true).also { renderCompleted(it, next, true) }
                    input.text.clear()
                    sendLabel()
                    return@setOnClickListener
                }
                val id = runCatching { journal.accept(next,followups.lastOrNull()?.text ?: pendingTurn.orEmpty(),chat.presentationSessionId()) }.getOrElse {
                    android.widget.Toast.makeText(context,it.message?.takeIf { note -> note.startsWith("待处理消息已满") } ?: "无法保存待处理消息，输入已保留",android.widget.Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val item = Followup(next,id)
                if (busy && pendingTurn != null) {
                    val outstanding = (listOf(pendingTurn) + followups.map { it.text } + next).takeLast(6)
                    classifyFollowup(item, outstanding.map { CompanionBubbleChat.Message("user", it) })
                }
                followups.addLast(item)
                if (followups.size == 1) armBacklog()
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
                activeOpening?.allowed(context) == true }
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
            if (runCatching {
                chat.preserveDirectInput(history,message,item?.id)
                if (item != null) journal.advance(item.id,"reply",item.receipt.orEmpty())
            }.isFailure) {
                failedFollowup = item; failedUser = message
                lastError = "对话记录写入失败，请检查存储空间。输入已保留，点此重试。"
                status.text = lastError
                status.visibility = android.view.View.VISIBLE
                return@setOnClickListener
            }
            inFlightUser = message; failedUser = null; failedFollowup = null
            if (replyingTo != null) runCatching { ContextFollowupEvents.answered(context,message) }
            if (initiativeQuestion != null) PetInitiative.clearOpening(context)
            initiativeQuestion = null
            if (!fromQueue && (typed == message || replyingTo != null)) input.text.clear()
            failedMessage?.let { content.removeView(it) }; failedMessage = null
            lastError = null
            val parallelViews = item?.id?.let(parallelBubbles::remove)
            val sentBubble = parallelViews?.first ?: if (root === panel) bubble(message, true).also { renderCompleted(it,message,true) } else TextView(context)
            val waitRow = if (root === panel) LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val bgCol = if (ui.dark) Color.parseColor("#142136") else Color.parseColor("#F8FAFC")
                val strokeCol = if (ui.dark) Color.parseColor("#2B446B") else Color.parseColor("#CBD5E1")
                background = ui.shape(bgCol, 14, strokeCol)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            } else null
            val vectorSpinner = if (root === panel) WhaleVectorStatusView(context) else null
            val waitLabel = if (root === panel) ui.text("理解你的问题并规划步骤", 13f, if (ui.dark) Color.parseColor("#DBEAFE") else Color.parseColor("#17324D")).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            } else null
            if (waitRow != null && vectorSpinner != null && waitLabel != null) {
                waitRow.addView(vectorSpinner, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) })
                waitRow.addView(waitLabel, LinearLayout.LayoutParams(0, -2, 1f))
                content.addView(waitRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
            val streamingBubble = parallelViews?.second ?: if (root === panel) bubble("", false).apply { visibility = android.view.View.GONE } else TextView(context)
            liveText = ""
            val waitStartMs = android.os.SystemClock.uptimeMillis()
            val petPhaseRef = java.util.concurrent.atomic.AtomicReference("thinking|理解你的问题并规划步骤")
            fun updatePetWaitBar() {
                if (closed || !busy || liveText.isNotEmpty() || root !== panel) return
                val raw = petPhaseRef.get().orEmpty()
                val parts = raw.split('|', limit = 2)
                val cat = if (parts.size == 2) parts[0] else "thinking"
                val desc = when {
                    parts.size == 2 && parts[1].isNotBlank() -> parts[1]
                    raw.startsWith("step:") -> "已完成：${raw.removePrefix("step:").take(22)}"
                    else -> "分析当前进展，准备下一步"
                }
                val sec = ((android.os.SystemClock.uptimeMillis() - waitStartMs) / 1000L).coerceAtLeast(0L)
                vectorSpinner?.setCategoryColor(cat)
                waitLabel?.text = if (sec > 0L) "$desc  ·  ${sec}s" else desc
            }
            val waitTicker = object : Runnable {
                override fun run() {
                    if (closed || !busy || liveText.isNotEmpty()) return
                    updatePetWaitBar()
                    handler.postDelayed(this, 1000L)
                }
            }
            if (root === panel) handler.postDelayed(waitTicker, 1000L)
            val turnExportedFiles = java.util.Collections.synchronizedList(mutableListOf<String>())
            chat.onNativePhase = { phase ->
                if (phase.startsWith("step:导出文件") || phase.startsWith("step:渲染 PDF")) {
                    val fn = phase.substringAfter("：", "").ifBlank { phase.substringAfterLast(":", "") }.trim()
                    if (fn.isNotEmpty()) turnExportedFiles.add(fn)
                }
                petPhaseRef.set(phase)
                handler.post { updatePetWaitBar() }
            }
            if (root === panel) renderLive = { text ->
                handler.removeCallbacks(waitTicker)
                waitRow?.visibility = android.view.View.GONE
                streamingBubble.visibility = android.view.View.VISIBLE
                streamingBubble.text = text
                panel.post { positionUpdater?.invoke() }
            }
            mainQuestion = null
            busy = true; onState("thinking"); newTopic.isEnabled = false; continueButton.isEnabled = chat.handoffSessionId() != null; sendLabel(); status.text = ""; status.visibility = android.view.View.GONE; panel.post { positionUpdater?.invoke() }
            val generation = ++request
            chat.onInteractionNeeded = { question -> handler.post {
                if (!closed && busy && request == generation) {
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
                if (!closed && busy && request == generation) {
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
                        if (pendingQuestion == null) { status.text = ""; status.visibility = android.view.View.GONE }
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
            val operation = PetReplyOperation(replyTimeoutMillis)
            val turnChat = chat
            activeReply = operation
            lastProgressAt = android.os.SystemClock.uptimeMillis()
            armBacklog()
            endWait.visibility=android.view.View.GONE
            operation.submit(replyExecutor, {
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
                            lastProgressAt = android.os.SystemClock.uptimeMillis()
                            latestPartial.set(partial)
                            schedulePartial()
                        }
                    }
                    val answer = item?.receipt?.let { receipt ->
                        val answer = CompanionBubbleChat.Reply(receipt, emptyList(), emptyList())
                        turnChat.finishReply(prior,answer)
                    } ?: replyRunner(turnChat, prior, scope, memoryAllowed, knowledgeAllowed, onPartial)
                    // A failed cleanup leaves a reviewable entry. The completed turn must not be retried.
                    if (item != null) PetReplyOperation.whileActive { runCatching { journal.remove(item.id) } }
                    answer
            }) { result ->
                handler.post {
                    if (closed || request != generation) return@post
                    if (activeReply === operation) activeReply=null
                    handler.removeCallbacks(checkBacklog)
                    endWait.visibility=android.view.View.GONE
                    acceptingPartials.set(false); latestPartial.set(null)
                    flushPartial = null
                    val samePanel = root === panel
                    mainQuestion = null; inFlightUser = null; busy = false; send.isEnabled = true; input.isEnabled = true
                    result.onSuccess { answer ->
                        pendingQuestion = null; questionDialog?.dismiss(); questionDialog = null; answerQuestion.visibility = android.view.View.GONE
                        onState("reply")
                        if (WhaleVoiceEngine.readConfig(context).autoSpeakPet) {
                            WhaleVoiceEngine.speakText(context, answer.text)
                        }
                        history.add(prior.last()); history.add(answer.message())
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
                        if (samePanel) {
                            streamingBubble.text = CompanionRichText.render(answer.text, ui)
                            renderCompleted(streamingBubble, answer.text, false)
                            answer.images.forEach(::imageBubble)
                            val exportedSnapshot = synchronized(turnExportedFiles) { turnExportedFiles.distinct() }
                            exportedSnapshot.forEach(::exportedFileOrImageCard)
                            answer.citations.take(2).forEach(::citationCard)
                            if (answer.suggestions.isNotEmpty()) {
                                val row = android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
                                val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                                answer.suggestions.take(3).forEach { opt ->
                                    val chip = TextView(context).apply {
                                        text = "✦ $opt"
                                        textSize = 12.5f
                                        setTextColor(ui.accent)
                                        setPadding(dp(11), dp(6), dp(11), dp(6))
                                        background = android.graphics.drawable.GradientDrawable().apply {
                                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                            cornerRadius = 999f
                                            setColor(Color.argb(24, Color.red(ui.accent), Color.green(ui.accent), Color.blue(ui.accent)))
                                            setStroke(dp(1).coerceAtLeast(1), Color.argb(96, Color.red(ui.accent), Color.green(ui.accent), Color.blue(ui.accent)))
                                        }
                                        setOnClickListener {
                                            content.removeView(row)
                                            input.setText(opt)
                                            send.performClick()
                                        }
                                    }
                                    strip.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
                                }
                                row.addView(strip)
                                content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(6) })
                                scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
                            }
                        }
                        status.text = ""; status.visibility = android.view.View.GONE
                    }.onFailure { error ->
                        // Detach the old runner from UI state even if native cancellation has
                        // not returned. Only its last durable pending ID can be recovered.
                        chat = CompanionBubbleChat(context,historyStore.load(),historyStore,::dispatchTask)
                        runCatching { chat.markStopped(history,error is BubbleTurnFailed) }
                        pendingQuestion = null; questionDialog?.dismiss(); questionDialog = null
                        answerQuestion.visibility = android.view.View.GONE
                        onState("idle")
                        failedMessage = sentBubble; failedUser = message; failedFollowup = item
                        if (draft.isEmpty()) {
                            draft = message
                            if (samePanel) { input.setText(message); input.setSelection(input.length()) }
                        }
                        if (samePanel) content.removeView(streamingBubble)
                        status.text = "⚠️ ↻" 
                        lastError = status.text.toString()
                        status.visibility = android.view.View.VISIBLE
                    }
                    if (samePanel) { continueButton.isEnabled = chat.handoffSessionId() != null; newTopic.isEnabled = history.isNotEmpty() || lastError != null }
                    if (!samePanel && root != null) { hide(); show() }
                    sendLabel()
                    sendNext?.invoke()
                    if (root == null && !busy && followups.isEmpty()) sendNext = null
                    panel.post { positionUpdater?.invoke() }
                }
            }
        }
        val toolView = PetInlineTool(context, back = {
            inlineTool?.pause(); selectedTool = null
            io.deepseekharness.mobile.WhaleMotion.enter(scroll)
            scroll.visibility = android.view.View.VISIBLE
            shortcuts.visibility = android.view.View.VISIBLE
            footer.visibility = android.view.View.VISIBLE
            sendState.visibility = android.view.View.VISIBLE
            syncControls()
            panel.post { positionUpdater?.invoke() }
        }, sendResult = { result -> input.setText(result); input.setSelection(input.text.length) })
        inlineTool = toolView
        toolView.visibility = android.view.View.GONE
        bodyHost.addView(toolView, android.widget.FrameLayout.LayoutParams(-1,-1))
        presentTool = { tool ->
            scroll.visibility = android.view.View.GONE
            shortcuts.visibility = android.view.View.GONE
            controlsScroll.visibility = android.view.View.GONE
            footer.visibility = android.view.View.GONE
            sendState.visibility = android.view.View.GONE
            toolView.open(tool)
            io.deepseekharness.mobile.WhaleMotion.enter(toolView)
            panel.post { positionUpdater?.invoke() }
        }
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(8)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val updatePosition = {
            val metrics = context.resources.displayMetrics
            val stableBounds = if (android.os.Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds
                else android.graphics.Rect(0,0,metrics.widthPixels,metrics.heightPixels)
            val currentPet = petBounds()
            val inset = panel.rootWindowInsets
            val keyboard = if (android.os.Build.VERSION.SDK_INT >= 30 && inset?.isVisible(android.view.WindowInsets.Type.ime()) == true)
                inset.getInsets(android.view.WindowInsets.Type.ime()).bottom else 0
            val frame = android.graphics.Rect().also { panel.getWindowVisibleDisplayFrame(it) }
            val screenWidth = stableBounds.width()
            val nextWidth = windowWidth(screenWidth)
            val resized = nextWidth != width
            if (resized) {
                width = nextWidth
                params.width = width
                workerBadge.maxWidth = minOf((width * .43f).toInt(), dp((134 * fontScale.coerceAtLeast(1f)).toInt()))
                val bodyWidth = (width - dp(18)).coerceAtLeast(1)
                for (index in 0 until content.childCount) {
                    when (val child = content.getChildAt(index)) {
                        is TextView -> child.maxWidth = bodyWidth
                        is android.widget.ImageView -> child.maxWidth = bodyWidth
                        is android.webkit.WebView -> child.layoutParams = child.layoutParams.apply { this.width = bodyWidth }
                    }
                }
            }
            val bars = if (android.os.Build.VERSION.SDK_INT >= 30) inset?.getInsets(android.view.WindowInsets.Type.systemBars())?.bottom ?: 0 else 0
            val imeFromFrame = if (frame.bottom in 200 until stableBounds.height()) stableBounds.height() - frame.bottom else 0
            val effectiveKeyboard = maxOf(keyboard, imeFromFrame)
            val usable = stableBounds.height() - maxOf(effectiveKeyboard, bars)
            val originTop=frame.top.coerceAtLeast(0)
            val available=(usable-originTop-dp(8)).coerceAtLeast(1)
            val anchorY=currentPet[1]-originTop
            val gap = dp(8)
            val hasSideRoom = maxOf(currentPet[0], screenWidth - currentPet[0] - currentPet[2]) >= width + gap
            val availableHeight = if (hasSideRoom) available else minOf(available, maxOf(anchorY, available-anchorY-currentPet[3])-gap)
            val measureWidth = android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY)
            panel.measure(measureWidth, android.view.View.MeasureSpec.UNSPECIFIED)
            // Reserve the header, shortcuts and composer before limiting expandable controls.
            var mandatory=panel.paddingTop+panel.paddingBottom
            for(i in 0 until panel.childCount) {
                val child=panel.getChildAt(i)
                if(child!==bodyHost && child!==controlsScroll && child.visibility!=android.view.View.GONE) mandatory+=child.measuredHeight
            }
            val nextLimit=PetChatHeight.controls(available,mandatory,density)
            if(controlLimit!=nextLimit) {
                controlLimit=nextLimit
                controlsScroll.requestLayout()
                panel.measure(measureWidth,android.view.View.MeasureSpec.UNSPECIFIED)
            }
            val chrome=mandatory+if(controlsScroll.visibility==android.view.View.GONE) 0 else controlsScroll.measuredHeight
            val toolOpen = toolView.visibility == android.view.View.VISIBLE
            val effectiveAvailHeight = if (toolOpen) available else availableHeight
            val natural = if (toolOpen) (available * 0.78f).toInt().coerceAtLeast(dp(420)) else content.measuredHeight.takeIf { it > 0 } ?: dp(64)
            val targetHeight = if (toolOpen) {
                minOf(available, maxOf(natural + chrome, (available * 0.80f).toInt()))
            } else {
                PetChatHeight.total(available, effectiveAvailHeight, chrome, natural, heightMode,
                    stableBounds.height() > stableBounds.width(), effectiveKeyboard > 0, false, density)
            }
            val desiredScroll = (targetHeight - chrome).coerceIn(minOf(dp(44), (available - chrome).coerceAtLeast(1)), (available - chrome).coerceAtLeast(1))
            val scrollParams = bodyHost.layoutParams as LinearLayout.LayoutParams
            val heightChanged = scrollParams.height != desiredScroll
            if (heightChanged) { scrollParams.height = desiredScroll; bodyHost.layoutParams = scrollParams }
            panel.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(available, android.view.View.MeasureSpec.AT_MOST))
            val actualHeight = panel.measuredHeight.coerceIn(1, available)
            val point = android.graphics.Point(0, dp(8))
            val placedAbove = false
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
            if (root === panel && (moved || resized || heightChanged)) runCatching { wm.updateViewLayout(panel, params) }
            Unit
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            panel.setWindowInsetsAnimationCallback(object : android.view.WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(insets: android.view.WindowInsets, animations: MutableList<android.view.WindowInsetsAnimation>): android.view.WindowInsets {
                    panel.post { if (root === panel) positionUpdater?.invoke() }
                    return insets
                }
                override fun onEnd(animation: android.view.WindowInsetsAnimation) {
                    panel.post { if (root === panel) positionUpdater?.invoke() }
                    panel.postDelayed({ if (root === panel) positionUpdater?.invoke() }, 48)
                }
            })
        }
        panel.setOnApplyWindowInsetsListener { view, insets ->
            view.post { if (root === panel) positionUpdater?.invoke() }
            view.onApplyWindowInsets(insets)
        }
        panel.addOnLayoutChangeListener { _,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom ->
            if(right-left!=oldRight-oldLeft || bottom-top!=oldBottom-oldTop)
                panel.post {if(root===panel) positionUpdater?.invoke()}
        }
        updatePosition()
        io.deepseekharness.mobile.WhaleMotion.overlay(params)
        if (runCatching { wm.addView(panel, params) }.isSuccess) {
            root = panel; positionUpdater = updatePosition
            if (!CompanionStore(context).reduceMotion) {
                panel.alpha = 0f
                panel.translationY = dp(26).toFloat()
                panel.scaleX = 0.94f
                panel.scaleY = 0.94f
                panel.pivotX = width * 0.5f
                panel.pivotY = dp(220).toFloat()
                panel.animate()
                    .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(240L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.08f))
                    .start()
            }
            selectedTool?.let { presentTool?.invoke(it) }
            showLivePartials = context.getSystemService(android.os.PowerManager::class.java).isInteractive
            if (showLivePartials) flushPartial?.invoke()
            workerInbox.displayed(display,renderedReports)
            deliveryInbox.markSeen(deliveries)
            workerPanel.refreshStatus()
            executor.execute { chat.prewarm() }
            armDeliveryWatcherIfNeeded()
            if (!busy) handler.post {
                if (root === panel && !busy) {
                    if (followups.isNotEmpty()) sendNext?.invoke()
                    else if (lastError == null && !chat.pendingDirect && chat.pendingUser != null) {
                        retryText = chat.pendingUser; send.performClick()
                    }
                }
            }
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
    fun hide() { inlineTool?.destroy(); inlineTool = null; presentTool = null; selectedTool = null; showLivePartials = false; recoveryDialog?.dismiss(); recoveryDialog = null; renderFollowups = null; renderParallel = null; if (!busy && followups.isEmpty()) sendNext = null; captureDialog?.dismiss(); captureDialog = null; renderLive = null; renderWorkerReport = null; workerPanel.detach(); questionDialog?.dismiss(); questionDialog = null; previewDialog?.dismiss(); previewDialog = null; if (!busy) onState("idle"); positionUpdater = null; val closing = root; root = null; closing?.let { v ->
            if (!CompanionStore(context).reduceMotion && v.isAttachedToWindow) {
                v.animate().cancel()
                v.animate()
                    .alpha(0f).translationY(20f * context.resources.displayMetrics.density).scaleX(0.95f).scaleY(0.95f)
                    .setDuration(150L)
                    .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
                    .withEndAction { runCatching { if (v.isAttachedToWindow) wm.removeView(v) } }
                    .start()
            } else {
                runCatching { wm.removeView(v) }
            }
        }; richViews.forEach { (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() }; richViews.clear() }
    fun destroy() { inlineTool?.destroy(); activeReply?.cancel(); activeReply=null; closed = true; handler.removeCallbacks(checkBacklog); followups.forEach { it.classification.cancel(); it.replyOperation.cancel() }; flushPartial = null; WorkerReportInbox.unsubscribe(workerReportListener); request++; busy = false; deliveryWatcher?.stop(); deliveryWatcher = null; hide(); workerPanel.destroy(); intake.shutdownNow(); replyExecutor.shutdownNow(); executor.shutdownNow() }
}
