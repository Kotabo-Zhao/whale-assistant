package io.deepseekharness.mobile.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.Toast
import io.deepseekharness.mobile.features.WhaleFeatureStore
import io.deepseekharness.mobile.features.loadWhaleTool
import org.json.JSONTokener
import java.util.concurrent.Executors

/** One tool renderer owned by the existing pet panel, with no additional Android window. */
internal class PetInlineTool(context: Context, private val back: () -> Unit, private val sendResult: (String) -> Unit) : LinearLayout(context) {
    private val ui = PetUi(context)
    private val store = WhaleFeatureStore(context)
    private val reader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var current: WhaleFeatureStore.Tool? = null
    private var web: WebView? = null
    private val title = ui.text("我的工具", 15f, ui.ink, true)
    private val pageHost = android.widget.FrameLayout(context)
    private val changed: (String, String) -> Unit = { id, revision ->
        main.post { if (current?.id == id && current?.revision != revision) invalidateTool() }
        Unit
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(ui.canvas)
        val bar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), 0, ui.dp(10), 0) }
        fun action(label: String, run: () -> Unit) = ui.text(label,13f,ui.accent).apply {
            gravity = Gravity.CENTER; minHeight = ui.dp(48); setPadding(ui.dp(8),0,ui.dp(8),0)
            isFocusable = true; contentDescription = label; setOnClickListener { run() }
        }
        bar.addView(action("←", back))
        bar.addView(title, LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(4) })
        bar.addView(action("⤢") {
            val tool = current ?: return@action
            runCatching { io.deepseekharness.mobile.features.WhaleFeatureActivity.openTool(context, tool.id, tool.revision) }
        }.apply { contentDescription = "全屏打开小软件" })
        addView(bar)
        addView(pageHost, LayoutParams(-1,0,1f))
        val actions = LinearLayout(context)
        actions.addView(action("带回对话") { result { sendResult(it); back() } }, LayoutParams(0,-2,1f))
        actions.addView(action("保存结果") {
            result { text ->
                val tool = current ?: return@result
                reader.execute {
                    val saved = runCatching { store.withActive(tool.id, tool.revision) {
                        CompanionKnowledgeStore(context).saveText(text,"shared_text",WhaleShareSource("自建功能 · " + tool.title,null))
                    } }
                    main.post { Toast.makeText(context,if (saved.isSuccess) "已保存到资料库" else "保存失败，请检查工具版本",Toast.LENGTH_SHORT).show() }
                }
            }
        }, LayoutParams(0,-2,1f))
        addView(actions)
        WhaleFeatureStore.subscribe(changed)
    }

    fun open(tool: WhaleFeatureStore.Tool) {
        visibility = View.VISIBLE
        if (current == tool && web != null) { web?.onResume(); return }
        generation++
        val request = generation
        releasePage()
        current = tool; title.text = tool.title
        reader.execute {
            val loaded = runCatching { store.withActive(tool.id,tool.revision) { it.getString("html") } }
            main.post {
                if (generation != request || current != tool) return@post
                loaded.onSuccess { html ->
                    val page = WebView(context).apply {
                        isVerticalScrollBarEnabled = true
                        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                    }
                    web = page
                    pageHost.addView(page, android.widget.FrameLayout.LayoutParams(-1, -1))
                    page.loadWhaleTool(tool.id, html, gone = { invalidateTool() })
                }.onFailure { invalidateTool() }
            }
        }
    }

    fun pause() { visibility = View.GONE; web?.onPause() }
    private fun result(use: (String) -> Unit) {
        val page = web ?: return
        val tool = current ?: return
        page.evaluateJavascript("""(() => { const rows=document.querySelectorAll('output[data-whale-result]');
            return rows.length===1 ? String(rows[0].value || rows[0].textContent || '').trim().slice(0,8001) : ''; })()""") { raw ->
            if (page !== web || tool != current) return@evaluateJavascript
            val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            if (text.isNullOrBlank() || text.length > 8000 || '\u0000' in text) {
                Toast.makeText(context,"请先生成一份有效的工具结果",Toast.LENGTH_SHORT).show()
            } else {
                val request = generation
                reader.execute {
                    val valid = runCatching { store.withActive(tool.id,tool.revision) { Unit } }.isSuccess
                    main.post {
                        if (request == generation && page === web && tool == current) {
                            if (valid) use(text) else invalidateTool()
                        }
                    }
                }
            }
        }
    }
    private fun invalidateTool() {
        generation++; current = null; releasePage()
        title.text = "工具已停用或版本已更新"
        pageHost.addView(ui.text("返回工具列表后重新选择当前版本。",14f,ui.muted))
    }
    private fun releasePage() { pageHost.removeAllViews(); web?.let { it.stopLoading(); it.destroy() }; web = null }
    fun destroy() { generation++; WhaleFeatureStore.unsubscribe(changed); releasePage(); reader.shutdownNow() }
}
