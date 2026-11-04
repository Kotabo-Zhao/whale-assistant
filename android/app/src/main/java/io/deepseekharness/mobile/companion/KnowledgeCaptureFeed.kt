package io.deepseekharness.mobile.companion

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A bounded list of pointers. Text and files are always read from the current library. */
internal class KnowledgeCaptureFeed(private val db: SQLiteDatabase, private val find: (String) -> JSONObject?) {
    private fun pointers(): JSONArray =
        db.rawQuery("SELECT value FROM knowledge_meta WHERE key=?", arrayOf(KEY)).use {
            if (it.moveToFirst()) JSONArray(it.getString(0)) else JSONArray()
        }

    /** Called inside the same transaction as the user-selected input. Re-sharing moves it to the top. */
    fun record(row: JSONObject, now: Long = System.currentTimeMillis()) {
        check(db.inTransaction())
        val id = row.getString("id")
        val newest = JSONObject().put("id", id).put("createdAt", row.getLong("createdAt")).put("capturedAt", now)
        row.optString("attachmentGeneration").takeIf(String::isNotEmpty)?.let { newest.put("generation", it) }
        val next = JSONArray().put(newest)
        val previous = pointers()
        for (index in 0 until previous.length()) {
            val pointer = previous.getJSONObject(index)
            if (pointer.getString("id") != id && next.length() < LIMIT) next.put(pointer)
        }
        db.execSQL("INSERT OR REPLACE INTO knowledge_meta(key,value) VALUES (?,?)", arrayOf(KEY, next.toString()))
    }

    fun current(id: String, createdAt: Long, generation: String): JSONObject? = find(id)?.takeIf {
        it.optLong("createdAt") == createdAt && (generation.isEmpty() || it.optString("attachmentGeneration") == generation)
    }

    fun cards(): JSONArray {
        val result = JSONArray()
        val pointers = pointers()
        for (index in 0 until minOf(pointers.length(), LIMIT)) {
            val pointer = pointers.getJSONObject(index)
            val row = current(pointer.getString("id"), pointer.getLong("createdAt"), pointer.optString("generation")) ?: continue
            val card = JSONObject(pointer.toString()).put("text", row.getString("text").take(96).dropLastWhile { it.isHighSurrogate() })
            for (field in listOf("source","mime","displayName","shareOrigin","mediaKind","coverage","parseState")) {
                if (row.has(field)) card.put(field, row.get(field))
            }
            result.put(card)
        }
        return result
    }

    // Content IDs are the write path's primary keys, including older originals that predate the feed.
    // Scan bounded groups by ID. A manually deleted first chunk must not hide the remaining chunks.
    fun contents(row: JSONObject, offset: Int): Pair<JSONArray, Int?> {
        val original = row.optString("originalId").isNotEmpty()
        val document = if (original) row.getString("originalId") else row.optString("documentId")
        if (!document.matches(Regex("[a-f0-9]{64}"))) return JSONArray() to null
        val maximum = CompanionKnowledgeData.MAX_DOCUMENT_TEXT / (CompanionKnowledgeData.MAX_RECORD_TEXT - 1) + 2
        require(offset in 0..maximum)
        val result = JSONArray()
        var end = if (original) maximum else row.optInt("chunkCount").coerceIn(0, maximum)
        var slot = offset
        while (slot <= end) {
            val last = minOf(end, slot + 15)
            val keys = (slot..last).map {
                if (it == 0) UUID.nameUUIDFromBytes("image-analysis:$document".toByteArray(Charsets.UTF_8)).toString()
                else KnowledgeChunks.recordId(document, it - 1)
            }
            val records = mutableMapOf<String, JSONObject>()
            val placeholders = keys.joinToString(",") { "?" }
            db.rawQuery("SELECT id,body FROM knowledge_records WHERE id IN ($placeholders)", keys.toTypedArray()).use {
                while (it.moveToNext()) records[it.getString(0)] = JSONObject(it.getString(1))
            }
            for ((i, key) in keys.withIndex()) {
                val part = records[key] ?: continue
                if (original && part.optString("originalId") != document) continue
                if (!original && (part.has("originalId") || part.optString("documentId") != document)) continue
                if (part.has("chunkCount")) end = minOf(end, part.getInt("chunkCount"))
                if (result.length() == 3) return result to (slot+i)
                result.put(part)
            }
            slot = last + 1
        }
        return result to null
    }

    companion object {
        const val KEY = "recent_capture_v1"
        private const val LIMIT = 8
    }
}
