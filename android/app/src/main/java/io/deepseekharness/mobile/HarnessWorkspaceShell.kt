package io.deepseekharness.mobile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** The conversation keeps its restricted WebView while only the surrounding navigation reflows. */
internal class HarnessWorkspaceShell(context: Context, navigate: (String) -> Unit) : FrameLayout(context) {
    val chat = WebView(context).apply { id = R.id.harness_web_view }
    private val strip = LinearLayout(context)
    private val navigation = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(strip) }
    private val home = TextView(context).apply {
        id = R.id.harness_manage
        text = context.getString(R.string.whale_workbench)
        gravity = Gravity.CENTER; textSize = 13f; setTypeface(null, Typeface.BOLD)
        isFocusable = true; setOnClickListener { navigate("conversation") }
    }
    private val heading = TextView(context).apply {
        setText(R.string.harness_tab_chat); gravity = Gravity.CENTER; textSize = 15f
        setTypeface(null, Typeface.BOLD); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val items = listOf(
        Destination(R.id.harness_tab_active, R.string.harness_tab_chat, R.drawable.ic_whale_nav_chat, null),
        Destination(R.id.harness_tab_memory, R.string.whale_tab_memory, R.drawable.ic_whale_nav_memory, "assistant-memory"),
        Destination(R.id.harness_tab_tasks, R.string.whale_tab_tasks, R.drawable.ic_whale_nav_tasks, "assistant-tasks"),
        Destination(R.id.harness_tab_plugins, R.string.whale_tab_tools, R.drawable.ic_whale_nav_plugins, "tools"),
        Destination(R.id.harness_tab_settings, R.string.whale_tab_account, R.drawable.ic_whale_nav_settings, "settings")
    )
    private var wide: Boolean? = null
    private data class Destination(val id: Int, val label: Int, val icon: Int, val route: String?)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()

    init {
        addView(chat); addView(navigation); addView(home); addView(heading)
        for (item in items) {
            val button = LinearLayout(context).apply {
                id = item.id; orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                contentDescription = context.getString(item.label); isSelected = item.route == null
                isFocusable = true; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                if (item.route != null) setOnClickListener { navigate(item.route) }
            }
            button.addView(ImageView(context).apply {
                setImageResource(item.icon); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20),dp(20)))
            button.addView(TextView(context).apply {
                setText(item.label); textSize = 11f; gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            strip.addView(button)
        }
        updateColors()
    }
    fun updateColors() {
        val dark = AppThemePreference.isDark(AppThemePreference.current(context),AppThemePreference.systemNight(context))
        val config = Configuration(resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val palette = context.createConfigurationContext(config)
        val bg = palette.getColor(R.color.harness_toolbar_background)
        val accent = palette.getColor(R.color.harness_search_focus)
        val muted = palette.getColor(R.color.harness_search_muted)
        setBackgroundColor(bg); home.setTextColor(accent); heading.setTextColor(palette.getColor(R.color.harness_toolbar_foreground)); navigation.setBackgroundColor(bg)
        for (i in items.indices) {
            val button = strip.getChildAt(i) as LinearLayout
            val ink = if (button.isSelected) accent else muted
            (button.getChildAt(0) as ImageView).setColorFilter(ink)
            (button.getChildAt(1) as TextView).setTextColor(ink)
            button.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf((accent and 0xffffff) or 0x25000000),
                GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(if (button.isSelected) (accent and 0xffffff) or 0x21000000 else Color.TRANSPARENT) },null)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec); val height = MeasureSpec.getSize(heightMeasureSpec)
        val rail = width >= dp(900) || width >= dp(600) && width > height
        if (wide != rail) {
            wide = rail
            strip.orientation = if (rail) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            strip.setPadding(dp(4),dp(3),dp(4),dp(3))
            for (i in items.indices) {
                strip.getChildAt(i).layoutParams = if (rail) LinearLayout.LayoutParams(-1,dp(55)) else LinearLayout.LayoutParams(0,dp(50),1f)
            }
            heading.visibility = if (rail) GONE else VISIBLE
            heading.layoutParams = LayoutParams(dp(92),dp(46),Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            home.layoutParams = LayoutParams(if (rail) dp(72) else dp(85),dp(46),Gravity.TOP or Gravity.LEFT)
            navigation.layoutParams = if (rail) LayoutParams(dp(72),-1,Gravity.LEFT).apply { topMargin = dp(46) }
                else LayoutParams(-1,dp(56),Gravity.BOTTOM)
            chat.layoutParams = LayoutParams(-1,-1).apply {
                if (rail) leftMargin = dp(72) else { topMargin = dp(46); bottomMargin = dp(56) }
            }
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
}
