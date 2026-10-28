package io.deepseekharness.mobile.companion

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/** Read-only checks for notification delivery; a ready result cannot guarantee delivery. */
internal object CompanionReminderHealth {
    const val CHANNEL = "companion_reminders"
    enum class State { READY, PERMISSION_DENIED, APP_DISABLED, CHANNEL_DISABLED, UNAVAILABLE }
    fun evaluate(permission: Boolean, enabled: Boolean, channelEnabled: Boolean): State = when {
        !permission -> State.PERMISSION_DENIED
        !enabled -> State.APP_DISABLED
        !channelEnabled -> State.CHANNEL_DISABLED
        else -> State.READY
    }
    fun read(context: Context): State = try {
        val allowed = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return State.UNAVAILABLE
        val channel = manager.getNotificationChannel(CHANNEL)
        evaluate(allowed, enabled, channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
    } catch (_: Exception) { State.UNAVAILABLE }
}
