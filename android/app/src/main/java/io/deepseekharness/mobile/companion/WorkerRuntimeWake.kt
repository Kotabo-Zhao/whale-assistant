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
                // 100% 纯原生 Lite 模式：直接通过原生 Kotlin Worker 并发池执行，不依赖 PRoot/Harness
                val workerTitle = title?.takeIf(String::isNotBlank) ?: task.trim().take(42)
                WhaleNativeWorkerPool.submit(workerTitle) {
                    val engine = WhaleNativeAgentEngine(app)
                    val res = engine.send(
                        userMessage = task,
                        history = emptyList(),
                        sessionId = sourceSession?.takeIf(String::isNotBlank) ?: "whale-worker-$commandId",
                    )
                    val finishedAt = System.currentTimeMillis()
                    runCatching {
                        val row = JSONObject()
                            .put("id", commandId)
                            .put("title", workerTitle)
                            .put("input", task.take(2000))
                            .put("mode", mode)
                            .put("state", "done")
                            .put("summary", res.reply.take(2000))
                            .put("writeSeq", 1L)
                            .put("createdAt", finishedAt)
                            .put("updatedAt", finishedAt)
                            .put("owner", sourceSession ?: "session-whale-task-desk")
                        store.put(row)
                    }
                    runCatching {
                        WorkerReportInbox(app).addCoordination(
                            JSONObject()
                                .put("id", commandId)
                                .put("title", workerTitle)
                                .put("summary", res.reply.take(800))
                                .put("status", "completed")
                                .put("atMs", finishedAt),
                        )
                    }
                    res.reply
                }
            }
            return receipt
        }
    }
}
