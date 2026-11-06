package io.deepseekharness.mobile.companion

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.deepseekharness.mobile.R
import kotlin.math.roundToInt

/** A bounded, touch-through overlay. Only the tools page attaches a library observer. */
internal class PetActionMenu(
    context: Context,
    initialPage: Page,
    private val accessibilityAvailable: () -> Boolean,
    private val dispatch: (Action) -> Unit,
    private val resized: () -> Unit,
    private val dismiss: () -> Unit,
    private val openTool: (io.deepseekharness.mobile.features.WhaleFeatureStore.Tool) -> Unit,
) : ScrollView(context) {
    enum class Page(val title: Int) { HOME(R.string.pet_menu_home), SAVE(R.string.pet_menu_capture), TOOLS(R.string.pet_menu_tools), DEVICE(R.string.pet_menu_device) }
    enum class Action(val label: Int) {
        PAUSE(R.string.pet_control_pause), RESUME(R.string.pet_control_resume), STOP(R.string.pet_control_stop),
        CHAT(R.string.pet_menu_chat), ASSIGN(R.string.pet_menu_assign), WRITE(R.string.pet_menu_write),
        CLIPBOARD(R.string.pet_menu_clipboard), SCREEN(R.string.pet_menu_screen), FILES(R.string.pet_menu_files),
        INBOX(R.string.pet_menu_inbox), HARNESS(R.string.pet_menu_harness),
        DISPLAY(R.string.pet_menu_display), APP(R.string.pet_menu_app), HIDE(R.string.pet_menu_hide), DISABLE(R.string.pet_menu_disable)
    }

    private val ui = PetUi(context)
    private var controlPanel: LinearLayout? = null
    private var controlLabel: TextView? = null
    private var controlPause: TextView? = null
    private var controlExplanation: TextView? = null
    fun refreshControl() {
        val phase=io.deepseekharness.mobile.DeviceControlState.current.phase
        controlLabel?.text=PetControlLabels.label(ui.palette,phase)
        val halted=phase=="paused" || phase=="stopped"
        controlPause?.setText(if(halted) R.string.pet_control_resume else R.string.pet_control_pause)
        controlExplanation?.visibility=if(halted) VISIBLE else GONE
        val visible=if(phase=="idle") GONE else VISIBLE
        if(controlPanel?.visibility!=visible) { controlPanel?.visibility=visible; resized() }
    }
    var page = initialPage
        private set
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(10))
    }
    private fun dp(size: Int) = (size * resources.displayMetrics.density).roundToInt()
    init {
        background = ui.shape(ui.surface, 20, ui.line)
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
        textSize = 15f
        minHeight = dp(48); minWidth = dp(48)
        gravity = if (tile) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(4), dp(10), dp(4))
        setTextColor(if (tile) ui.accent else ui.ink)
        background = ui.action(if (tile) ui.soft else ui.surface, 12)
        isFocusable = true
        setOnClickListener { click() }
    }

    private fun TextView.icon(resource: Int, trailing: Boolean = false) {
        val icon = requireNotNull(context.getDrawable(resource)).mutate().apply {
            setTint(currentTextColor); setBounds(0, 0, dp(if (trailing) 14 else 18), dp(if (trailing) 14 else 18))
        }
        compoundDrawablePadding = dp(5)
        if (trailing) setCompoundDrawablesRelative(null, null, icon, null)
        else setCompoundDrawablesRelative(icon, null, null, null)
    }

    private fun group(target: Page) {
        body.addView(button(target.title) { showPage(target) }.apply { icon(R.drawable.ic_pet_forward, trailing = true) })
    }

    private fun row(action: Action) { body.addView(button(action.label) { dispatch(action) }) }
    private fun tile(label: Int, drawable: Int, click: () -> Unit) =
        button(label, true, click).apply {
            minHeight = dp(52)
            when (label) {
                Action.CHAT.label -> { setTextColor(ui.onPrimary); background = ui.action(ui.primary, 14) }
                Page.SAVE.title -> { setTextColor(ui.ink); background = ui.action(ui.rose, 14) }
                Action.INBOX.label -> { setTextColor(ui.ink); background = ui.action(ui.honey, 14) }
            }
            icon(drawable)
        }

    private fun tiles(first: TextView, second: TextView) {
        val line = LinearLayout(context)
        line.addView(first, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(second, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        body.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }

    private fun sourceRow(action: Action, icon: Int, caption: Int) {
        body.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56); setPadding(dp(6), dp(4), dp(6), dp(4))
            background = ui.action(ui.surface, 12); isFocusable = true
            contentDescription = ui.string(action.label) + "，" + ui.string(caption)
            addView(android.widget.ImageView(context).apply {
                setImageDrawable(ui.palette.getDrawable(icon)); setColorFilter(ui.accent)
                setPadding(dp(7), dp(7), dp(7), dp(7)); background = ui.shape(ui.soft, 10)
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(ui.text(ui.string(action.label), 15f))
                addView(ui.text(ui.string(caption), 12f, ui.muted))
            })
            setOnClickListener { dispatch(action) }
        })
    }

    private fun render() {
        body.removeAllViews()
        if (page == Page.HOME) {
            body.addView(ui.header(ui.string(R.string.pet_ui_name), ui.string(R.string.pet_ui_actions), ui.close(dismiss)).apply {
                setPadding(dp(2), dp(3), 0, dp(6))
            })
        } else {
            val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(button(R.string.pet_menu_back) { showPage(Page.HOME) }.apply { setTextColor(ui.accent) })
            if (page == Page.SAVE) heading.addView(PetPortrait(context, ui, R.drawable.ic_whale_nav_memory), LinearLayout.LayoutParams(dp(35),dp(35)))
            heading.addView(ui.text(ui.string(page.title), 16f, ui.ink, true).apply {
                setPadding(dp(3), 0, 0, 0); androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(ui.close(dismiss))
            body.addView(heading)
        }
        controlPanel=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(8),dp(4),dp(8),dp(8)); background=ui.shape(ui.soft,14)
            controlLabel=ui.text("",13f,ui.ink,true).also { addView(it) }
            val actions=LinearLayout(context)
            controlPause=button(R.string.pet_control_pause,true) {
                dispatch(if(io.deepseekharness.mobile.DeviceControlState.current.phase in setOf("paused","stopped")) Action.RESUME else Action.PAUSE)
            }.also { actions.addView(it,LinearLayout.LayoutParams(0,-2,1f)) }
            actions.addView(button(R.string.pet_control_stop,true) { dispatch(Action.STOP) },LinearLayout.LayoutParams(0,-2,1f))
            addView(actions)
            controlExplanation=ui.text(ui.string(R.string.pet_control_new_actions),12f,ui.muted).also { addView(it) }
        }.also { body.addView(it) }
        refreshControl()
        when (page) {
            Page.HOME -> {
                tiles(tile(Action.CHAT.label, R.drawable.ic_whale_nav_chat) { dispatch(Action.CHAT) },
                    tile(Action.ASSIGN.label, R.drawable.ic_pet_letter) { dispatch(Action.ASSIGN) })
                tiles(tile(Page.SAVE.title, R.drawable.ic_pet_shell) { showPage(Page.SAVE) },
                    tile(Action.INBOX.label, R.drawable.ic_whale_nav_tasks) { dispatch(Action.INBOX) })
                body.addView(android.view.View(context).apply { setBackgroundColor(ui.line) }, LinearLayout.LayoutParams(-1, dp(1)))
                group(Page.TOOLS); group(Page.DEVICE)
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
                listOf(Action.HARNESS, Action.DISPLAY, Action.APP, Action.HIDE).forEach(::row)
                if (accessibilityAvailable()) row(Action.DISABLE)
                else body.addView(button(R.string.pet_menu_disabled) {}.apply { isEnabled = false; setTextColor(ui.muted) })
            }
        }
    }
}
