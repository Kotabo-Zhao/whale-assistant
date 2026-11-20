package io.deepseekharness.mobile.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 接收 AlarmManager 到点广播，在后台自主唤醒对应定时 Worker 任务。 */
class WhaleScheduledTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != WhaleScheduledTaskManager.ACTION_TRIGGER) return
        val id = intent.getStringExtra(WhaleScheduledTaskManager.EXTRA_TASK_ID)?.trim().orEmpty()
        if (id.isEmpty()) return
        WhaleScheduledTaskManager.onAlarmFired(context.applicationContext, id)
    }
}
