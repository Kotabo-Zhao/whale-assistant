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

    @Test fun findsSpeechAfterTenMinutesOfSilence() {
        val app = instrumentation.targetContext
        val root = File(app.cacheDir,"long-audio-"+UUID.randomUUID()).apply { check(mkdirs()) }
        val prefNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val wav = File(root,"late.wav")
        try {
            val clip = instrumentation.context.assets.open("whale-knowledge-mandarin.wav").use { it.readBytes() }
            val source = java.nio.ByteBuffer.wrap(clip).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var offset = 12
            while (String(clip,offset,4,Charsets.US_ASCII) != "data") {
                val length = source.getInt(offset+4)
                offset += 8 + length + (length and 1)
            }
            val speechBytes = source.getInt(offset+4)
            val silenceBytes = 605 * 16000 * 2
            val size = silenceBytes + speechBytes
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(size+36).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(size).array()
            java.io.RandomAccessFile(wav,"rw").use { output ->
                output.write(header); output.seek(44L+silenceBytes); output.write(clip,offset+8,speechBytes)
            }
            val start = SystemClock.elapsedRealtime()
            val baseline = InstrumentationRegistry.getArguments().getString("baseline") == "true"
            val parsed = runCatching { requireNotNull(WhaleDocumentImport.parse(app,wav,"wav","audio/wav")) }
            if (baseline) {
                assertEquals("没有识别到语音", parsed.exceptionOrNull()?.message)
            } else {
                val content = parsed.getOrThrow()
                assertEquals("extracted_text",content.coverage)
                assertEquals("audio_transcript",content.mediaKind)
                assertTrue(content.text,Regex("""\[10:0[5-9]\]""").containsMatchIn(content.text))
                assertTrue(content.text,content.text.contains("开会") && content.text.contains("笔记本"))
                val ctx = object : ContextWrapper(app) {
                    override fun getApplicationContext(): Context = this
                    override fun getNoBackupFilesDir() = root
                    override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences((root.name + name).also { prefNames.add(it) },mode)
                }
                UnifiedKnowledgeDatabase(ctx) { JSONArray() }.use { db ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    wav.inputStream().use { input ->
                        val bytes = ByteArray(65536)
                        while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes,0,n) }
                    }
                    val savedHash = digest.digest().joinToString("") { "%02x".format(it) }
                    KnowledgeObjects(ctx).store(wav,savedHash,64L*1024*1024)
                    val savedId = UUID.nameUUIDFromBytes("original:$savedHash".toByteArray()).toString()
                    db.appendRecordIfAbsent(JSONObject().put("id",savedId).put("source","original").put("originalId",savedHash)
                        .put("text","原件").put("createdAt",1L).put("coverage","not_parsed").put("displayName","长录音.wav").put("mime","audio/wav"),
                        reserveParse={})
                    WhaleParseJobs.process(ctx,requireNotNull(db.claimParse()),java.util.concurrent.atomic.AtomicBoolean(false))
                    val saved = requireNotNull(db.findById(savedId))
                    assertEquals("complete",saved.getString("parseState")); assertEquals("extracted_text",saved.getString("coverage"))
                    val hits = db.searchPage("笔记本",0,10)
                    assertEquals(1,hits.first.length())
                    assertTrue(hits.first.getJSONObject(0).getString("text").contains("[10:"))

                    // Upgrade an original which already has an indexed ten-minute excerpt.
                    val hash = "b".repeat(64)
                    val anchorId = UUID.nameUUIDFromBytes("original:$hash".toByteArray()).toString()
                    val old = "[00:03] 已保留的旧笔记\n[仅提取前 10 分钟语音]"
                    db.appendRecordIfAbsent(JSONObject().put("id",anchorId).put("source","original").put("originalId",hash)
                        .put("text","原件").put("createdAt",1L).put("coverage","partial").put("parseState","complete").put("displayName","长录音.wav").put("mime","audio/wav"))
                    db.appendDocument(hash,listOf(old),"长录音.wav","partial",true,mediaKind="audio_transcript")
                    val previous = requireNotNull(db.findById(anchorId))
                    assertTrue(db.canContinueAudio(previous))
                    assertTrue(db.retryParse(anchorId,previous.toString()) {})
                    assertFalse(db.retryParse(anchorId,previous.toString()) {})
                    val ticket = requireNotNull(db.claimParse())
                    assertEquals(600,ticket.getInt("audioFromSeconds"))
                    val tail = requireNotNull(WhaleDocumentImport.parse(app,wav,"wav","audio/wav",ticket.getInt("audioFromSeconds")))
                    assertTrue(tail.text,tail.text.contains("笔记本") && tail.text.contains("[10:"))
                    assertEquals(1,db.finishParse(ticket.getString("parseTicket"),tail).length())
                    assertEquals(0,db.finishParse(ticket.getString("parseTicket"),tail).length())
                    val originalChunk = requireNotNull(db.findById(KnowledgeChunks.recordId(hash,0)))
                    assertEquals(old,originalChunk.getString("text"))
                    assertEquals(2,originalChunk.getInt("chunkCount"))
                    assertEquals(hash,requireNotNull(db.findById(KnowledgeChunks.recordId(hash,1))).getString("originalId"))
                    val completed = requireNotNull(db.findById(anchorId))
                    assertEquals("extracted_text",completed.getString("coverage"))
                    assertFalse(db.canContinueAudio(completed))
                    assertFalse(db.retryParse(anchorId,completed.toString()) {})
                    val silence = requireNotNull(WhaleDocumentImport.parse(app,wav,"wav","audio/wav",620))
                    assertTrue(silence.text.isBlank())
                }
            }
            val elapsed = SystemClock.elapsedRealtime()-start
            if (!baseline) {
                // A voiced opening, a long digital pause, and a voiced ending exercise ASR restart and offsets.
                java.io.RandomAccessFile(wav,"rw").use { output -> output.seek(44); output.write(clip,offset+8,speechBytes) }
                val both = WhaleAudioTranscript.recognize(app,wav)
                assertTrue(both,both.lineSequence().any { it.startsWith("[00:") && it.contains("开会") })
                assertTrue(both,both.lineSequence().any { it.startsWith("[10:") && it.contains("开会") })
                var checks = 0
                assertThrows(IllegalStateException::class.java) { WhaleAudioTranscript.recognize(app,wav,stopped={ ++checks > 15 }) }
                assertEquals(16,checks)
            }
            instrumentation.sendStatus(0,Bundle().apply { putLong("fixtureValidationMs", elapsed); putString("lateSpeech", if(baseline) "missed" else parsed.getOrThrow().text) })
        } finally {
            prefNames.forEach { app.deleteSharedPreferences(it) }
            check(root.parentFile == app.cacheDir); root.walkBottomUp().forEach { it.delete() }
        }
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
