package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleExecutionRouterTest {

    private fun worker(id: String, title: String, step: String = "检索资料") =
        WhaleExecutionRouter.WorkerSnapshot(
            id = id,
            title = title,
            state = "running",
            stageLabel = "执行中",
            currentStep = step,
            elapsedSec = 24L,
            livePreview = "已收集 3 份数据",
        )

    @Test
    fun `explicit background request routes to SPAWN_WORKER`() {
        val d = WhaleExecutionRouter.route(
            userMessage = "在后台帮我调研 2026 安卓 AI 手机竞品并整理表格",
            activeWorkers = emptyList(),
        )
        assertEquals(WhaleExecutionRouter.Mode.SPAWN_WORKER, d.mode)
        assertTrue(d.payload.contains("2026 安卓 AI 手机竞品"))
        assertTrue(d.workerTitle.isNotBlank())
    }

    @Test
    fun `status query hits single active worker directly`() {
        val w = worker("w-1001", "竞品调研报告")
        val d = WhaleExecutionRouter.route(
            userMessage = "刚才那个后台任务怎么样了？",
            activeWorkers = listOf(w),
        )
        assertEquals(WhaleExecutionRouter.Mode.WORKER_STATUS, d.mode)
        assertEquals("w-1001", d.targetWorker?.id)
    }

    @Test
    fun `cancel with multiple active workers asks for disambiguation instead of killing blindly`() {
        val w1 = worker("w-1001", "竞品调研报告")
        val w2 = worker("w-1002", "整理会议纪要")
        val d = WhaleExecutionRouter.route(
            userMessage = "把后台任务停掉吧",
            activeWorkers = listOf(w1, w2),
        )
        assertEquals(WhaleExecutionRouter.Mode.WORKER_CANCEL, d.mode)
        assertNull(d.targetWorker)
        assertEquals(2, d.ambiguousWorkers.size)
    }

    @Test
    fun `cancel naming specific title targets exact worker`() {
        val w1 = worker("w-1001", "竞品调研报告")
        val w2 = worker("w-1002", "整理会议纪要")
        val d = WhaleExecutionRouter.route(
            userMessage = "取消竞品调研报告这个后台任务",
            activeWorkers = listOf(w1, w2),
        )
        assertEquals(WhaleExecutionRouter.Mode.WORKER_CANCEL, d.mode)
        assertEquals("w-1001", d.targetWorker?.id)
    }

    @Test
    fun `followup steering routes to matched active worker with cleaned payload`() {
        val w1 = worker("w-1001", "竞品调研报告")
        val d = WhaleExecutionRouter.route(
            userMessage = "给后台任务补充一下：加上续航和价格对比",
            activeWorkers = listOf(w1),
        )
        assertEquals(WhaleExecutionRouter.Mode.WORKER_FOLLOWUP, d.mode)
        assertNotNull(d.targetWorker)
        assertEquals("w-1001", d.targetWorker?.id)
        assertTrue(d.payload.contains("加上续航和价格对比"))
    }

    @Test
    fun `clear finished workers routes to WORKER_CLEAR`() {
        val d = WhaleExecutionRouter.route(
            userMessage = "清理已完成的后台任务",
            activeWorkers = emptyList(),
        )
        assertEquals(WhaleExecutionRouter.Mode.WORKER_CLEAR, d.mode)
    }

    @Test
    fun `short greeting routes to DIRECT_CHAT and tool task routes to FOREGROUND_AGENT`() {
        val chat = WhaleExecutionRouter.route("你好呀，在吗", emptyList())
        assertEquals(WhaleExecutionRouter.Mode.DIRECT_CHAT, chat.mode)

        val agent = WhaleExecutionRouter.route("帮我写一个 Python 排序脚本并运行", emptyList())
        assertEquals(WhaleExecutionRouter.Mode.FOREGROUND_AGENT, agent.mode)
    }
}
