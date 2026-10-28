package io.deepseekharness.mobile.accessibility

import android.content.Context

/** Only a boolean is persisted: no app name, task text, target or screen data. */
internal class AutomationTaskInterruptionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("automation_task_interruption_v1", Context.MODE_PRIVATE)
    fun pending(): Boolean = runCatching { prefs.getBoolean("pending", false) }.getOrDefault(true)
    fun arm(): Boolean = runCatching { prefs.edit().putBoolean("pending", true).commit() }.getOrDefault(false)
    fun clear(): Boolean = runCatching { prefs.edit().remove("pending").commit() }.getOrDefault(false)
    companion object {
        val lock = Any()
        fun active(snapshot: AutomationTaskSnapshot): Boolean = snapshot.taskId.isNotEmpty() && !automationTaskSettled(snapshot.status)
        fun requiresReview(snapshot: AutomationTaskSnapshot): Boolean = snapshot.steps.any {
            it.startedAtMs != null || it.status == AutomationTaskStepStatus.UNKNOWN ||
                (it.status == AutomationTaskStepStatus.OK && it.code == AutomationTaskCodes.ACTION_UNVERIFIED && it.action != AutomationTaskActions.WAIT)
        }
        fun orphaned(pending: Boolean, snapshot: AutomationTaskSnapshot?): Boolean =
            pending && (snapshot == null || snapshot.taskId.isEmpty() || automationTaskSettled(snapshot.status))
    }
}
