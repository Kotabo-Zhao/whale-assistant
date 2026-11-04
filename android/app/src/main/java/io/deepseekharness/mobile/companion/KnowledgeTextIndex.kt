package io.deepseekharness.mobile.companion

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** Encoded character pairs retain literal Chinese substring matches without a tokenizer dictionary. */
internal object KnowledgeTextIndex {
    // A backup may contain exactly these app-owned definitions. They are never copied into the live DB.
    val triggers = listOf(
        "CREATE TRIGGER knowledge_insert AFTER INSERT ON knowledge_records BEGIN INSERT OR REPLACE INTO knowledge_dirty VALUES (NEW.rowid); END",
        "CREATE TRIGGER knowledge_update AFTER UPDATE OF body ON knowledge_records BEGIN INSERT OR REPLACE INTO knowledge_dirty VALUES (NEW.rowid); END",
        "CREATE TRIGGER knowledge_delete AFTER DELETE ON knowledge_records BEGIN DELETE FROM knowledge_pairs WHERE docid=OLD.rowid; DELETE FROM knowledge_dirty WHERE rid=OLD.rowid; END",
    ).associateBy { it.substringAfter("CREATE TRIGGER ").substringBefore(' ') }

    val derivedTables = setOf("knowledge_dirty", "knowledge_pairs", "knowledge_pairs_content", "knowledge_pairs_docsize",
        "knowledge_pairs_segdir", "knowledge_pairs_segments", "knowledge_pairs_stat")

    fun rebuildAfterRestore(db: SQLiteDatabase) {
        db.delete("knowledge_pairs", null, null)
        db.delete("knowledge_dirty", null, null)
        db.execSQL("INSERT INTO knowledge_dirty SELECT rowid FROM knowledge_records")
        db.execSQL("INSERT OR REPLACE INTO knowledge_meta VALUES ('text_pairs_v1','1')")
        db.execSQL("INSERT OR REPLACE INTO knowledge_meta VALUES ('text_pairs_origins','1')")
    }

    fun install(db: SQLiteDatabase) {
        val ready = db.rawQuery("SELECT 1 FROM knowledge_meta WHERE key='text_pairs_v1'", null).use { it.moveToFirst() }
        if (ready) {
            val updated = db.rawQuery("SELECT 1 FROM knowledge_meta WHERE key='text_pairs_origins'", null).use { it.moveToFirst() }
            if (!updated) {
                db.execSQL("INSERT OR IGNORE INTO knowledge_dirty SELECT rowid FROM knowledge_records")
                db.execSQL("INSERT INTO knowledge_meta VALUES ('text_pairs_origins','1')")
            }
            return
        }
        db.execSQL("CREATE VIRTUAL TABLE knowledge_pairs USING fts4(tokens, tokenize=simple)")
        db.execSQL("CREATE TABLE knowledge_dirty (rid INTEGER PRIMARY KEY)")
        db.execSQL("INSERT INTO knowledge_dirty SELECT rowid FROM knowledge_records")
        triggers.values.forEach { db.execSQL(it) }
        db.execSQL("INSERT INTO knowledge_meta VALUES ('text_pairs_v1','1')")
        db.execSQL("INSERT INTO knowledge_meta VALUES ('text_pairs_origins','1')")
    }

    fun searchableText(row: JSONObject): String = row.getString("text") + "\n" + row.optString("displayName") + "\n" + WhaleShareSource.labels(row)

    fun contains(row: JSONObject, query: String): Boolean = row.getString("text").contains(query,ignoreCase=true) ||
        row.optString("displayName").contains(query,ignoreCase=true) || WhaleShareSource.labels(row).contains(query,ignoreCase=true)

    private fun pairs(text: String): Set<String> {
        val result = LinkedHashSet<String>()
        var previous = -1
        for (ch in text) {
            val current = ch.uppercaseChar().lowercaseChar().code
            if (previous >= 0) result.add(((previous.toLong() shl 16) or current.toLong()).toString(36))
            previous = current
        }
        return result
    }

    /** Unindexed and recently edited rows are always included, even while upgrading a large library. */
    fun candidates(db: SQLiteDatabase, query: String): Cursor {
        if (query.length < 2 || query.any(Char::isSurrogate))
            return db.rawQuery("SELECT body FROM knowledge_records ORDER BY position DESC", null)
        return matching(db, pairs(query).take(12).joinToString(" "))
    }
    fun related(db: SQLiteDatabase, terms: Set<String>): Cursor {
        require(terms.isNotEmpty())
        return matching(db, terms.map { pairs(it).first() }.distinct().joinToString(" OR "))
    }
    private fun matching(db: SQLiteDatabase, expression: String): Cursor {
        db.beginTransaction()
        try {
            db.rawQuery("SELECT r.rowid,r.body FROM knowledge_dirty d JOIN knowledge_records r ON r.rowid=d.rid LIMIT 32", null).use { cursor ->
                // Materialize one bounded batch before modifying the table being traversed.
                val batch = buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to JSONObject(cursor.getString(1))) }
                for ((rowId, row) in batch) {
                    val text = searchableText(row)
                    db.execSQL("INSERT OR REPLACE INTO knowledge_pairs(docid,tokens) VALUES (?,?)", arrayOf(rowId, pairs(text).joinToString(" ")))
                    db.execSQL("DELETE FROM knowledge_dirty WHERE rid=?", arrayOf(rowId))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return db.rawQuery("""
            SELECT body FROM knowledge_records WHERE rowid IN (
                SELECT docid FROM knowledge_pairs WHERE tokens MATCH ? UNION ALL SELECT rid FROM knowledge_dirty
            ) ORDER BY position DESC
        """.trimIndent(), arrayOf(expression))
    }
}
