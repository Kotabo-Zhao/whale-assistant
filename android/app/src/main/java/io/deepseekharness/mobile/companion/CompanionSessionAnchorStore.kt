package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A deliberately lossy, user-visible continuity index between the main Harness and the pet.
 * It is not a transcript store: callers must supply an already-reviewed short summary.
 */
internal class CompanionSessionAnchorStore(context: Context) {
    data class Anchor(val sessionId: String, val role: String, val atMs: Long, val summary: String)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = lockFor(context.applicationContext)

    fun append(sessionId: String, role: String, atMs: Long, summary: String): Anchor = synchronized(lock) {
        require(BubbleSessionRegistry.valid(sessionId) && role in setOf("user", "assistant") && atMs > 0)
        val safe = summary.trim().also { require(it.isNotEmpty() && it.length <= MAX_SUMMARY && !CONTROLS.containsMatchIn(it)) }
        val next = Anchor(sessionId, role, atMs, safe)
        val rows = read().toMutableList(); rows.add(next)
        save(rows.takeLast(MAX_ANCHORS))
        next
    }

    fun list(sessionId: String): List<Anchor> = synchronized(lock) {
        require(BubbleSessionRegistry.valid(sessionId))
        read().filter { it.sessionId == sessionId }
    }

    fun exportArchive(): ByteArray = synchronized(lock) { encode(read()).toByteArray(Charsets.UTF_8) }
    fun mergeArchive(bytes: ByteArray): Int = synchronized(lock) {
        val imported = decode(bytes); val current = read().toMutableList(); val before = current.size
        current.addAll(imported.filter { candidate -> current.none { it.sessionId == candidate.sessionId && it.role == candidate.role && it.atMs == candidate.atMs && it.summary == candidate.summary } })
        save(current.sortedBy { it.atMs }.takeLast(MAX_ANCHORS)); current.size - before
    }

    private fun read(): List<Anchor> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val root = JSONArray(raw); require(root.length() <= MAX_ANCHORS)
        (0 until root.length()).map { index -> root.getJSONObject(index).let { row ->
            Anchor(row.getString("sessionId"), row.getString("role"), row.getLong("atMs"), row.getString("summary")).also {
                require(BubbleSessionRegistry.valid(it.sessionId) && it.role in setOf("user", "assistant") && it.atMs > 0 && it.summary.isNotBlank() && it.summary.length <= MAX_SUMMARY && !CONTROLS.containsMatchIn(it.summary))
            }
        } }
    }.getOrElse { emptyList() }

    private fun save(rows: List<Anchor>) {
        val value = encode(rows)
        check(prefs.edit().putString(KEY, value).commit()) { "无法保存会话锚点" }
    }

    companion object {
        private const val PREFS = "companion_session_anchors_v1"
        private const val KEY = "anchors"
        private const val MAX_ANCHORS = 40
        private const val MAX_SUMMARY = 160
        private val CONTROLS = Regex("[\\u0000-\\u001F\\u007F]")
        private val locks = java.util.WeakHashMap<Context, Any>()
        private fun lockFor(context: Context): Any = synchronized(locks) { locks.getOrPut(context) { Any() } }
        private fun encode(rows: List<Anchor>) = JSONArray().apply { rows.forEach { anchor -> put(JSONObject().put("sessionId", anchor.sessionId).put("role", anchor.role).put("atMs", anchor.atMs).put("summary", anchor.summary)) } }.toString()
        private fun decode(bytes: ByteArray): List<Anchor> { require(bytes.size <= 20_000); val root = JSONArray(String(bytes, Charsets.UTF_8)); require(root.length() <= MAX_ANCHORS); return (0 until root.length()).map { index -> root.getJSONObject(index).let { Anchor(it.getString("sessionId"), it.getString("role"), it.getLong("atMs"), it.getString("summary")) } }.also { rows -> rows.forEach { require(BubbleSessionRegistry.valid(it.sessionId) && it.role in setOf("user", "assistant") && it.atMs > 0 && it.summary.isNotBlank() && it.summary.length <= MAX_SUMMARY && !CONTROLS.containsMatchIn(it.summary)) } } }
    }
}
