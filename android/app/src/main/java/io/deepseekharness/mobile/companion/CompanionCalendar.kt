package io.deepseekharness.mobile.companion

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.TimeZone

/** A fresh local snapshot, never persisted or sent to the assistant model. */
internal object CompanionCalendar {
    const val MAX_EVENTS = 20
    data class Event(val title: String, val start: Long, val end: Long, val allDay: Boolean)
    data class Snapshot(val state: State, val events: List<Event> = emptyList(), val truncated: Boolean = false)
    enum class State { OK, NO_PERMISSION, UNAVAILABLE }

    /** Calendar arithmetic handles 23/25-hour days at daylight-saving transitions. */
    fun dayRange(now: Long, zone: TimeZone): Pair<Long, Long> {
        val calendar = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        return start to calendar.timeInMillis
    }

    internal fun snapshot(permission: Boolean, query: () -> List<Event>?): Snapshot {
        if (!permission) return Snapshot(State.NO_PERMISSION)
        return try {
            val items = query() ?: return Snapshot(State.UNAVAILABLE)
            Snapshot(State.OK, items.take(MAX_EVENTS), items.size > MAX_EVENTS)
        } catch (_: SecurityException) { Snapshot(State.NO_PERMISSION) }
          catch (_: Exception) { Snapshot(State.UNAVAILABLE) }
    }

    fun read(context: Context, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Snapshot = snapshot(
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    ) {
            val (start, end) = dayRange(now, zone)
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
                ContentUris.appendId(this, start); ContentUris.appendId(this, end)
            }.build()
            val columns = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY)
            val cursor = context.contentResolver.query(uri, columns,
                "${CalendarContract.Calendars.VISIBLE}=1 AND (${CalendarContract.Events.STATUS} IS NULL OR ${CalendarContract.Events.STATUS} != ?)",
                arrayOf(CalendarContract.Events.STATUS_CANCELED.toString()), "${CalendarContract.Instances.BEGIN} ASC")
                ?: return@snapshot null
            cursor.use {
                val items = mutableListOf<Event>()
                while (items.size <= MAX_EVENTS && it.moveToNext()) {
                    val title = it.getString(0).orEmpty().trim().take(160).ifBlank { "无标题" }
                    items.add(Event(title, it.getLong(1), it.getLong(2), it.getInt(3) != 0))
                }
                items
            }
    }
}

/** Read-only model tool, separately gated from local UI calendar permission. */
internal object CompanionCalendarModel {
    fun execute(enabled: Boolean, param: String, now: Long, zone: TimeZone, read: () -> CompanionCalendar.Snapshot): String {
        if (param.isNotEmpty()) throw io.deepseekharness.mobile.runtime.RuntimeFailure("CALENDAR_INPUT_INVALID", "日历查询不接受参数")
        if (!enabled) throw io.deepseekharness.mobile.runtime.RuntimeFailure("CALENDAR_MODEL_DISABLED", "请先在助手设置中允许对话访问日历")
        val snapshot = read()
        when (snapshot.state) {
            CompanionCalendar.State.NO_PERMISSION -> throw io.deepseekharness.mobile.runtime.RuntimeFailure("CALENDAR_PERMISSION_REQUIRED", "请先在助手设置中授权读取日历")
            CompanionCalendar.State.UNAVAILABLE -> throw io.deepseekharness.mobile.runtime.RuntimeFailure("CALENDAR_UNAVAILABLE", "系统日历暂时不可用")
            CompanionCalendar.State.OK -> Unit
        }
        val rows = org.json.JSONArray()
        snapshot.events.forEach { event -> rows.put(org.json.JSONObject().put("title", event.title).put("startMs", event.start).put("endMs", event.end).put("allDay", event.allDay)) }
        val (start, end) = CompanionCalendar.dayRange(now, zone)
        return org.json.JSONObject().put("schemaVersion", 1).put("source", "device_calendar")
            .put("checkedAtMs", now).put("timeZone", zone.id).put("dayStartMs", start).put("dayEndMs", end)
            .put("events", rows).put("truncated", snapshot.truncated).toString()
    }
}
