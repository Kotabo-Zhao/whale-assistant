package io.deepseekharness.mobile.companion

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID

/** Persistent metadata only: no source text, credentials, model calls, or scheduling. */
internal class UnderstandingQueue(path: File) : AutoCloseable {
    private val db = SQLiteDatabase.openOrCreateDatabase(path, null)

    init {
        try {
            db.beginTransaction()
            try {
                check(db.version == 0 || db.version == 1) { "unsupported understanding queue version" }
                db.execSQL("CREATE TABLE IF NOT EXISTS sources (id TEXT PRIMARY KEY, enabled INTEGER NOT NULL, epoch INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, source TEXT NOT NULL, input_hash TEXT NOT NULL, epoch INTEGER NOT NULL, state TEXT NOT NULL, token TEXT, attempts INTEGER NOT NULL DEFAULT 0, next_at INTEGER NOT NULL DEFAULT 0, UNIQUE(source, input_hash, epoch))")
                db.execSQL("CREATE INDEX IF NOT EXISTS jobs_ready ON jobs(state, next_at)")
                db.execSQL("CREATE TABLE IF NOT EXISTS understanding_events (id TEXT PRIMARY KEY, source TEXT NOT NULL, input_hash TEXT NOT NULL, epoch INTEGER NOT NULL, body TEXT NOT NULL, UNIQUE(source, input_hash, epoch))")
                db.execSQL("CREATE TABLE IF NOT EXISTS understanding_results (job_id TEXT PRIMARY KEY, event_id TEXT NOT NULL, body TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS understanding_items (id TEXT PRIMARY KEY, job_id TEXT NOT NULL, event_id TEXT NOT NULL, title TEXT NOT NULL, state TEXT NOT NULL, UNIQUE(job_id, title))")
                db.execSQL("CREATE TABLE IF NOT EXISTS understanding_item_reminders (item_id TEXT PRIMARY KEY NOT NULL, due_at INTEGER NOT NULL, enabled INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS understanding_forget (event_id TEXT PRIMARY KEY NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS understanding_event_threads (event_id TEXT PRIMARY KEY NOT NULL, thread_hash TEXT NOT NULL, observed_at INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS understanding_thread_lookup ON understanding_event_threads(thread_hash)")
                db.execSQL("CREATE TABLE IF NOT EXISTS understanding_item_evidence (item_id TEXT NOT NULL, event_id TEXT NOT NULL, PRIMARY KEY(item_id,event_id)) WITHOUT ROWID")
                db.execSQL("INSERT OR IGNORE INTO understanding_item_evidence(item_id,event_id) SELECT id,event_id FROM understanding_items")
                if (db.version == 0) db.version = 1
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } catch (e: Exception) { db.close(); throw e }
    }

    data class Claim(val id: String, val source: String, val inputHash: String, val epoch: Long, val token: String)

    /** A permission change invalidates all old work, including results already in flight. */
    fun setSourceEnabled(source: String, enabled: Boolean) = transaction {
        validateSource(source)
        val current = sourceState(source)
        if (current == null) {
            db.execSQL("INSERT INTO sources VALUES (?, ?, 1)", arrayOf(source, if (enabled) 1 else 0))
        } else if (current.first != enabled) {
            db.execSQL("UPDATE sources SET enabled = ?, epoch = epoch + 1 WHERE id = ?", arrayOf(if (enabled) 1 else 0, source))
            db.execSQL("UPDATE jobs SET state = 'cancelled', token = NULL WHERE source = ? AND state IN ('pending', 'running')", arrayOf(source))
        }
    }

    fun revokeAll() = transaction {
        db.execSQL("UPDATE sources SET enabled=0, epoch=epoch+1 WHERE enabled=1")
        db.execSQL("UPDATE jobs SET state='cancelled', token=NULL WHERE state IN ('pending','running')")
    }

    fun clearCollectedData() = transaction {
        db.execSQL("UPDATE sources SET enabled=0, epoch=epoch+1 WHERE enabled=1")
        db.execSQL("INSERT OR IGNORE INTO understanding_forget(event_id) SELECT id FROM understanding_events")
        db.delete("understanding_results", null, null)
        db.delete("understanding_items", null, null)
        db.delete("understanding_item_reminders", null, null)
        db.delete("understanding_item_evidence", null, null)
        db.delete("understanding_event_threads", null, null)
        db.delete("jobs", null, null)
        db.delete("understanding_events", null, null)
    }

    /** Excludes credentials, source permissions and queued work. */
    fun exportArchive(): ByteArray = transaction {
        val events = org.json.JSONArray(); val results = org.json.JSONArray(); val items = org.json.JSONArray(); val forgotten = org.json.JSONArray()
        val threads = org.json.JSONArray(); val evidence = org.json.JSONArray(); val reminders = org.json.JSONArray()
        db.rawQuery("SELECT id,source,input_hash,epoch,body FROM understanding_events ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) events.put(org.json.JSONObject().put("id", c.getString(0)).put("source", c.getString(1)).put("hash", c.getString(2)).put("epoch", c.getLong(3)).put("body", c.getString(4)))
        }
        db.rawQuery("SELECT job_id,event_id,body FROM understanding_results ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) results.put(org.json.JSONObject().put("job", c.getString(0)).put("event", c.getString(1)).put("body", c.getString(2)))
        }
        db.rawQuery("SELECT id,job_id,event_id,title,state FROM understanding_items ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) items.put(org.json.JSONObject().put("id", c.getString(0)).put("job", c.getString(1)).put("event", c.getString(2)).put("title", c.getString(3)).put("state", c.getString(4)))
        }
        db.rawQuery("SELECT event_id FROM understanding_forget", null).use { c -> while (c.moveToNext()) forgotten.put(c.getString(0)) }
        db.rawQuery("SELECT event_id,thread_hash,observed_at FROM understanding_event_threads", null).use { c -> while (c.moveToNext()) threads.put(org.json.JSONObject().put("event", c.getString(0)).put("thread", c.getString(1)).put("at", c.getLong(2))) }
        db.rawQuery("SELECT item_id,event_id FROM understanding_item_evidence", null).use { c -> while (c.moveToNext()) evidence.put(org.json.JSONObject().put("item", c.getString(0)).put("event", c.getString(1))) }
        db.rawQuery("SELECT item_id,due_at,enabled FROM understanding_item_reminders", null).use { c -> while (c.moveToNext()) reminders.put(org.json.JSONObject().put("item", c.getString(0)).put("at", c.getLong(1)).put("status", c.getInt(2))) }
        CompanionKnowledgeData.utf8(org.json.JSONObject().put("version", 1).put("events", events).put("results", results).put("items", items).put("forgotten", forgotten).put("threads", threads).put("itemEvidence", evidence).put("reminders", reminders).toString())
            .also { require(it.size <= 8_000_000) { "后台备份超过单次大小上限" } }
    }

    /** Merge validated archive transactionally, never schedules work or restores permission. */
    fun importArchive(bytes: ByteArray): Int = transaction {
        require(bytes.size <= 8_000_000)
        val decoded = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val root = org.json.JSONObject(decoded)
        require(root.opt("version") == 1)
        fun validId(id: String) = id.matches(Regex("[a-zA-Z0-9._:-]{1,200}"))
        val forgotten = root.getJSONArray("forgotten")
        for (i in 0 until forgotten.length()) {
            val id = forgotten.getString(i); require(validId(id))
            db.insertWithOnConflict("understanding_forget", null, ContentValues().apply { put("event_id", id) }, SQLiteDatabase.CONFLICT_IGNORE)
        }
        db.execSQL("DELETE FROM jobs WHERE EXISTS (SELECT 1 FROM understanding_events e JOIN understanding_forget f ON f.event_id=e.id WHERE e.source=jobs.source AND e.input_hash=jobs.input_hash AND e.epoch=jobs.epoch)")
        db.execSQL("DELETE FROM understanding_items WHERE event_id IN (SELECT event_id FROM understanding_forget)")
        db.execSQL("DELETE FROM understanding_item_reminders WHERE item_id NOT IN (SELECT id FROM understanding_items)")
        db.execSQL("DELETE FROM understanding_item_evidence WHERE item_id NOT IN (SELECT id FROM understanding_items) OR event_id IN (SELECT event_id FROM understanding_forget)")
        db.execSQL("DELETE FROM understanding_event_threads WHERE event_id IN (SELECT event_id FROM understanding_forget)")
        db.execSQL("DELETE FROM understanding_results WHERE event_id IN (SELECT event_id FROM understanding_forget)")
        db.execSQL("DELETE FROM understanding_events WHERE id IN (SELECT event_id FROM understanding_forget)")
        var imported = 0
        val events = root.getJSONArray("events")
        for (i in 0 until events.length()) {
            val row = events.getJSONObject(i); val id = row.getString("id"); val source = row.getString("source"); val hash = row.getString("hash"); val body = row.getString("body")
            val notificationSource = source.startsWith("notification:") && CompanionSourcePolicy.validPackage(source.removePrefix("notification:"))
            val sharedMaterialSource = source == "shared_material" && id.matches(Regex("share:[a-f0-9]{64}"))
            require(validId(id) && (notificationSource || sharedMaterialSource) && body.isNotBlank() && body.length <= (if (sharedMaterialSource) 8_000 else 4_000) && row.getLong("epoch") > 0)
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(CompanionKnowledgeData.utf8("${id.length}:$id$body")).joinToString("") { "%02x".format(it.toInt() and 255) }
            require(hash == expected)
            if (db.rawQuery("SELECT 1 FROM understanding_forget WHERE event_id=?", arrayOf(id)).use { it.moveToFirst() }) continue
            val values = ContentValues().apply { put("id", id); put("source", source); put("input_hash", hash); put("epoch", row.getLong("epoch")); put("body", body) }
            if (db.insertWithOnConflict("understanding_events", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) imported++
            else check(db.rawQuery("SELECT source,input_hash,epoch,body FROM understanding_events WHERE id=?", arrayOf(id)).use { c -> c.moveToFirst() && c.getString(0) == source && c.getString(1) == hash && c.getLong(2) == row.getLong("epoch") && c.getString(3) == body }) { "event conflict" }
        }
        val threads = if (root.has("threads")) root.getJSONArray("threads") else org.json.JSONArray()
        for (i in 0 until threads.length()) {
            val row = threads.getJSONObject(i); val event = row.getString("event"); val thread = row.getString("thread"); val at = row.getLong("at")
            require(validId(event) && thread.matches(Regex("[a-f0-9]{64}")) && at > 0)
            if (!db.rawQuery("SELECT 1 FROM understanding_events WHERE id=?", arrayOf(event)).use { it.moveToFirst() }) continue
            val values = ContentValues().apply { put("event_id", event); put("thread_hash", thread); put("observed_at", at) }
            if (db.insertWithOnConflict("understanding_event_threads", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L)
                check(db.rawQuery("SELECT thread_hash,observed_at FROM understanding_event_threads WHERE event_id=?", arrayOf(event)).use { c -> c.moveToFirst() && c.getString(0) == thread && c.getLong(1) == at }) { "thread conflict" }
        }
        val results = root.getJSONArray("results")
        for (i in 0 until results.length()) {
            val row = results.getJSONObject(i); val job = row.getString("job"); val event = row.getString("event"); val body = row.getString("body")
            require(validId(job) && validId(event)); UnderstandingModelContract.validate(body, event)
            if (!db.rawQuery("SELECT 1 FROM understanding_events WHERE id=? AND NOT EXISTS (SELECT 1 FROM understanding_forget WHERE event_id=?)", arrayOf(event, event)).use { it.moveToFirst() }) continue
            val values = ContentValues().apply { put("job_id", job); put("event_id", event); put("body", body) }
            if (db.insertWithOnConflict("understanding_results", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L)
                check(db.rawQuery("SELECT event_id,body FROM understanding_results WHERE job_id=?", arrayOf(job)).use { c -> c.moveToFirst() && c.getString(0) == event && c.getString(1) == body }) { "result conflict" }
        }
        val items = root.getJSONArray("items")
        for (i in 0 until items.length()) {
            val row = items.getJSONObject(i); val id = row.getString("id"); val job = row.getString("job"); val event = row.getString("event"); val title = row.getString("title"); val state = row.getString("state")
            require(validId(id) && validId(job) && validId(event) && title.isNotBlank() && title.length <= 200 && !CompanionStore.sensitive(title) && state in setOf("candidate", "open", "done", "cancelled"))
            if (!db.rawQuery("SELECT 1 FROM understanding_results WHERE job_id=? AND event_id=?", arrayOf(job, event)).use { it.moveToFirst() }) continue
            val values = ContentValues().apply { put("id", id); put("job_id", job); put("event_id", event); put("title", title); put("state", state) }
            // Existing item state wins over an older backup.
            db.insertWithOnConflict("understanding_items", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
        val evidence = if (root.has("itemEvidence")) root.getJSONArray("itemEvidence") else org.json.JSONArray()
        for (i in 0 until evidence.length()) {
            val row = evidence.getJSONObject(i); val item = row.getString("item"); val event = row.getString("event")
            require(validId(item) && validId(event))
            if (!db.rawQuery("SELECT 1 FROM understanding_items WHERE id=?", arrayOf(item)).use { it.moveToFirst() } || !db.rawQuery("SELECT 1 FROM understanding_events WHERE id=?", arrayOf(event)).use { it.moveToFirst() }) continue
            db.insertWithOnConflict("understanding_item_evidence", null, ContentValues().apply { put("item_id", item); put("event_id", event) }, SQLiteDatabase.CONFLICT_IGNORE)
        }
        val reminders = if (root.has("reminders")) root.getJSONArray("reminders") else org.json.JSONArray()
        for (i in 0 until reminders.length()) {
            val row = reminders.getJSONObject(i); val item = row.getString("item"); val at = row.getLong("at"); val state = row.getInt("status")
            require(validId(item) && at > 0 && state in 0..2)
            if (!db.rawQuery("SELECT 1 FROM understanding_items WHERE id=?", arrayOf(item)).use { it.moveToFirst() }) continue
            // Restored reminders require explicit review; existing local state wins.
            db.insertWithOnConflict("understanding_item_reminders", null, ContentValues().apply { put("item_id", item); put("due_at", at); put("enabled", if (state == 0) 0 else 2) }, SQLiteDatabase.CONFLICT_IGNORE)
        }
        imported
    }

    fun pendingCount(): Long = db.rawQuery("SELECT COUNT(*) FROM jobs WHERE state IN ('pending','running')", null).use {
        it.moveToFirst(); it.getLong(0)
    }

    data class PendingStatus(val count: Long, val attempts: Int, val nextAtMs: Long?)
    fun pendingStatus(): PendingStatus = db.rawQuery(
        "SELECT COUNT(*),COALESCE(MAX(attempts),0),MIN(CASE WHEN state='pending' THEN next_at END) FROM jobs WHERE state IN ('pending','running')", null
    ).use { c -> c.moveToFirst(); PendingStatus(c.getLong(0), c.getInt(1), if (c.isNull(2)) null else c.getLong(2)) }

    /** Manual retry only releases waiting work; an in-flight model request keeps its lease. */
    fun retryPending(now: Long): Int = transaction {
        require(now >= 0)
        db.update("jobs", ContentValues().apply { put("next_at", now) }, "state='pending' AND next_at>?", arrayOf(now.toString()))
    }

    fun enqueue(source: String, inputHash: String): Boolean = transaction {
        validateSource(source)
        require(inputHash.matches(Regex("[a-f0-9]{64}"))) { "invalid input hash" }
        val state = sourceState(source)
        check(state != null && state.first) { "source not enabled" }
        val values = ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("source", source); put("input_hash", inputHash)
            put("epoch", state.second); put("state", "pending")
        }
        db.insertWithOnConflict("jobs", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** Ingest and enqueue atomically. Text stays in the private database. */
    fun ingest(source: String, eventId: String, body: String, threadHash: String? = null, observedAt: Long = 0): Boolean = transaction {
        validateSource(source)
        require(eventId.matches(Regex("[a-zA-Z0-9._:-]{1,200}")))
        require(body.isNotBlank() && body.length <= 4000)
        require(threadHash == null || (threadHash.matches(Regex("[a-f0-9]{64}")) && observedAt > 0))
        val state = sourceState(source)
        check(state != null && state.first) { "source not enabled" }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(CompanionKnowledgeData.utf8("${eventId.length}:$eventId$body"))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        db.rawQuery("SELECT source, input_hash, epoch FROM understanding_events WHERE id = ?", arrayOf(eventId)).use {
            if (it.moveToFirst()) {
                check(it.getString(0) == source && it.getString(1) == hash && it.getLong(2) == state.second) { "event id conflict" }
                return@transaction false
            }
        }
        val values = ContentValues().apply { put("id", eventId); put("source", source); put("input_hash", hash); put("epoch", state.second); put("body", body) }
        db.insertOrThrow("understanding_events", null, values)
        if (threadHash != null) db.insertOrThrow("understanding_event_threads", null, ContentValues().apply { put("event_id", eventId); put("thread_hash", threadHash); put("observed_at", observedAt) })
        val job = ContentValues().apply { put("id", UUID.randomUUID().toString()); put("source", source); put("input_hash", hash); put("epoch", state.second); put("state", "pending") }
        db.insertOrThrow("jobs", null, job)
        true
    }

    fun eventFor(claim: Claim): Pair<String, String>? = db.rawQuery(
        "SELECT e.id, e.body FROM understanding_events e JOIN jobs j ON j.source=e.source AND j.input_hash=e.input_hash AND j.epoch=e.epoch JOIN sources s ON s.id=j.source AND s.enabled=1 AND s.epoch=j.epoch WHERE j.id=? AND j.state='running' AND j.token=?",
        arrayOf(claim.id, claim.token)
    ).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }

    /** Commit validated proposals with the job transition; revoked or stale claims write nothing. */
    fun commitResult(claim: Claim, eventId: String, result: String, autoMemory: Boolean = false, suppressedHashes: Set<String> = emptySet()): Boolean = transaction {
        val event = eventFor(claim) ?: return@transaction false
        if (event.first != eventId) return@transaction false
        UnderstandingModelContract.validate(result, eventId)
        val values = ContentValues().apply { put("job_id", claim.id); put("event_id", eventId); put("body", result) }
        db.insertOrThrow("understanding_results", null, values)
        if (autoMemory) {
            check(db.rawQuery("SELECT value FROM metadata WHERE key='legacy_imported'", null).use { it.moveToFirst() && it.getString(0) == "1" })
            var position = db.rawQuery("SELECT COALESCE(MAX(position),-1)+1 FROM memories", null).use { it.moveToFirst(); it.getLong(0) }
            val claims = org.json.JSONObject(result).getJSONArray("claims")
            var added = false
            for (i in 0 until claims.length()) {
                val text = claims.getJSONObject(i).getString("text").trim()
                val hash = CompanionMemorySuppression.digest(text)
                if (hash in suppressedHashes || db.rawQuery("SELECT 1 FROM memory_text_index WHERE digest=? LIMIT 1", arrayOf(hash)).use { it.moveToFirst() }) continue
                val id = UUID.randomUUID().toString()
                val row = org.json.JSONObject().put("id", id).put("text", text).put("source", "auto_understanding")
                    .put("status", "confirmed").put("createdAt", System.currentTimeMillis())
                    .put("evidenceSource", claim.source).put("evidenceEventId", eventId)
                val memory = ContentValues().apply { put("position", position++); put("id", id); put("body", row.toString()) }
                db.insertOrThrow("memories", null, memory)
                db.insertOrThrow("memory_text_index", null, ContentValues().apply { put("id", id); put("digest", hash) })
                MemorySearchIndex.put(db, id, row)
                added = true
            }
            if (added) db.execSQL("UPDATE metadata SET value = CAST(value AS INTEGER) + 1 WHERE key = 'revision'")
        }
        val items = org.json.JSONObject(result).optJSONArray("items") ?: org.json.JSONArray()
        for (i in 0 until items.length()) {
            val title = items.getJSONObject(i).getString("title").trim()
            if (items.length() == 1 && mergeThreadItem(claim, eventId, title, event.second)) continue
            val itemId = UUID.randomUUID().toString()
            val item = ContentValues().apply {
                put("id", itemId); put("job_id", claim.id); put("event_id", eventId)
                put("title", title); put("state", "candidate")
            }
            if (db.insertWithOnConflict("understanding_items", null, item, SQLiteDatabase.CONFLICT_IGNORE) != -1L)
                db.insertOrThrow("understanding_item_evidence", null, ContentValues().apply { put("item_id", itemId); put("event_id", eventId) })
        }
        val update = ContentValues().apply { put("state", "done"); putNull("token") }
        check(db.update("jobs", update, "id=? AND state='running' AND token=?", arrayOf(claim.id, claim.token)) == 1)
        true
    }

    /** Merge only one active, text-compatible item from the same system notification identity. */
    private fun mergeThreadItem(claim: Claim, eventId: String, title: String, eventBody: String): Boolean {
        val thread = db.rawQuery("SELECT thread_hash,observed_at FROM understanding_event_threads WHERE event_id=?", arrayOf(eventId)).use {
            if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null
        } ?: return false
        data class Previous(val id: String, val title: String, val eventId: String, val at: Long, val body: String)
        val candidates = mutableListOf<Previous>()
        db.rawQuery("SELECT i.id,i.title,i.event_id,t.observed_at,e.body FROM understanding_items i JOIN understanding_event_threads t ON t.event_id=i.event_id JOIN understanding_events e ON e.id=i.event_id WHERE t.thread_hash=? AND e.source=? AND i.state IN ('candidate','open') AND i.event_id<>? ORDER BY t.observed_at DESC LIMIT 2", arrayOf(thread.first, claim.source, eventId)).use { c ->
            while (c.moveToNext()) candidates.add(Previous(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4)))
        }
        if (candidates.size != 1) return false
        val old = candidates.single()
        if (thread.second <= old.at) return false
        val same = MemorySearchIndex.normalize(old.title.trim()) == MemorySearchIndex.normalize(title)
        val signature = CompanionMemorySuppression.encodeSignature(old.title)
        if (!same && (signature == null || !CompanionMemorySuppression.matches(title, setOf(signature)))) return false
        db.insertWithOnConflict("understanding_item_evidence", null, ContentValues().apply { put("item_id", old.id); put("event_id", eventId) }, SQLiteDatabase.CONFLICT_IGNORE)
        db.update("understanding_items", ContentValues().apply { put("job_id", claim.id); put("event_id", eventId); put("title", title) }, "id=?", arrayOf(old.id))
        if (listOf("改期", "改到", "改为", "延期", "调整至", "调整到").any(eventBody::contains)) {
            val now = System.currentTimeMillis(); val zone = java.time.ZoneId.systemDefault()
            val oldTime = CompanionNotificationTimeSuggestion.find(old.body, now, zone, old.at)
            val newTime = CompanionNotificationTimeSuggestion.find(eventBody, now, zone, thread.second)
            val reminder = db.rawQuery("SELECT due_at FROM understanding_item_reminders WHERE item_id=? AND enabled=1", arrayOf(old.id)).use { if (it.moveToFirst()) it.getLong(0) else null }
            if (reminder != null) {
                val next = if (oldTime != null && newTime != null) runCatching { Math.addExact(newTime, Math.subtractExact(reminder, oldTime)) }.getOrNull() else null
                if (next != null && CompanionReminderPolicy.isFuture(next, now)) db.execSQL("UPDATE understanding_item_reminders SET due_at=? WHERE item_id=? AND enabled=1", arrayOf(next, old.id))
                else db.execSQL("UPDATE understanding_item_reminders SET enabled=2 WHERE item_id=? AND enabled=1", arrayOf(old.id))
            }
        }
        return true
    }

    /** Results remain proposals; source revocation prevents further model work, not local review. */
    fun recentResults(limit: Int): List<Pair<String, String>> {
        require(limit in 1..100)
        return db.rawQuery("SELECT event_id, body FROM understanding_results ORDER BY rowid DESC LIMIT ?", arrayOf(limit.toString())).use { cursor ->
            val results = mutableListOf<Pair<String, String>>()
            while (cursor.moveToNext()) results.add(cursor.getString(0) to cursor.getString(1))
            results
        }
    }

    data class CandidateEvidence(val eventId: String, val source: String, val event: String, val result: String)
    fun sourceEvent(eventId: String): Pair<String, String>? = db.rawQuery(
        "SELECT source, body FROM understanding_events WHERE id=?", arrayOf(eventId)
    ).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }
    /** Removing a source must retract its candidates before a stale worker can publish them again. */
    fun forgetEvents(eventIds: Set<String>): List<String> = transaction {
        require(eventIds.all { it.matches(Regex("share:[a-f0-9]{64}")) })
        if (eventIds.isEmpty()) return@transaction emptyList()
        val placeholders = eventIds.joinToString(",") { "?" }
        val args = eventIds.toTypedArray()
        val items = mutableListOf<String>()
        db.rawQuery("SELECT id FROM understanding_items WHERE event_id IN ($placeholders)", args).use { c -> while (c.moveToNext()) items.add(c.getString(0)) }
        eventIds.forEach { id -> db.insertWithOnConflict("understanding_forget", null, ContentValues().apply { put("event_id", id) }, SQLiteDatabase.CONFLICT_IGNORE) }
        db.delete("understanding_item_evidence", "event_id IN ($placeholders) OR item_id IN (SELECT id FROM understanding_items WHERE event_id IN ($placeholders))", args + args)
        db.delete("understanding_item_reminders", "item_id IN (SELECT id FROM understanding_items WHERE event_id IN ($placeholders))", args)
        db.delete("understanding_items", "event_id IN ($placeholders)", args)
        db.delete("understanding_results", "event_id IN ($placeholders)", args)
        db.delete("understanding_event_threads", "event_id IN ($placeholders)", args)
        db.delete("understanding_events", "id IN ($placeholders)", args)
        items
    }
    data class Item(val id: String, val title: String, val state: String, val eventId: String, val reminderStatus: Int = 0)
    fun item(id: String): Item? = db.rawQuery(
        "SELECT i.id,i.title,i.state,i.event_id,COALESCE(r.enabled,0) FROM understanding_items i LEFT JOIN understanding_item_reminders r ON r.item_id=i.id WHERE i.id=?", arrayOf(id)
    ).use { c -> if (c.moveToFirst()) Item(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4)) else null }
    fun sharedRecordId(eventId: String): String? = db.rawQuery(
        "SELECT record_id FROM knowledge_source_events WHERE event_id=? LIMIT 1", arrayOf(eventId)
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    fun listItems(limit: Int): List<Item> {
        require(limit in 1..100)
        return db.rawQuery("SELECT i.id,i.title,i.state,i.event_id,COALESCE(r.enabled,0) FROM understanding_items i LEFT JOIN understanding_item_reminders r ON r.item_id=i.id ORDER BY i.rowid DESC LIMIT ?", arrayOf(limit.toString())).use { cursor ->
            val items = mutableListOf<Item>()
            while (cursor.moveToNext()) items.add(Item(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getInt(4)))
            items
        }
    }
    fun updateItem(id: String, expected: String, next: String): Boolean = transaction {
        require(expected in setOf("candidate", "open", "done", "cancelled") && next in setOf("open", "done", "cancelled"))
        val values = ContentValues().apply { put("state", next) }
        val updated = db.update("understanding_items", values, "id=? AND state=?", arrayOf(id, expected)) == 1
        if (updated && next in setOf("done", "cancelled")) db.execSQL("UPDATE understanding_item_reminders SET enabled=0 WHERE item_id=?", arrayOf(id))
        updated
    }
    fun setItemReminder(id: String, expected: String, dueAt: Long, now: Long): Boolean = transaction {
        require(expected in setOf("candidate", "open") && CompanionReminderPolicy.isFuture(dueAt, now))
        val updated = db.update("understanding_items", ContentValues().apply { put("state", "open") }, "id=? AND state=?", arrayOf(id, expected)) == 1
        if (updated) db.insertWithOnConflict("understanding_item_reminders", null, ContentValues().apply { put("item_id", id); put("due_at", dueAt); put("enabled", 1) }, SQLiteDatabase.CONFLICT_REPLACE)
        updated
    }
    fun itemReminderCurrent(id: String, dueAt: Long): Boolean = db.rawQuery(
        "SELECT 1 FROM understanding_item_reminders r JOIN understanding_items i ON i.id=r.item_id WHERE r.item_id=? AND r.due_at=? AND r.enabled=1 AND i.state='open'", arrayOf(id, dueAt.toString())
    ).use { it.moveToFirst() }
    fun disableItemReminder(id: String, dueAt: Long) = transaction {
        db.update("understanding_item_reminders", ContentValues().apply { put("enabled", 0) }, "item_id=? AND due_at=?", arrayOf(id, dueAt.toString()))
    }
    fun desiredItemReminders(): List<Triple<String, String, Long>> = db.rawQuery(
        "SELECT i.id,i.title,r.due_at FROM understanding_item_reminders r JOIN understanding_items i ON i.id=r.item_id WHERE r.enabled=1 AND i.state='open'", null
    ).use { c -> val rows = mutableListOf<Triple<String,String,Long>>(); while (c.moveToNext()) rows.add(Triple(c.getString(0), c.getString(1), c.getLong(2))); rows }
    fun recentEvidence(limit: Int): List<CandidateEvidence> {
        require(limit in 1..100)
        return db.rawQuery("SELECT e.id, e.source, e.body, r.body FROM understanding_results r JOIN understanding_events e ON e.id=r.event_id ORDER BY r.rowid DESC LIMIT ?", arrayOf(limit.toString())).use { cursor ->
            val results = mutableListOf<CandidateEvidence>()
            while (cursor.moveToNext()) results.add(CandidateEvidence(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)))
            results
        }
    }

    fun claim(now: Long): Claim? = transaction {
        require(now >= 0)
        val claim = db.rawQuery("SELECT j.id, j.source, j.input_hash, j.epoch FROM jobs j JOIN sources s ON s.id = j.source AND s.enabled = 1 AND s.epoch = j.epoch WHERE j.state = 'pending' AND j.next_at <= ? ORDER BY j.rowid LIMIT 1", arrayOf(now.toString())).use {
            if (!it.moveToFirst()) null else Claim(it.getString(0), it.getString(1), it.getString(2), it.getLong(3), UUID.randomUUID().toString())
        }
        if (claim != null) db.execSQL("UPDATE jobs SET state = 'running', token = ?, attempts = attempts + 1 WHERE id = ?", arrayOf(claim.token, claim.id))
        claim
    }

    /** The caller must apply results in a future shared transaction; this only records completion. */
    fun finish(claim: Claim): Boolean = transition(claim, "done", 0)
    fun retry(claim: Claim, nextAt: Long): Boolean { require(nextAt >= 0); return transition(claim, "pending", nextAt) }

    /** Call only on exclusive worker startup, after the previous worker is known to have stopped. */
    fun recoverInterrupted() = transaction {
        db.execSQL("UPDATE jobs SET state = 'pending', token = NULL WHERE state = 'running'")
    }

    private fun transition(claim: Claim, state: String, nextAt: Long): Boolean = transaction {
        val values = ContentValues().apply { put("state", state); putNull("token"); put("next_at", nextAt) }
        db.update("jobs", values, "id = ? AND source = ? AND epoch = ? AND state = 'running' AND token = ? AND EXISTS (SELECT 1 FROM sources WHERE id = ? AND enabled = 1 AND epoch = ?)",
            arrayOf(claim.id, claim.source, claim.epoch.toString(), claim.token, claim.source, claim.epoch.toString())) == 1
    }

    private fun sourceState(source: String): Pair<Boolean, Long>? = db.rawQuery("SELECT enabled, epoch FROM sources WHERE id = ?", arrayOf(source)).use {
        if (it.moveToFirst()) (it.getInt(0) == 1) to it.getLong(1) else null
    }
    private fun validateSource(source: String) { require(source.matches(Regex("[a-zA-Z0-9._:-]{1,200}"))) { "invalid source" } }
    private fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try { val result = block(); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    override fun close() = db.close()
}
