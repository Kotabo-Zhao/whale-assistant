package io.deepseekharness.mobile.companion

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 纯原生定时与延迟自主任务调度器（基于 Android AlarmManager + SharedPreferences，零外部依赖）。
 *
 * 到点自动唤醒 [WhaleScheduledTaskReceiver]，将任务投递至 [WorkerRuntimeWake.submit] 执行并回传 Router。
 */
internal object WhaleScheduledTaskManager {
    private const val PREFS = "whale_scheduled_tasks_v1"
    private const val KEY_ITEMS = "items"
    const val ACTION_TRIGGER = "io.deepseekharness.mobile.ACTION_WHALE_SCHEDULED_TASK"
    const val EXTRA_TASK_ID = "task_id"

    @Synchronized
    fun create(
        context: Context,
        title: String,
        task: String,
        delayMinutes: Long,
        repeatDaily: Boolean = false,
    ): JSONObject {
        val app = context.applicationContext
        val cleanTask = task.trim()
        require(cleanTask.isNotEmpty() && cleanTask.length <= 2000) { "定时任务内容不能为空且不超过 2000 字" }
        val cleanTitle = title.trim().ifBlank { cleanTask.take(32) }
        val clampedDelay = delayMinutes.coerceIn(1L, 10_080L)
        val now = System.currentTimeMillis()
        val triggerAtMs = now + clampedDelay * 60_000L
        val id = UUID.randomUUID().toString()

        val row = JSONObject()
            .put("id", id)
            .put("title", cleanTitle)
            .put("task", cleanTask)
            .put("triggerAtMs", triggerAtMs)
            .put("repeatDaily", repeatDaily)
            .put("createdAtMs", now)

        val arr = loadRaw(app)
        val next = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("id") != id) next.put(item)
        }
        next.put(row)
        saveRaw(app, next)
        scheduleAlarm(app, id, triggerAtMs)
        return row
    }

    @Synchronized
    fun list(context: Context): JSONArray = loadRaw(context.applicationContext)

    @Synchronized
    fun cancel(context: Context, id: String): Boolean {
        val app = context.applicationContext
        val arr = loadRaw(app)
        val next = JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("id") == id) {
                removed = true
            } else {
                next.put(item)
            }
        }
        if (removed) {
            saveRaw(app, next)
            cancelAlarm(app, id)
        }
        return removed
    }

    @Synchronized
    fun onAlarmFired(context: Context, id: String) {
        val app = context.applicationContext
        val arr = loadRaw(app)
        var matched: JSONObject? = null
        val next = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("id") == id) {
                matched = item
                if (item.optBoolean("repeatDaily", false)) {
                    val nextTrigger = System.currentTimeMillis() + 24L * 60L * 60L * 1000L
                    item.put("triggerAtMs", nextTrigger)
                    next.put(item)
                    scheduleAlarm(app, id, nextTrigger)
                }
            } else {
                next.put(item)
            }
        }
        saveRaw(app, next)
        val target = matched ?: return
        runCatching {
            WorkerRuntimeWake.submit(
                context = app,
                task = target.optString("task"),
                mode = "create",
                title = "定时任务：" + target.optString("title"),
                needs = "none",
                sourceSession = null,
            )
        }
    }

    private fun scheduleAlarm(app: Context, id: String, triggerAtMs: Long) {
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(app, id)
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }.onFailure {
            runCatching { am.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pi) }
        }
    }

    private fun cancelAlarm(app: Context, id: String) {
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pendingIntent(app, id)) }
    }

    private fun pendingIntent(app: Context, id: String): PendingIntent {
        val intent = Intent(app, WhaleScheduledTaskReceiver::class.java).apply {
            action = ACTION_TRIGGER
            putExtra(EXTRA_TASK_ID, id)
        }
        return PendingIntent.getBroadcast(
            app,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun loadRaw(app: Context): JSONArray {
        val raw = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, "[]") ?: "[]"
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun saveRaw(app: Context, arr: JSONArray) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ITEMS, arr.toString())
            .apply()
    }
}
