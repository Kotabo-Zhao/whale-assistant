package io.deepseekharness.mobile.companion

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.StrikethroughSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

/** Small offline renderer for the chat formats the model is asked to use. */
internal object CompanionRichText {
    private val inline = Regex("""(\*\*[^\n*]+\*\*|__[^\n_]+__|~~[^\n~]+~~|`[^`\n]+`|\$[^$\n]+\$|\\\([^\n]+?\\\)|\[[^\]\n]+\]\(https?://[^\s)]+\))""")
    private val latexSymbols = mapOf(
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ", "\\epsilon" to "ε",
        "\\theta" to "θ", "\\lambda" to "λ", "\\mu" to "μ", "\\pi" to "π", "\\sigma" to "σ",
        "\\phi" to "φ", "\\omega" to "ω", "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ",
        "\\Lambda" to "Λ", "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Phi" to "Φ", "\\Omega" to "Ω",
        "\\times" to "×", "\\cdot" to "·", "\\pm" to "±", "\\leq" to "≤", "\\geq" to "≥",
        "\\neq" to "≠", "\\infty" to "∞", "\\sum" to "∑", "\\int" to "∫",
    )

    /** Pure visible-text projection for local tests and accessibility labels. */
    fun visibleText(source: String): String {
        var fenced = false
        return source.replace("\r\n", "\n").replace('\r', '\n').split('\n').mapNotNull { raw ->
            if (raw.trim().startsWith("```")) {
                fenced = !fenced
                null
            } else if (fenced) raw
            else {
                val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(raw)
                val blockMath = raw.trim().takeIf { it.startsWith("$$") && it.endsWith("$$") && it.length > 4 }
                when {
                    heading != null -> inlineText(heading.groupValues[2])
                    blockMath != null -> latex(blockMath.removePrefix("$$").removeSuffix("$$"))
                    raw.trimStart().startsWith("> ") -> "│ ${inlineText(raw.trimStart().removePrefix("> "))}"
                    else -> Regex("^\\s*[-*+]\\s+(.+)$").matchEntire(raw)?.let { "• ${inlineText(it.groupValues[1])}" } ?: inlineText(raw)
                }
            }
        }.joinToString("\n")
    }

    fun render(source: String): Spanned {
        val result = SpannableStringBuilder()
        var fenced = false
        source.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEachIndexed { index, raw ->
            if (raw.trim().startsWith("```")) {
                fenced = !fenced
                if (index > 0) result.append('\n')
                return@forEachIndexed
            }
            if (index > 0) result.append('\n')
            val start = result.length
            if (fenced) {
                result.append(raw)
                code(result, start, result.length)
                return@forEachIndexed
            }
            val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(raw)
            val blockMath = raw.trim().takeIf { it.startsWith("$$") && it.endsWith("$$") && it.length > 4 }
            when {
                heading != null -> {
                    appendInline(result, heading.groupValues[2])
                    result.setSpan(StyleSpan(Typeface.BOLD), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    result.setSpan(RelativeSizeSpan((1.45f - (heading.groupValues[1].length - 1) * .09f).coerceAtLeast(1.0f)), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                blockMath != null -> {
                    result.append(latex(blockMath.removePrefix("$$").removeSuffix("$$")))
                    math(result, start, result.length)
                }
                raw.trimStart().startsWith("> ") -> {
                    result.append("│ ")
                    appendInline(result, raw.trimStart().removePrefix("> "))
                    result.setSpan(ForegroundColorSpan(Color.rgb(74, 99, 125)), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    val bullet = Regex("^\\s*[-*+]\\s+(.+)$").matchEntire(raw)
                    if (bullet != null) {
                        result.append("• ")
                        appendInline(result, bullet.groupValues[1])
                    } else appendInline(result, raw)
                }
            }
        }
        return result
    }

    private fun appendInline(result: SpannableStringBuilder, value: String) {
        var offset = 0
        inline.findAll(value).forEach { match ->
            result.append(value.substring(offset, match.range.first))
            val token = match.value
            val start = result.length
            when {
                token.startsWith("**") || token.startsWith("__") -> {
                    result.append(token.substring(2, token.length - 2))
                    result.setSpan(StyleSpan(Typeface.BOLD), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                token.startsWith("~~") -> {
                    result.append(token.substring(2, token.length - 2))
                    result.setSpan(StrikethroughSpan(), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                token.startsWith('`') -> {
                    result.append(token.substring(1, token.length - 1))
                    code(result, start, result.length)
                }
                token.startsWith('$') -> {
                    result.append(latex(token.substring(1, token.length - 1)))
                    math(result, start, result.length)
                }
                token.startsWith("\\(") -> {
                    result.append(latex(token.substring(2, token.length - 2)))
                    math(result, start, result.length)
                }
                token.startsWith('[') -> {
                    val label = token.substringAfter('[').substringBefore(']')
                    val url = token.substringAfter("(").removeSuffix(")")
                    result.append(label)
                    result.setSpan(URLSpan(url), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    result.setSpan(ForegroundColorSpan(Color.rgb(22, 104, 174)), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            offset = match.range.last + 1
        }
        result.append(value.substring(offset))
    }

    private fun inlineText(value: String): String = inline.replace(value) { match ->
        val token = match.value
        when {
            token.startsWith("**") || token.startsWith("__") || token.startsWith("~~") -> token.substring(2, token.length - 2)
            token.startsWith('`') -> token.substring(1, token.length - 1)
            token.startsWith('$') -> latex(token.substring(1, token.length - 1))
            token.startsWith("\\(") -> latex(token.substring(2, token.length - 2))
            token.startsWith('[') -> token.substringAfter('[').substringBefore(']')
            else -> token
        }
    }

    private fun latex(value: String): String {
        var rendered = value.trim()
        repeat(3) {
            rendered = Regex("\\\\frac\\{([^{}]+)}\\{([^{}]+)}").replace(rendered) { "${it.groupValues[1]}⁄${it.groupValues[2]}" }
            rendered = Regex("\\\\sqrt\\{([^{}]+)}").replace(rendered) { "√${it.groupValues[1]}" }
        }
        latexSymbols.forEach { (latex, glyph) -> rendered = rendered.replace(latex, glyph) }
        return rendered.replace("\\left", "").replace("\\right", "").replace("\\,", " ").replace("\\ ", " ")
    }

    private fun code(text: SpannableStringBuilder, start: Int, end: Int) {
        if (end <= start) return
        text.setSpan(TypefaceSpan(Typeface.MONOSPACE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(BackgroundColorSpan(Color.rgb(239, 242, 247)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun math(text: SpannableStringBuilder, start: Int, end: Int) {
        code(text, start, end)
        text.setSpan(ForegroundColorSpan(Color.rgb(77, 52, 120)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
