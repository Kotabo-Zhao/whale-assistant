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
 * Shared Studio V2 typography, surfaces, and tactile controls for all native overlay
 * windows (Pet Bubble Chat, Action Menu, Radial Drag Wheel, Worker Cards, Live Island, Dialogs).
 *
 * Mirrors the exact 3-world palette & material grammar of `src/whale-studio-v2.css`:
 *  - simple: Deep-Sea Swiss Instrument (precision 1dp hairlines, sapphire signal)
 *  - cute:   Pelagic Porcelain & Glass (luminous cerulean + oceanic depth)
 *  - usagi:  Nagano Comic Zine (butter cream paper, 1.8dp cocoa ink stroke, warm honey)
 */
internal class PetUi(private val context: Context) {
    val palette = AppThemePreference.palette(AppLanguage.localizedContext(context))
    val dark: Boolean = (palette.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val stylePref = context.getSharedPreferences("whale-interface", 0).getString("style", "cute") ?: "cute"
    val simple = stylePref == "simple"
    val usagi = stylePref == "usagi"

    val surface: Int = when {
        usagi -> Color.parseColor(if (dark) "#241A12" else "#FFFDF6")
        simple -> Color.parseColor(if (dark) "#0E131B" else "#FFFFFF")
        else -> Color.parseColor(if (dark) "#101E34" else "#FFFFFF")
    }
    val canvas: Int = when {
        usagi -> Color.parseColor(if (dark) "#18110B" else "#FFFBEA")
        simple -> Color.parseColor(if (dark) "#06080C" else "#F4F5F7")
        else -> Color.parseColor(if (dark) "#08111F" else "#EEF6FF")
    }
    val ink: Int = when {
        usagi -> Color.parseColor(if (dark) "#FEF3C7" else "#3A2312")
        simple -> Color.parseColor(if (dark) "#EDF1F7" else "#0B0F17")
        else -> Color.parseColor(if (dark) "#ECF4FF" else "#12263F")
    }
    val muted: Int = when {
        usagi -> Color.parseColor(if (dark) "#D5B78C" else "#846142")
        simple -> Color.parseColor(if (dark) "#8A97AB" else "#576071")
        else -> Color.parseColor(if (dark) "#8AA5C9" else "#587497")
    }
    val accent: Int = when {
        usagi -> Color.parseColor(if (dark) "#FBBF24" else "#E08200")
        simple -> Color.parseColor(if (dark) "#60A5FA" else "#0F52BA")
        else -> Color.parseColor(if (dark) "#5CC8FF" else "#1B7EF2")
    }
    val rose: Int = when {
        usagi -> Color.parseColor(if (dark) "#F472B6" else "#FF6B8B")
        else -> Color.parseColor(if (dark) "#FF8FB8" else "#FF6B9D")
    }
    val honey: Int = palette.getColor(R.color.pet_honey)
    val soft: Int = when {
        usagi -> Color.parseColor(if (dark) "#332417" else "#FEF0C3")
        simple -> Color.parseColor(if (dark) "#17202E" else "#EBEDF2")
        else -> Color.parseColor(if (dark) "#142540" else "#E3EFFC")
    }
    val primary: Int = when {
        usagi -> Color.parseColor(if (dark) "#FBBF24" else "#F59E0B")
        simple -> Color.parseColor(if (dark) "#60A5FA" else "#0F52BA")
        else -> Color.parseColor(if (dark) "#5CC8FF" else "#1B7EF2")
    }
    val onPrimary: Int = when {
        usagi -> Color.parseColor("#2B1707")
        simple -> Color.parseColor(if (dark) "#06080C" else "#FFFFFF")
        else -> Color.parseColor(if (dark) "#071221" else "#FFFFFF")
    }
    val line: Int = when {
        usagi -> Color.parseColor(if (dark) "#B47B38" else "#6B4423")
        simple -> Color.parseColor(if (dark) "#243042" else "#DCE2EC")
        else -> Color.parseColor(if (dark) "#233C61" else "#CBE0FA")
    }
    val alert: Int = palette.getColor(R.color.pet_alert)
    val answer: Int = when {
        usagi -> Color.parseColor(if (dark) "#2C1F14" else "#FFF9E4")
        simple -> Color.parseColor(if (dark) "#151C28" else "#F8F9FB")
        else -> Color.parseColor(if (dark) "#172A47" else "#F5FAFF")
    }

    fun dp(n: Int) = (n * context.resources.displayMetrics.density).roundToInt()
    fun string(id: Int): String = palette.getString(id)

    fun shape(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        val tunedRadius = when {
            simple -> minOf(18, radius)
            usagi -> minOf(22, radius)
            else -> radius
        }
        cornerRadius = dp(tunedRadius).toFloat()
        if (stroke != null) {
            val strokePx = if (usagi) maxOf(dp(2), (1.6f * context.resources.displayMetrics.density).roundToInt()) else maxOf(1, dp(1))
            setStroke(strokePx, stroke)
        }
    }

    fun action(fill: Int, radius: Int, stroke: Int? = null) =
        RippleDrawable(ColorStateList.valueOf((accent and 0x00ffffff) or 0x26000000), shape(fill, radius, stroke), null)

    fun divider() = View(context).apply {
        setBackgroundColor(line)
        alpha = if (usagi) 0.45f else 0.7f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, dp(1)))
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
            setPadding(dp(16), dp(10), dp(10), dp(10))
        }
        row.background = GradientDrawable().apply {
            setColor(surface)
            val r = dp(if (simple) 16 else 22).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        if (simple) {
            row.addView(android.widget.ImageView(context).apply {
                setImageDrawable(palette.getDrawable(icon)?.mutate()?.apply { setTint(accent) })
                setPadding(dp(5), dp(5), dp(5), dp(5))
                background = shape(soft, 10)
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
        background = action(Color.TRANSPARENT, 14)
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
        background = shape(canvas, if (simple) 12 else 16, line)
    }

    fun dialogBuilder(title: String, subtitle: String?, icon: Int, end: View? = null): AlertDialog.Builder {
        val darkMode = palette.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val theme = if (darkMode) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
        return AlertDialog.Builder(context, theme).setCustomTitle(header(title, subtitle, end, icon))
    }

    fun finishDialog(dialog: AlertDialog, widthDp: Int = 364) {
        dialog.window?.apply {
            setWindowAnimations(if (io.deepseekharness.mobile.WhaleMotion.enabled()) R.style.WhaleOverlayMotion else 0)
            setBackgroundDrawable(shape(surface, if (simple) 16 else 22, line))
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
                background = action(
                    if (id == AlertDialog.BUTTON_POSITIVE) primary else surface,
                    if (simple) 10 else 14,
                    if (usagi && id == AlertDialog.BUTTON_POSITIVE) line else null,
                )
            }
        }
    }
}
