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
import java.time.LocalDateTime
import java.time.Instant
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
            "list" -> {
                val clock = now()
                val zoneId = ZoneId.of(zone())
                JSONObject().put("items", list()).put("nowMillis", clock).put("timeZone", zoneId.id)
                    .put("nowLocal", Instant.ofEpochMilli(clock).atZone(zoneId).toOffsetDateTime().toString()).toString()
            }
            "create" -> {
                val text = (request.opt("text") as? String)?.trim() ?: throw invalid()
                val hasEpoch = request.has("atMillis")
                val hasLocal = request.has("localDateTime")
                if (hasEpoch == hasLocal) throw invalid()
                val timestamp = if (hasEpoch) {
                    when (val value = request.opt("atMillis")) {
                        is Int -> value.toLong()
                        is Long -> value
                        else -> throw invalid()
                    }
                } else {
                    val value = request.opt("localDateTime") as? String ?: throw invalid()
                    if (!value.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}"))) throw invalid()
                    val requestedZone = request.opt("timeZone") as? String ?: throw invalid()
                    if (requestedZone != zone()) throw RuntimeFailure("REMINDER_TIMEZONE_CHANGED", "设备时区已变化，请重新确认提醒时间")
                    val resolved = try {
                        val local = LocalDateTime.parse(value)
                        val offsets = ZoneId.of(requestedZone).rules.getValidOffsets(local)
                        if (offsets.size != 1) throw invalid()
                        local.toInstant(offsets[0]).toEpochMilli()
                    } catch (error: RuntimeFailure) { throw error }
                    catch (_: Exception) { throw invalid() }
                    resolved
                }
                if (text.isEmpty() || text.length > 200 || CompanionStore.sensitive(text) || timestamp <= now() + 10_000) throw invalid()
                if (!notificationsAvailable()) throw RuntimeFailure("REMINDER_NOTIFICATION_DISABLED", "请先允许助手发送通知")
                try { create(text, timestamp) }
                catch (_: IllegalArgumentException) { throw invalid() }
                catch (e: Exception) { throw RuntimeFailure("REMINDER_SCHEDULE_FAILED", "提醒安排失败，请检查设备设置", e) }
                JSONObject().put("scheduled", true).put("atMillis", timestamp).put("timeZone", zone()).put("inexact", true).toString()
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
