package io.deepseekharness.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.deepseekharness.mobile.runtime.RuntimeFailure
import io.deepseekharness.mobile.shizuku.DeviceCommandResult

/** Execution-owned state. UI actions revoke the current generation before the next device step. */
internal object DeviceControlState {
    data class Snapshot(val phase: String = "idle", val action: String = "", val since: Long = 0)
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(Snapshot) -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val cancelExecutor by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    private var generation = 0L
    @Volatile var current = Snapshot()
        private set
    private var cancel: (() -> Unit)? = null
    private val execution = java.util.concurrent.locks.ReentrantLock()
    private var halted = false
    private var indicator: io.deepseekharness.mobile.companion.DeviceControlOverlay? = null
    fun refreshIndicator(context: Context) {
        val app=context.applicationContext
        main.post {
            if(indicator==null) indicator=io.deepseekharness.mobile.companion.DeviceControlOverlay(app,app.getSystemService(android.view.WindowManager::class.java))
            indicator?.refresh()
        }
    }
    private var holder: Pair<String,Context>? = null
    private val workerVersions = LinkedHashMap<String,Long>()
    @Synchronized fun worker(context: Context, row: org.json.JSONObject) {
        val id=row.optString("id"); val version=row.optLong("writeSeq")
        if(id.isEmpty() || version <= (workerVersions[id] ?: -1)) return
        workerVersions[id]=version
        if(workerVersions.size>256) workerVersions.remove(workerVersions.keys.first())
        if(row.optString("mode")=="device" && row.optString("state") in setOf("starting","running","cancelling")) {
            refreshIndicator(context)
            holder=id to context.applicationContext
            if(!halted && current.phase=="idle") publish("waiting","后台设备任务")
        } else if(holder?.first==id) {
            holder=null
            if(!halted && current.phase=="waiting") publish("idle")
        }
    }
    @Synchronized fun cancellation(action: (() -> Unit)?) { cancel = action }
    private fun publish(phase: String, action: String = current.action) {
        current = Snapshot(phase, action, SystemClock.elapsedRealtime())
        listeners.forEach { it(current) }
    }
    fun subscribe(listener: (Snapshot) -> Unit) { listeners.add(listener) }
    fun unsubscribe(listener: (Snapshot) -> Unit) { listeners.remove(listener) }
    @Synchronized fun disconnected() {
        holder=null; cancel=null
        if(!halted && current.phase!="idle") { generation++; publish("idle") }
    }
    @Synchronized fun pause() { halted = true; generation++; publish("paused") }
    @Synchronized fun resume() { halted = false; generation++; publish(if(holder==null) "idle" else "waiting") }
    fun stop() {
        val (action,task) = synchronized(this) { halted = true; generation++; publish("stopped"); (cancel to holder).also { cancel = null } }
        cancelExecutor.execute {
            runCatching { action?.invoke() }
            runCatching { io.deepseekharness.mobile.accessibility.DeepSeekAccessibilityService.current()?.cancelAutomationTask() }
            task?.let { (id,context) -> runCatching {
                io.deepseekharness.mobile.companion.WorkerDashboardStore(context).command(org.json.JSONObject().put("id",id).put("action","cancel"))
            } }
        }
    }

    fun execute(context: Context, observing: Boolean, name: String, cancelAction: (() -> Unit)? = null,
                block: () -> DeviceCommandResult): DeviceCommandResult {
        refreshIndicator(context)
        execution.lock()
        try {
        val ticket = synchronized(this) {
            if (halted)
                throw RuntimeFailure("DEVICE_CONTROL_PAUSED", "手机操作已由用户暂停，请在桌宠状态条中继续")
            generation++; cancel = cancelAction
            publish(if (observing) "observing" else "operating", name)
            generation
        }
        val started = SystemClock.elapsedRealtime()
        try {
            return block().also { result ->
                synchronized(this) {
                    if (ticket == generation) {
                        cancel = null
                        publish(if (result.ok) "waiting" else "failed")
                    }
                }
            }
        } catch (error: Throwable) {
            synchronized(this) { if (generation == ticket) { cancel = null; publish("failed") } }
            throw error
        } finally {
            io.deepseekharness.mobile.runtime.DeviceObservationTiming.record(context,name,SystemClock.elapsedRealtime()-started)
            main.postDelayed({
                synchronized(this) {
                    if (generation == ticket && holder==null && current.phase in setOf("waiting","failed")) publish("idle")
                }
            }, if (observing) 3000 else 30_000)
        }
        } finally { execution.unlock() }
    }
}
