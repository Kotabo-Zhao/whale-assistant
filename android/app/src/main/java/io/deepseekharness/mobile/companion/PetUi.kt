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

/** Shared typography, surfaces and controls for the pet's small windows. */
internal class PetUi(private val context: Context) {
    val palette = AppThemePreference.palette(AppLanguage.localizedContext(context))
    val dark: Boolean = (palette.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val stylePref = context.getSharedPreferences("whale-interface", 0).getString("style", "cute") ?: "cute"
    private val simple = stylePref == "simple"
    val usagi = stylePref == "usagi"
    val surface = if (usagi) Color.parseColor(if (dark) "#231A12" else "#FFFDF2") else palette.getColor(if (simple) R.color.pet_simple_surface else R.color.pet_surface)
    val canvas = if (usagi) Color.parseColor(if (dark) "#1A130D" else "#FFF9E2") else palette.getColor(if (simple) R.color.pet_simple_canvas else R.color.pet_canvas)
    val ink = if (usagi) Color.parseColor(if (dark) "#FEF3C7" else "#3B2613") else palette.getColor(if (simple) R.color.pet_simple_ink else R.color.pet_ink)
    val muted = if (usagi) Color.parseColor(if (dark) "#D6B88C" else "#8A6A47") else palette.getColor(if (simple) R.color.pet_simple_muted else R.color.pet_muted)
    val accent = if (usagi) Color.parseColor(if (dark) "#FBBF24" else "#D97706") else palette.getColor(if (simple) R.color.pet_simple_accent else R.color.pet_accent)
    val rose = palette.getColor(R.color.pet_rose)
    val honey = palette.getColor(R.color.pet_honey)
    val soft = if (usagi) Color.parseColor(if (dark) "#342619" else "#FEF3C7") else palette.getColor(if (simple) R.color.pet_simple_soft else R.color.pet_soft)
    val primary = if (usagi) Color.parseColor(if (dark) "#F59E0B" else "#F59E0B") else palette.getColor(if (simple) R.color.pet_simple_primary else R.color.pet_primary)
    val onPrimary = if (usagi) Color.parseColor("#2B1A08") else palette.getColor(if (simple) R.color.pet_simple_on_primary else R.color.pet_on_primary)
    val line = if (usagi) Color.parseColor(if (dark) "#4D3924" else "#F2D48B") else palette.getColor(if (simple) R.color.pet_simple_line else R.color.pet_line)
    val alert = palette.getColor(R.color.pet_alert)
    val answer = palette.getColor(if (simple) R.color.pet_simple_answer else R.color.pet_answer)
    fun dp(n: Int) = (n * context.resources.displayMetrics.density).roundToInt()
    fun string(id: Int): String = palette.getString(id)
    fun shape(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(if (simple) minOf(22, radius) else radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    fun action(fill: Int, radius: Int, stroke: Int? = null) =
        RippleDrawable(ColorStateList.valueOf((accent and 0x00ffffff) or 0x22000000), shape(fill, radius, stroke), null)

    fun divider() = View(context).apply {
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, dp(1)))
    }

    fun text(value: CharSequence, size: Float, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    fun leadingIcon(view: TextView, id: Int, size: Int = 16) = with(view) {
        val icon = requireNotNull(palette.getDrawable(id)).mutate().apply {
            setTint(currentTextColor); setBounds(0, 0, dp(size), dp(size))
        }
        setCompoundDrawablesRelative(icon, null, null, null); compoundDrawablePadding = dp(5)
    }
    fun header(title: String, subtitle: String? = null, end: View? = null, icon: Int = R.drawable.ic_pet_whale): LinearLayout {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(6), dp(10), dp(8))
        }
        row.background = GradientDrawable().apply {
            setColor(surface)
            val r = dp(if (simple) 18 else 22).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        if (simple) {
            row.addView(android.widget.ImageView(context).apply {
                setImageDrawable(palette.getDrawable(icon)?.mutate()?.apply { setTint(accent) })
                setPadding(dp(4), dp(4), dp(4), dp(4))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
        } else row.addView(PetPortrait(context, this, icon), LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(4), 0)
            addView(text(title, 15.5f, ink, true).apply { ViewCompat.setAccessibilityHeading(this, true) })
            if (!subtitle.isNullOrBlank()) addView(text(subtitle, 11.5f, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (end != null) row.addView(end)
        return row
    }

    fun close(onClick: () -> Unit) = text("×", 21f, muted).apply {
        contentDescription = string(R.string.pet_menu_close); gravity = Gravity.CENTER
        minWidth = dp(40); minHeight = dp(40); background = action(Color.TRANSPARENT, 12)
        isFocusable = true; setOnClickListener { io.deepseekharness.mobile.WhaleMotion.feedback(this); onClick() }
    }

    fun editor(field: EditText) = field.apply {
        setTextColor(ink); setHintTextColor(muted); textSize = 15f
        gravity = Gravity.TOP or Gravity.START
        imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        setPadding(dp(14), dp(12), dp(14), dp(12)); background = shape(canvas, 14, line)
    }

    fun dialogBuilder(title: String, subtitle: String?, icon: Int, end: View? = null): AlertDialog.Builder {
        val dark = palette.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val theme = if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
        return AlertDialog.Builder(context, theme).setCustomTitle(header(title, subtitle, end, icon))
    }

    fun finishDialog(dialog: AlertDialog, widthDp: Int = 364) {
        dialog.window?.apply {
            setWindowAnimations(if (io.deepseekharness.mobile.WhaleMotion.enabled()) R.style.WhaleOverlayMotion else 0)
            setBackgroundDrawable(shape(surface, 20, line))
            setLayout(minOf(dp(widthDp), (context.resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(1)),
                WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        for (id in listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE)) {
            dialog.getButton(id)?.apply {
                isAllCaps = false; minHeight = dp(44)
                setTextColor(if (id == AlertDialog.BUTTON_POSITIVE) onPrimary else accent)
                background = action(if (id == AlertDialog.BUTTON_POSITIVE) primary else surface, 12)
            }
        }
    }
}
