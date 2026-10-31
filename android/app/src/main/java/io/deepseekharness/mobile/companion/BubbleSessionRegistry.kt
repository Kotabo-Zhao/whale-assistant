package io.deepseekharness.mobile.companion

import android.content.Context

/** Persistent labels for sessions created by the desktop pet, including resumed sessions. */
internal class BubbleSessionRegistry(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("companion_bubble_sessions_v1", Context.MODE_PRIVATE)
    fun record(sessionId: String) = synchronized(lock) {
        require(valid(sessionId))
        val sessions = prefs.getStringSet("sessions", emptySet()).orEmpty().toMutableSet()
        if (sessions.add(sessionId)) check(prefs.edit().putStringSet("sessions", sessions).commit()) { "无法保存桌宠会话权限" }
    }
    fun contains(sessionId: String): Boolean = synchronized(lock) {
        valid(sessionId) && sessionId in prefs.getStringSet("sessions", emptySet()).orEmpty()
    }
    companion object {
        private val lock = Any()
        fun valid(id: String): Boolean = id.matches(Regex("[A-Za-z0-9._-]{1,200}"))
    }
}
