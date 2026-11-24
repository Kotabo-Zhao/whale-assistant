package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WhaleSuperagentRelayTest {

    @Before
    fun setUp() {
        WhaleSuperagentRelay.resetHandbackCacheForTest()
    }

    @Test
    fun `silent notices buffer cleanly, deduplicate consecutive entries, and drain once`() {
        WhaleSuperagentRelay.recordNotice("s1", "任务A已自动回收")
        WhaleSuperagentRelay.recordNotice("s1", "任务A已自动回收")
        WhaleSuperagentRelay.recordNotice("s1", "自治模式已切换为均衡")

        val drained = WhaleSuperagentRelay.drainNotices("s1")
        assertEquals(2, drained.size)
        assertTrue(WhaleSuperagentRelay.drainNotices("s1").isEmpty())

        val formatted = WhaleSuperagentRelay.formatNoticesForPrompt(drained)
        assertTrue(formatted.contains("任务A已自动回收"))
        assertTrue(formatted.contains("无需专门回复"))
    }

    @Test
    fun `orphaned unread followups are collected without duplicates`() {
        val orphaned = WhaleSuperagentRelay.collectOrphanedFollowups(
            listOf("in-1" to "加上价格对比", "in-2" to "加上价格对比", "in-3" to "输出 Markdown 表格"),
        )
        assertTrue(orphaned.contains("加上价格对比"))
        assertTrue(orphaned.contains("输出 Markdown 表格"))
        assertEquals(2, orphaned.lines().size)
    }

    @Test
    fun `handback anti-loop guard allows each input to be handed back at most once`() {
        assertTrue(WhaleSuperagentRelay.tryMarkHandedBack("w-1", "北京现在几点？"))
        assertFalse(WhaleSuperagentRelay.tryMarkHandedBack("w-1", "北京现在几点？"))
        assertTrue(WhaleSuperagentRelay.tryMarkHandedBack("w-1", "另外帮我翻译一句"))
    }

    @Test
    fun `interrupted recovery prompt preserves original mandate and unread followups`() {
        val worker = WhaleSuperagentRelay.PersistedWorker(
            workerId = "w-99",
            title = "竞品调研",
            prompt = "整理 2026 安卓 AI 助手对比表",
            startedAtMs = 1_000L,
        )
        val prompt = WhaleSuperagentRelay.buildRecoveryPrompt(worker, "顺便加上内存占用")
        assertTrue(prompt.contains("中断恢复提示"))
        assertTrue(prompt.contains("整理 2026 安卓 AI 助手对比表"))
        assertTrue(prompt.contains("顺便加上内存占用"))
    }
}
