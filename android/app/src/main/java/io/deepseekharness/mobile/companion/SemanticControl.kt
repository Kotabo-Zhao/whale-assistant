package io.deepseekharness.mobile.companion

import android.content.Context

/** Consent, generation and bounded daily requests; no source text or credentials are stored here. */
internal class SemanticControl(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("semantic_search_v1", Context.MODE_PRIVATE)
    val enabled: Boolean get() = SemanticEmbeddings.isCloudConfigured(app) && prefs.getBoolean("enabled", false)
    val memoryEnabled: Boolean get() = enabled && prefs.getBoolean("memory_enabled", true)
    fun generation(): Long = prefs.getLong("generation", 0L)
    fun cursor(): Long = prefs.getLong("cursor", -1L)
    fun phase(): String = prefs.getString("phase", "knowledge").takeIf { it == "memory" } ?: "knowledge"

    fun setEnabled(value: Boolean) = synchronized(lock) {
        val next = generation() + 1
        val editor = prefs.edit().putBoolean("enabled", value).putLong("generation", next).putLong("cursor", -1L).putString("phase", "knowledge").putBoolean("complete", false)
        if (!value) editor.putBoolean("memory_enabled", false)
        check(editor.commit())
        SemanticKnowledgeSearch.clearCache()
        if (!value) SemanticEmbeddings.releaseWhenIdle()
        if (!value && java.io.File(app.noBackupFilesDir, "semantic-index-v1.db").isFile) SemanticIndex(app).use { it.clear() }
    }
    fun invalidate(id: String? = null) = synchronized(lock) {
        check(prefs.edit().putLong("generation", generation() + 1).putLong("cursor", -1L).putString("phase", "knowledge").putBoolean("complete", false).commit())
        SemanticKnowledgeSearch.clearCache()
        if (java.io.File(app.noBackupFilesDir, "semantic-index-v1.db").isFile) SemanticIndex(app).use { if (id == null) it.clear() else it.delete(id) }
    }
    /** Additions keep existing vectors but require the scan to revisit the database. */
    fun added() = synchronized(lock) {
        check(prefs.edit().putLong("generation", generation() + 1).putLong("cursor", -1L).putString("phase", "knowledge").putBoolean("complete", false).commit())
        SemanticKnowledgeSearch.clearCache()
    }
    fun publish(expectedGeneration: Long, action: () -> Unit): Boolean = synchronized(lock) {
        if (!enabled || generation() != expectedGeneration) return@synchronized false
        action()
        true
    }
    fun advance(expectedGeneration: Long, position: Long, complete: Boolean, phase: String? = null): Boolean = synchronized(lock) {
        if (!enabled || generation() != expectedGeneration) return@synchronized false
        require(phase == null || phase in setOf("knowledge", "memory"))
        val editor = prefs.edit().putLong("cursor", position).putBoolean("complete", complete)
        if (phase != null) editor.putString("phase", phase)
        check(editor.commit())
        true
    }
    fun setMemoryEnabled(value: Boolean) = synchronized(lock) {
        if (value) check(enabled) { "请先启用资料语义检索" }
        check(prefs.edit().putBoolean("memory_enabled", value).putLong("generation", generation() + 1)
            .putLong("cursor", -1L).putString("phase", "knowledge").putBoolean("complete", false).commit())
        SemanticKnowledgeSearch.clearCache()
        if (!value && java.io.File(app.noBackupFilesDir, "semantic-index-v1.db").isFile) SemanticIndex(app).use { it.clearMemories() }
    }
    /** Memory mutations invalidate only their derived vectors; null means an unknown bulk change. */
    fun memoryChanged(ids: Set<String>?) = synchronized(lock) {
        if (!memoryEnabled) return@synchronized
        check(prefs.edit().putLong("generation", generation() + 1).putLong("cursor", -1L)
            .putString("phase", "knowledge").putBoolean("complete", false).commit())
        SemanticKnowledgeSearch.clearCache()
        if (java.io.File(app.noBackupFilesDir, "semantic-index-v1.db").isFile) SemanticIndex(app).use { index ->
            if (ids == null) index.clearMemories() else ids.forEach { index.delete("memory:$it") }
        }
    }
    fun isComplete(): Boolean = prefs.getBoolean("complete", false)
    fun setStatus(value: String) { prefs.edit().putString("status", value.take(120)).apply() }
    fun status(): String = prefs.getString("status", "尚未建立索引") ?: "尚未建立索引"
    fun reserve(kind: String): Boolean = synchronized(lock) {
        require(kind == "index" || kind == "query")
        if (!enabled) return@synchronized false
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
        val key = "${kind}_day"
        val countKey = "${kind}_count"
        val count = if (prefs.getString(key, "") == day) prefs.getInt(countKey, 0) else 0
        if (count >= 50) return@synchronized false
        check(prefs.edit().putString(key, day).putInt(countKey, count + 1).commit())
        true
    }
    companion object { private val lock = Any() }
}
