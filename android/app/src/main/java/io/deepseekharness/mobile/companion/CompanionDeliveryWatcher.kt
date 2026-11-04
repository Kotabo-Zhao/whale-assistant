package io.deepseekharness.mobile.companion

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 盯住一个桌宠 Harness 会话，把「用户那一轮结束之后」由后台投递触发的新回合交给气泡展示。
 *
 * 背景（详见 `CompanionBubbleChat.deliveriesSince` 的注释）：桌宠气泡的流折叠只认自己这一轮的
 * `requestId`，而 Worker 回执 / 屏幕陪看唤醒是父会话里**新的一轮**用户消息
 * （`source.kind = mobile-workers|mobile-screen-watch`），并且气泡的 WebSocket 在用户那轮
 * `turn/end` 时就已关闭 —— 所以结果虽然写进了会话，却永远不会出现在桌宠里。
 *
 * 这个监察器只做三件事：按固定间隔做一次**只读**快照、把新回合文本交给回调、没有未完成任务就收工。
 * 边界：单实例单线程；最多跑 [MAX_DURATION_MS]；会话被换掉、气泡销毁、连续两轮无新内容即停；
 * 不修改会话、不发 prompt、不持久化正文（持久化在 [CompanionDeliveryInbox]，只存摘要）。
 */
internal class CompanionDeliveryWatcher(
    private val context: Context,
    private val chat: CompanionBubbleChat,
    private val sessionId: String,
    startTurn: Int,
    private val onDelivery: (Delivery) -> Unit,
    private val onDone: () -> Unit = {},
) {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "companion-delivery").apply { isDaemon = true }
    }
    private val stopped = AtomicBoolean(false)
    private var lastTurn = startTurn

    fun start() {
        executor.execute { loop() }
    }

    fun stop() {
        stopped.set(true)
        executor.shutdownNow()
    }

    private fun loop() {
        val startedAt = System.currentTimeMillis()
        var quietPolls = 0
        try {
            while (!stopped.get() && System.currentTimeMillis() - startedAt < MAX_DURATION_MS) {
                if (!sleep(INTERVAL_MS) || stopped.get()) return
                // 用户换了会话或开了新话题：旧会话的回执不再属于当前气泡。
                if (chat.currentSessionId() != sessionId) return
                val found = runCatching { chat.deliveriesSince(lastTurn) }
                    .onFailure { android.util.Log.w("dsh-runtime", "delivery poll failed: ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrElse { emptyList() }
                if (found.isNotEmpty()) android.util.Log.d("dsh-runtime", "delivery poll: +${found.size} turn(s), last=$lastTurn")
                for (delivery in found) {
                    if (stopped.get()) return
                    lastTurn = maxOf(lastTurn, delivery.turn)
                    onDelivery(delivery)
                }
                quietPolls = if (found.isEmpty()) quietPolls + 1 else 0
                // 连续两轮没有新回合、且这个会话已经没有未完成任务 → 收工，绝不常转。
                if (quietPolls >= 2 && !hasActiveWorkers()) return
            }
        } finally {
            stopped.set(true)
            runCatching { onDone() }
            executor.shutdown()
        }
    }

    private fun sleep(millis: Long): Boolean = try {
        Thread.sleep(millis); true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt(); false
    }

    private fun hasActiveWorkers(): Boolean = runCatching {
        WorkerDashboardStore(context).activeFor(sessionId)
    }.getOrDefault(false)

    private companion object {
        /** 8 秒：回执到达体的延迟上限，同时保证每天最多 10,800 次只读快照（且只在有任务未完成时）。 */
        const val INTERVAL_MS = 8_000L
        const val MAX_DURATION_MS = 30L * 60 * 1000
        /** 与 guest `workers.js` 的行状态对齐：这些状态表示任务还没结束。 */
        val ACTIVE_STATES = setOf("queued", "starting", "running", "waiting", "blocked")
    }
}
