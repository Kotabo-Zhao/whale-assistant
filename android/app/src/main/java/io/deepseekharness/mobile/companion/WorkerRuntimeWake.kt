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
                            .put("writeSeq", 2L)
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
                runCatching { store.acknowledge(commandId) }
                runCatching {
                    val now = System.currentTimeMillis()
                    store.put(
                        JSONObject()
                            .put("id", commandId)
                            .put("title", workerTitle)
                            .put("input", task.take(2000))
                            .put("mode", mode)
                            .put("state", "running")
                            .put("summary", "原生后台 Worker 正在执行…")
                            .put("writeSeq", 1L)
                            .put("createdAt", now)
                            .put("updatedAt", now)
                            .put("owner", ownerSession),
                    )
                }
                launchNativeLongWorker(
                    app = app,
                    store = store,
                    commandId = commandId,
                    workerTitle = workerTitle,
                    task = task,
                    mode = mode,
                    ownerSession = ownerSession,
                    initialStage = 1,
                    initialDigest = "",
                    resumeCount = 0,
                    publishCallback = ::publishCallback,
                )
            }
            return receipt
        }

        /** 应用启动时扫描未完成的长任务检查点并自动断点续跑。 */
        fun resumeInterrupted(context: Context) {
            val app = context.applicationContext
            executor.execute {
                val resumable = runCatching {
                    WhaleLongTaskStore.listResumable(app, WhaleNativeWorkerPool.activeIds())
                }.getOrDefault(emptyList())
                if (resumable.isEmpty()) return@execute
                val store = WorkerDashboardStore(app)
                for (cp in resumable.take(3)) {
                    fun sha256Hex(raw: String): String =
                        java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) }
                    fun publishCallback(state: String, replyText: String) {
                        val finishedAt = System.currentTimeMillis()
                        val cleanSummary = replyText.trim().ifBlank { if (state == "done") "任务已执行完成。" else "任务执行失败。" }
                        runCatching {
                            store.put(
                                JSONObject()
                                    .put("id", cp.id).put("title", cp.title).put("input", cp.task.take(2000))
                                    .put("mode", cp.mode).put("state", state).put("summary", cleanSummary.take(4000))
                                    .put("writeSeq", 3L).put("createdAt", cp.updatedAt).put("updatedAt", finishedAt)
                                    .put("owner", cp.ownerSession),
                            )
                        }
                        runCatching {
                            val prefix = if (state == "done") "【断点续跑已完成】" else "【断点续跑失败】"
                            WorkerReportInbox(app).addCoordination(
                                JSONObject()
                                    .put("owner", cp.ownerSession)
                                    .put("fingerprint", sha256Hex("${cp.id}:$state:$finishedAt"))
                                    .put("summary", "$prefix${cp.title}\n\n${cleanSummary.take(4500)}")
                                    .put("next", "任务 ID：${cp.id}"),
                            )
                        }
                    }
                    launchNativeLongWorker(
                        app = app,
                        store = store,
                        commandId = cp.id,
                        workerTitle = cp.title,
                        task = cp.task,
                        mode = cp.mode,
                        ownerSession = cp.ownerSession,
                        initialStage = cp.stage,
                        initialDigest = cp.progressDigest,
                        resumeCount = cp.resumeCount + 1,
                        publishCallback = ::publishCallback,
                    )
                }
            }
        }

        private fun launchNativeLongWorker(
            app: Context,
            @Suppress("UNUSED_PARAMETER") store: WorkerDashboardStore,
            commandId: String,
            workerTitle: String,
            task: String,
            mode: String,
            ownerSession: String,
            initialStage: Int,
            initialDigest: String,
            resumeCount: Int,
            publishCallback: (String, String) -> Unit,
        ) {
            val maxStages = 3
            var cp = WhaleLongTaskStore.Checkpoint(
                id = commandId,
                title = workerTitle,
                task = task,
                mode = mode,
                ownerSession = ownerSession,
                stage = initialStage,
                maxStages = maxStages,
                progressDigest = initialDigest,
                resumeCount = resumeCount,
                state = "running",
                updatedAt = System.currentTimeMillis(),
            )
            WhaleLongTaskStore.save(app, cp)

            WhaleNativeWorkerPool.submit(workerTitle, id = commandId) {
                val pm = runCatching { app.getSystemService(android.os.PowerManager::class.java) }.getOrNull()
                val wakeLock = runCatching {
                    pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "whale-assistant:worker-$commandId")?.apply {
                        setReferenceCounted(false)
                        acquire(15 * 60 * 1000L)
                    }
                }.getOrNull()
                try {
                    val engine = WhaleNativeAgentEngine(app)
                    var finalReply = ""
                    for (stage in initialStage..maxStages) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
                        cp = cp.copy(stage = stage, updatedAt = System.currentTimeMillis())
                        WhaleLongTaskStore.save(app, cp)
                        val stagePrompt = WhaleLongTaskStore.buildStagePrompt(cp)
                        val res = engine.send(
                            userMessage = stagePrompt,
                            history = emptyList(),
                            sessionId = "whale-worker-$commandId-s$stage",
                            onProgress = { round, step, preview ->
                                val totalRound = (stage - 1) * 20 + round
                                val stageLabel = if (stage > 1) "阶段 $stage · $step" else step
                                WhaleNativeWorkerPool.updateProgress(commandId, round = totalRound, step = stageLabel, preview = preview)
                                if (round % 4 == 0 && preview.isNotBlank()) {
                                    cp = cp.copy(progressDigest = preview.take(1600), updatedAt = System.currentTimeMillis())
                                    WhaleLongTaskStore.save(app, cp)
                                }
                            },
                        )
                        finalReply = res.reply
                        if (!res.hitRoundLimit || stage >= maxStages || res.cancelled) break
                        val stepSummary = res.steps.takeLast(8).joinToString("；") { "${it.label}(${if (it.ok) "成功" else "失败"})" }
                        val digest = buildString {
                            if (cp.progressDigest.isNotBlank()) append(cp.progressDigest.take(900)).append("\n")
                            if (stepSummary.isNotBlank()) append("第 $stage 阶段步骤：$stepSummary\n")
                            if (res.reply.isNotBlank()) append("第 $stage 阶段小结：${res.reply.take(1200)}")
                        }
                        cp = cp.copy(stage = stage + 1, progressDigest = digest.take(2400), updatedAt = System.currentTimeMillis())
                        WhaleLongTaskStore.save(app, cp)
                    }
                    WhaleLongTaskStore.markTerminal(app, commandId, "done")
                    publishCallback("done", finalReply)
                    runCatching { io.deepseekharness.mobile.OverlayBallService.companionTaskHint() }
                    finalReply
                } catch (error: Throwable) {
                    val cancelled = error is InterruptedException
                    WhaleLongTaskStore.markTerminal(app, commandId, if (cancelled) "cancelled" else "failed")
                    val msg = error.message ?: error.javaClass.simpleName
                    publishCallback(if (cancelled) "cancelled" else "failed", msg)
                    throw error
                } finally {
                    runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
                }
            }
        }
    }
}
