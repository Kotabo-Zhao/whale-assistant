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
    private val activeTicket=ThreadLocal<Long>()
    private var generation = 0L
    private var revocation = 0L
    @Synchronized fun executionVersion():Long = generation
    @Synchronized fun admissionVersion():Long = revocation
    @Synchronized fun checkAdmission(value:Long) {
        if(halted || value!=revocation) throw RuntimeFailure("DEVICE_CONTROL_PAUSED","暂停前的排队动作已撤销，请重新观察")
    }
    @Volatile var current = Snapshot()
        private set
    private var cancel: (() -> Unit)? = null
    private val execution = java.util.concurrent.locks.ReentrantLock()
    private var halted = false
    fun refreshIndicator(context: Context) {
        OverlayBallService.refreshDeviceControl(context.applicationContext)
    }
    private var holder: Pair<String,Context>? = null
    private var holderRun = ""
    private val workerVersions = LinkedHashMap<String,Long>()
    @Synchronized fun worker(context: Context, row: org.json.JSONObject) {
        val id=row.optString("id"); val version=row.optLong("writeSeq")
        if(id.isEmpty() || version <= (workerVersions[id] ?: -1)) return
        workerVersions[id]=version
        if(workerVersions.size>256) workerVersions.remove(workerVersions.keys.first())
        if(row.optString("mode")=="device" && row.optString("state") in setOf("starting","running","cancelling")) {
            refreshIndicator(context)
            holder=id to context.applicationContext
            holderRun=row.optJSONObject("health")?.optString("runId").orEmpty()
            if(!halted && current.phase=="idle") publish("waiting","后台设备任务")
        } else if(holder?.first==id) {
            holder=null; holderRun=""
            if(!halted && current.phase=="waiting") publish("idle")
        }
    }
    fun cancellation(action: (() -> Unit)?) {
        val revoked=synchronized(this) {
            val ticket=activeTicket.get()
            if(action!=null && (halted || ticket!=null && ticket!=generation)) true
            else {cancel=action;false}
        }
        if(revoked) {
            runCatching{action?.invoke()}
            throw RuntimeFailure("DEVICE_CONTROL_PAUSED","手机操作已暂停，未开始后续步骤")
        }
    }
    @Synchronized fun checkCurrent() {
        val ticket=activeTicket.get()
        if(halted || ticket!=null && ticket!=generation)
            throw RuntimeFailure("DEVICE_CONTROL_PAUSED","手机操作已暂停，未开始后续步骤")
    }
    fun notifyNativeStep(phase: String, action: String) {
        synchronized(this) {
            if (halted && phase != "idle") return
            publish(phase, action)
        }
    }
    private fun publish(phase: String, action: String = current.action) {
        current = Snapshot(phase, action, SystemClock.elapsedRealtime())
        listeners.forEach { it(current) }
    }
    @Synchronized fun stage(observing: Boolean) {
        checkCurrent()
        if (activeTicket.get() == null) return
        val phase=if(observing) "observing" else "operating"
        if(current.phase!=phase) publish(phase)
    }
    @Synchronized fun failed() { checkCurrent(); if(activeTicket.get()!=null) publish("failed") }
    fun subscribe(listener: (Snapshot) -> Unit) { listeners.add(listener) }
    fun unsubscribe(listener: (Snapshot) -> Unit) { listeners.remove(listener) }
    fun disconnected() {
        val action=synchronized(this) {
            holder=null;holderRun="";generation++;revocation++
            if(!halted && current.phase!="idle") publish("idle")
            cancel.also{cancel=null}
        }
        cancelExecutor.execute{runCatching{action?.invoke()}}
    }
    fun pause() {
        val action=synchronized(this) {halted=true;generation++;revocation++;publish("paused");cancel.also{cancel=null}}
        cancelExecutor.execute{runCatching{action?.invoke()}}
    }
    @Synchronized fun resume() { halted = false; generation++; revocation++; publish(if(holder==null) "idle" else "waiting") }
    fun holdWorker(id: String, runId: String) {
        synchronized(this) { if(holder?.first==id && holderRun==runId) stop() }
    }
    fun stop() {
        val (action,task) = synchronized(this) { halted = true; generation++; revocation++; publish("stopped"); (cancel to holder).also { cancel = null } }
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
        if(activeTicket.get()!=null) {checkCurrent();return block().also{checkCurrent()}}
        val admission=synchronized(this){revocation}
        execution.lock()
        try {
        val ticket = synchronized(this) {
            if (halted || admission!=revocation)
                throw RuntimeFailure("DEVICE_CONTROL_PAUSED", "手机操作已由用户暂停，请点桌宠恢复接收新操作；旧步骤不会重放")
            generation++; cancel = cancelAction
            publish(if (observing) "observing" else "operating", name)
            generation
        }
        activeTicket.set(ticket)
        refreshIndicator(context)
        val started = SystemClock.elapsedRealtime()
        try {
            return block().also { result ->
                checkCurrent()
                synchronized(this) {
                    if (ticket == generation) {
                        cancel = null
                        publish(if (result.ok && current.phase!="failed") "waiting" else "failed")
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
        } finally { activeTicket.remove();execution.unlock() }
    }
}
