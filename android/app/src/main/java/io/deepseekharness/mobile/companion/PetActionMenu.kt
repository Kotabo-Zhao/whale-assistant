package io.deepseekharness.mobile.companion

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.deepseekharness.mobile.R
import kotlin.math.roundToInt

/**
 * 桌宠快捷菜单（基于最新原生 Super Agent 能力重新规划）：
 * - 顶部 2×2 全彩可爱贴纸主入口：对话 / 识屏 / 存资料 / 任务
 * - 底部精简入口：我的工具（工坊小工具架） / 主界面与桌宠（回到主应用、副屏、隐藏桌宠、无障碍）
 */
internal class PetActionMenu(
    context: Context,
    initialPage: Page,
    private val accessibilityAvailable: () -> Boolean,
    private val dispatch: (Action) -> Unit,
    private val resized: () -> Unit,
    private val dismiss: () -> Unit,
    private val openTool: (io.deepseekharness.mobile.features.WhaleFeatureStore.Tool) -> Unit,
) : ScrollView(context) {
    enum class Page(val title: Int) {
        HOME(R.string.pet_menu_home),
        SAVE(R.string.pet_menu_capture),
        TOOLS(R.string.pet_menu_tools),
        DEVICE(R.string.pet_menu_device),
    }

    enum class Action(val label: Int) {
        PAUSE(R.string.pet_control_pause), RESUME(R.string.pet_control_resume), STOP(R.string.pet_control_stop),
        CHAT(R.string.pet_menu_chat), ASSIGN(R.string.pet_menu_assign), WRITE(R.string.pet_menu_write),
        CLIPBOARD(R.string.pet_menu_clipboard), SCREEN(R.string.pet_menu_screen), FILES(R.string.pet_menu_files),
        INBOX(R.string.pet_menu_inbox), HARNESS(R.string.pet_menu_harness),
        DISPLAY(R.string.pet_menu_display), APP(R.string.pet_menu_app), HIDE(R.string.pet_menu_hide), DISABLE(R.string.pet_menu_disable),
    }

    private val ui = PetUi(context)
    private var controlPanel: LinearLayout? = null
    private var controlLabel: TextView? = null
    private var controlPause: TextView? = null
    private var controlExplanation: TextView? = null

    fun refreshControl() {
        val phase = io.deepseekharness.mobile.DeviceControlState.current.phase
        controlLabel?.text = PetControlLabels.label(ui.palette, phase)
        val halted = phase == "paused" || phase == "stopped"
        controlPause?.setText(if (halted) R.string.pet_control_resume else R.string.pet_control_pause)
        controlExplanation?.visibility = if (halted) VISIBLE else GONE
        val visible = if (phase == "idle") GONE else VISIBLE
        if (controlPanel?.visibility != visible) { controlPanel?.visibility = visible; resized() }
    }

    var page = initialPage
        private set

    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(12))
    }

    private fun dp(size: Int) = (size * resources.displayMetrics.density).roundToInt()

    init {
        background = ui.shape(ui.surface, 22, ui.line)
        elevation = dp(10).toFloat()
        isVerticalScrollBarEnabled = false
        clipToOutline = true
        addView(body)
        render()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { dismiss(); return true }
        return super.dispatchTouchEvent(event)
    }

    private fun showPage(next: Page) {
        page = next
        render()
        scrollTo(0, 0)
        resized()
        io.deepseekharness.mobile.WhaleMotion.enter(body)
    }

    private fun button(label: Int, tile: Boolean = false, click: () -> Unit) = TextView(context).apply {
        setText(label)
        textSize = 14.5f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        minHeight = dp(46); minWidth = dp(48)
        gravity = if (tile) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(4), dp(10), dp(4))
        setTextColor(if (tile) ui.accent else ui.ink)
        background = ui.action(if (tile) ui.soft else ui.surface, if (ui.simple) 10 else 15, if (tile || ui.usagi) ui.line else null)
        isFocusable = true
        setOnClickListener { io.deepseekharness.mobile.WhaleMotion.feedback(this); click() }
    }

    private fun TextView.trailingArrow() {
        val icon = requireNotNull(context.getDrawable(R.drawable.ic_pet_forward)).mutate().apply {
            setTint(ui.muted); setBounds(0, 0, dp(14), dp(14))
        }
        compoundDrawablePadding = dp(6)
        setCompoundDrawablesRelative(compoundDrawablesRelative[0], null, icon, null)
    }

    private fun TextView.cuteLeadingIcon(resource: Int, sizeDp: Int = 22) {
        val icon = androidx.core.content.ContextCompat.getDrawable(context, resource)?.mutate() ?: return
        icon.setBounds(0, 0, dp(sizeDp), dp(sizeDp))
        compoundDrawablePadding = dp(7)
        setCompoundDrawablesRelative(icon, null, compoundDrawablesRelative[2], null)
    }

    private fun group(target: Page, cuteIcon: Int, customTitle: String? = null) {
        body.addView(
            button(target.title) { showPage(target) }.apply {
                if (customTitle != null) text = customTitle
                cuteLeadingIcon(cuteIcon, 20)
                trailingArrow()
            },
        )
    }

    private fun row(action: Action) { body.addView(button(action.label) { dispatch(action) }) }

    private fun cuteTile(
        labelText: String,
        cuteIcon: Int,
        bgColor: Int,
        inkColor: Int,
        click: () -> Unit,
    ) = TextView(context).apply {
        text = labelText
        textSize = 14.5f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        minHeight = dp(52)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(4), dp(10), dp(4))
        setTextColor(inkColor)
        background = ui.action(bgColor, if (ui.simple) 12 else 17, if (ui.usagi || !ui.simple) ui.line else null)
        cuteLeadingIcon(cuteIcon, 22)
        isFocusable = true
        setOnClickListener { io.deepseekharness.mobile.WhaleMotion.feedback(this); click() }
    }

    private fun tiles(first: TextView, second: TextView) {
        val line = LinearLayout(context)
        line.addView(first, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(second, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        body.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun sourceRow(action: Action, icon: Int, caption: Int) {
        body.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(54); setPadding(dp(6), dp(4), dp(6), dp(4))
            background = ui.action(ui.surface, 12); isFocusable = true
            contentDescription = ui.string(action.label) + "，" + ui.string(caption)
            addView(android.widget.ImageView(context).apply {
                setImageDrawable(ui.palette.getDrawable(icon)); setColorFilter(ui.accent)
                setPadding(dp(7), dp(7), dp(7), dp(7)); background = ui.shape(ui.soft, 10)
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(ui.text(ui.string(action.label), 14.5f))
                addView(ui.text(ui.string(caption), 12f, ui.muted))
            })
            setOnClickListener { dispatch(action) }
        })
    }

    private fun render() {
        body.removeAllViews()
        if (page == Page.HOME) {
            body.addView(ui.header(ui.string(R.string.pet_ui_name), null, ui.close(dismiss)).apply {
                setPadding(dp(2), dp(2), 0, dp(6))
            })
        } else {
            val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(button(R.string.pet_menu_back) { showPage(Page.HOME) }.apply { setTextColor(ui.accent) })
            if (page == Page.SAVE) heading.addView(PetPortrait(context, ui, R.drawable.ic_whale_nav_memory), LinearLayout.LayoutParams(dp(34), dp(34)))
            heading.addView(ui.text(ui.string(page.title), 15.5f, ui.ink, true).apply {
                setPadding(dp(3), 0, 0, 0); androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(ui.close(dismiss))
            body.addView(heading)
        }
        controlPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8)); background = ui.shape(ui.soft, 14)
            controlLabel = ui.text("", 13f, ui.ink, true).also { addView(it) }
            val actions = LinearLayout(context)
            controlPause = button(R.string.pet_control_pause, true) {
                dispatch(if (io.deepseekharness.mobile.DeviceControlState.current.phase in setOf("paused", "stopped")) Action.RESUME else Action.PAUSE)
            }.also { actions.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
            actions.addView(button(R.string.pet_control_stop, true) { dispatch(Action.STOP) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(actions)
            controlExplanation = ui.text(ui.string(R.string.pet_control_new_actions), 12f, ui.muted).also { addView(it) }
        }.also { body.addView(it) }
        refreshControl()
        when (page) {
            Page.HOME -> {
                tiles(
                    cuteTile("对话", R.drawable.ic_cute_chat, ui.primary, ui.onPrimary) { dispatch(Action.CHAT) },
                    cuteTile("识屏", R.drawable.ic_cute_appearance, ui.soft, ui.accent) { dispatch(Action.SCREEN) },
                )
                tiles(
                    cuteTile("存资料", R.drawable.ic_cute_memory, ui.rose, ui.ink) { showPage(Page.SAVE) },
                    cuteTile("任务", R.drawable.ic_cute_tasks, ui.honey, ui.ink) { dispatch(Action.INBOX) },
                )
                body.addView(android.view.View(context).apply { setBackgroundColor(ui.line) }, LinearLayout.LayoutParams(-1, dp(1)))
                group(Page.TOOLS, R.drawable.ic_cute_workshop)
                group(Page.DEVICE, R.drawable.ic_cute_device, "主界面与桌宠")
            }
            Page.SAVE -> {
                sourceRow(Action.CLIPBOARD, R.drawable.ic_whale_nav_memory, R.string.pet_source_clip)
                sourceRow(Action.SCREEN, R.drawable.ic_pet_frame, R.string.pet_source_screen)
                sourceRow(Action.FILES, R.drawable.ic_folder, R.string.pet_source_file)
                sourceRow(Action.WRITE, R.drawable.ic_pet_assign, R.string.pet_source_note)
            }
            Page.TOOLS -> {
                body.addView(WhalePetToolShelf(context, resized, { tool -> openTool(tool); dismiss() }, dismiss))
            }
            Page.DEVICE -> {
                listOf(Action.APP, Action.DISPLAY, Action.HIDE).forEach(::row)
                if (accessibilityAvailable()) row(Action.DISABLE)
                else body.addView(button(R.string.pet_menu_disabled) {}.apply { isEnabled = false; setTextColor(ui.muted) })
            }
        }
    }
}
