package io.deepseekharness.mobile.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import io.deepseekharness.mobile.MainActivity
import io.deepseekharness.mobile.R

/**
 * 系统原生实况进度通知桥（替代或配合自绘悬浮灵动岛，避免与手机自带灵动岛重影打架）：
 *
 * - Android 16 (API 36+)：通过反射安全调用 `setRequestPromotedOngoing(true)` 申请系统原生 Live Updates 状态栏胶囊；
 * - Android 8–15：发布低打扰常驻进行中进度通知（带进度条、点击直达主对话），并附带厂商焦点通知兼容字段；
 * - 自绘悬浮灵动岛通过 [isFloatingIslandEnabled] 独立控制，用户可随时关闭自绘岛、只用系统原生实况通知。
 */
internal object WhaleNativeLiveNotification {
    private const val CHANNEL_ID = "whale_live_progress_v1"
    private const val NOTIFICATION_ID = 42088
    private const val PREFS = "whale_live_island_prefs_v1"
    private const val KEY_FLOATING_ENABLED = "floating_island_enabled"
    private const val KEY_NATIVE_NOTIF_ENABLED = "native_live_notif_enabled"

    private var lastSignature = ""

    fun isFloatingIslandEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FLOATING_ENABLED, false) // 默认关闭自绘悬浮岛，避免遮挡手机自带灵动岛

    fun setFloatingIslandEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FLOATING_ENABLED, enabled).apply()
    }

    fun isNativeNotifEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NATIVE_NOTIF_ENABLED, true)

    fun setNativeNotifEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NATIVE_NOTIF_ENABLED, enabled).apply()
        if (!enabled) cancel(context)
    }

    fun update(
        context: Context,
        title: String,
        subtitle: String,
        ongoing: Boolean,
        indeterminate: Boolean = true,
    ) {
        if (!isNativeNotifEnabled(context)) return
        val sig = "$title|$subtitle|$ongoing|$indeterminate"
        if (sig == lastSignature) return
        lastSignature = sig

        runCatching {
            val app = context.applicationContext
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val ch = NotificationChannel(
                        CHANNEL_ID,
                        "任务实时进度（实况通知）",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "在系统通知栏与支持实况胶囊的机型上展示后台任务、录制宏与执行进度"
                        setShowBadge(false)
                        enableVibration(false)
                        setSound(null, null)
                    }
                    nm.createNotificationChannel(ch)
                }
            }

            val openIntent = Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val openPi = PendingIntent.getActivity(app, 42088, openIntent, piFlags)

            val extras = Bundle().apply {
                // 标准短文案与厂商实况/焦点通知温和兼容字段（ROM 支持即进系统胶囊，不支持则优雅保持原生进度通知）
                putString("android.shortCriticalText", subtitle.take(12))
                putBoolean("miui.focus.enable", ongoing)
                putString("miui.focus.ticker", "$title · $subtitle".take(28))
            }

            val builder = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_overlay_ball)
                .setContentTitle(title.take(36))
                .setContentText(subtitle.take(64))
                .setContentIntent(openPi)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addExtras(extras)

            if (ongoing) {
                builder.setProgress(100, if (indeterminate) 0 else 100, indeterminate)
            } else {
                builder.setProgress(0, 0, false)
                builder.setAutoCancel(true)
            }

            val notif = builder.build()
            // Android 16+ 原生 Live Updates Promoted Ongoing 反射兼容（不强依赖 compileSdk 36）
            if (ongoing && Build.VERSION.SDK_INT >= 36) {
                runCatching {
                    val f = notif.javaClass.getField("flags")
                    // FLAG_PROMOTED_ONGOING = 0x20000000
                    f.setInt(notif, f.getInt(notif) or 0x20000000)
                }
            }
            nm.notify(NOTIFICATION_ID, notif)
        }
    }

    fun cancel(context: Context) {
        if (lastSignature.isEmpty()) return
        lastSignature = ""
        runCatching {
            val nm = context.applicationContext.getSystemService(NotificationManager::class.java)
            nm?.cancel(NOTIFICATION_ID)
        }
    }
}
