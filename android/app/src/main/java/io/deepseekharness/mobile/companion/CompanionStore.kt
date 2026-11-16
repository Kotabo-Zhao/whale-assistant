package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 未写入偏好时的对话访问默认值。
 * 缺键视为开启记忆与资料检索；已经保存成 false 的选择保持关闭。
 * 通知正文、日历、写入资料和完全权限不在这里放行。
 */
internal object CompanionAccessDefaults {
    const val BUBBLE_MEMORY = true
    const val KNOWLEDGE_READ = true
    const val KNOWLEDGE_WRITE = false
    const val NOTIFICATION_MODEL = false
    const val CALENDAR_MODEL = false
}

/** 本地有界记录；不通过 Harness 或网络发送。 */
class CompanionStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences("companion_private_v1", Context.MODE_PRIVATE)

    var bubblePermissionPreset: String
        get() = prefs.getString("bubble_permission_preset", "default")?.takeIf { it in setOf("default", "read-only", "workspace-write", "danger-full-access") } ?: "default"
        set(value) { require(value in setOf("default", "read-only", "workspace-write", "danger-full-access")); prefs.edit().putString("bubble_permission_preset", value).apply() }

    var chatWidthMode: String
        get() = prefs.getString("chat_width_mode", null) ?: "auto"
        set(value) { prefs.edit().putString("chat_width_mode", value).apply() }
    var chatHeightMode: String
        get() = prefs.getString("chat_height_mode", null) ?: "auto"
        set(value) { prefs.edit().putString("chat_height_mode", value).apply() }

    var bubbleMemoryAccess: Boolean
        get() = prefs.getBoolean("bubble_memory_access", CompanionAccessDefaults.BUBBLE_MEMORY)
        set(value) { prefs.edit().putBoolean("bubble_memory_access", value).apply() }
    var proactiveMemoryChat: Boolean
        get() = prefs.getBoolean("proactive_memory_chat", false)
        set(value) { prefs.edit().putBoolean("proactive_memory_chat", value).apply() }
    var modelInitiative: Boolean
        get() = prefs.getBoolean("model_initiative", false)
        set(value) {
            prefs.edit().putBoolean("model_initiative", value).apply()
            if (!value) PetInitiative.clearOpening(app)
        }
    var contextFollowup: Boolean
        get() = prefs.getBoolean("context_followup", false)
        set(value) {
            check(prefs.edit().putBoolean("context_followup", value).commit())
            if (!value) { ContextFollowupEvents.cancel(app); PetInitiative.clearContextOpening(app) }
        }
    var proactiveMemoryFrequency: String
        get() = prefs.getString("proactive_memory_frequency", "daily")?.takeIf { it in setOf("daily", "weekly") } ?: "daily"
        set(value) { require(value in setOf("daily", "weekly")); prefs.edit().putString("proactive_memory_frequency", value).apply() }
    /** User-level accessibility preference for the floating companion only. */
    var reduceMotion: Boolean
        get() = prefs.getBoolean("reduce_motion", false)
        set(value) { prefs.edit().putBoolean("reduce_motion", value).apply() }
    /** Explicit provider selection; changing this changes where background content is sent. */
    var understandingProvider: String
        get() = prefs.getString("understanding_provider", "deepseek") ?: "deepseek"
        set(value) {
            require(value in understandingProviderChoices(io.deepseekharness.mobile.runtime.RuntimeStore(app).settings()).map { it.id })
            // A custom model choice belongs to one provider; never silently carry it to another.
            check(prefs.edit().putString("understanding_provider", value).remove("understanding_model").commit())
        }
    /** Optional custom-provider model selection. Built-ins keep using the runtime default-model setting. */
    var understandingModel: String?
        get() = prefs.getString("understanding_model", null)
        set(value) {
            if (value == null) { check(prefs.edit().remove("understanding_model").commit()); return }
            require(value.isNotBlank() && value.length <= 200 && value.none { it.isISOControl() })
            val provider = io.deepseekharness.mobile.runtime.RuntimeStore(app).settings().customModelProviders
                .firstOrNull { it.id == understandingProvider } ?: throw IllegalArgumentException("当前后台供应商不支持独立模型选择")
            require(provider.models.any { it.id == value }) { "后台模型已不在当前供应商配置中" }
            check(prefs.edit().putString("understanding_model", value).commit())
        }
    var backgroundUnderstanding: Boolean
        get() = prefs.getBoolean("background_understanding", false)
        set(value) {
            if (value) check(BackgroundUnderstanding.stop(app)) { "无法核对旧任务，后台理解未启用" }
            check(prefs.edit().putBoolean("background_understanding", value).commit())
            if (!value) BackgroundUnderstanding.stop(app)
        }
    var backgroundAutoMemory: Boolean
        get() = prefs.getBoolean("background_auto_memory", false)
        set(value) { prefs.edit().putBoolean("background_auto_memory", value).apply() }

    var notificationBubbles: Boolean
        get() = prefs.getBoolean("notification_bubbles", true)
        set(value) { prefs.edit().putBoolean("notification_bubbles", value).apply() }
    var candidateNotifications: Boolean
        get() = prefs.getBoolean("candidate_notifications", false)
        set(value) { prefs.edit().putBoolean("candidate_notifications", value).apply() }
    /** Separate opt-in: local notification excerpts remain untrusted until the user confirms. */
    var notificationMemorySuggestions: Boolean
        get() = prefs.getBoolean("notification_memory_suggestions", false)
        set(value) { prefs.edit().putBoolean("notification_memory_suggestions", value).apply() }
    /** Explicit opt-in to store eligible notification excerpts as unverified, searchable memories. */
    var automaticNotificationMemory: Boolean
        get() = prefs.getBoolean("automatic_notification_memory", false)
        set(value) { prefs.edit().putBoolean("automatic_notification_memory", value).apply() }
    /** Explicit opt-in for assistant-proposed memories to be saved without per-item review. */
    var automaticAssistantMemory: Boolean
        get() = prefs.getBoolean("automatic_assistant_memory", false)
        set(value) { prefs.edit().putBoolean("automatic_assistant_memory", value).apply() }
    /** Separate opt-in for aggregate app usage; system access permission is also required. */
    var automaticUsageMemory: Boolean
        get() = prefs.getBoolean("automatic_usage_memory", false)
        set(value) { prefs.edit().putBoolean("automatic_usage_memory", value).apply() }
    fun dismissedMemorySuggestions(): Set<String> = prefs.getStringSet("dismissed_memory_suggestions", emptySet())?.toSet() ?: emptySet()
    fun dismissMemorySuggestion(id: String) = synchronized(lock) {
        require(CompanionNotificationTarget.validDigest(id))
        val next = dismissedMemorySuggestions().filter(CompanionNotificationTarget::validDigest).takeLast(99).toMutableSet()
        next.add(id)
        prefs.edit().putStringSet("dismissed_memory_suggestions", next).apply()
    }
    fun clearDismissedMemorySuggestions() { prefs.edit().remove("dismissed_memory_suggestions").apply() }
    var notificationCapture: Boolean
        get() = prefs.getBoolean("capture", false)
        set(value) {
            if (value && !notificationCapture && backgroundUnderstanding) check(BackgroundUnderstanding.stop(app)) { "无法核对旧任务，通知采集未启用" }
            check(prefs.edit().putBoolean("capture", value).commit())
            if (!value) BackgroundUnderstanding.stop(app)
        }
    /** Separate from collection: allowing model access can send notification text externally. */
    var notificationModelAccess: Boolean
        get() = prefs.getBoolean("notification_model_access", CompanionAccessDefaults.NOTIFICATION_MODEL)
        set(value) { prefs.edit().putBoolean("notification_model_access", value).apply() }
    /** Separate consent for sharing excerpts with the configured model. */
    var knowledgeModelAccess: Boolean
        get() = prefs.getBoolean("knowledge_model_access", CompanionAccessDefaults.KNOWLEDGE_READ)
        set(value) {
            check(prefs.edit().putBoolean("knowledge_model_access", value).commit())
            if (!value) {
                ContextFollowupEvents.cancel(app)
                PetInitiative.clearContextOpening(app)
                SemanticControl(app).setEnabled(false)
                SemanticIndexJobs.cancel(app)
            }
        }
    var knowledgeModelWrite: Boolean
        get() = prefs.getBoolean("knowledge_model_write", CompanionAccessDefaults.KNOWLEDGE_WRITE)
        set(value) { check(prefs.edit().putBoolean("knowledge_model_write", value).commit()) }
    /** Explicit separate consent for sharing calendar titles with the configured model. */
    var calendarModelAccess: Boolean
        get() = prefs.getBoolean("calendar_model_access", CompanionAccessDefaults.CALENDAR_MODEL)
        set(value) { prefs.edit().putBoolean("calendar_model_access", value).apply() }
    var dailyBriefEnabled: Boolean
        get() = prefs.getBoolean("daily_brief_enabled", false)
        set(value) { prefs.edit().putBoolean("daily_brief_enabled", value).apply() }
    var dailyBriefMinute: Int
        get() = prefs.getInt("daily_brief_minute", 8 * 60).takeIf { it in 0 until 1440 } ?: 8 * 60
        set(value) { require(value in 0 until 1440); prefs.edit().putInt("daily_brief_minute", value).apply() }
    /** The local brief's forward-looking calendar/reminder horizon. */
    var dailyBriefDays: Int
        get() = prefs.getInt("daily_brief_days", 1).takeIf { it in setOf(1, 3, 7) } ?: 1
        set(value) { require(value in setOf(1, 3, 7)); prefs.edit().putInt("daily_brief_days", value).apply() }
    var dailyBriefLastDay: String?
        get() = prefs.getString("daily_brief_last_day", null)
        set(value) { prefs.edit().putString("daily_brief_last_day", value).apply() }
    var calendarHints: Boolean
        get() = prefs.getBoolean("calendar_hints", false)
        set(value) { prefs.edit().putBoolean("calendar_hints", value).apply() }
    var usageSuggestions: Boolean
        get() = prefs.getBoolean("usage_suggestions", false)
        set(value) { prefs.edit().putBoolean("usage_suggestions", value).apply() }
    var recurringBubbleDetails: Boolean
        get() = prefs.getBoolean("recurring_bubble_details", false)
        set(value) { prefs.edit().putBoolean("recurring_bubble_details", value).apply() }
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
    fun setExcludedPackages(packages: Set<String>) {
        check(BackgroundUnderstanding.revokeExcluded(app, excludedPackages() + packages)) { "无法更新来源任务状态" }
        check(prefs.edit().putStringSet("excluded_packages", packages.toSet()).commit())
    }

    fun record(packageName: String, title: String, text: String, now: Long = System.currentTimeMillis(), notificationKey: String? = null): Boolean = synchronized(lock) {
        if (!notificationCapture || packageName in excludedPackages() || sensitive("$title $text")) return@synchronized false
        val records = read(now)
        val key = CompanionNotificationDedup.digest(notificationKey)
        val contentHash = CompanionNotificationDedup.contentDigest(title, text)
        val shortTitle = CompanionNotificationRecords.truncate(title, 160)
        val shortText = CompanionNotificationRecords.truncate(text, 400)
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

    fun removeRecordsForPackage(packageName: String): Int = synchronized(lock) {
        val (kept, removed) = CompanionNotificationRecords.withoutPackage(read(), packageName)
        if (removed > 0) check(prefs.edit().putString("records", kept.toString()).commit()) { "未能确认清除结果" }
        removed
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
    fun truncate(value: String, maximum: Int): String {
        require(maximum >= 0)
        var end = minOf(value.length, maximum)
        if (end > 0 && end < value.length && Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) end--
        return value.substring(0, end)
    }
    fun withoutPackage(rows: JSONArray, packageName: String): Pair<JSONArray, Int> {
        require(CompanionSourcePolicy.validPackage(packageName)) { "应用包名无效" }
        val kept = JSONArray()
        var removed = 0
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            if (row.getString("package") == packageName) removed++ else kept.put(row)
        }
        return kept to removed
    }

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
