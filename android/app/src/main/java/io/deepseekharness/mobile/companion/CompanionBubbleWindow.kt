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

/** Native floating chat; recent completed messages are restored across service restarts. */
internal class CompanionBubbleWindow(private val context: Context, private val wm: WindowManager, private val onState: (String) -> Unit = {}, private val openSession: (String) -> Unit = {}, private val petBounds: () -> IntArray) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val historyStore = CompanionBubbleHistory(context)
    private val anchorStore = CompanionSessionAnchorStore(context)
    private val restored = historyStore.load()
    private val history = restored?.messages?.toMutableList() ?: mutableListOf()
    private val chat = CompanionBubbleChat(context, restored)
    private var root: LinearLayout? = null
    private var previewDialog: android.app.AlertDialog? = null
    private var request = 0L
    private var busy = false
    private var closed = false
    private var draft = ""
    private var lastError: String? = null
    private var positionUpdater: (() -> Unit)? = null
    val visible: Boolean get() = root != null

    fun toggle() { if (visible) hide() else show() }
    fun show(opening: String? = null) {
        if (closed || visible) return
        val density = context.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val metrics = context.resources.displayMetrics
        val width = minOf(dp(320), metrics.widthPixels - dp(16))
        val pet = petBounds()
        var petOnRight = pet[0] + pet[2] / 2 >= metrics.widthPixels / 2
        // This estimate only selects the order before measuring; the final coordinates use actual height.
        var showAbove = pet[1] >= dp(175) || pet[1] + pet[3] + dp(175) > metrics.heightPixels
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = if (showAbove) Gravity.BOTTOM else Gravity.TOP }
        val scroll = ScrollView(context).apply { addView(content); isFillViewport = true }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, minOf(dp(460), (metrics.heightPixels * 0.55f).toInt())))
        fun styleBubble(view: TextView, isUser: Boolean) {
            view.setPadding(dp(13), dp(9), dp(13), dp(9))
            view.background = GradientDrawable().apply {
                setColor(if (isUser) Color.rgb(205, 235, 255) else Color.rgb(255, 248, 252))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), if (isUser) Color.rgb(164, 214, 241) else Color.rgb(243, 209, 225))
            }
        }
        fun atBottom(): Boolean = scroll.getChildAt(0)?.let { child ->
            child.bottom - (scroll.scrollY + scroll.height) <= dp(48)
        } ?: true
        fun bubble(message: String, isUser: Boolean): TextView {
            val shouldFollow = isUser || atBottom()
            val view = TextView(context).apply {
                text = message; textSize = 15f; setTextColor(Color.rgb(44, 54, 75)); tag = isUser; maxWidth = width - dp(45)
                styleBubble(this, isUser)
            }
            content.addView(view, LinearLayout.LayoutParams(-2, -2).apply { gravity = if (isUser) Gravity.END else Gravity.START; bottomMargin = dp(6) })
            if (shouldFollow) scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
            return view
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
            val coverage = when (citation.parsingCoverage) { "partial" -> " · 部分解析"; "not_parsed" -> " · 尚未解析"; else -> "" }
            val segment = CompanionBubbleChatPolicy.citationSegmentLabel(citation)
            val view = TextView(context).apply {
                text = "资料来源 · ${citation.displayName}$segment$coverage"
                textSize = 12f; setTextColor(Color.rgb(48, 91, 125)); maxWidth = width - dp(45)
                contentDescription = "回答资料来源：${citation.displayName}$segment$coverage"
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = GradientDrawable().apply { setColor(Color.rgb(238, 247, 255)); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.rgb(177, 213, 240)) }
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
        if (history.isEmpty()) bubble(opening ?: "嗨，想聊什么？", false)
        else history.takeLast(8).forEach {
            val parsed = if (it.role == "assistant") CompanionBubbleChatPolicy.parseInlineImages(it.text) else CompanionBubbleChatPolicy.InlineImages(it.text, emptyList())
            bubble(parsed.text, it.role == "user"); (it.images + parsed.images).distinct().take(6).forEach(::imageBubble); if (it.role == "assistant") it.citations.forEach(::citationCard)
        }
        val input = EditText(context).apply {
            hint = "说点什么…"; textSize = 15f; maxLines = 3; filters = arrayOf(android.text.InputFilter.LengthFilter(2000))
            setText(draft); setSelection(text.length)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { draft = s?.toString().orEmpty(); panel.post { positionUpdater?.invoke() } }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        val send = TextView(context).apply { text = "发送"; textSize = 16f; setPadding(dp(10), dp(12), dp(10), dp(12)) }
        val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(4), 0); background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(24).toFloat() } }
        val continueButton = TextView(context).apply {
            text = "主对话"; textSize = 13f; setPadding(dp(4), dp(12), dp(4), dp(12))
            contentDescription = "在主对话继续桌宠会话"
            isEnabled = !busy && chat.snapshot(history) != null
            setOnClickListener {
                if (!busy) chat.snapshot(history)?.sessionId?.let { id -> hide(); openSession(id) }
            }
        }
        val newTopic = TextView(context).apply {
            text = "新话题"; textSize = 13f; setPadding(dp(4), dp(12), dp(4), dp(12))
            contentDescription = "开始新话题并保留之前的聊天记录"
            isEnabled = !busy && (history.isNotEmpty() || lastError != null)
            setOnClickListener {
                if (busy || (history.isEmpty() && lastError == null)) return@setOnClickListener
                busy = true; send.isEnabled = false; input.isEnabled = false; isEnabled = false; continueButton.isEnabled = false
                val generation = ++request
                executor.execute {
                    val result = runCatching { historyStore.archiveAndClear(); chat.startNewTopic() }
                    handler.post {
                        if (closed || request != generation) return@post
                        busy = false
                        result.onSuccess { history.clear(); draft = ""; lastError = null }
                            .onFailure { lastError = "无法开启新话题，原聊天记录已保留" }
                        if (root != null) { hide(); show() }
                    }
                }
            }
        }
        footer.addView(input, LinearLayout.LayoutParams(0, -2, 1f)); footer.addView(send); footer.addView(continueButton); footer.addView(newTopic); footer.addView(TextView(context).apply { text = "×"; textSize = 22f; setPadding(dp(4), dp(5), dp(6), dp(5)); setOnClickListener { hide() } }); panel.addView(footer)
        val status = TextView(context).apply { text = ""; textSize = 12f; setTextColor(Color.rgb(80, 45, 45)); background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat() } }
        panel.addView(status)
        // Message bubbles always precede the input, regardless of the pet's screen position.
        status.text = if (busy) "正在回复…" else lastError.orEmpty()
        status.visibility = if (busy || lastError != null) android.view.View.VISIBLE else android.view.View.GONE
        if (busy) { status.text = "正在回复…"; send.isEnabled = false; input.isEnabled = false }
        var failedMessage: TextView? = null
        send.setOnClickListener {
            val message = runCatching { CompanionBubbleChatPolicy.validateMessage(input.text.toString()) }.getOrNull() ?: return@setOnClickListener
            if (busy) return@setOnClickListener
            failedMessage?.let { content.removeView(it) }; failedMessage = null
            lastError = null
            val sentBubble = bubble(message, true)
            busy = true; onState("thinking"); newTopic.isEnabled = false; continueButton.isEnabled = false; send.isEnabled = false; input.isEnabled = false; status.text = "正在回复…"; status.visibility = android.view.View.VISIBLE; panel.post { positionUpdater?.invoke() }
            val generation = ++request
            val prior = (history + CompanionBubbleChat.Message("user", message)).takeLast(19)
            executor.execute {
                val result = runCatching {
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
                    chat.reply(prior, scope, memoryAllowed, knowledgeAllowed)
                }
                handler.post {
                    if (closed || request != generation) return@post
                    val samePanel = root === panel
                    busy = false; send.isEnabled = true; input.isEnabled = true
                    result.onSuccess { answer ->
                        onState("reply")
                        history.add(CompanionBubbleChat.Message("user", message)); history.add(CompanionBubbleChat.Message("assistant", answer.text, answer.images, answer.citations))
                        while (history.size > 18) { history.removeAt(0); history.removeAt(0) }
                        chat.snapshot(history)?.let { snapshot -> executor.execute {
                            runCatching {
                                historyStore.save(snapshot)
                                // The anchor is intentionally lossy and is never sent back into Harness automatically.
                                fun summary(value: String) = value.replace(Regex("\\s+"), " ").trim().take(160)
                                anchorStore.append(snapshot.sessionId, "user", System.currentTimeMillis(), summary(message))
                                anchorStore.append(snapshot.sessionId, "assistant", System.currentTimeMillis(), summary(answer.text))
                            }
                        } }
                        draft = ""
                        if (samePanel) { bubble(answer.text, false); answer.images.forEach(::imageBubble); answer.citations.forEach(::citationCard); input.text.clear() }
                        status.text = ""; status.visibility = android.view.View.GONE
                    }.onFailure { error ->
                        onState("idle")
                        failedMessage = sentBubble
                        status.text = error.message?.takeIf { it.startsWith("本机") || it.startsWith("对话") || it.startsWith("回复") || it.startsWith("模型") }
                            ?: "发送失败，请检查本机服务和网络；输入已保留。"
                        lastError = status.text.toString()
                        status.visibility = android.view.View.VISIBLE
                    }
                    if (samePanel) { continueButton.isEnabled = chat.snapshot(history) != null; newTopic.isEnabled = history.isNotEmpty() || lastError != null }
                    if (!samePanel && root != null) { hide(); show() }
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
            val currentPet = petBounds()
            val inset = panel.rootWindowInsets
            val keyboard = if (android.os.Build.VERSION.SDK_INT >= 30 && inset?.isVisible(android.view.WindowInsets.Type.ime()) == true)
                inset.getInsets(android.view.WindowInsets.Type.ime()).bottom else 0
            val frame = android.graphics.Rect().also { panel.getWindowVisibleDisplayFrame(it) }
            val frameBottom = if (frame.bottom > 0) minOf(metrics.heightPixels, frame.bottom) else metrics.heightPixels
            val usable = minOf(frameBottom, metrics.heightPixels - keyboard)
            val desiredScroll = minOf(dp(460), (metrics.heightPixels * 0.55f).toInt(), (usable - dp(110)).coerceAtLeast(dp(120)))
            val scrollParams = scroll.layoutParams as LinearLayout.LayoutParams
            if (scrollParams.height != desiredScroll) { scrollParams.height = desiredScroll; scroll.layoutParams = scrollParams }
            panel.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, android.view.View.MeasureSpec.AT_MOST))
            val actualHeight = panel.measuredHeight.coerceIn(1, metrics.heightPixels)
            val point = io.deepseekharness.mobile.overlay.CompanionHintLayout.place(metrics.widthPixels, metrics.heightPixels,
                currentPet[0], currentPet[1], currentPet[2], currentPet[3], width, actualHeight, dp(2), dp(30))
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
            val insets = panel.rootWindowInsets
            val imeBottom = if (android.os.Build.VERSION.SDK_INT >= 30 && insets?.isVisible(android.view.WindowInsets.Type.ime()) == true)
                insets.getInsets(android.view.WindowInsets.Type.ime()).bottom else 0
            val visibleFrame = android.graphics.Rect().also { panel.getWindowVisibleDisplayFrame(it) }
            val visibleBottom = if (visibleFrame.bottom > 0) minOf(metrics.heightPixels, visibleFrame.bottom) else metrics.heightPixels
            val availableBottom = minOf(visibleBottom, metrics.heightPixels - imeBottom).coerceAtLeast(actualHeight)
            val newY = minOf(point.y, (availableBottom - actualHeight - dp(4)).coerceAtLeast(0))
            val moved = params.x != point.x || params.y != newY
            params.x = point.x; params.y = newY
            if (root === panel && moved) runCatching { wm.updateViewLayout(panel, params) }
            Unit
        }
        panel.setOnApplyWindowInsetsListener { view, insets ->
            view.post { if (root === panel) positionUpdater?.invoke() }
            view.onApplyWindowInsets(insets)
        }
        updatePosition()
        if (runCatching { wm.addView(panel, params) }.isSuccess) { root = panel; positionUpdater = updatePosition }
    }
    fun followPet() { positionUpdater?.invoke() }
    fun hide() { previewDialog?.dismiss(); previewDialog = null; if (!busy) onState("idle"); positionUpdater = null; root?.let { runCatching { wm.removeView(it) } }; root = null }
    fun destroy() { closed = true; request++; busy = false; hide(); executor.shutdownNow() }
}
