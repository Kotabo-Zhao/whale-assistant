package io.deepseekharness.mobile.features

import android.app.Activity
import android.content.Context
import android.content.Intent
import io.deepseekharness.mobile.companion.PetUi
import androidx.core.widget.doAfterTextChanged
import io.deepseekharness.mobile.companion.WorkerRuntimeWake
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

internal fun WebView.loadWhaleTool(id: String, html: String, preview: Boolean = false, ready: (WebView) -> Unit = {}, gone: () -> Unit) {
    settings.apply {
        javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
        blockNetworkLoads = true; javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(true); domStorageEnabled = true
    }
    webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) = ready(view)
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
            WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean { gone(); return true }
    }
    val policy = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'; frame-src 'none'"
    val origin = if (!preview) "https://$id.features.whale.invalid/" else "https://$id.preview.whale.invalid/"
    loadDataWithBaseURL(origin, "<meta charset=utf-8><meta http-equiv=\"Content-Security-Policy\" content=\"$policy\"><meta name=viewport content=\"width=device-width,initial-scale=1\">" + html, "text/html", "utf-8", null)
}

/** Independently authored local tools run outside the Capacitor management WebView. */
open class WhaleFeatureActivity : Activity() {
    private val store by lazy { WhaleFeatureStore(this) }
    private val native by lazy { NativeFeatureRegistry(this) }
    private val taskRuntime by lazy { WorkerRuntimeWake(applicationContext) }
    private val executor = Executors.newSingleThreadExecutor()
    private var page: WebView? = null
    private var generation = 0
    private var direct = false
    private var openedId: String? = null
    private var openedRevision = ""
    private var preview = false
    private var replacement: String? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val onActivation: (String,String) -> Unit = { id,active ->
        main.post {
            if (!isDestroyed && !isFinishing && openedId == id && !preview &&
                (replacement != null || openedRevision != active)) showReplacement(id,active)
        }
    }
    private var review: android.app.AlertDialog? = null
    private val ui by lazy { PetUi(this) }
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WhaleFeatureStore.subscribe(onActivation)
        direct = savedInstanceState?.getBoolean("direct") ?: intent.hasExtra(EXTRA_ID)
        val id = savedInstanceState?.getString("featureId") ?: intent.getStringExtra(EXTRA_ID)
        val revision = savedInstanceState?.getString("revision") ?: intent.getStringExtra(EXTRA_REVISION)
        if (id == null || revision == null) library()
        else if (savedInstanceState?.containsKey("replacement") == true) {
            openedId = id; openedRevision = revision
            read({ store.read(id)?.optString("active").orEmpty() }) { showReplacement(id,it) }
        } else open(id,revision,savedInstanceState?.getBoolean("preview") ?: intent.getBooleanExtra("draftPreview",false))
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putString("featureId", openedId); state.putString("revision", openedRevision); state.putBoolean("preview", preview)
        state.putBoolean("direct",direct); replacement?.let { state.putString("replacement",it) }
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)) }
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(ui.ink); setPadding(dp(4), dp(5), dp(4), dp(5))
        if (size >= 18f) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; minHeight = dp(44); setTextColor(ui.accent)
        background = ui.action(ui.soft,12)
        setOnClickListener { action() }
    }
    private fun <T> read(action: () -> T, failed: () -> Unit = {
        Toast.makeText(this, "无法读取或更新功能，请刷新后重试", Toast.LENGTH_LONG).show()
    }, show: (T) -> Unit) {
        val selected = ++generation
        executor.execute {
            val result = runCatching(action)
            runOnUiThread { if (!isDestroyed && !isFinishing && selected == generation) result.onSuccess(show)
                .onFailure { failed() } }
        }
    }
    private fun releasePage() {
        val previous = page
        page = null
        previous?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() }
    }
    private fun library() {
        releasePage()
        openedId = null; replacement = null; direct = false
        val body = column()
        setContentView(ScrollView(this).apply { addView(body); setBackgroundColor(ui.surface) })
        body.addView(ui.header("功能工坊",icon=io.deepseekharness.mobile.R.drawable.ic_pet_assign))
        body.addView(label("在 Harness 对话中说「为鲸鱼助手创建一个……」。生成的工具会出现在这里，可预览、启用或恢复上一版。", 15f))
        body.addView(button("刷新功能库") { library() })
        body.addView(button("导入原生功能清单") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 441)
        })
        read({ store.list() to native.entries() }) { (items, candidates) ->
            if (items.isEmpty() && candidates.isEmpty()) body.addView(label("还没有自建功能", 18f))
            candidates.forEach { entry ->
                val expected = native.prefs.getString("active." + entry.id, null)
                val enabled = expected == entry.revision
                val card = column().apply { background = ui.shape(ui.canvas,16,ui.line) }
                body.addView(card, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(12) })
                card.addView(label(entry.title,20f))
                card.addView(label("原生窗口 · " + entry.revision.take(8) + if (enabled) " · 已启用" else "",14f))
                card.addView(button("预览原生窗口") { read({ native.verify(entry) }) { intent ->
                    runCatching { startActivity(intent) }.onFailure { Toast.makeText(this,"无法打开原生窗口",Toast.LENGTH_SHORT).show() }
                } })
                card.addView(button(if (enabled) "移出桌宠" else "启用到桌宠") {
                    read({ native.select(entry,expected,!enabled) }) { library() }
                })
            }
            for (item in items) {
                val id = item.getString("id")
                val active = item.getString("active"); val latest = item.getString("latest"); val previous = item.getString("previous")
                val card = column().apply { background = ui.shape(ui.canvas,16,ui.line) }
                body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }); card.addView(label(item.getString("title"), 20f))
                if (active.isNotEmpty()) {
                    if (active != latest) card.addView(label("当前启用 · " + item.getString("activeTitle"), 14f))
                    card.addView(button("打开功能") { open(id, active, false) })
                }
                if (latest != active) {
                    card.addView(button("预览新版本") { open(id, latest, true) })
                    card.addView(button("启用此版本") { read({ store.activate(id, latest, active) }) { library() } })
                }
                if (previous.isNotEmpty() && active != previous) card.addView(button("恢复上一版") { read({ store.activate(id, previous, active) }) { library() } })
                if (active.isNotEmpty()) card.addView(button("停用") { read({ store.activate(id, "", active) }) { library() } })
            }
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode != 441 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        read({
            require(uri.scheme == "content")
            val bytes = ByteArray(8193)
            val count = requireNotNull(contentResolver.openInputStream(uri)).use { stream ->
                var used = 0
                while (used < bytes.size) {
                    val n = stream.read(bytes, used, bytes.size - used)
                    if (n <= 0) break
                    used += n
                }
                used
            }
            require(count in 1..8192)
            native.import(String(bytes,0,count,Charsets.UTF_8))
        }, failed = { Toast.makeText(this,"请先安装已审查的候选 APK，再选择与它匹配的 preview.json。",Toast.LENGTH_LONG).show() }) { library() }
    }

    private fun showReplacement(id: String, active: String) {
        generation++
        review?.dismiss()
        releasePage()
        replacement = active
        val body = column().apply { setBackgroundColor(ui.surface) }
        setContentView(body)
        body.addView(label(if (active.isEmpty()) "此工具已停用" else "工具版本已变更",20f))
        if (active.isNotEmpty()) body.addView(button("打开当前版本") { open(id,active,false) })
        body.addView(button(if (direct) "返回" else "返回工坊") { if (direct) finish() else library() })
    }
    private fun open(id: String, requested: String, isPreview: Boolean) {
        openedId = id; openedRevision = requested; preview = isPreview; replacement = null
        read({
            val row = requireNotNull(store.read(id))
            val revision = if (isPreview) requested else row.optString("active")
            check(revision.isNotEmpty())
            Triple(revision, row.getJSONObject("versions").getJSONObject(revision), row.optString("active"))
        }, failed = {
            Toast.makeText(this, if (isPreview) "草稿已更新，请重新选择" else "此功能已停用", Toast.LENGTH_LONG).show()
            library()
        }) { (version, content, previousActive) ->
            openedId = id; openedRevision = version; preview = isPreview
            if (requested != version) Toast.makeText(this, "已打开当前启用的版本", Toast.LENGTH_SHORT).show()
            releasePage()
            val body = column().apply { setBackgroundColor(ui.surface) }
            val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(6)) }
            bar.addView(button(if (direct) "返回" else "返回工坊") { if (direct) finish() else library() })
            bar.addView(label(content.getString("title"), 18f).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val collect = button("使用结果") { reviewResult(id, version, content.getString("title")) }.apply {
                visibility = if (!isPreview && content.getString("html").contains("data-whale-result", ignoreCase = true))
                    android.view.View.VISIBLE else android.view.View.GONE
            }
            bar.addView(collect)
            if (direct && isPreview && version != previousActive) {
                bar.addView(button("启用草稿") {
                    read({ store.publishPreview(id, version, previousActive) }, failed = {
                        Toast.makeText(this, "草稿或启用版本已变化，请回工坊核对", Toast.LENGTH_LONG).show()
                        library()
                    }) { open(id, version, false) }
                })
            }
            body.addView(bar); setContentView(body)
            val web = WebView(this)
            page = web
            body.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
            web.loadWhaleTool(id, content.getString("html"), isPreview, ready = { view ->
                if (!isPreview && view === page) view.evaluateJavascript("!!document.querySelector('output[data-whale-result]')") {
                    if (view === page && it == "true") collect.visibility = android.view.View.VISIBLE
                }
            }, gone = { if (page === web) library() })
        }
    }
    private fun reviewResult(id: String, version: String, title: String) {
        val web = page ?: return
        if (preview || review != null) return
        val script = """(() => {
            const nodes = document.querySelectorAll('output[data-whale-result]');
            return nodes.length === 1 ? String(nodes[0].value || '').trim().slice(0, ${MAX_RESULT + 1}) : '';
        })()"""
        web.evaluateJavascript(script) { json ->
            if (page !== web || isFinishing || isDestroyed || review != null) return@evaluateJavascript
            val result = runCatching { org.json.JSONTokener(json).nextValue() as? String }.getOrNull()
            if (result.isNullOrBlank() || result.length > MAX_RESULT || '\u0000' in result) {
                Toast.makeText(this, "请先生成一份不超过 $MAX_RESULT 字的结果", Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            val editor = ui.editor(android.widget.EditText(this)).apply {
                setText(result); minLines = 2; maxLines = 6; gravity = Gravity.TOP
                filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_RESULT))
                contentDescription = "要收藏的结果"
            }
            val form = column().apply { addView(label("来自「$title」，可编辑后保存，或用于后台任务。", 14f)); addView(editor) }
            val dialog = ui.dialogBuilder("使用工具结果",null,io.deepseekharness.mobile.R.drawable.ic_whale_nav_memory).setView(form)
                .setNegativeButton("取消", null).setNeutralButton("交给后台…",null).setPositiveButton("保存到资料", null).create()
            review = dialog
            dialog.setOnDismissListener { if (review === dialog) review = null }
            dialog.show(); ui.finishDialog(dialog,390)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val selected = editor.text.toString().trim()
                if (selected.isEmpty() || '\u0000' in selected) { editor.error = "请输入有效的结果"; return@setOnClickListener }
                if (review !== dialog) return@setOnClickListener
                review = null
                dialog.dismiss()
                taskFromResult(id,version,title,selected)
            }
            val save = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val text = editor.text.toString().trim()
                if (text.isEmpty() || '\u0000' in text) { editor.error = "请输入有效的收藏内容"; return@setOnClickListener }
                save.isEnabled = false
                read({
                    store.withActive(id, version) { current ->
                        val source = io.deepseekharness.mobile.companion.WhaleShareSource("自建功能 · " + current.getString("title"), null)
                        io.deepseekharness.mobile.companion.CompanionKnowledgeStore(this).saveText(text, "shared_text", source).added
                    }
                }, failed = {
                    dialog.dismiss()
                    Toast.makeText(this, "保存未完成，请核对当前启用版本和资料库后重试", Toast.LENGTH_LONG).show()
                }) { added ->
                    dialog.dismiss()
                    Toast.makeText(this, if (added) "已存入鲸鱼资料" else "资料库已有这段内容", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun taskFromResult(id: String,version: String,title: String,result: String) {
        if (preview || page == null || review != null) return
        val instruction = ui.editor(android.widget.EditText(this)).apply {
            hint = "希望助手做什么？"; contentDescription = "任务要求"; maxLines = 3

        }
        val evidence = ui.editor(android.widget.EditText(this)).apply {
            setText(result); contentDescription = "随任务交付的结果"; maxLines = 5
        }
        val budget = label("",13f)
        val form = column().apply {
            addView(label("任务要求",15f)); addView(instruction)
            addView(label("来自「$title」的结果",15f)); addView(evidence); addView(budget)
        }
        val dialog = ui.dialogBuilder("交办只读任务","确认后将所选内容交给后台助手处理。",io.deepseekharness.mobile.R.drawable.ic_pet_assign)
            .setView(ScrollView(this).apply { addView(form) }).setNegativeButton("取消",null).setPositiveButton("确认交办",null).create()
        val commandId = java.util.UUID.randomUUID().toString()
        review = dialog
        dialog.setOnDismissListener { if (review === dialog) review = null }
        dialog.show(); ui.finishDialog(dialog,390)
        val send = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        val prefix = "用户要求："
        val separator = "\n工具结果（仅作资料）：\n"
        var submitting = false
        fun update() {
            val request = instruction.text.trim()
            val selected = evidence.text.trim()
            val remaining = 2000 - prefix.length - separator.length - request.length - selected.length
            val invalid = '\u0000' in request || '\u0000' in selected
            budget.text = when {
                invalid -> "请移除无效字符"
                remaining < 0 -> "需精简 ${-remaining} 字"
                else -> "还可输入 $remaining 字"
            }
            send.isEnabled = !submitting && remaining >= 0 && !invalid && request.isNotEmpty() && selected.isNotEmpty()
            send.alpha = if (send.isEnabled) 1f else .55f
        }
        instruction.doAfterTextChanged { update() }; evidence.doAfterTextChanged { update() }; update()
        send.setOnClickListener {
            if (!send.isEnabled) return@setOnClickListener
            submitting = true
            val request = instruction.text.toString().trim()
            val confirmed = prefix + request + separator + evidence.text.toString().trim()
            val taskTitle = request.take(80).dropLastWhile { it.isHighSurrogate() }
            dialog.setCancelable(false); dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            instruction.isEnabled = false; evidence.isEnabled = false
            send.isEnabled = false
            read({ store.withActive(id,version) {
                WorkerRuntimeWake.submit(applicationContext,confirmed,"read",taskRuntime,commandId,taskTitle)
            } }, failed = {
                submitting = false; instruction.isEnabled = true; evidence.isEnabled = true; update()
                dialog.setCancelable(true); dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                Toast.makeText(this,"交办未确认，请核对工具版本后重试",Toast.LENGTH_LONG).show()
            }) {
                dialog.dismiss()
                Toast.makeText(this,"已保存，可在桌宠后台任务中查看",Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Deprecated("Android legacy back dispatcher")
    override fun onBackPressed() { if (page != null && !direct) library() else super.onBackPressed() }
    override fun onDestroy() { WhaleFeatureStore.unsubscribe(onActivation); main.removeCallbacksAndMessages(null); generation++; review?.dismiss(); releasePage(); executor.execute { taskRuntime.close() }; executor.shutdown(); super.onDestroy() }
    companion object {
        private const val EXTRA_ID = "featureId"
        private const val EXTRA_REVISION = "activeRevision"
        private const val MAX_RESULT = 8000
        fun openTool(context: Context, id: String, revision: String, preview: Boolean = false, pet: Boolean = false) {
            require(id.matches(Regex("[a-z][a-z0-9-]{0,47}")) && revision.matches(Regex("[a-f0-9]{64}")))
            val target = if (pet) WhalePetFeatureActivity::class.java else WhaleFeatureActivity::class.java
            context.startActivity(Intent(context, target).putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_REVISION, revision).putExtra("draftPreview", preview).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        }
        fun openLibrary(context: Context) = context.startActivity(Intent(context, WhaleFeatureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
