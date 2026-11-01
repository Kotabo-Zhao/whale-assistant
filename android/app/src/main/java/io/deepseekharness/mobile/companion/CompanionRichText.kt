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
    private val inline = Regex("""(\*\*[^\n*]+\*\*|__[^\n_]+__|~~[^\n~]+~~|`[^`\n]+`|\$[^$\n]+\$|\\\([^\n]+?\\\)|\[[^\]\n]+\]\(https?://[^\s)]+\)|(?<!\*)\*[^*\n]+\*(?!\*)|(?<!_)_[^_\n]+_(?!_))""")
    private val latexSymbols = mapOf(
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ", "\\epsilon" to "ε",
        "\\theta" to "θ", "\\lambda" to "λ", "\\mu" to "μ", "\\pi" to "π", "\\sigma" to "σ",
        "\\phi" to "φ", "\\omega" to "ω", "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ",
        "\\Lambda" to "Λ", "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Phi" to "Φ", "\\Omega" to "Ω",
        "\\times" to "×", "\\cdot" to "·", "\\pm" to "±", "\\leq" to "≤", "\\geq" to "≥",
        "\\neq" to "≠", "\\infty" to "∞", "\\sum" to "∑", "\\int" to "∫",
    )

    private data class Block(val text: String, val fenced: Boolean = false, val math: Boolean = false)

    /** Pure visible-text projection for local tests and accessibility labels. */
    fun visibleText(source: String): String {
        return blocks(source).mapNotNull { block ->
            if (block.fenced) block.text
            else if (block.math) latex(block.text)
            else {
                val raw = block.text
                val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(raw)
                when {
                    heading != null -> inlineText(heading.groupValues[2])
                    raw.trimStart().startsWith("> ") -> "│ ${inlineText(raw.trimStart().removePrefix("> "))}"
                    else -> listPrefix(raw)?.let { "${it.first}${inlineText(it.second)}" } ?: inlineText(raw)
                }
            }
        }.joinToString("\n")
    }

    fun render(source: String): Spanned {
        val result = SpannableStringBuilder()
        blocks(source).forEachIndexed { index, block ->
            if (index > 0) result.append('\n')
            val start = result.length
            if (block.fenced) {
                result.append(block.text)
                code(result, start, result.length)
                return@forEachIndexed
            }
            if (block.math) {
                result.append(latex(block.text))
                math(result, start, result.length)
                return@forEachIndexed
            }
            val raw = block.text
            val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(raw)
            when {
                heading != null -> {
                    appendInline(result, heading.groupValues[2])
                    result.setSpan(StyleSpan(Typeface.BOLD), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    result.setSpan(RelativeSizeSpan((1.45f - (heading.groupValues[1].length - 1) * .09f).coerceAtLeast(1.0f)), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                raw.trimStart().startsWith("> ") -> {
                    result.append("│ ")
                    appendInline(result, raw.trimStart().removePrefix("> "))
                    result.setSpan(ForegroundColorSpan(Color.rgb(74, 99, 125)), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    val prefix = listPrefix(raw)
                    if (prefix != null) { result.append(prefix.first); appendInline(result, prefix.second) } else appendInline(result, raw)
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
                token.startsWith('*') || token.startsWith('_') -> {
                    result.append(token.substring(1, token.length - 1))
                    result.setSpan(StyleSpan(Typeface.ITALIC), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
            token.startsWith('*') || token.startsWith('_') -> token.substring(1, token.length - 1)
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
        rendered = Regex("\\\\(?:text|mathrm|mathbf|mathit|operatorname)\\{([^{}]*)}").replace(rendered) { it.groupValues[1] }
        rendered = Regex("\\^\\{([^{}]+)}").replace(rendered) { superscript(it.groupValues[1]) }
        rendered = Regex("_\\{([^{}]+)}").replace(rendered) { subscript(it.groupValues[1]) }
        rendered = Regex("\\^([A-Za-z0-9+=()\\-])").replace(rendered) { superscript(it.groupValues[1]) }
        rendered = Regex("_([A-Za-z0-9+=()\\-])").replace(rendered) { subscript(it.groupValues[1]) }
        return rendered.replace("\\left", "").replace("\\right", "").replace("\\displaystyle", "")
            .replace("\\quad", "  ").replace("\\,", " ").replace("\\ ", " ").replace("\\\\", "\n")
    }

    private fun superscript(value: String): String {
        val plain = "0123456789+-=()abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val raised = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return value.map { char -> plain.indexOf(char).takeIf { it >= 0 }?.let(raised::get) ?: char }.joinToString("")
    }
    private fun subscript(value: String): String = value.map { "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎".getOrNull("0123456789+-=()".indexOf(it)) ?: it }.joinToString("")

    private fun listPrefix(raw: String): Pair<String, String>? {
        Regex("^\\s*[-*+]\\s+(.+)$").matchEntire(raw)?.let { return "• " to it.groupValues[1] }
        Regex("^\\s*(\\d+[.)])\\s+(.+)$").matchEntire(raw)?.let { return "${it.groupValues[1]} " to it.groupValues[2] }
        return null
    }

    /** Groups multi-line $$...$$ and \\[...\\] math before ordinary Markdown line rendering. */
    private fun blocks(source: String): List<Block> {
        val output = mutableListOf<Block>(); var fenced = false; var mathEnd: String? = null; val math = StringBuilder()
        fun closeMath() { output.add(Block(math.toString().trim(), math = true)); math.setLength(0); mathEnd = null }
        for (raw in source.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if (mathEnd != null) {
                val end = requireNotNull(mathEnd)
                val at = raw.indexOf(end)
                if (at >= 0) { math.append(raw.substring(0, at)); closeMath(); val tail = raw.substring(at + end.length); if (tail.isNotBlank()) output.add(Block(tail)) }
                else { if (math.isNotEmpty()) math.append('\n'); math.append(raw) }
                continue
            }
            if (raw.trim().startsWith("```")) { fenced = !fenced; continue }
            if (fenced) { output.add(Block(raw, fenced = true)); continue }
            val trimmed = raw.trim()
            val marker = when { trimmed.startsWith("$$") -> "$$"; trimmed.startsWith("\\[") -> "\\]"; else -> null }
            if (marker != null) {
                val body = trimmed.removePrefix(if (marker == "$$") "$$" else "\\[")
                val end = body.indexOf(marker)
                if (end >= 0) output.add(Block(body.substring(0, end).trim(), math = true))
                else { math.append(body); mathEnd = marker }
            } else output.add(Block(raw))
        }
        if (mathEnd != null) output.add(Block(math.toString().trim(), math = true))
        return output
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
