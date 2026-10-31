package io.deepseekharness.mobile.companion

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.deepseekharness.mobile.OverlayBallService
import io.deepseekharness.mobile.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 用户明确建立的一次性提醒；系统可能因省电而延迟投递。 */
internal class CompanionReminder(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("companion_reminders_v1", Context.MODE_PRIVATE)
    private val alarm = context.getSystemService(AlarmManager::class.java)

    fun list(): JSONArray = synchronized(lock) { read() }

    fun add(text: String, whenMillis: Long) = synchronized(lock) {
        require(text.isNotBlank() && text.length <= 200 && !CompanionStore.sensitive(text)) { "内容为空、过长或可能含敏感信息" }
        require(CompanionReminderPolicy.isFuture(whenMillis, System.currentTimeMillis())) { "请选择未来时间" }
        val rows = read()
        require(rows.length() < 100) { "提醒已满，请先删除" }
        val id = UUID.randomUUID().toString()
        val row = JSONObject().put("id", id).put("text", text.trim()).put("at", whenMillis).put("state", "scheduled")
        rows.put(row); save(rows)
        try { schedule(row) } catch (e: Exception) {
            row.put("state", "failed"); save(rows); throw e
        }
    }

    /** Stable item ID makes retries and rescheduling idempotent. */
    fun upsertLinked(id: String, text: String, whenMillis: Long, revision: Int) = synchronized(lock) {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        require(text.isNotBlank() && text.length <= 200 && !CompanionStore.sensitive(text))
        require(CompanionReminderPolicy.isFuture(whenMillis, System.currentTimeMillis()))
        require(revision > 0)
        check(UnifiedUnderstandingStore.open(context).use { it.queue.itemReminderCurrent(id, whenMillis, revision) }) { "事项提醒已变化" }
        val rows = read()
        val existing = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
        require(existing == null || existing.optBoolean("linkedItem", false)) { "提醒标识冲突" }
        if (existing == null) require(rows.length() < 100) { "提醒已满，请先删除" }
        val row = existing ?: JSONObject().put("id", id).put("linkedItem", true).also { rows.put(it) }
        row.put("text", text.trim()).put("at", whenMillis).put("linkedItemRevision", revision).put("state", "scheduled")
        save(rows)
        try { schedule(row) } catch (e: Exception) { row.put("state", "failed"); save(rows); throw e }
    }

    fun remove(id: String) = synchronized(lock) {
        val rows = read(); val kept = JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.getString("id") != id) kept.put(row)
            else if (row.optBoolean("linkedItem", false)) UnifiedUnderstandingStore.open(context).use { it.queue.disableItemReminder(id, row.getLong("at"), row.optInt("linkedItemRevision")) }
        }
        save(kept)
        alarm?.cancel(pending(id))
    }

    fun removeLinked(id: String) = synchronized(lock) {
        val rows = read()
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
        if (row?.optBoolean("linkedItem", false) == true) remove(id)
    }

    fun restore() = synchronized(lock) {
        val rows = read(); var changed = false
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optString("state") != "scheduled") continue
            if (row.optBoolean("linkedItem", false) && !linkedCurrent(row)) { row.put("state", "cancelled"); alarm?.cancel(pending(row.getString("id"))); changed = true; continue }
            if (CompanionReminderPolicy.isMissed(row.optLong("at"), System.currentTimeMillis())) { row.put("state", "missed"); changed = true }
            else try { schedule(row) } catch (_: Exception) { row.put("state", "failed"); changed = true }
        }
        if (changed) save(rows)
        val desired = runCatching { UnifiedUnderstandingStore.open(context).use { it.queue.desiredItemReminders() } }.getOrNull().orEmpty()
        for (desiredReminder in desired) if (CompanionReminderPolicy.isFuture(desiredReminder.dueAtMs, System.currentTimeMillis())) {
            val current = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("id") == desiredReminder.itemId }
            if (current == null || current.optLong("at") != desiredReminder.dueAtMs || current.optInt("linkedItemRevision") != desiredReminder.revision || current.optString("state") != "scheduled")
                runCatching { upsertLinked(desiredReminder.itemId, desiredReminder.title, desiredReminder.dueAtMs, desiredReminder.revision) }
        }
    }

    fun deliver(id: String) = synchronized(lock) {
        val rows = read()
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("id") == id } ?: return
        if (row.optString("state") != "scheduled") return
        if (row.optBoolean("linkedItem", false)) {
            if (!linkedCurrent(row)) { restore(); return }
            if (row.optLong("at") > System.currentTimeMillis()) return
        }
        val permitted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!permitted) { row.put("state", "failed"); save(rows); return }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager == null || !manager.areNotificationsEnabled()) { row.put("state", "failed"); save(rows); return }
        manager.createNotificationChannel(NotificationChannel("companion_reminders", "鲸鱼娘提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, CompanionActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "companion_reminders").setSmallIcon(R.drawable.ic_overlay_ball)
            .setContentTitle("鲸鱼娘提醒").setContentText(row.getString("text")).setContentIntent(open).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try {
            manager.notify(id.hashCode(), notification)
            row.put("state", "delivered"); save(rows)
            OverlayBallService.companionHint("有一条提醒，请查看通知。")
        } catch (_: Exception) { row.put("state", "failed"); save(rows) }
    }

    private fun schedule(row: JSONObject) {
        val manager = alarm ?: error("系统提醒服务不可用")
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, row.getLong("at"), pending(row.getString("id")))
    }
    private fun linkedCurrent(row: JSONObject): Boolean = runCatching {
        UnifiedUnderstandingStore.open(context).use { it.queue.itemReminderCurrent(row.getString("id"), row.getLong("at"), row.optInt("linkedItemRevision")) }
    }.getOrDefault(false)
    private fun pending(id: String): PendingIntent {
        val intent = Intent(context, CompanionReminderReceiver::class.java).setData(Uri.parse("dsh-reminder://local/$id"))
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun read(): JSONArray = JSONArray(prefs.getString("items", "[]"))
    private fun save(rows: JSONArray) { check(prefs.edit().putString("items", rows.toString()).commit()) { "无法保存提醒" } }
    companion object { private val lock = Any() }
}

internal object CompanionReminderPolicy {
    fun isFuture(at: Long, now: Long) = at > now && at - now > 10_000
    fun isMissed(at: Long, now: Long) = at <= now
}

class CompanionReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.data?.lastPathSegment ?: return
        runCatching { CompanionReminder(context).deliver(id) }
    }
}
