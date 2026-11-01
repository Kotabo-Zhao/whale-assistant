package io.deepseekharness.mobile.companion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Rebuildable, private cache. Source records remain authoritative. */
internal class SemanticIndex(context: Context) : AutoCloseable {
    data class Hit(val id: String, val revision: String, val start: Int, val score: Double)
    private val db = SQLiteDatabase.openOrCreateDatabase(File(context.applicationContext.noBackupFilesDir, "semantic-index-v1.db"), null)
    init {
        db.execSQL("CREATE TABLE IF NOT EXISTS indexed_records (record_id TEXT PRIMARY KEY, revision TEXT NOT NULL, model TEXT NOT NULL, segments INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS vectors (record_id TEXT NOT NULL, segment_start INTEGER NOT NULL, vector BLOB NOT NULL, PRIMARY KEY(record_id,segment_start))")
    }
    fun isCurrent(id: String, revision: String): Boolean = db.rawQuery(
        "SELECT segments,(SELECT COUNT(*) FROM vectors WHERE record_id=?) FROM indexed_records WHERE record_id=? AND revision=? AND model=?",
        arrayOf(id, id, revision, SemanticEmbeddings.MODEL),
    ).use { it.moveToFirst() && it.getInt(0) > 0 && it.getInt(0) == it.getInt(1) }

    fun replace(id: String, revision: String, segments: List<Pair<Int, FloatArray>>) {
        require(id.matches(Regex("(?:memory:)?[a-f0-9-]{36}")) && revision.matches(Regex("[a-f0-9]{64}")))
        // 资料上限为 8000 字，按 480 字安全切片时恰好可能产生 17 块。
        require(segments.isNotEmpty() && segments.size <= 17 && segments.map { it.first }.toSet().size == segments.size)
        db.beginTransaction()
        try {
            db.delete("vectors", "record_id=?", arrayOf(id))
            for ((start, vector) in segments) {
                require(start >= 0 && vector.size == SemanticEmbeddings.DIMENSIONS && vector.all { it.isFinite() })
                val bytes = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                vector.forEach { bytes.putFloat(it) }
                db.insertOrThrow("vectors", null, ContentValues().apply { put("record_id", id); put("segment_start", start); put("vector", bytes.array()) })
            }
            db.insertWithOnConflict("indexed_records", null, ContentValues().apply {
                put("record_id", id); put("revision", revision); put("model", SemanticEmbeddings.MODEL); put("segments", segments.size)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun delete(id: String) {
        db.beginTransaction()
        try {
            db.delete("vectors", "record_id=?", arrayOf(id)); db.delete("indexed_records", "record_id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun clear() {
        db.beginTransaction()
        try { db.delete("vectors", null, null); db.delete("indexed_records", null, null); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }

    fun clearMemories() {
        db.beginTransaction()
        try {
            db.delete("vectors", "record_id LIKE 'memory:%'", null)
            db.delete("indexed_records", "record_id LIKE 'memory:%'", null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Exact cosine scan over stored vectors, bounded by a top-k heap rather than library size. */
    fun search(query: FloatArray, limit: Int, memory: Boolean = false): List<Hit> {
        require(query.size == SemanticEmbeddings.DIMENSIONS && query.all { it.isFinite() } && limit in 1..100)
        val heap = java.util.PriorityQueue<Hit>(compareBy<Hit> { it.score }.thenByDescending { it.id })
        var currentId: String? = null
        var currentBest: Hit? = null
        fun retain(hit: Hit?) {
            if (hit == null) return
            heap.add(hit)
            if (heap.size > limit) heap.poll()
        }
        val scope = if (memory) "r.record_id LIKE 'memory:%'" else "r.record_id NOT LIKE 'memory:%'"
        db.rawQuery("SELECT r.record_id,r.revision,v.segment_start,v.vector FROM indexed_records r JOIN vectors v ON v.record_id=r.record_id WHERE r.model=? AND $scope ORDER BY r.record_id,v.segment_start", arrayOf(SemanticEmbeddings.MODEL)).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                if (currentId != id) { retain(currentBest); currentId = id; currentBest = null }
                val bytes = cursor.getBlob(3)
                if (bytes.size != SemanticEmbeddings.DIMENSIONS * 4) continue
                val vector = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                var score = 0.0
                for (i in query.indices) score += query[i].toDouble() * vector.float.toDouble()
                if (!score.isFinite()) continue
                if (currentBest == null || score > currentBest!!.score) currentBest = Hit(id, cursor.getString(1), cursor.getInt(2), score)
            }
        }
        retain(currentBest)
        return heap.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.id })
    }
    override fun close() = db.close()
}
