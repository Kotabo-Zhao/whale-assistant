package io.deepseekharness.mobile.companion

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import io.deepseekharness.mobile.OverlayBallService
import io.deepseekharness.mobile.R
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Daily or weekly local-time reminders. No missed occurrences are replayed after restart. */
internal object CompanionRecurringPolicy {
    fun next(now: Long, hour: Int, minute: Int, weekday: Int, zone: ZoneId): Long {
        require(hour in 0..23 && minute in 0..59 && weekday in 0..7)
        val localNow = Instant.ofEpochMilli(now).atZone(zone)
        var date = localNow.toLocalDate()
        repeat(9) {
            if (weekday == 0 || date.dayOfWeek.value == weekday) {
                val instant = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
                if (instant > now) return instant
            }
            date = date.plusDays(1)
        }
        error("无法计算下次提醒")
    }
    /** Only a due, scheduled occurrence may be claimed; the next time makes duplicate delivery a no-op. */
    fun claim(row: JSONObject, now: Long, zone: ZoneId): Boolean {
        if (row.optString("state") != "scheduled" || row.getLong("nextAt") > now) return false
        val following = next(now, row.getInt("hour"), row.getInt("minute"), row.getInt("weekday"), zone)
        row.put("nextAt", following).put("last", "pending")
        return true
    }
}

internal class CompanionRecurringReminder(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("companion_recurring_v1", Context.MODE_PRIVATE)
    private val alarm = app.getSystemService(AlarmManager::class.java)
    fun list(): JSONArray = synchronized(lock) { read() }
    fun add(text: String, hour: Int, minute: Int, weekday: Int) = synchronized(lock) {
        val content = text.trim()
        require(content.isNotBlank() && content.length <= 200 && !CompanionStore.sensitive(content)) { "内容为空、过长或可能含敏感信息" }
        val next = CompanionRecurringPolicy.next(System.currentTimeMillis(), hour, minute, weekday, ZoneId.systemDefault())
        val rows = read(); require(rows.length() < 50) { "重复提醒已满，请先删除" }
        val row = JSONObject().put("id", UUID.randomUUID().toString()).put("text", content).put("hour", hour)
            .put("minute", minute).put("weekday", weekday).put("nextAt", next).put("state", "scheduled").put("last", "none")
        rows.put(row); save(rows)
        try { schedule(row) } catch (e: Exception) { row.put("state", "failed"); save(rows); throw e }
    }
    fun remove(id: String) = synchronized(lock) {
        val rows = read(); val kept = JSONArray(); var found = false
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.getString("id") == id) found = true else kept.put(row)
        }
        if (found) { save(kept); alarm?.cancel(pending(id)) }
    }
    fun retry(id: String) = synchronized(lock) {
        val rows = read()
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("id") == id } ?: error("提醒已不存在")
        require(row.optString("state") == "failed") { "提醒仍在安排中" }
        row.put("nextAt", next(row, System.currentTimeMillis())).put("state", "scheduled")
        save(rows)
        try { schedule(row) } catch (e: Exception) { row.put("state", "failed"); save(rows); throw e }
    }
    fun restore(recalculate: Boolean = false, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val rows = read(); var changed = false
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optString("state") != "scheduled") continue
            val overdue = row.getLong("nextAt") <= now
            if (overdue || recalculate) {
                row.put("nextAt", next(row, now)); if (overdue) row.put("last", "missed")
                changed = true
            }
        }
        if (changed) save(rows)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optString("state") == "scheduled") {
                try { schedule(row) } catch (_: Exception) { row.put("state", "failed"); changed = true }
            }
        }
        if (changed) save(rows)
    }
    fun deliver(id: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val rows = read()
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("id") == id } ?: return
        if (!CompanionRecurringPolicy.claim(row, now, ZoneId.systemDefault())) return
        // Persist the next occurrence before notification, so duplicate broadcasts do not replay this one.
        save(rows)
        try { schedule(row) } catch (_: Exception) { row.put("state", "failed") }
        val result = runCatching {
            val manager = app.getSystemService(NotificationManager::class.java) ?: error("notification unavailable")
            manager.createNotificationChannel(NotificationChannel(CompanionReminderHealth.CHANNEL, "鲸鱼娘提醒", NotificationManager.IMPORTANCE_DEFAULT))
            check(CompanionReminderHealth.read(app) == CompanionReminderHealth.State.READY)
            val open = PendingIntent.getActivity(app, 0, Intent(app, CompanionActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(app, CompanionReminderHealth.CHANNEL).setSmallIcon(R.drawable.ic_overlay_ball)
                .setContentTitle("鲸鱼娘重复提醒").setContentText(row.getString("text")).setContentIntent(open).setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
            manager.notify("whale-recurring:$id", 0, notification)
        }
        row.put("last", if (result.isSuccess) "submitted" else "failed")
        save(rows)
        if (result.isSuccess) OverlayBallService.companionHint("有一条提醒，请查看通知。")
    }
    private fun next(row: JSONObject, now: Long) = CompanionRecurringPolicy.next(now, row.getInt("hour"), row.getInt("minute"), row.getInt("weekday"), ZoneId.systemDefault())
    private fun schedule(row: JSONObject) {
        val manager = alarm ?: error("系统提醒服务不可用")
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, row.getLong("nextAt"), pending(row.getString("id")))
    }
    private fun pending(id: String): PendingIntent {
        val intent = Intent(app, CompanionRecurringReceiver::class.java).setAction(ACTION_DELIVER).setData(Uri.parse("whale-repeat://local/$id"))
        return PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun read(): JSONArray {
        val raw = prefs.getString("items", "[]") ?: error("重复提醒数据不可用")
        require(raw.length <= 100_000)
        val rows = JSONArray(raw); require(rows.length() <= 50)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); UUID.fromString(row.getString("id"))
            require(row.getString("text").length in 1..200 && row.getInt("hour") in 0..23 && row.getInt("minute") in 0..59 && row.getInt("weekday") in 0..7 && row.getLong("nextAt") > 0)
            require(row.getString("state") in setOf("scheduled", "failed"))
        }
        return rows
    }
    private fun save(rows: JSONArray) { check(prefs.edit().putString("items", rows.toString()).commit()) { "无法保存重复提醒" } }
    companion object { private val lock = Any(); const val ACTION_DELIVER = "io.deepseekharness.mobile.RECURRING_REMINDER" }
}

class CompanionRecurringReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != CompanionRecurringReminder.ACTION_DELIVER || intent.data?.scheme != "whale-repeat" || intent.data?.host != "local") return
        val id = intent.data?.lastPathSegment ?: return
        runCatching { UUID.fromString(id); CompanionRecurringReminder(context).deliver(id) }
    }
}

class CompanionRecurringClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        runCatching { CompanionRecurringReminder(context).restore(recalculate = true) }
    }
}
