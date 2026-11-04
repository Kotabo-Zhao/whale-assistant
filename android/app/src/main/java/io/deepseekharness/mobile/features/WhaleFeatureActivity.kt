package io.deepseekharness.mobile.features

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
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

/** Independently authored local tools run outside the Capacitor management WebView. */
class WhaleFeatureActivity : Activity() {
    private val store by lazy { WhaleFeatureStore(this) }
    private val executor = Executors.newSingleThreadExecutor()
    private var page: WebView? = null
    private var generation = 0
    private var direct = false
    private var openedId: String? = null
    private var openedRevision = ""
    private var preview = false
    private var review: android.app.AlertDialog? = null
    private val dark get() = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val surface get() = if (dark) Color.rgb(23, 34, 47) else Color.rgb(246, 249, 252)
    private val ink get() = if (dark) Color.rgb(234, 241, 249) else Color.rgb(32, 51, 66)
    private val accent get() = if (dark) Color.rgb(182, 216, 255) else Color.rgb(33, 82, 132)
    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        direct = intent.hasExtra(EXTRA_ID)
        val id = savedInstanceState?.getString("featureId") ?: intent.getStringExtra(EXTRA_ID)
        val revision = savedInstanceState?.getString("revision") ?: intent.getStringExtra(EXTRA_REVISION)
        if (id == null || revision == null) library() else open(id, revision, savedInstanceState?.getBoolean("preview") ?: intent.getBooleanExtra("draftPreview", false))
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putString("featureId", openedId); state.putString("revision", openedRevision); state.putBoolean("preview", preview)
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)) }
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(ink); setPadding(dp(4), dp(5), dp(4), dp(5))
        if (size >= 18f) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; minHeight = dp(44); setTextColor(accent)
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (dark) Color.rgb(42, 63, 87) else Color.rgb(224, 237, 249))
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
        page?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() }
        page = null
    }
    private fun library() {
        releasePage()
        openedId = null; direct = false
        val body = column()
        setContentView(ScrollView(this).apply { addView(body); setBackgroundColor(surface) })
        body.addView(label("功能工坊", 26f))
        body.addView(label("在 Harness 对话中说「为鲸鱼助手创建一个……」。生成的工具会出现在这里，可预览、启用或恢复上一版。", 15f))
        body.addView(button("刷新功能库") { library() })
        read(store::list) { items ->
            if (items.isEmpty()) body.addView(label("还没有自建功能", 18f))
            for (item in items) {
                val id = item.getString("id")
                val active = item.getString("active"); val latest = item.getString("latest"); val previous = item.getString("previous")
                val card = column().apply { background = rounded(if (dark) Color.rgb(30, 45, 60) else Color.WHITE, 16) }
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
    private fun open(id: String, requested: String, isPreview: Boolean) {
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
            val body = column().apply { setBackgroundColor(surface) }
            val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(6)) }
            bar.addView(button(if (direct) "返回" else "返回工坊") { if (direct) finish() else library() })
            bar.addView(label(content.getString("title"), 18f).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val collect = button("收藏结果") { reviewResult(id, version, content.getString("title")) }.apply {
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
            web.settings.apply {
                javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
                blockNetworkLoads = true; javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(true); domStorageEnabled = true
            }
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (!isPreview && view === page) view.evaluateJavascript("!!document.querySelector('output[data-whale-result]')") {
                        if (view === page && it == "true") collect.visibility = android.view.View.VISIBLE
                    }
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    library(); return true
                }
            }
            body.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
            val policy = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'; frame-src 'none'"
            // Each tool owns one local-storage origin. Preview cannot mutate enabled-tool data.
            val origin = if (!isPreview) "https://$id.features.whale.invalid/" else "https://$id.preview.whale.invalid/"
            web.loadDataWithBaseURL(origin, "<meta charset=utf-8><meta http-equiv=\"Content-Security-Policy\" content=\"$policy\"><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
                content.getString("html"), "text/html", "utf-8", null)
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
            val editor = android.widget.EditText(this).apply {
                setText(result); minLines = 2; maxLines = 6; gravity = Gravity.TOP
                filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_RESULT))
                contentDescription = "要收藏的结果"
            }
            val form = column().apply { addView(label("来自「$title」，可以修改后保存。", 14f)); addView(editor) }
            val dialog = android.app.AlertDialog.Builder(this).setTitle("存入鲸鱼资料").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存到资料", null).create()
            review = dialog
            dialog.setOnDismissListener { if (review === dialog) review = null }
            dialog.show()
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

    @Deprecated("Android legacy back dispatcher")
    override fun onBackPressed() { if (page != null && !direct) library() else super.onBackPressed() }
    override fun onDestroy() { generation++; review?.dismiss(); releasePage(); executor.shutdown(); super.onDestroy() }
    companion object {
        private const val EXTRA_ID = "featureId"
        private const val EXTRA_REVISION = "activeRevision"
        private const val MAX_RESULT = 8000
        fun openTool(context: Context, id: String, revision: String, preview: Boolean = false) {
            require(id.matches(Regex("[a-z][a-z0-9-]{0,47}")) && revision.matches(Regex("[a-f0-9]{64}")))
            context.startActivity(Intent(context, WhaleFeatureActivity::class.java).putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_REVISION, revision).putExtra("draftPreview", preview).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        }
        fun openLibrary(context: Context) = context.startActivity(Intent(context, WhaleFeatureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
