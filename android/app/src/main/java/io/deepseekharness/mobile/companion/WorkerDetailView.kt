package io.deepseekharness.mobile.companion

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** One page of one task. Keep results visible while leaving long inputs and mandate text within reach. */
internal class WorkerDetailView(context: Context, row: JSONObject) : ScrollView(context) {
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun dp(value: Int) = (resources.displayMetrics.density * value + .5f).toInt()
    private val ui = PetUi(context)
    private fun text(value: String, size: Float, muted: Boolean = false, bold: Boolean = false) =
        TextView(context).apply {
            text = value; textSize = size
            setTextColor(if (muted) ui.muted else ui.ink)
            if (bold) setTypeface(typeface,Typeface.BOLD)
        }

    private fun heading(value: String) {
        content.addView(text(value,15f,bold=true).apply { setPadding(0,dp(12),0,dp(3)) })
    }

    private fun toggle(label: String, onClick: (TextView) -> Unit) = text(label,13f).apply {
        minHeight = dp(44); gravity = android.view.Gravity.CENTER_VERTICAL; isFocusable = true
        setTextColor(ui.accent); setOnClickListener { onClick(this) }
    }

    private fun paragraph(value: String, limit: Int, label: String) {
        val preview = value.lineSequence().take(limit).joinToString("\n").take(limit*38)
            .dropLastWhile { it.isHighSurrogate() }.trimEnd()
        val shortened = preview.length < value.length
        val excerpt = if (shortened) "$preview…" else value
        val view = text(excerpt,15f).apply {
            setLineSpacing(dp(2).toFloat(),1f); setTextIsSelectable(true)
        }
        content.addView(view)
        if (shortened) {
            var expanded = false
            content.addView(toggle("展开$label") { button ->
                expanded = !expanded
                view.text = if (expanded) value else excerpt
                button.text = if (expanded) "收起$label" else "展开$label"
            })
        }
    }

    init {
        content.setPadding(dp(20),dp(4),dp(20),dp(8)); addView(content)
        val state = when (row.optString("state")) {
            "waiting" -> "待回答"; "running" -> "执行中"; "completed" -> "已完成"; "queued" -> "排队中"
            "starting" -> "启动中"; "failed" -> "未完成"; "blocked" -> "遇到阻碍"; "interrupted" -> "中断待确认"
            "cancelled" -> "已取消"; "cancelling" -> "正在取消"; else -> "历史工单"
        }
        val mode = when (row.optString("mode")) { "create" -> "创作工具"; "device" -> "设备操作"; else -> "读取整理" }
        content.addView(text("$state  ·  $mode",13f,muted=true))
        for ((key,label) in listOf("question" to "待回答","summary" to "最新结果","progress" to "当前进展","uiError" to "提示")) {
            val value = row.optString(key)
            if (value.isNotBlank()) { heading(label); paragraph(value,4,label) }
        }
        row.optString("goalOutcome").takeIf { it.isNotBlank() }?.let {
            heading("目标与完成条件"); paragraph(it,3,"目标")
            val criteria=row.optJSONArray("goalCriteria")
            for(index in 0 until (criteria?.length() ?: 0)) content.addView(text("• " + criteria!!.getString(index),14f))
            content.addView(text(when(row.optString("goalState")) {
                "verified" -> "协调会话已逐项核验"
                "verification" -> "子任务已返回，目标尚待协调会话核验"
                "budget" -> "总预算已用完，请检查进展后继续"
                else -> "目标仍在推进"
            },13f,muted=true))
        }
        row.optJSONObject("budgetUsage")?.let { used ->
            val limit=row.optJSONObject("budget")
            heading("本工单资源")
            content.addView(text("已记录 "+used.optLong("tokens")+" tokens · "+used.optLong("calls")+" 次工具调用 · "+used.optLong("milliseconds")/60000+" 分钟",13f,muted=true))
            if(limit!=null) content.addView(text("预算 "+limit.optLong("tokens")+" tokens · "+limit.optLong("calls")+" 次 · "+limit.optLong("milliseconds")/60000+" 分钟",13f,muted=true))
        }
        val mandate = row.optString("scope")
        val scope = listOf(row.optString("title").takeUnless { mandate.startsWith(it) }.orEmpty(), mandate,
            row.optString("decisionReason"), row.optString("decisionEvidence")).filter { it.isNotBlank() }.distinct().joinToString("\n\n")
        if (scope.isNotEmpty()) {
            val full = text(scope,14f).apply { setTextIsSelectable(true); visibility = View.GONE }
            content.addView(toggle("查看任务范围与依据") { button ->
                val expanded = full.visibility == View.GONE
                full.visibility = if (expanded) View.VISIBLE else View.GONE
                button.text = if (expanded) "收起任务范围与依据" else "查看任务范围与依据"
            })
            content.addView(full)
        }
        val events = row.getJSONArray("events")
        if (events.length() > 0) {
            heading("执行记录")
            val total = row.getInt("historyCount"); val offset = row.getInt("historyOffset")
            content.addView(text("第 ${total-offset-events.length()+1}–${total-offset} 条 · 共 $total 条",12f,muted=true))
        }
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val kind = when (event.getString("type")) {
                "progress" -> "进展"; "report" -> "回执"
                else -> when (event.optString("kind")) { "reply" -> "补充回答"; "update" -> "追加信息"; "resume" -> "继续说明"; else -> "任务输入" }
            }
            val label = if (event.has("revision")) "第 ${event.getLong("revision")} 轮 · $kind" else kind
            content.addView(text(label,13f,muted=true,bold=true).apply { setPadding(0,dp(10),0,dp(2)) })
            paragraph(event.optString("text",event.optString("summary")),3,"记录")
        }
    }
}
