package io.deepseekharness.mobile.accessibility

import android.content.Context
import io.deepseekharness.mobile.runtime.RuntimeFailure

/** Reserved under the task lock; never hold that lock during package IO. */
internal object PluginMaintenanceLease {
    @Volatile private var held = false
    fun active(): Boolean = held
    fun acquire(context: Context) = synchronized(AutomationTaskInterruptionStore.lock) {
        val snapshot = DeepSeekAccessibilityService.current()?.currentAutomationTask()
        val interrupted = AutomationTaskInterruptionStore(context).pending()
        if (held || interrupted || (snapshot != null && AutomationTaskInterruptionStore.active(snapshot))) {
            throw RuntimeFailure("PLUGIN_TASK_BUSY", "手机任务尚未结束或需要处理，未修改插件")
        }
        held = true
    }
    fun release() = synchronized(AutomationTaskInterruptionStore.lock) { held = false }
}
