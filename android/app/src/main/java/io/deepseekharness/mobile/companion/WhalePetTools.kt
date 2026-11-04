package io.deepseekharness.mobile.companion

import android.content.Context
import io.deepseekharness.mobile.R
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import io.deepseekharness.mobile.features.WhaleFeatureActivity
import io.deepseekharness.mobile.features.WhaleFeatureStore
import io.deepseekharness.mobile.features.loadWhaleTool
import android.webkit.WebView
import java.util.concurrent.Executors

/** Both pet surfaces mount this shelf only after the user opens My tools. */
internal class WhalePetToolShelf(context: Context, private val resized: () -> Unit, private val launched: () -> Unit) : LinearLayout(context) {
    private val inline = LinearLayout(context).apply { orientation = VERTICAL }
    private val store = WhaleFeatureStore(context)
    private var selection: WhaleFeatureStore.Tool? = null
    private var page: WebView? = null
    private var generation = 0

    private fun closeTool() {
        generation++
        selection = null
        val web = page
        page = null
        inline.removeAllViews()
        web?.apply { stopLoading(); destroy() }
        resized()
    }

    override fun onDetachedFromWindow() {
        closeTool()
        super.onDetachedFromWindow()
    }

    private fun openTool(tool: WhaleFeatureStore.Tool) {
        closeTool()
        selection = tool
        val request = generation
        reader.execute {
            val html = runCatching { store.withActive(tool.id, tool.revision) { it.getString("html") } }.getOrNull()
            post {
                if (!isAttachedToWindow || generation != request) return@post
                if (html == null) { closeTool(); return@post }
                val ui = PetUi(context)
                val actions = LinearLayout(context)
                fun action(label: String, click: () -> Unit) = ui.text(label, 13f, ui.accent).apply {
                    minHeight = ui.dp(44); gravity = Gravity.CENTER; setPadding(ui.dp(8), 0, ui.dp(8), 0); setOnClickListener { click() }
                }
                actions.addView(action("收起工具") { closeTool() })
                actions.addView(action("展开窗口") { WhaleFeatureActivity.openTool(context, tool.id, tool.revision, pet = true); launched() })
                inline.addView(actions)
                val web = WebView(context)
                page = web
                inline.addView(web, LayoutParams(-1, ui.dp(158)))
                web.loadWhaleTool(tool.id, html, gone = { if (page === web) closeTool() })
                resized()
            }
        }
    }
    private companion object { val reader = Executors.newSingleThreadExecutor() }

    init {
        val ui = PetUi(context)
        orientation = VERTICAL
        addView(WhalePetTools(context, resized, ::openTool) { tools ->
            if (selection != null && tools.none { it.id == selection?.id && it.revision == selection?.revision }) closeTool()
        })
        addView(inline)
        addView(TextView(context).apply {
            setText(R.string.pet_menu_studio)
            textSize = 15f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val unit = resources.displayMetrics.density
            minHeight = (48 * unit).toInt(); setPadding((12 * unit).toInt(), 0, 0, 0)
            setTextColor(ui.accent)
            isFocusable = true
            setOnClickListener { WhaleFeatureActivity.openLibrary(context); launched() }
        })
    }
}

/** Read published metadata off the UI thread while the shelf is attached. */
internal class WhalePetTools(context: Context, private val resized: () -> Unit, private val open: (WhaleFeatureStore.Tool) -> Unit,
    private val onList: (List<WhaleFeatureStore.Tool>) -> Unit) : HorizontalScrollView(context) {
    private val ui = PetUi(context)
    private val strip = LinearLayout(context)
    private val store = WhaleFeatureStore(context)
    private var generation = 0
    private val change: () -> Unit = { post { if (isAttachedToWindow) refresh() }; Unit }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    init {
        visibility = View.GONE
        isHorizontalScrollBarEnabled = false
        addView(strip)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WhaleFeatureStore.subscribe(change)
        refresh()
    }

    override fun onDetachedFromWindow() {
        generation++
        WhaleFeatureStore.unsubscribe(change)
        super.onDetachedFromWindow()
    }

    private fun refresh() {
        val current = ++generation
        reader.execute {
            val tools = runCatching { store.activeTools() }.getOrDefault(emptyList())
            post {
                if (!isAttachedToWindow || generation != current) return@post
                onList(tools)
                strip.removeAllViews()
                tools.forEach { tool ->
                    strip.addView(TextView(context).apply {
                        text = tool.title; contentDescription = "打开工具 · ${tool.title}"
                        textSize = 13f; gravity = Gravity.CENTER; minHeight = dp(48)
                        setPadding(dp(12), 0, dp(12), 0); maxWidth = dp(168); maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(ui.accent)
                        background = ui.action(ui.soft, 12)
                        isFocusable = true
                        setOnClickListener {
                            open(tool)
                        }
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6); bottomMargin = dp(3) })
                }
                visibility = if (tools.isEmpty()) View.GONE else View.VISIBLE
                resized()
            }
        }
    }

    companion object { private val reader = Executors.newSingleThreadExecutor() }
}
