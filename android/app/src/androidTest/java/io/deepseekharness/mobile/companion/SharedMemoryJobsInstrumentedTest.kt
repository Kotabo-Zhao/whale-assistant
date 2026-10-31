package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class SharedMemoryJobsInstrumentedTest {
    @Test fun userCancellationPreventsCommitUntilExplicitResume() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val tag = UUID.randomUUID().toString()
        val dir = File(base.cacheDir, "shared-cancel-$tag").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = dir
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences("$tag-$name", mode)
        }
        val knowledge = CompanionKnowledgeStore(context)
        assertTrue(knowledge.add("cancelled source"))
        val row = knowledge.search("").getJSONObject(0); val id = row.getString("id"); val text = row.getString("text")
        SharedMemoryJobs.enqueueWithoutSchedule(context, id, text, "share:" + CompanionMemorySuppression.digest(text))
        val hash = CompanionMemorySuppression.digest(text)
        assertTrue(SharedMemoryJobs.cancelRecord(context, id, hash))
        assertEquals("cancelled", SharedMemoryJobs.status(context, id, hash).getString("state"))
        assertEquals(0L, SharedMemoryJobs.pendingCount(context))
        assertTrue(SharedMemoryJobs.resumeRecord(context, id, text))
        assertEquals("queued", SharedMemoryJobs.status(context, id, hash).getString("state"))
    }

    @Test fun chunksAtomicCompletionAndUnifiedRestore() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val tag=UUID.randomUUID().toString()
        val dir=File(base.cacheDir,"unified-$tag").apply { check(mkdirs()) }
        val context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir():File=dir
            override fun getCacheDir():File=dir
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=base.getSharedPreferences("$tag-$name",mode)
        }
        val memory=CompanionMemoryStore(context);memory.migrateToDatabase()
        val knowledge=CompanionKnowledgeStore(context)
        val text="a".repeat(7999)+"🐋"+"b".repeat(16000)+"合同尾部约定"
        val chunks=knowledge.addDocument(text,CompanionMemorySuppression.digest(text))
        assertEquals(text,(0 until chunks.length()).joinToString("") { chunks.getJSONObject(it).getString("text") })
        repeat(110) { assertTrue(knowledge.add("capacity-$it")) }
        assertTrue(knowledge.search("").length()>100)
        val allRecent = knowledge.search("")
        val page = knowledge.recent(7, 4)
        assertEquals(4, page.length())
        for (i in 0 until page.length()) assertEquals(allRecent.getJSONObject(i + 7).getString("id"), page.getJSONObject(i).getString("id"))
        assertEquals(0, knowledge.recent(10000, 4).length())

        UnifiedKnowledgeDatabase(context) { JSONArray() }.use { database ->
            val original = database.read()
            val reversed = JSONArray((0 until original.length()).reversed().map { original.getJSONObject(it) })
            database.write(reversed)
            assertEquals(reversed.toString(), database.read().toString())
            database.write(reversed)
            assertEquals(reversed.toString(), database.read().toString())
            database.write(original)
            assertEquals(original.toString(), database.read().toString())
        }
        val chunk=chunks.getJSONObject(0);val value=chunk.getString("text")
        val event="share:"+CompanionMemorySuppression.digest(value)
        SharedMemoryJobs.enqueueWithoutSchedule(context,chunk.getString("id"),value,event)
        val job=SharedMemoryJobs.Job(chunk.getString("id"),CompanionMemorySuppression.digest(value),event)
        val facts=JSONArray();CompanionUserMemory.add(facts,"atomic fact","user_edit",1,false)
        val result="""{"version":1,"claims":[],"items":[]}"""
        MemoryMigrationDatabase(UnifiedUnderstandingStore.path(context)).use { db ->
            assertThrows(Exception::class.java) { db.commitSharedJob(facts,job,result) { error("simulated interruption") } }
        }
        assertEquals(0,memory.listForUser().length());assertEquals(1L,SharedMemoryJobs.pendingCount(context))
        MemoryMigrationDatabase(UnifiedUnderstandingStore.path(context)).use { assertTrue(it.commitSharedJob(facts,job,result)) }
        assertEquals(1,memory.listForUser().length());assertEquals(0L,SharedMemoryJobs.pendingCount(context))
        assertTrue(knowledge.annotate(chunk.getString("id"),value,"model summary"))
        assertEquals(1,memory.listForUser().length())
        knowledge.add("image analysis")
        val imageRow=knowledge.search("image analysis").getJSONObject(0)
        assertEquals("image analysis", knowledge.findById(imageRow.getString("id"))!!.getString("text"))
        val rawEvent="share:"+CompanionMemorySuppression.digest("raw image bytes")
        SharedMemoryJobs.enqueueWithoutSchedule(context,imageRow.getString("id"),"image analysis",rawEvent)
        memory.saveSharedFacts(rawEvent,JSONArray().put(JSONObject().put("text","image fact")))
        assertTrue(knowledge.remove(imageRow.getString("id"),"image analysis"))
        assertNull(knowledge.findById(imageRow.getString("id")))
        memory.saveSharedFacts(rawEvent,JSONArray().put(JSONObject().put("text","reworded image fact")))
        assertEquals(1,memory.listForUser().length())
        val backup=UnifiedMemoryBackup.export(context)
        memory.addForUser("after snapshot","user_edit")
        assertThrows(Exception::class.java) { UnifiedMemoryBackup.restore(context,backup) { error("simulated restore failure") } }
        assertEquals(2,memory.listForUser().length())
        val old=memory.listForUser().getJSONObject(0)
        assertTrue(memory.deleteForUser(old.getString("id"),old.toString()))
        UnifiedMemoryBackup.restore(context,backup)
        assertEquals(0,memory.listForUser().length()) // Tombstones survive restoring older snapshots.
        assertTrue(knowledge.search("").length()>100)
        SharedMemoryJobs.enqueueWithoutSchedule(context,chunk.getString("id"),value,event)
        MemoryMigrationDatabase(UnifiedUnderstandingStore.path(context)).use { assertFalse(it.commitSharedJob(facts,job,result)) }
        assertEquals(0,memory.listForUser().length()) // A pre-restore worker cannot commit to the new generation.
        assertEquals(1L,SharedMemoryJobs.pendingCount(context))
    }

    @Test fun knowledgeMigrationRetainsLegacyButDoesNotResurrectDeletion() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val tag = UUID.randomUUID().toString()
        val directory = File(base.cacheDir, "knowledge-migration-$tag").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences("$tag-$name", mode)
        }
        val rows = JSONArray()
        CompanionKnowledgeData.add(rows, "migration source", 1)
        val legacy = File(directory, "companion-knowledge-v1.json")
        legacy.writeText(JSONObject().put("version", 1).put("items", rows).toString())
        val original = legacy.readBytes()
        val oldQueue = File(directory, "shared-memory-jobs-v1.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(oldQueue, null).use { db ->
            db.execSQL("CREATE TABLE jobs(record_id TEXT,text_hash TEXT,event_id TEXT,attempts INTEGER,next_at INTEGER)")
            db.execSQL("INSERT INTO jobs VALUES (?,?,?,2,0)", arrayOf(rows.getJSONObject(0).getString("id"), CompanionMemorySuppression.digest("migration source"), "share:" + CompanionMemorySuppression.digest("migration source")))
        }
        assertEquals(1L, SharedMemoryJobs.pendingCount(context))
        val store = CompanionKnowledgeStore(context)
        store.search("") // Trigger the lazy legacy migration before checking reopen.
        UnifiedKnowledgeDatabase(context) { error("must not reimport") }.use { db ->
            assertThrows(Exception::class.java) { db.write(JSONArray().put(rows.getJSONObject(0)).put(rows.getJSONObject(0))) }
            assertEquals(rows.toString(), db.read().toString())
        }
        val row = store.search("").getJSONObject(0)
        assertEquals(rows.getJSONObject(0).getString("id"), row.getString("id"))
        val memory = CompanionMemoryStore(context)
        memory.migrateToDatabase()
        val event = "share:" + CompanionMemorySuppression.digest("migration source")
        memory.saveSharedFacts(event, JSONArray().put(JSONObject().put("text", "derived fact")))
        assertEquals(1, memory.listForUser().length())
        assertTrue(store.remove(row.getString("id"), row.getString("text")))
        assertEquals(0, CompanionKnowledgeStore(context).search("").length())
        assertEquals(0, memory.listForUser().length())
        memory.saveSharedFacts(event, JSONArray().put(JSONObject().put("text", "reworded derived fact")))
        assertEquals(0, memory.listForUser().length())
        SharedMemoryJobs.runOne(context, AtomicBoolean(false), UnderstandingModel { _, _ -> error("deleted source must not call model") })
        assertEquals(0L, SharedMemoryJobs.pendingCount(context))
        assertEquals(0L, SharedMemoryJobs.pendingCount(context))
        assertArrayEquals(original, legacy.readBytes())
        assertTrue(UnifiedUnderstandingStore.path(context).isFile)
    }

    @Test fun failedJobsSurviveAndDeletedMemoriesDoNotReturn() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val testId = UUID.randomUUID().toString()
        val directory = File(base.cacheDir, "shared-job-test-$testId").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("shared-job-test-$testId-$name", mode)
        }
        val knowledge = CompanionKnowledgeStore(context)
        val source = "Test fixture: whale likes jasmine tea"
        assertTrue(knowledge.add(source))
        val id = knowledge.search("").getJSONObject(0).getString("id")
        val event = "share:" + CompanionMemorySuppression.digest(source)
        SharedMemoryJobs.enqueueWithoutSchedule(context, id, source, event)
        val fail = UnderstandingModel { _, _ -> error("offline") }
        assertThrows(Exception::class.java) { SharedMemoryJobs.runOne(context, AtomicBoolean(false), fail) }
        assertEquals(1L, SharedMemoryJobs.pendingCount(context))
        SharedMemoryJobs.resetDelays(context)
        val model = UnderstandingModel { request, _ -> JSONObject().put("version", 1).put("claims", JSONArray().put(
            JSONObject().put("text", "Test whale likes jasmine tea").put("evidence_ids", JSONArray().put(request)))).toString() }
        SharedMemoryJobs.runOne(context, AtomicBoolean(false), model)
        assertEquals(0L, SharedMemoryJobs.pendingCount(context))
        val memory = CompanionMemoryStore(context)
        memory.migrateToDatabase()
        var row = memory.listForUser().getJSONObject(0)
        val correction = JSONObject().put("op", "update").put("id", row.getString("id"))
            .put("expectedText", row.getString("text")).put("text", "Test whale now likes lemon tea")
        assertTrue(JSONObject(memory.execute(correction.toString())).getBoolean("updated"))
        assertThrows(Exception::class.java) { memory.execute(correction.toString()) }
        SharedMemoryJobs.enqueueWithoutSchedule(context, id, source, event)
        SharedMemoryJobs.runOne(context, AtomicBoolean(false), model)
        assertEquals(1, memory.listForUser().length())
        row = memory.listForUser().getJSONObject(0)
        assertEquals("Test whale now likes lemon tea", row.getString("text"))
        assertTrue(memory.deleteForUser(row.getString("id"), row.toString()))
        SharedMemoryJobs.enqueueWithoutSchedule(context, id, source, event)
        SharedMemoryJobs.runOne(context, AtomicBoolean(false), model)
        assertEquals(0, memory.listForUser().length())
        SharedMemoryJobs.enqueueWithoutSchedule(context, id, source, event)
        assertTrue(knowledge.remove(id, source))
        SharedMemoryJobs.runOne(context, AtomicBoolean(false), model)
        assertEquals(0L, SharedMemoryJobs.pendingCount(context))
    }
}
