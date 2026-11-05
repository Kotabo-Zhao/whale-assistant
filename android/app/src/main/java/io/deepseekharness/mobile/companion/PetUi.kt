package io.deepseekharness.mobile.companion

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
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
    private val simple = context.getSharedPreferences("whale-interface", 0).getString("style", "cute") == "simple"
    val surface = palette.getColor(if (simple) R.color.pet_simple_surface else R.color.pet_surface)
    val canvas = palette.getColor(if (simple) R.color.pet_simple_canvas else R.color.pet_canvas)
    val ink = palette.getColor(if (simple) R.color.pet_simple_ink else R.color.pet_ink)
    val muted = palette.getColor(if (simple) R.color.pet_simple_muted else R.color.pet_muted)
    val accent = palette.getColor(if (simple) R.color.pet_simple_accent else R.color.pet_accent)
    val rose = palette.getColor(R.color.pet_rose)
    val honey = palette.getColor(R.color.pet_honey)
    val soft = palette.getColor(if (simple) R.color.pet_simple_soft else R.color.pet_soft)
    val primary = palette.getColor(if (simple) R.color.pet_simple_primary else R.color.pet_primary)
    val onPrimary = palette.getColor(if (simple) R.color.pet_simple_on_primary else R.color.pet_on_primary)
    val line = palette.getColor(if (simple) R.color.pet_simple_line else R.color.pet_line)
    val alert = palette.getColor(R.color.pet_alert)
    val answer = palette.getColor(if (simple) R.color.pet_simple_answer else R.color.pet_answer)
    fun dp(n: Int) = (n * context.resources.displayMetrics.density).roundToInt()
    fun string(id: Int): String = palette.getString(id)
    fun shape(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(if (simple) minOf(12, radius) else maxOf(12, radius)).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    fun action(fill: Int, radius: Int, stroke: Int? = null) =
        RippleDrawable(ColorStateList.valueOf((accent and 0x00ffffff) or 0x24000000), shape(fill, radius, stroke), null)

    fun text(value: CharSequence, size: Float, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    fun leadingIcon(view: TextView, id: Int, size: Int = 18) = with(view) {
        val icon = requireNotNull(palette.getDrawable(id)).mutate().apply {
            setTint(currentTextColor); setBounds(0, 0, dp(size), dp(size))
        }
        setCompoundDrawablesRelative(icon, null, null, null); compoundDrawablePadding = dp(6)
    }
    fun header(title: String, subtitle: String? = null, end: View? = null, icon: Int = R.drawable.ic_pet_whale): LinearLayout {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(10), dp(8))
        }
        val tint = when (icon) { R.drawable.ic_whale_nav_memory -> rose; R.drawable.ic_pet_assign -> honey; else -> soft }
        row.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(if (simple) surface else tint, surface, surface)).apply {
            val r = dp(if (simple) 7 else 20).toFloat()
            cornerRadii = floatArrayOf(r,r,r,r,0f,0f,0f,0f)
        }
        if (simple) {
            row.addView(android.widget.ImageView(context).apply {
                setImageDrawable(palette.getDrawable(icon)?.mutate()?.apply { setTint(accent) })
                setPadding(dp(4),dp(4),dp(4),dp(4))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(32),dp(32)))
        } else row.addView(PetPortrait(context, this, icon), LinearLayout.LayoutParams(dp(52), dp(52)))
        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(2), 0)
            addView(text(title, 17f, ink, true).apply { ViewCompat.setAccessibilityHeading(this, true) })
            if (!subtitle.isNullOrBlank()) addView(text(subtitle, 12f, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (end != null) row.addView(end)
        return row
    }

    fun close(onClick: () -> Unit) = text("×", 22f, muted).apply {
        contentDescription = string(R.string.pet_menu_close); gravity = Gravity.CENTER
        minWidth = dp(48); minHeight = dp(48); background = action(surface, 18)
        isFocusable = true; setOnClickListener { onClick() }
    }

    fun editor(field: EditText) = field.apply {
        setTextColor(ink); setHintTextColor(muted); textSize = 15f
        gravity = Gravity.TOP or Gravity.START
        imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        setPadding(dp(12), dp(10), dp(12), dp(10)); background = shape(canvas, 12, line)
    }

    fun dialogBuilder(title: String, subtitle: String?, icon: Int, end: View? = null): AlertDialog.Builder {
        val dark = palette.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val theme = if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
        return AlertDialog.Builder(context, theme).setCustomTitle(header(title, subtitle, end, icon))
    }

    fun finishDialog(dialog: AlertDialog, widthDp: Int = 364) {
        dialog.window?.apply {
            setBackgroundDrawable(shape(surface, 20, line))
            setLayout(minOf(dp(widthDp), (context.resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(1)),
                WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        for (id in listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE)) {
            dialog.getButton(id)?.apply {
                isAllCaps = false; minHeight = dp(48)
                setTextColor(if (id == AlertDialog.BUTTON_POSITIVE) onPrimary else accent)
                background = action(if (id == AlertDialog.BUTTON_POSITIVE) primary else surface, 12)
            }
        }
    }
}
