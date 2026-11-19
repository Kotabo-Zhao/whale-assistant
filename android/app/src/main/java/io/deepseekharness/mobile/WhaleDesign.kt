package io.deepseekharness.mobile

import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import org.json.JSONObject

/**
 * Shared semantic palette for every native host, generated tools, floating pet, and WebView.
 * - simple (`sea = true`):  Grok-inspired monochrome minimalism (crisp light / true OLED dark)
 * - cute   (`sea = false`): Whale-Chan ocean-sky kawaii style (soft cloud light / twilight ocean dark)
 */
internal data class WhaleDesign(
    val sea: Boolean, val dark: Boolean, val canvas: Int, val surface: Int, val raised: Int,
    val ink: Int, val muted: Int, val line: Int, val accent: Int, val onAccent: Int, val soft: Int,
) {
    val radius get() = if (sea) 12 else 24
    val nativeStyle get() = when {
        sea && dark -> R.style.WhaleFamilySeaDark
        sea -> R.style.WhaleFamilySeaLight
        dark -> R.style.WhaleFamilyMoonDark
        else -> R.style.WhaleFamilyMoonLight
    }
    fun hex(color: Int) = "#%06x".format(color and 0xffffff)
    fun webTheme() = JSONObject().put("mode", if (dark) "dark" else "light").put("accent", hex(accent))
        .put("background", hex(canvas)).put("surface", hex(surface)).put("surfaceOpacity", 100)
        .put("controlOpacity", 100).put("sidebarOpacity", 100).put("rightPanelOpacity", 100)
    fun variables(): String = listOf(
        "--whale-background:" + hex(canvas), "--whale-surface:" + hex(surface), "--whale-raised:" + hex(raised),
        "--whale-text:" + hex(ink), "--whale-muted:" + hex(muted), "--whale-border:" + hex(line),
        "--whale-accent:" + hex(accent), "--whale-on-accent:" + hex(onAccent), "--whale-soft:" + hex(soft),
        "--whale-radius:" + radius + "px",
    ).joinToString(";") + ";"

    fun color(name: String): Int? = when (name.removePrefix("pet_simple_").removePrefix("pet_")) {
        "canvas", "harness_toolbar_background", "overlay_panel_background", "app_window_decoration" -> canvas
        "surface", "harness_page_background", "harness_search_surface" -> surface
        "answer", "harness_search_action", "overlay_panel_action_pressed" -> raised
        "ink", "harness_toolbar_foreground", "overlay_panel_foreground" -> ink
        "muted", "harness_search_muted", "overlay_panel_muted_foreground" -> muted
        "line", "harness_search_border", "overlay_ball_stroke" -> line
        "accent", "primary", "harness_search_focus", "overlay_secondary_foreground" -> accent
        "on_primary" -> onAccent
        "soft", "overlay_secondary_background", "overlay_secondary_background_disabled", "overlay_ball_pressed" -> soft
        "overlay_ball_background" -> surface
        "alert" -> Color.parseColor(if (dark) "#ffb4b4" else "#a33742")
        "rose" -> Color.parseColor(if (dark) "#45333f" else "#f5e5ed")
        "honey" -> Color.parseColor(if (dark) "#463e2b" else "#fff0d6")
        else -> null
    }

    companion object {
        fun of(context: Context): WhaleDesign {
            val sea = context.getSharedPreferences("whale-interface", 0).getString("style", "cute") == "simple"
            val dark = AppThemePreference.isDark(AppThemePreference.current(context), AppThemePreference.systemNight(context))
            val values = when {
                // Grok-inspired OLED dark & crisp monochrome light
                sea && dark -> listOf("#000000", "#0d0d0f", "#16161a", "#fafafa", "#a1a1aa", "#26262c", "#f4f4f5", "#09090b", "#1f1f24")
                sea -> listOf("#fafafa", "#ffffff", "#f4f4f5", "#09090b", "#52525b", "#e4e4e7", "#18181b", "#ffffff", "#efefef")
                // Whale-Chan twilight ocean dark & cloud ocean-sky light
                dark -> listOf("#121626", "#1b2136", "#232b45", "#f0f5ff", "#a9b8d9", "#323d5e", "#8ec5ff", "#0f1d38", "#263654")
                else -> listOf("#f6f9ff", "#ffffff", "#edf4ff", "#1f2d47", "#5b6f91", "#d4e4ff", "#4f86eb", "#ffffff", "#e8f1ff")
            }.map(Color::parseColor)
            return WhaleDesign(sea, dark, values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8])
        }

        /** Context.getColor is final. Resolve the family at the shared resource boundary. */
        @Suppress("DEPRECATION")
        fun wrap(base: Context, design: WhaleDesign): Context {
            val original = base.resources
            val resources = object : Resources(original.assets, original.displayMetrics, original.configuration) {
                private fun mapped(id: Int): Int? = if (id ushr 24 == 0x7f)
                    runCatching { design.color(original.getResourceEntryName(id)) }.getOrNull() else null
                override fun getColor(id: Int, theme: Theme?): Int = mapped(id) ?: original.getColor(id, theme)
                override fun getColor(id: Int): Int = mapped(id) ?: original.getColor(id)
                override fun getColorStateList(id: Int, theme: Theme?): ColorStateList =
                    mapped(id)?.let(ColorStateList::valueOf) ?: original.getColorStateList(id, theme)
            }
            return object : ContextWrapper(base) {
                private val familyTheme by lazy {
                    resources.newTheme().apply {
                        setTo(base.theme)
                        applyStyle(design.nativeStyle, true)
                    }
                }
                override fun getResources() = resources
                override fun getTheme(): Resources.Theme = familyTheme
            }
        }
    }
}
