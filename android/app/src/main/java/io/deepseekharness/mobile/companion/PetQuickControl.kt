package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONObject

/** Foreground task controls do not queue behind a pending model response. */
internal object PetQuickControl {
    fun reply(context: Context, owner: String?, decision: PetIntentRouter.Decision): String {
        val desk = WorkerDashboardStore(context)
        val records = desk.list().getJSONArray("items")
        val owners = setOfNotNull(owner,"session-whale-task-desk")
        val rows = (0 until records.length()).map(records::getJSONObject).filter { it.optString("owner") in owners }
        val active = rows.filter { it.optString("state") in setOf("queued","starting","running","waiting","blocked","interrupted") }
        if (decision.intent == PetIntentRouter.Intent.TASK_STATUS) {
            val labels = mapOf("queued" to "排队", "starting" to "启动中", "running" to "执行中", "waiting" to "等你回答", "blocked" to "受阻", "interrupted" to "已中断")
            return if (active.isEmpty()) "当前会话没有未结束的后台工单；前台的这条请求仍会单独返回。" else
                active.take(8).joinToString("\n") { "${it.optString("title")}：${labels[it.optString("state")] ?: it.optString("state")}" } +
                    if (active.size > 8) "\n另有 ${active.size-8} 项，可在后台任务中查看。" else ""
        }
        check(decision.intent == PetIntentRouter.Intent.TASK_CANCEL && decision.independent)
        val matches = active.filter { decision.label.isBlank() || it.optString("title") == decision.label }
        if (matches.size != 1) return if (matches.isEmpty()) "没有找到可确认的后台任务，尚未取消任何任务。可以从后台任务卡选择要停止的那一项。"
            else "有 ${matches.size} 项后台任务，请在后台任务卡选择要停止的那一项，或告诉我完整任务名；尚未取消任何任务。"
        val row = matches.single()
        desk.command(JSONObject().put("id",row.getString("id")).put("action","cancel"))
        return "已请求停止「${row.optString("title")}」。正在收尾的动作需以任务卡最终状态为准。"
    }
}
