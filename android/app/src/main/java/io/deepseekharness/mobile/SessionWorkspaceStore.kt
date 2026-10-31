package io.deepseekharness.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-owned organisation metadata for Harness sessions.
 *
 * This deliberately stores neither transcript nor a copied Harness title.  A title override,
 * group and archive flag are harmless local annotations keyed by the runtime session id; the
 * real transcript remains exclusively in the Harness session directory.
 */
internal class SessionWorkspaceStore(context: Context) {
    data class Entry(val id: String, val title: String?, val group: String?, val archived: Boolean)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = lockFor(context.applicationContext)

    fun entries(): List<Entry> = synchronized(lock) { read().values.sortedBy { it.id } }

    /** Applies exactly the supplied fields. Null title/group removes that local annotation. */
    fun update(id: String, title: String?, hasTitle: Boolean, group: String?, hasGroup: Boolean, archived: Boolean?, hasArchived: Boolean): List<Entry> = synchronized(lock) {
        require(validId(id)) { "会话标识无效" }
        require(hasTitle || hasGroup || hasArchived) { "没有可更新的会话属性" }
        val values = read().toMutableMap()
        val old = values[id] ?: Entry(id, null, null, false)
        val next = old.copy(
            title = if (hasTitle) title?.let(::validTitle) else old.title,
            group = if (hasGroup) group?.let(::validGroup) else old.group,
            archived = if (hasArchived) archived ?: error("归档状态无效") else old.archived,
        )
        if (next.title == null && next.group == null && !next.archived) values.remove(id) else values[id] = next
        require(values.size <= MAX_ENTRIES) { "会话组织记录过多" }
        save(values)
        values.values.sortedBy { it.id }
    }

    /** Contains only user-created annotations, suitable for the encrypted app-private full backup. */
    fun exportArchive(): ByteArray = synchronized(lock) { encode(read().values).toString().toByteArray(Charsets.UTF_8) }

    fun mergeArchive(bytes: ByteArray): Int = synchronized(lock) {
        val imported = decode(bytes)
        val values = read().toMutableMap()
        var added = 0
        for (entry in imported) {
            if (entry.id !in values) added++
            values[entry.id] = entry
        }
        require(values.size <= MAX_ENTRIES) { "会话组织记录过多" }
        save(values)
        added
    }

    private fun read(): Map<String, Entry> = runCatching {
        decode(prefs.getString(KEY, null)?.toByteArray(Charsets.UTF_8) ?: return emptyMap()).associateBy { it.id }
    }.getOrElse { emptyMap() }

    private fun save(values: Map<String, Entry>) {
        check(prefs.edit().putString(KEY, encode(values.values).toString()).commit()) { "无法保存会话组织信息" }
    }

    companion object {
        private const val PREFS = "session_workspace_v1"
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 500
        private const val MAX_TITLE = 200
        private const val MAX_GROUP = 80
        private val ID = Regex("session-[A-Za-z0-9._-]{1,190}")
        private val controls = Regex("[\\u0000-\\u001F\\u007F]")
        private val locks = java.util.WeakHashMap<Context, Any>()
        private fun lockFor(context: Context): Any = synchronized(locks) { locks.getOrPut(context) { Any() } }
        private fun validId(value: String) = ID.matches(value)
        private fun validTitle(value: String): String { require(value.isNotBlank() && value.length <= MAX_TITLE && !controls.containsMatchIn(value)); return value }
        private fun validGroup(value: String): String { require(value.isNotBlank() && value.length <= MAX_GROUP && !controls.containsMatchIn(value)); return value }
        private fun encode(entries: Collection<Entry>): JSONObject = JSONObject().put("version", 1).put("entries", JSONArray().apply {
            entries.sortedBy { it.id }.forEach { entry -> put(JSONObject().put("id", entry.id).apply {
                entry.title?.let { put("title", it) }; entry.group?.let { put("group", it) }; if (entry.archived) put("archived", true)
            }) }
        })
        fun decode(bytes: ByteArray): List<Entry> {
            require(bytes.size <= 150_000) { "会话组织备份过大" }
            val root = JSONObject(String(bytes, Charsets.UTF_8)); require(root.getInt("version") == 1)
            val rows = root.getJSONArray("entries"); require(rows.length() <= MAX_ENTRIES)
            val seen = mutableSetOf<String>()
            return (0 until rows.length()).map { index -> rows.getJSONObject(index).let { row ->
                val id = row.getString("id"); require(validId(id) && seen.add(id))
                val title = row.optString("title", "").takeIf(String::isNotEmpty)?.let(::validTitle)
                val group = row.optString("group", "").takeIf(String::isNotEmpty)?.let(::validGroup)
                Entry(id, title, group, row.optBoolean("archived", false))
            } }
        }
    }
}
