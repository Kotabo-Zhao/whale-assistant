package io.deepseekharness.mobile.companion

import android.content.Context
import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID

internal class CompanionRecurringBridge(context: Context) {
    private val app = context.applicationContext
    private val store = CompanionRecurringReminder(app)
    fun execute(param: String): String = CompanionRecurringBridgeEngine(store::list, store::add, store::remove,
        { CompanionReminderHealth.read(app) == CompanionReminderHealth.State.READY }, { ZoneId.systemDefault().id }).execute(param)
}

internal class CompanionRecurringBridgeEngine(
    private val list: () -> JSONArray,
    private val create: (String, Int, Int, Int) -> Unit,
    private val remove: (String) -> Unit,
    private val notificationsAvailable: () -> Boolean,
    private val zone: () -> String,
) {
    fun execute(param: String): String {
        if (param.length > 4096) throw invalid()
        val request = try { JSONObject(param) } catch (_: Exception) { throw invalid() }
        return when (request.opt("op") as? String ?: throw invalid()) {
            "list" -> JSONObject().put("items", list()).put("timeZone", zone()).toString()
            "create" -> {
                val text = (request.opt("text") as? String)?.trim() ?: throw invalid()
                val hour = request.opt("hour") as? Int ?: throw invalid()
                val minute = request.opt("minute") as? Int ?: throw invalid()
                val weekday = request.opt("weekday") as? Int ?: throw invalid()
                val requestedZone = request.opt("timeZone") as? String ?: throw invalid()
                if (requestedZone != zone()) throw RuntimeFailure("REMINDER_TIMEZONE_CHANGED", "设备时区已变化，请重新确认提醒时间")
                if (text.isEmpty() || text.length > 200 || CompanionStore.sensitive(text) || hour !in 0..23 || minute !in 0..59 || weekday !in 0..7) throw invalid()
                if (!notificationsAvailable()) throw RuntimeFailure("REMINDER_NOTIFICATION_DISABLED", "请先允许助手发送通知")
                try { create(text, hour, minute, weekday) }
                catch (_: IllegalArgumentException) { throw invalid() }
                catch (e: Exception) { throw RuntimeFailure("REMINDER_SCHEDULE_FAILED", "重复提醒安排失败，请检查设备设置", e) }
                JSONObject().put("scheduled", true).put("timeZone", zone()).put("inexact", true).toString()
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
