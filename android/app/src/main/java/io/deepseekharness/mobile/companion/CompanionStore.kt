package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 本地有界记录；不通过 Harness 或网络发送。 */
class CompanionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("companion_private_v1", Context.MODE_PRIVATE)

    var notificationCapture: Boolean
        get() = prefs.getBoolean("capture", false)
        set(value) { prefs.edit().putBoolean("capture", value).apply() }
    var reminders: Boolean
        get() = prefs.getBoolean("reminders", false)
        set(value) { prefs.edit().putBoolean("reminders", value).apply() }

    var quietHoursEnabled: Boolean
        get() = prefs.getBoolean("quiet_hours_enabled", false)
        set(value) { prefs.edit().putBoolean("quiet_hours_enabled", value).apply() }
    var quietHoursStart: Int
        get() = prefs.getInt("quiet_hours_start", 22 * 60).takeIf { it in 0 until 1440 } ?: 22 * 60
        set(value) { require(value in 0 until 1440); prefs.edit().putInt("quiet_hours_start", value).apply() }
    var quietHoursEnd: Int
        get() = prefs.getInt("quiet_hours_end", 8 * 60).takeIf { it in 0 until 1440 } ?: 8 * 60
        set(value) { require(value in 0 until 1440); prefs.edit().putInt("quiet_hours_end", value).apply() }

    fun inQuietHours(now: java.util.Calendar = java.util.Calendar.getInstance()): Boolean =
        CompanionQuietHours.contains(quietHoursEnabled, quietHoursStart, quietHoursEnd,
            now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE))

    fun excludedPackages(): Set<String> = prefs.getStringSet("excluded_packages", emptySet())?.toSet() ?: emptySet()
    fun setExcludedPackages(packages: Set<String>) { prefs.edit().putStringSet("excluded_packages", packages.toSet()).apply() }

    fun record(packageName: String, title: String, text: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        if (!notificationCapture || packageName in excludedPackages() || sensitive("$title $text")) return@synchronized false
        val records = read(now)
        records.put(JSONObject().put("package", packageName.take(200)).put("title", title.take(160))
            .put("text", text.take(400)).put("time", now))
        val trimmed = JSONArray()
        for (index in maxOf(0, records.length() - MAX_RECORDS) until records.length()) trimmed.put(records.getJSONObject(index))
        prefs.edit().putString("records", trimmed.toString()).apply()
        true
    }

    fun read(now: Long = System.currentTimeMillis()): JSONArray = synchronized(lock) {
        val result = JSONArray()
        val stored = runCatching { JSONArray(prefs.getString("records", "[]")) }.getOrDefault(JSONArray())
        for (index in 0 until stored.length()) {
            val item = stored.optJSONObject(index) ?: continue
            val time = item.optLong("time", 0)
            if (time in (now - RETENTION_MS)..now) result.put(item)
        }
        if (stored.length() != result.length()) prefs.edit().putString("records", result.toString()).apply()
        result
    }

    fun clear() = synchronized(lock) { prefs.edit().remove("records").apply() }

    companion object {
        const val MAX_RECORDS = 50
        private val lock = Any()
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private val sensitivePattern = Regex("(?i)验证码|校验码|动态码|密码|口令|密钥|令牌|verification code|one.time password|otp|password|passcode|api[ _-]?key|access[ _-]?token|\\bsk-[a-z0-9_-]{12,}|\\b\\d{4,8}\\b")
        fun sensitive(value: String): Boolean = sensitivePattern.containsMatchIn(value)
    }
}
