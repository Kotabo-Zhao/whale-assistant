package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray

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
    /** Exported identities contain no transcript or credentials. */
    fun exportArchive(): ByteArray = synchronized(lock) {
        JSONArray(prefs.getStringSet("sessions", emptySet()).orEmpty().sorted()).toString().toByteArray(Charsets.UTF_8)
    }
    fun mergeArchive(bytes: ByteArray): Int = synchronized(lock) {
        val values = validateArchive(bytes)
        val sessions = prefs.getStringSet("sessions", emptySet()).orEmpty().toMutableSet()
        val before = sessions.size
        for (i in 0 until values.length()) sessions.add(values.getString(i).also { require(valid(it)) })
        if (sessions.size != before) check(prefs.edit().putStringSet("sessions", sessions).commit()) { "无法恢复桌宠会话权限" }
        sessions.size - before
    }
    companion object {
        private val lock = Any()
        fun valid(id: String): Boolean = id.matches(Regex("[A-Za-z0-9._-]{1,200}"))
        fun validateArchive(bytes: ByteArray): JSONArray {
            require(bytes.size <= 50_000)
            val values = JSONArray(String(bytes, Charsets.UTF_8))
            require(values.length() <= 200)
            for (i in 0 until values.length()) require(valid(values.getString(i)))
            return values
        }
    }
}
