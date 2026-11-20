package io.deepseekharness.mobile.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 接收 AlarmManager 到点广播，在后台线程安全唤醒对应定时 Worker 任务（避免主线程 I/O 阻塞）。 */
class WhaleScheduledTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != WhaleScheduledTaskManager.ACTION_TRIGGER) return
        val id = intent.getStringExtra(WhaleScheduledTaskManager.EXTRA_TASK_ID)?.trim().orEmpty()
        if (id.isEmpty()) return
        val app = context.applicationContext
        val pending = goAsync()
        Thread({
            try {
                WhaleScheduledTaskManager.onAlarmFired(app, id)
            } finally {
                runCatching { pending.finish() }
            }
        }, "whale-alarm-fire").apply { isDaemon = true }.start()
    }
}
