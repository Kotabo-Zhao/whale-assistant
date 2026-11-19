package io.deepseekharness.mobile.companion

import android.content.Context
import io.deepseekharness.mobile.runtime.RuntimeEventSink
import io.deepseekharness.mobile.runtime.RuntimeHost
import io.deepseekharness.mobile.runtime.RuntimeStateSnapshot
import org.json.JSONObject
import java.util.concurrent.Executors

/** The overlay retains its runtime subscription until the service closes. */
internal class WorkerRuntimeWake(private val context: Context) : RuntimeEventSink {
    private var closed = false
    private var subscribed = false

    @Synchronized
    fun wake() {
        if (!closed) {
            val controller = RuntimeHost.acquire(context, this)
            subscribed = true
            start(controller)
        }
    }

    @Synchronized
    fun close() {
        closed = true
        if (subscribed) RuntimeHost.detachPluginSink(this)
    }

    override fun onProgress(snapshot: RuntimeStateSnapshot) = Unit
    override fun onTerminalOutput(sessionId: String, dataBase64: String, suppressPublicOutput: Boolean) = Unit
    override fun onTerminalExit(sessionId: String, exitCode: Int) = Unit

    companion object {
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "whale-worker-wake").apply { isDaemon = true }
        }

        private fun start(controller: io.deepseekharness.mobile.runtime.MobileRuntimeController) {
            if (runCatching { controller.openHarnessAccess() }.isFailure) {
                RuntimeHost.ensureDeviceBridge(controller)
                RuntimeHost.startHarness(controller)
            }
        }

        fun submit(
            context: Context,
            task: String,
            mode: String,
            overlay: WorkerRuntimeWake? = null,
            commandId: String = java.util.UUID.randomUUID().toString(),
            title: String? = null,
            needs: String = "none",
            sourceSession: String? = null,
        ): JSONObject {
            val app = context.applicationContext
            val store = WorkerDashboardStore(app)
            BubbleSessionRegistry(app).record("session-whale-task-desk")
            val receipt = store.submit(task, mode, commandId, title, needs, sourceSession)
            val hasRunningHarness = runCatching { RuntimeHost.controllerOrNull()?.openHarnessAccess() }.isSuccess
            if (hasRunningHarness) {
                executor.execute {
                    runCatching {
                        if (overlay != null) overlay.wake() else start(checkNotNull(RuntimeHost.controllerOrNull()))
                    }.onFailure { store.dispatchUnavailable(receipt.getString("commandId")) }
                }
            } else {
                // 100% 纯原生 Lite 模式：直接通过原生 Kotlin Worker 并发池执行，共享统一 commandId，并在完成/失败时完整回传给 Router 与收件箱
                val workerTitle = title?.takeIf(String::isNotBlank) ?: task.trim().take(42)
                val ownerSession = sourceSession?.takeIf { BubbleSessionRegistry.valid(it) } ?: "session-whale-task-desk"
                fun sha256Hex(raw: String): String {
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
                    return digest.joinToString("") { "%02x".format(it) }
                }
                fun publishCallback(state: String, replyText: String) {
                    val finishedAt = System.currentTimeMillis()
                    val cleanSummary = replyText.trim().ifBlank { if (state == "done") "任务已执行完成。" else "任务执行失败。" }
                    runCatching {
                        val row = JSONObject()
                            .put("id", commandId)
                            .put("title", workerTitle)
                            .put("input", task.take(2000))
                            .put("mode", mode)
                            .put("state", state)
                            .put("summary", cleanSummary.take(4000))
                            .put("writeSeq", 1L)
                            .put("createdAt", finishedAt)
                            .put("updatedAt", finishedAt)
                            .put("owner", ownerSession)
                        store.put(row)
                    }
                    runCatching {
                        val prefix = if (state == "done") "【后台 Worker 已完成】" else "【后台 Worker 执行失败】"
                        val coordSummary = "$prefix$workerTitle\n\n${cleanSummary.take(4500)}"
                        val fingerprint = sha256Hex("$commandId:$state:$finishedAt")
                        WorkerReportInbox(app).addCoordination(
                            JSONObject()
                                .put("owner", ownerSession)
                                .put("fingerprint", fingerprint)
                                .put("summary", coordSummary)
                                .put("next", "任务 ID：$commandId"),
                        )
                    }
                }
                WhaleNativeWorkerPool.submit(workerTitle, id = commandId) {
                    try {
                        val engine = WhaleNativeAgentEngine(app)
                        val res = engine.send(
                            userMessage = task,
                            history = emptyList(),
                            sessionId = "whale-worker-$commandId",
                        )
                        publishCallback("done", res.reply)
                        res.reply
                    } catch (error: Throwable) {
                        val msg = error.message ?: error.javaClass.simpleName
                        publishCallback("failed", msg)
                        throw error
                    }
                }
            }
            return receipt
        }
    }
}
