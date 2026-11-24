package io.deepseekharness.mobile.companion

import android.content.Context
import io.deepseekharness.mobile.R
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
        content.addView(text("●  $state  ·  $mode", 12.5f, bold = true).apply {
            setTextColor(ui.accent)
            background = ui.shape(ui.soft, 999, ui.line)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        })
        for ((key,label) in listOf("question" to "待回答","summary" to "最新结果","progress" to "当前进展","uiError" to "提示","supervisionNote" to "任务监督")) {
            val value = row.optString(key)
            if (value.isNotBlank()) { heading(label); paragraph(value,4,label) }
        }
        val taskRecovery=row.optJSONObject("recovery").takeIf{row.optBoolean("recoveryRequired")}
        val goalRecovery=row.optJSONObject("goalRecovery")
        if(taskRecovery!=null || goalRecovery!=null) {
            heading("恢复副本 · 待核对")
            val detail=StringBuilder()
            for(item in listOfNotNull(taskRecovery,goalRecovery)) {
                detail.append(item.optString("note")).append("\n")
                val preview=if(item.optBoolean("usable")) item.optJSONObject("preview") else item.optJSONObject("localPreview") ?: item.optJSONObject("preview")
                for((key,label) in listOf("title" to "标题","input" to "原请求","summary" to "先前结果","outcome" to "预期结果","criteria" to "完成条件","budgetUsage" to "已记录用量","budget" to "预算")) {
                    val value=preview?.opt(key)
                    if(value!=null && value!=JSONObject.NULL) {
                        val display=when(value) {
                            is JSONObject -> "${value.optLong("tokens")} tokens · ${value.optLong("calls")} 次调用 · ${value.optLong("milliseconds")/60000} 分钟"
                            is org.json.JSONArray -> (0 until value.length()).joinToString("、"){value.optString(it)}
                            else -> value.toString()
                        }
                        detail.append(label).append("  ").append(display).append("\n")
                    }
                }
            }
            paragraph(detail.toString(),7,"恢复副本")
            val usable=listOfNotNull(taskRecovery,goalRecovery).all{it.optBoolean("usable")}
            if(usable) content.addView(toggle("核对恢复副本后解锁") { button ->
                ui.dialogBuilder("确认恢复副本",null,R.drawable.ic_pet_assign)
                    .setMessage("请核对副本、已执行动作和剩余预算。解锁后可补充说明，再继续原工单。")
                    .setNegativeButton("继续核对",null).setPositiveButton("已核对，解锁") { _,_ ->
                        button.isEnabled=false
                        recoveryCommands.execute {
                            val result=runCatching {WorkerDashboardStore(context).command(JSONObject().put("id",row.getString("id"))
                                .put("action","recover").put("recoveryAcknowledged",true)
                                .put("recoveryIncident",taskRecovery?.optString("incidentId"))
                                .put("goalRecoveryIncident",goalRecovery?.optString("incidentId")))}
                            button.post {button.text=if(result.isSuccess) "已提交解锁，请刷新工单" else "副本已变化，请重新打开核对"}
                        }
                    }.create().apply {
                        if(context !is android.app.Activity) window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                        show();ui.finishDialog(this)
                    }
            }) else content.addView(text("缺少可校验完整副本，请查看会话历史或导入备份后重新交办。",13f,muted=true))
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
            val limit = row.optJSONObject("budget")
            heading("本工单资源")
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = ui.shape(ui.soft, if (ui.simple) 12 else 16, ui.line)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(text("已记录 " + used.optLong("tokens") + " tokens · " + used.optLong("calls") + " 次工具调用 · " + used.optLong("milliseconds") / 60000 + " 分钟", 13f, bold = true))
                if (limit != null) addView(text("预算 " + limit.optLong("tokens") + " tokens · " + limit.optLong("calls") + " 次 · " + limit.optLong("milliseconds") / 60000 + " 分钟", 12.5f, muted = true).apply { setPadding(0, dp(3), 0, 0) })
            }
            content.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
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
    companion object {private val recoveryCommands=java.util.concurrent.Executors.newSingleThreadExecutor()}

}
