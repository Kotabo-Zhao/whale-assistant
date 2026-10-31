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
        val row = memory.listForUser().getJSONObject(0)
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
