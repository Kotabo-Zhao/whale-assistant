package io.deepseekharness.mobile.companion

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.deepseekharness.mobile.OverlayBallService

class CompanionNotificationService : NotificationListenerService() {
    override fun onListenerConnected() { super.onListenerConnected(); current = this }
    override fun onListenerDisconnected() { if (current === this) current = null; super.onListenerDisconnected() }
    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }

    companion object {
        @Volatile private var current: CompanionNotificationService? = null
        internal fun isConnected(): Boolean = current != null

        /** Only called after a foreground user action. Never stores or reconstructs a PendingIntent. */
        internal fun openOriginal(candidate: CompanionBrief.Candidate): Boolean {
            val service = current ?: return false
            return runCatching {
                val matches = service.activeNotifications.filter { sbn ->
                    val extras = sbn.notification.extras
                    val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                    val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                    CompanionNotificationTarget.matches(candidate.packageName, candidate.notificationKeyHash, candidate.notificationContentHash,
                        sbn.packageName, sbn.key, title, text, sbn.notification.contentIntent?.creatorPackage)
                }
                if (matches.size != 1) return false
                val intent = matches.single().notification.contentIntent ?: return false
                intent.send()
                true
            }.getOrDefault(false)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName || sbn.isOngoing) return
        val store = CompanionStore(this)
        if (!store.notificationCapture || sbn.notification.visibility == Notification.VISIBILITY_SECRET) return
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        if (CompanionStore.sensitive("$title $text")) return
        if (runCatching { store.record(sbn.packageName, title, text, notificationKey = sbn.key) }.getOrDefault(false)) OverlayBallService.companionHint("有一条新通知，可以在助手设置中查看。")
    }
}
