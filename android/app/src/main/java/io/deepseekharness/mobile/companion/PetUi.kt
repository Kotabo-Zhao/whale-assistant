package io.deepseekharness.mobile.companion

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import io.deepseekharness.mobile.AppLanguage
import io.deepseekharness.mobile.AppThemePreference
import io.deepseekharness.mobile.R
import kotlin.math.roundToInt

/**
 * Quiet-Luxury & Borderless Tonal Design Tokens for all native Android overlay windows.
 * Uses subtle tonal stepping + whisper-thin 1px translucent edges instead of heavy 2dp borders.
 */
internal class PetUi(private val context: Context) {
    val palette = AppThemePreference.palette(AppLanguage.localizedContext(context))
    val dark: Boolean = (palette.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val stylePref = context.getSharedPreferences("whale-interface", 0).getString("style", "cute") ?: "cute"
    val simple = stylePref == "simple"
    val usagi = stylePref == "usagi"

    val surface: Int = when {
        usagi -> Color.parseColor(if (dark) "#1F1A15" else "#FFFDF9")
        simple -> Color.parseColor(if (dark) "#12141A" else "#FFFFFF")
        else -> Color.parseColor(if (dark) "#111D30" else "#FFFFFF")
    }
    val canvas: Int = when {
        usagi -> Color.parseColor(if (dark) "#15120F" else "#FAF6EC")
        simple -> Color.parseColor(if (dark) "#090A0D" else "#F6F6F4")
        else -> Color.parseColor(if (dark) "#09111D" else "#F2F6FB")
    }
    val ink: Int = when {
        usagi -> Color.parseColor(if (dark) "#F5ECDC" else "#2F2319")
        simple -> Color.parseColor(if (dark) "#EEF0F4" else "#111318")
        else -> Color.parseColor(if (dark) "#EAF1FB" else "#162438")
    }
    val muted: Int = when {
        usagi -> Color.parseColor(if (dark) "#AD9A82" else "#826E5A")
        simple -> Color.parseColor(if (dark) "#8B92A1" else "#6B7280")
        else -> Color.parseColor(if (dark) "#8AA2C2" else "#647B99")
    }
    val accent: Int = when {
        usagi -> Color.parseColor(if (dark) "#EAB354" else "#D98B24")
        simple -> Color.parseColor(if (dark) "#6B9EFF" else "#1D4ED8")
        else -> Color.parseColor(if (dark) "#67B8FF" else "#2E7CF6")
    }
    val rose: Int = when {
        usagi -> Color.parseColor(if (dark) "#EB8DA0" else "#E87A90")
        else -> Color.parseColor(if (dark) "#F59BB9" else "#F27AA3")
    }
    val honey: Int = palette.getColor(R.color.pet_honey)
    val soft: Int = when {
        usagi -> Color.parseColor(if (dark) "#2A231C" else "#F4ECDA")
        simple -> Color.parseColor(if (dark) "#1B1F29" else "#EEEFEC")
        else -> Color.parseColor(if (dark) "#142238" else "#E8F0F9")
    }
    val primary: Int = when {
        usagi -> Color.parseColor(if (dark) "#D99634" else "#E29B36")
        simple -> Color.parseColor(if (dark) "#2563EB" else "#17191F")
        else -> Color.parseColor(if (dark) "#3B8EF5" else "#2E7CF6")
    }
    val onPrimary: Int = when {
        usagi -> Color.parseColor(if (dark) "#1A130B" else "#FFFFFF")
        else -> Color.parseColor("#FFFFFF")
    }
    // Whisper-quiet hairline alpha — eliminates heavy cartoon outlines
    val line: Int = when {
        usagi -> if (dark) Color.argb(26, 245, 224, 190) else Color.argb(24, 120, 86, 48)
        simple -> if (dark) Color.argb(20, 255, 255, 255) else Color.argb(18, 17, 19, 24)
        else -> if (dark) Color.argb(24, 180, 216, 255) else Color.argb(18, 22, 36, 56)
    }
    val alert: Int = palette.getColor(R.color.pet_alert)
    val answer: Int = when {
        usagi -> Color.parseColor(if (dark) "#26201A" else "#FAF5E8")
        simple -> Color.parseColor(if (dark) "#171A22" else "#F7F7F5")
        else -> Color.parseColor(if (dark) "#16263F" else "#F4F8FD")
    }

    fun dp(n: Int) = (n * context.resources.displayMetrics.density).roundToInt()
    fun string(id: Int): String = palette.getString(id)

    fun shape(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(if (simple) minOf(20, radius) else radius).toFloat()
        if (stroke != null) setStroke(1, stroke)
    }

    fun action(fill: Int, radius: Int, stroke: Int? = null) =
        RippleDrawable(ColorStateList.valueOf((accent and 0x00ffffff) or 0x1E000000), shape(fill, radius, stroke), null)

    fun divider() = View(context).apply {
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    fun text(value: CharSequence, size: Float, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        letterSpacing = if (bold) -0.015f else 0f
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun leadingIcon(view: TextView, id: Int, size: Int = 16) = with(view) {
        val icon = requireNotNull(palette.getDrawable(id)).mutate().apply {
            setTint(currentTextColor)
            setBounds(0, 0, dp(size), dp(size))
        }
        setCompoundDrawablesRelative(icon, null, null, null)
        compoundDrawablePadding = dp(6)
    }

    fun header(title: String, subtitle: String? = null, end: View? = null, icon: Int = R.drawable.ic_pet_whale): LinearLayout {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(10), dp(10))
        }
        row.background = GradientDrawable().apply {
            setColor(surface)
            val r = dp(if (simple) 18 else 24).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        if (simple) {
            row.addView(android.widget.ImageView(context).apply {
                setImageDrawable(palette.getDrawable(icon)?.mutate()?.apply { setTint(accent) })
                setPadding(dp(5), dp(5), dp(5), dp(5))
                background = shape(soft, 12)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
        } else {
            row.addView(PetPortrait(context, this, icon), LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(4), 0)
            addView(text(title, 15.5f, ink, true).apply { ViewCompat.setAccessibilityHeading(this, true) })
            if (!subtitle.isNullOrBlank()) addView(text(subtitle, 11.5f, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (end != null) row.addView(end)
        return row
    }

    fun close(onClick: () -> Unit) = text("×", 21f, muted).apply {
        contentDescription = string(R.string.pet_menu_close)
        gravity = Gravity.CENTER
        minWidth = dp(40)
        minHeight = dp(40)
        background = action(Color.TRANSPARENT, 999)
        isFocusable = true
        setOnClickListener { io.deepseekharness.mobile.WhaleMotion.feedback(this); onClick() }
    }

    fun editor(field: EditText) = field.apply {
        setTextColor(ink)
        setHintTextColor(muted)
        textSize = 15f
        gravity = Gravity.TOP or Gravity.START
        imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = shape(soft, 16)
    }

    fun dialogBuilder(title: String, subtitle: String?, icon: Int, end: View? = null): AlertDialog.Builder {
        val darkMode = palette.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val theme = if (darkMode) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
        return AlertDialog.Builder(context, theme).setCustomTitle(header(title, subtitle, end, icon))
    }

    fun finishDialog(dialog: AlertDialog, widthDp: Int = 364) {
        dialog.window?.apply {
            setWindowAnimations(if (io.deepseekharness.mobile.WhaleMotion.enabled()) R.style.WhaleOverlayMotion else 0)
            setBackgroundDrawable(shape(surface, 24, line))
            setLayout(
                minOf(dp(widthDp), (context.resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(1)),
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        for (id in listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE)) {
            dialog.getButton(id)?.apply {
                isAllCaps = false
                minHeight = dp(44)
                setTextColor(if (id == AlertDialog.BUTTON_POSITIVE) onPrimary else accent)
                background = action(if (id == AlertDialog.BUTTON_POSITIVE) primary else Color.TRANSPARENT, 999)
            }
        }
    }
}
