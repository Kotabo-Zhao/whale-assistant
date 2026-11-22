package io.deepseekharness.mobile.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import io.deepseekharness.mobile.HarnessKeepAliveService
import io.deepseekharness.mobile.runtime.RuntimeStore

/** 接收 AlarmManager 到点广播，在后台线程安全唤醒对应定时 Worker 任务（带短期唤醒锁与可选保活拉起，避免息屏中断）。 */
class WhaleScheduledTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != WhaleScheduledTaskManager.ACTION_TRIGGER) return
        val id = intent.getStringExtra(WhaleScheduledTaskManager.EXTRA_TASK_ID)?.trim().orEmpty()
        if (id.isEmpty()) return
        val app = context.applicationContext
        val pm = runCatching { app.getSystemService(PowerManager::class.java) }.getOrNull()
        val handoffLock = runCatching {
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "whale-assistant:alarm-$id")?.apply {
                setReferenceCounted(false)
                acquire(60_000L)
            }
        }.getOrNull()
        val pending = goAsync()
        Thread({
            try {
                if (runCatching { RuntimeStore(app).keepRuntimeInBackground() }.getOrDefault(false)) {
                    runCatching { HarnessKeepAliveService.startAfterBoot(app) }
                }
                WhaleScheduledTaskManager.onAlarmFired(app, id)
            } finally {
                runCatching { if (handoffLock?.isHeld == true) handoffLock.release() }
                runCatching { pending.finish() }
            }
        }, "whale-alarm-fire").apply { isDaemon = true }.start()
    }
}
