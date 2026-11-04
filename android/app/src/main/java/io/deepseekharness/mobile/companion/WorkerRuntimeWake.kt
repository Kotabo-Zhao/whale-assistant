package io.deepseekharness.mobile.companion

import android.content.Context
import io.deepseekharness.mobile.runtime.RuntimeEventSink
import io.deepseekharness.mobile.runtime.RuntimeHost
import io.deepseekharness.mobile.runtime.RuntimeStateSnapshot
import java.util.concurrent.Executors

/** The overlay retains its runtime subscription until the service closes. */
internal class WorkerRuntimeWake(private val context: Context) : RuntimeEventSink {
    private var closed = false
    private var subscribed = false
    @Synchronized fun wake() {
        if (!closed) {
            val controller = RuntimeHost.acquire(context, this)
            subscribed = true
            start(controller)
        }
    }
    @Synchronized fun close() { closed = true; if (subscribed) RuntimeHost.detachPluginSink(this) }
    override fun onProgress(snapshot: RuntimeStateSnapshot) = Unit
    override fun onTerminalOutput(sessionId: String, dataBase64: String, suppressPublicOutput: Boolean) = Unit
    override fun onTerminalExit(sessionId: String, exitCode: Int) = Unit

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        private fun start(controller: io.deepseekharness.mobile.runtime.MobileRuntimeController) {
            if (runCatching { controller.openHarnessAccess() }.isFailure) {
                RuntimeHost.ensureDeviceBridge(controller)
                RuntimeHost.startHarness(controller)
            }
        }
        fun submit(context: Context, task: String, mode: String, overlay: WorkerRuntimeWake? = null, commandId: String = java.util.UUID.randomUUID().toString(), title: String? = null): org.json.JSONObject {
            val store = WorkerDashboardStore(context)
            // Desktop and app submissions share the more restrictive pet memory permission.
            BubbleSessionRegistry(context).record("session-whale-task-desk")
            val receipt = store.submit(task, mode, commandId, title)
            executor.execute {
                runCatching {
                    if (overlay != null) overlay.wake() else start(checkNotNull(RuntimeHost.controllerOrNull()))
                }.onFailure { store.dispatchUnavailable(receipt.getString("commandId")) }
            }
            return receipt
        }
    }
}
