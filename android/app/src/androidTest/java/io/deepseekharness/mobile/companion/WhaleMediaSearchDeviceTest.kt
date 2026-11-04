package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WhaleMediaSearchDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun recognizesChineseAudioFromAFile() {
        val context = instrumentation.targetContext
        val file = File.createTempFile("speech-", ".wav", context.cacheDir)
        try {
            instrumentation.context.assets.open("whale-knowledge-mandarin.wav").use { input -> file.outputStream().use(input::copyTo) }
            val start = SystemClock.elapsedRealtime()
            val text = WhaleAudioTranscript.recognize(context, file)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("fixtureTranscript", text); putLong("recognitionMs", SystemClock.elapsedRealtime() - start)
            })
            assertTrue(text, text.contains("开会") && text.contains("笔记本") && text.contains("[00:"))
        } finally { file.delete() }
    }

    @Test fun indexedSearchPreservesResultsAndTracksEditsAndDeletes() {
        val base = instrumentation.targetContext
        val key = "knowledge-benchmark-" + UUID.randomUUID()
        val root = File(base.cacheDir, key).apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(key + name, mode)
        }
        try {
            val seed = JSONArray()
            repeat(1200) { i ->
                seed.put(JSONObject().put("id", UUID.randomUUID().toString()).put("createdAt", i + 1).put("source", "shared_text")
                    .put("text", "文档编号 $i " + "日常会议记录。".repeat(24) + if (i % 173 == 0) "次日上午开会" else ""))
            }
            UnifiedKnowledgeDatabase(context) { seed }.use { db ->
                val query = "次日上午"
                fun scan(): List<String> {
                    val all = db.read()
                    return (all.length() - 1 downTo 0).map(all::getJSONObject).filter { it.getString("text").contains(query) }.map { it.getString("id") }
                }
                fun index(): List<String> {
                    val hits = db.searchPage(query, 0, 50).first
                    return (0 until hits.length()).map { hits.getJSONObject(it).getString("id") }
                }
                val expected = scan()
                assertEquals(7, expected.size)
                repeat(40) { assertEquals(expected, index()) }
                val before = SystemClock.elapsedRealtimeNanos()
                repeat(12) { assertEquals(expected, scan()) }
                val middle = SystemClock.elapsedRealtimeNanos()
                repeat(12) { assertEquals(expected, index()) }
                val after = SystemClock.elapsedRealtimeNanos()
                instrumentation.sendStatus(0, Bundle().apply {
                    putLong("scanMeanUs", (middle - before) / 12_000)
                    putLong("indexMeanUs", (after - middle) / 12_000); putInt("hits", expected.size)
                })
                val row = db.findById(expected.first())!!
                db.updateRecord(row.getString("id"), row.put("text", "修订资料"))
                assertEquals(expected.drop(1), index())
                val victim = db.findById(expected.last())!!
                assertTrue(db.trashRecord(victim.getString("id"), victim.getString("text"), System.currentTimeMillis()))
                assertEquals(expected.drop(1).dropLast(1), index())
            }
        } finally { base.deleteSharedPreferences(key + "companion_private_v1"); root.deleteRecursively() }
    }
}
