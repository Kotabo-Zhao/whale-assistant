package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.R

internal enum class CompanionPreferencePage(val title: Int, val summary: Int = 0, val icon: Int = 0) {
    HOME(R.string.pref_home),
    PET(R.string.pref_pet, R.string.pref_pet_summary, R.drawable.ic_whale_nav_chat),
    DATA(R.string.pref_data, R.string.pref_data_summary, R.drawable.ic_whale_nav_memory),
    ALERTS(R.string.pref_alerts, R.string.pref_alerts_summary, R.drawable.ic_whale_nav_tasks),
    DAILY(R.string.pref_daily, R.string.pref_daily_summary, R.drawable.ic_pet_calendar),
    PERMISSIONS(R.string.pref_permissions, R.string.pref_permissions_summary, R.drawable.ic_whale_nav_settings),
    MEMORY(R.string.pref_memory, R.string.pref_memory_summary, R.drawable.ic_whale_nav_memory),
    LIBRARY(R.string.pref_library, R.string.pref_library_summary, R.drawable.ic_whale_nav_memory),
    BACKUP(R.string.pref_backup, R.string.pref_backup_summary, R.drawable.ic_whale_nav_settings),
    MAINTENANCE(R.string.pref_maintenance, R.string.pref_maintenance_summary, R.drawable.ic_whale_nav_settings),
    NOTIFICATIONS(R.string.pref_notifications, R.string.pref_notifications_summary, R.drawable.ic_whale_nav_tasks),
    REMINDERS(R.string.pref_reminders, R.string.pref_reminders_summary, R.drawable.ic_whale_nav_tasks),
    BACKGROUND(R.string.pref_background, R.string.pref_background_summary, R.drawable.ic_whale_nav_plugins),
    RECORDS(R.string.pref_records, R.string.pref_records_summary, R.drawable.ic_whale_nav_tasks),
    APPS(R.string.pref_apps, R.string.pref_apps_summary, R.drawable.ic_whale_nav_plugins),
    BRIEF(R.string.pref_brief, R.string.pref_brief_summary, R.drawable.ic_pet_calendar);

    companion object {
        fun from(value: String?): CompanionPreferencePage? = entries.firstOrNull { it.name == value }
    }
}
