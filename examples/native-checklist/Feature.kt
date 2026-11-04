package io.deepseekharness.candidate

import android.content.res.Configuration
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object Feature {

    fun create(context: android.content.Context): android.view.View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()

        val isLandscape =
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val input = EditText(context).apply {
            contentDescription = "原始清单"
            hint = "每行一条"
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            setTextIsSelectable(true)
        }

        val result = TextView(context).apply {
            contentDescription = "整理结果"
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            minLines = 4
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val resultScroll = ScrollView(context).apply {
            isFillViewport = true
            addView(
                result,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val run = Button(context).apply {
            text = "整理清单"
            setOnClickListener { result.text = dedupe(input.text.toString()) }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        if (isLandscape) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            row.addView(
                input,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
            row.addView(
                resultScroll,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = dp(12)
                }
            )
            root.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
            root.addView(
                run,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
            )
        } else {
            root.addView(
                input,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
            root.addView(
                run,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
            )
            root.addView(
                resultScroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    topMargin = dp(12)
                }
            )
        }

        return root
    }

    private fun dedupe(raw: String): String {
        val seen = LinkedHashSet<String>()
        val lines = ArrayList<String>()
        for (line in raw.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (seen.add(trimmed)) lines.add(trimmed)
        }
        return lines.joinToString("\n")
    }
}
