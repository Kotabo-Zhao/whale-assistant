package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WhaleNativeWorkerPoolTest {

    @Test
    fun submitWithUnifiedIdTracksLiveProgressAndCompletion() {
        val id = UUID.randomUUID().toString()
        val started = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)

        val returnedId = WhaleNativeWorkerPool.submit(title = "测试后台整理任务", id = id) {
            started.countDown()
            WhaleNativeWorkerPool.updateProgress(id, round = 2, step = "正在执行工具：whale_knowledge_save", preview = "已整理第 2 节内容")
            allowFinish.await(5, TimeUnit.SECONDS)
            "最终完成摘要：4 节全部写入资料库"
        }

        assertEquals(id, returnedId)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        Thread.sleep(60)

        val runningSnap = WhaleNativeWorkerPool.get(id)
        assertNotNull(runningSnap)
        assertEquals("running", runningSnap!!.getString("state"))
        assertEquals(2, runningSnap.getInt("round"))
        assertTrue(runningSnap.getString("currentStep").contains("whale_knowledge_save"))
        assertTrue(runningSnap.getString("livePreview").contains("第 2 节"))
        assertTrue(id in WhaleNativeWorkerPool.activeIds())

        allowFinish.countDown()
        var finished = false
        for (i in 0 until 40) {
            val s = WhaleNativeWorkerPool.get(id)
            if (s?.optString("state") == "completed") {
                finished = true
                assertTrue(s.getString("summary").contains("最终完成摘要"))
                assertEquals("已完成", s.getString("currentStep"))
                break
            }
            Thread.sleep(50)
        }
        assertTrue("Worker should reach completed state", finished)
        assertFalse(id in WhaleNativeWorkerPool.activeIds())
        assertTrue(WhaleNativeWorkerPool.recentSummaries(10).contains("测试后台整理任务"))
        assertTrue(WhaleNativeWorkerPool.remove(id))
    }

    @Test
    fun cancelMarksWorkerCancelledAndClearFinishedRemovesIt() {
        val id = UUID.randomUUID().toString()
        val started = CountDownLatch(1)
        val block = CountDownLatch(1)

        WhaleNativeWorkerPool.submit(title = "可取消的长任务", id = id) {
            started.countDown()
            block.await(10, TimeUnit.SECONDS)
            "should-not-finish"
        }

        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(WhaleNativeWorkerPool.cancel(id))
        val cancelled = WhaleNativeWorkerPool.get(id)
        assertNotNull(cancelled)
        assertEquals("cancelled", cancelled!!.getString("state"))
        assertEquals("已取消", cancelled.getString("currentStep"))
        assertFalse(id in WhaleNativeWorkerPool.activeIds())

        val cleared = WhaleNativeWorkerPool.clearFinished()
        assertTrue(cleared >= 1)
    }
}
