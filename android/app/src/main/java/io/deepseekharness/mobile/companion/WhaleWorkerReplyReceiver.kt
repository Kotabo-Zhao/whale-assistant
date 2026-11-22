package io.deepseekharness.mobile.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import io.deepseekharness.mobile.R

/**
 * 后台 Worker 遇阻提问时的通知栏快捷回复接收器：
 * 用户直接在系统通知栏输入回复，无需切回 App 即可唤醒暂停中的 Worker 继续执行。
 */
class WhaleWorkerReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_QUICK_REPLY) return
        val workerId = intent.getStringExtra(EXTRA_WORKER_ID).orEmpty().trim()
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY_TEXT)?.toString()?.trim().orEmpty()
        if (workerId.isEmpty() || replyText.isEmpty()) return

        val pendingResult = goAsync()
        Thread({
            try {
                WorkerRuntimeWake.followupOrResume(context.applicationContext, workerId, replyText)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm != null && notifId != 0) {
                    val doneNote = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_overlay_ball)
                        .setContentTitle("鲸鱼娘已收到回复")
                        .setContentText("正在继续推进后台任务：$replyText")
                        .setAutoCancel(true)
                        .build()
                    nm.notify(notifId, doneNote)
                }
            } catch (_: Exception) {
                // 静默兜底，避免广播崩溃
            } finally {
                pendingResult.finish()
            }
        }, "whale-worker-quick-reply").start()
    }

    companion object {
        const val CHANNEL_ID = "whale_worker_ask"
        const val ACTION_QUICK_REPLY = "io.deepseekharness.mobile.ACTION_WORKER_QUICK_REPLY"
        const val EXTRA_WORKER_ID = "worker_id"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val KEY_REPLY_TEXT = "whale_worker_reply_text"

        fun notifyAskUser(context: Context, workerId: String, title: String, question: String) {
            val app = context.applicationContext
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "后台任务提问与进展", NotificationManager.IMPORTANCE_HIGH)
                    )
                }
            }
            val notifId = 8400 + (workerId.hashCode() and 0x3FF)
            val replyIntent = Intent(app, WhaleWorkerReplyReceiver::class.java).apply {
                action = ACTION_QUICK_REPLY
                putExtra(EXTRA_WORKER_ID, workerId)
                putExtra(EXTRA_NOTIF_ID, notifId)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val replyPi = PendingIntent.getBroadcast(app, notifId, replyIntent, flags)
            val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
                .setLabel("直接回复继续执行…")
                .build()
            val replyAction = NotificationCompat.Action.Builder(
                R.drawable.ic_overlay_ball,
                "快捷回复",
                replyPi,
            ).addRemoteInput(remoteInput).build()

            val note = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_overlay_ball)
                .setContentTitle("鲸鱼娘需要确认：$title")
                .setContentText(question)
                .setStyle(NotificationCompat.BigTextStyle().bigText(question))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(replyAction)
                .build()
            runCatching { nm.notify(notifId, note) }
        }
    }
}
