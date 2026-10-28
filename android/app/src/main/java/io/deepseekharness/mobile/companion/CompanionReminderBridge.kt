package io.deepseekharness.mobile.companion

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID

/** 仅接收结构化提醒命令，不解析自然语言，也不读取其他应用的数据。 */
internal class CompanionReminderBridge(context: Context) {
    private val app = context.applicationContext
    private val store = CompanionReminder(app)

    fun execute(param: String): String = CompanionReminderBridgeEngine(
        list = store::list,
        create = store::add,
        remove = store::remove,
        notificationsAvailable = {
            val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val manager = app.getSystemService(NotificationManager::class.java)
            val channel = manager?.getNotificationChannel("companion_reminders")
            granted && manager?.areNotificationsEnabled() == true && channel?.importance != NotificationManager.IMPORTANCE_NONE
        },
        now = System::currentTimeMillis,
        zone = { ZoneId.systemDefault().id },
    ).execute(param)
}

internal class CompanionReminderBridgeEngine(
    private val list: () -> JSONArray,
    private val create: (String, Long) -> Unit,
    private val remove: (String) -> Unit,
    private val notificationsAvailable: () -> Boolean,
    private val now: () -> Long,
    private val zone: () -> String,
) {
    fun execute(param: String): String {
        if (param.length > 4096) throw invalid()
        val request = try { JSONObject(param) } catch (_: Exception) { throw invalid() }
        return when (request.opt("op") as? String ?: throw invalid()) {
            "list" -> JSONObject().put("items", list()).put("nowMillis", now()).put("timeZone", zone()).toString()
            "create" -> {
                val text = (request.opt("text") as? String)?.trim() ?: throw invalid()
                val timestamp = when (val value = request.opt("atMillis")) {
                    is Int -> value.toLong()
                    is Long -> value
                    else -> throw invalid()
                }
                if (text.isEmpty() || text.length > 200 || CompanionStore.sensitive(text) || timestamp <= now() + 10_000) throw invalid()
                if (!notificationsAvailable()) throw RuntimeFailure("REMINDER_NOTIFICATION_DISABLED", "请先允许助手发送通知")
                try { create(text, timestamp) }
                catch (_: IllegalArgumentException) { throw invalid() }
                catch (e: Exception) { throw RuntimeFailure("REMINDER_SCHEDULE_FAILED", "提醒安排失败，请检查设备设置", e) }
                JSONObject().put("scheduled", true).put("atMillis", timestamp).put("inexact", true).toString()
            }
            "delete" -> {
                val id = request.opt("id") as? String ?: throw invalid()
                if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) throw invalid()
                val rows = list()
                val exists = (0 until rows.length()).any { rows.optJSONObject(it)?.optString("id") == id }
                if (exists) remove(id)
                JSONObject().put("removed", exists).toString()
            }
            else -> throw invalid()
        }
    }

    private fun invalid() = RuntimeFailure("REMINDER_INPUT_INVALID", "提醒内容、时间或标识无效")
}
