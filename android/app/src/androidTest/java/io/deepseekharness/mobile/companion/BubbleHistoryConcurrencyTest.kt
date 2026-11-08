package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BubbleHistoryConcurrencyTest {
    @Test fun concurrentExportsAndWritesKeepWholeSnapshotsAndPrivatePendingInput() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir,"bubble-history-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val ctx = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = root
        }
        val pool = Executors.newFixedThreadPool(3)
        fun snapshot(serial: String) = CompanionBubbleHistory.Snapshot("pet-$serial","a".repeat(64),
            listOf(CompanionBubbleChat.Message("user",serial),CompanionBubbleChat.Message("assistant",serial.repeat(1024))),
            org.json.JSONObject().put("kind","direct").put("requestId",serial).put("prompt","私有待处理文本").put("user",serial).toString())
        try {
            val first = CompanionBubbleHistory(ctx)
            val second = CompanionBubbleHistory(ctx)
            val reader = CompanionBubbleHistory(ctx)
            first.save(snapshot("initial"),requireWritable=true)
            val start = CountDownLatch(1)
            val futures = listOf(first,second).mapIndexed { index,store -> pool.submit {
                start.await()
                repeat(24) { store.save(snapshot("$index-$it"),requireWritable=true) }
            } }
            val exports = pool.submit {
                start.await()
                repeat(48) {
                    val live = requireNotNull(reader.load())
                    assertEquals(live.messages[0].text,org.json.JSONObject(live.pending!!).getString("requestId"))
                    val archive = CompanionBubbleHistory.decode(requireNotNull(reader.exportArchive()))
                    assertNull(archive.pending)
                    assertEquals(archive.messages[0].text.repeat(1024),archive.messages[1].text)
                }
            }
            start.countDown()
            (futures + exports).forEach { it.get(30,TimeUnit.SECONDS) }
            reader.archiveAndClear()
            assertNull(first.load())
            val archive = root.listFiles()!!.single { it.name.startsWith("companion-bubble-history-") }
            assertTrue(second.restoreArchive(archive.readBytes()))
            assertNull(first.load()!!.pending)
            val file = File(root,"companion-bubble-history-v1.json")
            file.writeText("损坏的测试记录")
            val fresh = CompanionBubbleHistory(ctx)
            assertThrows(IllegalStateException::class.java) { fresh.save(snapshot("new"),requireWritable=true) }
            assertThrows(IllegalStateException::class.java) { fresh.exportArchive() }
            assertEquals("损坏的测试记录",file.readText())
            file.writeBytes(archive.readBytes())
            assertNotNull(fresh.load())
            fresh.save(snapshot("recovered"),requireWritable=true)
            assertEquals("pet-recovered",first.load()?.sessionId)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(30,TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }
}
