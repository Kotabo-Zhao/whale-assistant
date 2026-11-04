package io.deepseekharness.mobile

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** 应用语言独立于系统语言保存；仅支持明确列出的语言。 */
internal object AppLanguage {
    private const val PREFERENCES = "app_language"
    private const val KEY = "language"

    fun save(context: Context, language: String): Boolean {
        require(language == "zh-CN" || language == "en") { "不支持的应用语言" }
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(KEY, language).commit()
    }

    /** 没有手动选择时，使用系统首选的受支持语言。 */
    fun current(context: Context): String = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(KEY, null)
        ?.takeIf { it == "en" || it == "zh-CN" }
        ?: android.content.res.Resources.getSystem().configuration.locales.let { locales ->
            (0 until locales.size()).firstNotNullOfOrNull { index ->
                when (locales[index].language) { "zh" -> "zh-CN"; "en" -> "en"; else -> null }
            } ?: "zh-CN"
        }

    fun localizedContext(context: Context): Context {
        val language = current(context)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return context.createConfigurationContext(configuration)
    }
}
