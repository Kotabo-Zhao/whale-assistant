package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 本地有界记录；不通过 Harness 或网络发送。 */
class CompanionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("companion_private_v1", Context.MODE_PRIVATE)

    var notificationBubbles: Boolean
        get() = prefs.getBoolean("notification_bubbles", true)
        set(value) { prefs.edit().putBoolean("notification_bubbles", value).apply() }
    var candidateNotifications: Boolean
        get() = prefs.getBoolean("candidate_notifications", false)
        set(value) { prefs.edit().putBoolean("candidate_notifications", value).apply() }
    var notificationCapture: Boolean
        get() = prefs.getBoolean("capture", false)
        set(value) { prefs.edit().putBoolean("capture", value).apply() }
    /** Separate from collection: allowing model access can send notification text externally. */
    var notificationModelAccess: Boolean
        get() = prefs.getBoolean("notification_model_access", false)
        set(value) { prefs.edit().putBoolean("notification_model_access", value).apply() }
    /** Explicit separate consent for sharing calendar titles with the configured model. */
    var calendarModelAccess: Boolean
        get() = prefs.getBoolean("calendar_model_access", false)
        set(value) { prefs.edit().putBoolean("calendar_model_access", value).apply() }
    var calendarHints: Boolean
        get() = prefs.getBoolean("calendar_hints", false)
        set(value) { prefs.edit().putBoolean("calendar_hints", value).apply() }
    var usageSuggestions: Boolean
        get() = prefs.getBoolean("usage_suggestions", false)
        set(value) { prefs.edit().putBoolean("usage_suggestions", value).apply() }
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

    fun shortcuts(): Set<String> = prefs.getStringSet("app_shortcuts", emptySet())?.toSet() ?: emptySet()
    fun addShortcut(packageName: String) = synchronized(lock) {
        val next = CompanionShortcutPolicy.add(shortcuts(), packageName)
        prefs.edit().putStringSet("app_shortcuts", next).apply()
    }
    fun removeShortcut(packageName: String) = synchronized(lock) {
        val next = shortcuts().toMutableSet().apply { remove(packageName) }
        prefs.edit().putStringSet("app_shortcuts", next).apply()
    }

    fun excludedPackages(): Set<String> = prefs.getStringSet("excluded_packages", emptySet())?.toSet() ?: emptySet()
    fun setExcludedPackages(packages: Set<String>) { prefs.edit().putStringSet("excluded_packages", packages.toSet()).apply() }

    fun record(packageName: String, title: String, text: String, now: Long = System.currentTimeMillis(), notificationKey: String? = null): Boolean = synchronized(lock) {
        if (!notificationCapture || packageName in excludedPackages() || sensitive("$title $text")) return@synchronized false
        val records = read(now)
        val key = CompanionNotificationDedup.digest(notificationKey)
        val contentHash = CompanionNotificationDedup.contentDigest(title, text)
        val shortTitle = title.take(160)
        val shortText = text.take(400)
        for (index in 0 until records.length()) {
            val prior = records.optJSONObject(index) ?: continue
            if (prior.optString("package") == packageName.take(200) && CompanionNotificationDedup.isDuplicate(
                    key, contentHash, now, prior.optString("notificationKeyHash").takeIf { it.isNotEmpty() },
                    prior.optString("notificationContentHash").takeIf { it.isNotEmpty() }, prior.optLong("time", 0))) return@synchronized false
        }
        val entry = JSONObject().put("package", packageName.take(200)).put("title", shortTitle)
            .put("text", shortText).put("time", now)
        if (key != null) entry.put("notificationKeyHash", key).put("notificationContentHash", contentHash)
        records.put(entry)
        val trimmed = JSONArray()
        for (index in maxOf(0, records.length() - MAX_RECORDS) until records.length()) trimmed.put(records.getJSONObject(index))
        prefs.edit().putString("records", trimmed.toString()).apply()
        true
    }

    fun read(now: Long = System.currentTimeMillis()): JSONArray = synchronized(lock) {
        val result = JSONArray()
        val stored = CompanionNotificationRecords.parse(prefs.getString("records", "[]") ?: throw IllegalStateException("记录为空"))
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

/** Corrupt data must not be mistaken for an empty inbox or replaced by the next notification. */
internal object CompanionNotificationRecords {
    fun parse(raw: String): JSONArray {
        require(raw.length <= 256_000) { "通知记录过大" }
        val rows = JSONArray(raw)
        require(rows.length() <= CompanionStore.MAX_RECORDS) { "通知记录过多" }
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: error("通知记录格式无效")
            require(row.opt("package") is String && row.opt("title") is String && row.opt("text") is String && row.opt("time") is Number) { "通知记录格式无效" }
        }
        return rows
    }
}

internal object CompanionShortcutPolicy {
    const val MAX_SHORTCUTS = 6
    fun add(existing: Set<String>, packageName: String): Set<String> {
        require(existing.size <= MAX_SHORTCUTS && existing.all(CompanionSourcePolicy::validPackage)) { "快捷方式数据无效" }
        require(CompanionSourcePolicy.validPackage(packageName)) { "应用包名无效" }
        require(packageName in existing || existing.size < MAX_SHORTCUTS) { "最多保留 6 个快捷方式" }
        return existing + packageName
    }
}
