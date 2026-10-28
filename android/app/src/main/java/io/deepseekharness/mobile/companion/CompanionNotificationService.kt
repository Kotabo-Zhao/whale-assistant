package io.deepseekharness.mobile.companion

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.deepseekharness.mobile.OverlayBallService

class CompanionNotificationService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName || sbn.isOngoing) return
        val store = CompanionStore(this)
        if (!store.notificationCapture || sbn.notification.visibility == Notification.VISIBILITY_SECRET) return
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        if (CompanionStore.sensitive("$title $text")) return
        if (store.record(sbn.packageName, title, text, notificationKey = sbn.key)) OverlayBallService.companionHint("有一条新通知，可以在助手设置中查看。")
    }
}
