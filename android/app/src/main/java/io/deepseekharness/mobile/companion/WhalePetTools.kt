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
import io.deepseekharness.mobile.features.NativeFeatureRegistry
import java.util.concurrent.Executors

/** Both pet surfaces mount this shelf only after the user opens My tools. */
internal class WhalePetToolShelf(context: Context, resized: () -> Unit, openTool: (WhaleFeatureStore.Tool) -> Unit,
    launched: () -> Unit) : LinearLayout(context) {
    init {
        val ui = PetUi(context)
        orientation = VERTICAL
        addView(WhalePetTools(context, resized, openTool, launched))
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
    private val launched: () -> Unit) : HorizontalScrollView(context) {
    private val ui = PetUi(context)
    private val strip = LinearLayout(context)
    private val native = NativeFeatureRegistry(context)
    private val nativeChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        post { if (isAttachedToWindow) refresh() }
    }
    private val store = WhaleFeatureStore(context)
    private var generation = 0
    private val change: (String,String) -> Unit = { _,_ -> post { if (isAttachedToWindow) refresh() }; Unit }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    init {
        visibility = View.GONE
        isHorizontalScrollBarEnabled = false
        addView(strip)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WhaleFeatureStore.subscribe(change)
        native.prefs.registerOnSharedPreferenceChangeListener(nativeChanged)
        refresh()
    }

    override fun onDetachedFromWindow() {
        generation++
        WhaleFeatureStore.unsubscribe(change)
        native.prefs.unregisterOnSharedPreferenceChangeListener(nativeChanged)
        super.onDetachedFromWindow()
    }

    private fun refresh() {
        val current = ++generation
        reader.execute {
            val tools = runCatching { store.activeTools() }.getOrDefault(emptyList())
            val candidates = native.entries().filter(native::active)
            post {
                if (!isAttachedToWindow || generation != current) return@post
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
                candidates.forEach { entry ->
                    strip.addView(TextView(context).apply {
                        text = entry.title; contentDescription = "打开原生工具 · " + entry.title
                        textSize = 13f; gravity = Gravity.CENTER; minHeight = dp(48)
                        setPadding(dp(12),0,dp(12),0); maxWidth = dp(168); maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END; setTextColor(ui.accent)
                        background = ui.action(ui.soft,12); isFocusable = true
                        setOnClickListener {
                            reader.execute {
                                runCatching { native.openFromPet(entry) }.onSuccess {
                                    post { if (isAttachedToWindow) launched() }
                                }.onFailure {
                                    post { android.widget.Toast.makeText(context,"原生功能已停用或安装包已变更",android.widget.Toast.LENGTH_SHORT).show() }
                                }
                            }
                        }
                    }, LinearLayout.LayoutParams(-2,-2).apply { marginStart = dp(6); bottomMargin = dp(3) })
                }
                visibility = if (tools.isEmpty() && candidates.isEmpty()) View.GONE else View.VISIBLE
                resized()
            }
        }
    }

    companion object { private val reader = Executors.newSingleThreadExecutor() }
}
