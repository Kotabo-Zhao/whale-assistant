package io.deepseekharness.mobile.companion

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.deepseekharness.mobile.R

/** Sends only generic text; candidate content remains in the local record store. */
internal object CompanionCandidateNotifications {
    private const val CHANNEL = "companion_candidates"
    private const val INTERVAL = 30L * 60 * 1000
    private val lock = Any()
    fun due(now: Long, previous: Long): Boolean = now > 0 && (previous <= 0 || (previous <= now && now - previous >= INTERVAL))
    fun show(context: Context, quiet: Boolean) = synchronized(lock) {
        if (quiet || !CompanionStore(context).candidateNotifications) return@synchronized
        val permission = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(NotificationManager::class.java) ?: return@synchronized
        if (!permission || !manager.areNotificationsEnabled()) return@synchronized
        val prefs = context.getSharedPreferences("companion_candidate_alert_v1", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!due(now, prefs.getLong("last_sent", 0))) return@synchronized
        runCatching {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "鲸鱼娘候选事项", NotificationManager.IMPORTANCE_DEFAULT))
            if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return@runCatching
            val intent = Intent(context, CompanionActivity::class.java).putExtra("open_notification_candidates", true)
            val open = PendingIntent.getActivity(context, 0x444e, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val note = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_overlay_ball)
                .setContentTitle("鲸鱼娘助手").setContentText("可能有待处理事项，点击查看。")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build()
            manager.notify(0x44534804, note)
            prefs.edit().putLong("last_sent", now).apply()
        }
    }
}
