package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.RuntimeFailure
import io.deepseekharness.mobile.runtime.RuntimeScopedResource
import io.deepseekharness.mobile.shizuku.DeviceCommand
import io.deepseekharness.mobile.shizuku.DeviceFilePolicy
import io.deepseekharness.mobile.shizuku.DeviceCommandRunner
import io.deepseekharness.mobile.shizuku.ShizukuRuntime
import io.deepseekharness.mobile.accessibility.DeepSeekAccessibilityService
import io.deepseekharness.mobile.accessibility.AccessibilityAutomationStore
import io.deepseekharness.mobile.capability.DeviceCapabilityFacts
import io.deepseekharness.mobile.capability.DeviceCapabilityPolicy
import io.deepseekharness.mobile.companion.CompanionTaskModel
import io.deepseekharness.mobile.virtualscreen.VirtualScreenService
import io.deepseekharness.mobile.virtualscreen.VirtualScreenShellPolicy
import io.deepseekharness.mobile.runtime.TaskNotification
import io.deepseekharness.mobile.runtime.TurnCompletionPolicy
import org.json.JSONObject
import android.content.Context
import android.os.Build
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest

/**
 * 设备命令桥：把容器内 dsh 的工具调用转成宿主 Shizuku 执行。
 *
 * 容器内 agent 通过 dsh-device 的固定白名单命令调用；文件命令只允许投递区 inbox/outbox 的相对路径。
 * http://127.0.0.1:<动态端口>/device-command（容器与宿主共享 loopback）。
 * 桥按白名单命令执行：自动创建一次性设备 Shell 会话 -> DeviceCommandRunner -> 关闭。
 * 认证：Bearer token（App 生成并注入容器环境 DSH_DEVICE_BRIDGE_TOKEN）。
 *
 * 不经过 Shell 的内建命令：`automationPolicy`（自动化授权闸门）、`deviceCapabilities`（统一能力清单）与 `notify-turn-complete`
 * （访客侧「一轮任务已完成」的收单点，只触发固定文案的通知，见
 * `TurnCompletionPolicy`）。后者受严格参数校验与限流约束，限流命中静默忽略。
 *
 * 注意：Android 运行时没有 com.sun.net.httpserver，这里用 ServerSocket 实现
 * 极简 HTTP/1.1 服务（只支持单个 POST 端点 + 固定 Content-Length 请求体）。
 *
 * 生命周期：由 `RuntimeHost` 以进程级资源持有，与 Harness 运行时同生共死。
 * 保活生效时 Harness 进程仍在运行，Activity 重建不得重建或拆除本桥，
 * 否则 guest 注入的端口与 Bearer token 会立即失效。
 */
class DeviceBridgeServer(
    private val shizuku: ShizukuRuntime,
    private val runner: DeviceCommandRunner,
    private val token: String,
    port: Int = 0,
    private val shellEnabled: () -> Boolean = { false },
    private val appContext: Context? = null,
) : RuntimeScopedResource {
    private val serverChannel=java.nio.channels.ServerSocketChannel.open().apply {
        configureBlocking(false)
        bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),64)
    }
    private val server=serverChannel.socket()
    private val readiness=java.nio.channels.Selector.open()
    // Parse bounded HTTP requests separately; worker ledger writes never occupy screen-control threads.
    private val executor = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(16))
    private val controlExecutor = ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(8))
    private val mirrorExecutor = ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(32))
    private val running = AtomicBoolean(true)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    val localPort: Int get() = server.localPort
    private val screenJournalDelegate=lazy {
        val context=appContext ?: throw RuntimeFailure("SCREEN_UNAVAILABLE","屏幕服务不可用")
        io.deepseekharness.mobile.companion.ScreenExecutionJournal(java.io.File(context.noBackupFilesDir,"screen-execution-v1.db"))
    }
    private val screenJournal by screenJournalDelegate
    private data class ScreenAttempt(val id:String,var step:Int=0)
    private val screenAttempt=ThreadLocal<ScreenAttempt>()
    private fun journaledScreen(command:String,request:JSONObject,block:()->JSONObject):JSONObject {
        // Old clients remain usable: a single action is keyed by its one-use observation lease.
        val id=request.optString("operationId").ifEmpty{request.optString("observationId")}
        if(!screenJournal.begin(id,command,request)) return JSONObject().put("status","recorded")
            .put("operationId",id).put("doNotReplay",true).put("record",screenJournal.read(id))
            .put("nextStep","已有执行记录，未重新操作；需要当前画面时调用 mobile_screen_recover")
        screenAttempt.set(ScreenAttempt(id))
        try {
            val result=block()
            screenJournal.finish(id,result)
            val state=screenJournal.read(id)!!.getString("state")
            if(state in setOf("unknown","stopped")) DeviceControlState.failed()
            return result.put("operationId",id).put("journalState",state)
        } catch(error:Throwable) {
            runCatching { screenJournal.interrupted(id,(error as? RuntimeFailure)?.code ?: "SCREEN_EXECUTION_INTERRUPTED") }
            throw error
        } finally {screenAttempt.remove()}
    }
    private fun screenForeground():String {
        val service=DeepSeekAccessibilityService.current()
        if(service==null) {
            if(!shellEnabled()) throw RuntimeFailure("DEVICE_SHELL_DISABLED","请先开启 AI Shell")
            return shizuku.mainScreenForeground().also{DeviceControlState.checkCurrent()}
        }
        val result=service.execute("accessibilityTree","")
        if(!result.ok) throw RuntimeFailure(result.errorCode ?: "SCREEN_APP_UNAVAILABLE","无法核实前台应用")
        return JSONObject(result.text).getString("packageName")
    }

    fun start() {
        serverChannel.register(readiness,java.nio.channels.SelectionKey.OP_ACCEPT)
        val thread = Thread({ acceptLoop() }, "dsh-device-bridge")
        thread.isDaemon = true
        thread.start()
    }

    /**
     * 取得进程级 Application 上下文；拿不到时返回 null。
     *
     * 为什么用反射而不是构造参数：本类的构造点固定在 `RuntimeHost.ensureDeviceBridge`
     * 内，而只有 `notify-turn-complete` 这一条命令需要 Context（发通知与写审计）。
     * 为了它给全类加一个构造参数，会让所有既有调用方与顺序依赖一起动；而
     * `ActivityThread.currentApplication()` 是进程级单例，桥只可能在
     * `Application.onCreate()` 之后被创建，因此**实际可达的失败只有一种**：
     * 拿不到 Context，此时该命令返回 `TURN_NOTIFY_UNAVAILABLE` 并静默降级，
     * 绝不影响任何别的命令。若后续要接更正规的注入，把本方法换成构造参数即可，
     * 调用点只有下面那一处。
     */
    /**
     * 根通道开关（用户在应用设置里显式打开）。
     *
     * 读不到 Context 时按**关**处理：这与 [DeviceCapabilityFacts] 的默认值同一方向，
     * 也意味着「读不到设置」永远不会被当成「用户开着根通道」。
     */
    private fun rootEngineUsable(): Boolean = applicationContextOrNull()
        ?.let { io.deepseekharness.mobile.shizuku.RootEngineAccess.usable(it) }
        ?: false

    private fun applicationContextOrNull(): Context? = try {
        val current = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null)
        (current as? Context)?.applicationContext
    } catch (_: Throwable) {
        null
    }

    private val screenshotLock = Any()
    private val screenshotSession = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val screenshotCleanup = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private var screenshotEpoch = 0L

    private fun releaseScreenshot() {
        screenshotSession.getAndSet(null)?.let { runCatching { shizuku.close(it) } }
    }

    /** Called inside the operation generation; raw formats may fall back before any action is sent. */
    private data class ScreenLease(val id:String,val width:Int,val height:Int,val version:Long,val at:Long,
        val region:android.graphics.Rect?,val textFingerprint:String="")
    private var screenLease:ScreenLease?=null
    private val textRepetition=io.deepseekharness.mobile.companion.ScreenTextRepetition()
    private fun textFingerprint(value:JSONObject):String {
        val nodes=value.optJSONObject("tree")?.optJSONArray("nodes") ?: value.optJSONObject("image")?.optJSONArray("lines") ?: return ""
        val visible=(0 until nodes.length()).mapNotNull { i -> nodes.optJSONObject(i)?.let { node ->
            val bounds=node.optJSONArray("bounds")
            if(bounds!=null && bounds.optInt(1)<80) null else node.optString("text").takeIf{it.isNotBlank()}
        } }.joinToString("\n")
        return if(visible.isEmpty()) "" else MessageDigest.getInstance("SHA-256").digest(visible.toByteArray()).joinToString(""){"%02x".format(it)}
    }
    private fun realScreenSize(context:Context):android.graphics.Point =
        android.graphics.Point().also { context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealSize(it) }

    private fun observeScreen(context:Context,request:JSONObject):JSONObject {
        DeviceControlState.stage(true)
        screenLease=null
        if(request.has("ocr") && request.opt("ocr") !is Boolean) throw RuntimeFailure("SCREEN_INPUT_INVALID","OCR 参数无效")
        if(request.has("region") && request.has("ocr") && !request.getBoolean("ocr"))
            throw RuntimeFailure("SCREEN_INPUT_INVALID","指定区域时不能关闭 OCR")
        val started=android.os.SystemClock.elapsedRealtime()
        val tree=DeepSeekAccessibilityService.current()?.execute("accessibilityTree","")
        if(tree?.ok==false && tree.errorCode!="ACCESSIBILITY_WINDOW_UNAVAILABLE")
            throw RuntimeFailure(tree.errorCode ?: "SCREEN_UNAVAILABLE","当前原生观察未获放行")
        DeviceControlState.checkCurrent()
        val value=JSONObject().put("treeAvailable",tree?.ok==true).put("observedAtMs",System.currentTimeMillis())
        val treeJson=if(tree?.ok==true) JSONObject(tree.text) else null
        if(treeJson!=null) value.put("tree",treeJson) else value.put("treeReason",tree?.errorCode ?: "ACCESSIBILITY_UNAVAILABLE")
        val nodes=treeJson?.optJSONArray("nodes")
        val useful=nodes!=null && (0 until nodes.length()).any {i->
            val n=nodes.getJSONObject(i);n.optString("text").isNotBlank() || n.optString("contentDescription").isNotBlank()
        }
        val ocr=request.optBoolean("ocr",!useful || request.has("region"))
        value.put("method",if(ocr) "native_tree_and_local_ocr" else "native_tree")
        val image=if(ocr) {
            DeviceCommand.requireShellRoute(DeviceCommand.SCREENSHOT,false,shellEnabled())
            observeImage(request).also {value.put("image",it)}
        } else null
        DeviceControlState.checkCurrent()
        val size=if(image!=null) android.graphics.Point(image.getInt("width"),image.getInt("height")) else realScreenSize(context)
        val roi=if(!useful && image!=null && request.has("region")) image.getJSONArray("region").let {
            android.graphics.Rect(it.getInt(0),it.getInt(1),it.getInt(0)+it.getInt(2),it.getInt(1)+it.getInt(3))
        } else null
        if((useful || image!=null) && image?.optBoolean("visualStable",true)!=false) {
            val lease=ScreenLease(java.util.UUID.randomUUID().toString(),size.x,size.y,
                DeviceControlState.executionVersion(),started,roi,textFingerprint(value))
            screenLease=lease
            value.put("observationId",lease.id).put("validForMs",15_000)
        } else if(image?.optBoolean("visualStable",true)==false)
            value.put("nextStep","画面仍在变化，本帧不发放操作租约；请重新观察，不要沿用动画中的坐标")
        value.put("width",size.x).put("height",size.y)
            .put("observationMs",android.os.SystemClock.elapsedRealtime()-started)
        return value
    }

    private fun actAndObserve(context:Context,request:JSONObject,sequence:Boolean=false):JSONObject {
        val action=request.optString("action")
        if(action !in setOf("tap","swipe","back","text")) throw RuntimeFailure("SCREEN_INPUT_INVALID","动作只支持 tap、swipe、back、text")
        val text=if(action=="text") request.optString("text") else ""
        if(action=="text" && (request.opt("text") !is String || text.length !in 1..8000 || text.contains('\u0000') ||
            request.opt("replace") !is Boolean || !request.optString("packageName").matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))))
            throw RuntimeFailure("SCREEN_INPUT_INVALID","文本、替换方式或目标应用无效")
        val lease=screenLease
        screenLease=null // Consume before injection: uncertain outcomes can never replay this observation.
        if(lease==null || request.optString("observationId")!=lease.id ||
            lease.version+(if(sequence) 0 else 1)!=DeviceControlState.executionVersion() ||
            android.os.SystemClock.elapsedRealtime()-lease.at>15_000)
            throw RuntimeFailure("SCREEN_OBSERVATION_STALE","请重新观察后再操作")
        val actual=realScreenSize(context)
        if(actual.x!=lease.width || actual.y!=lease.height)
            throw RuntimeFailure("SCREEN_GEOMETRY_CHANGED","屏幕尺寸已变化，请重新观察")
        if(context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            throw RuntimeFailure("SCREEN_LOCKED","请先解锁手机")
        fun integer(key:String):Int {
            val v=request.opt(key)
            if(v !is Int && v !is Long) throw RuntimeFailure("SCREEN_INPUT_INVALID","坐标必须是整数")
            val n=(v as Number).toLong()
            if(n !in 0..65535) throw RuntimeFailure("SCREEN_INPUT_INVALID","坐标超出范围")
            return n.toInt()
        }
        var x=0;var y=0;var ex=0;var ey=0
        if(action=="tap" || action=="swipe") {
            x=integer("x");y=integer("y")
            ex=if(action=="swipe") integer("endX") else x;ey=if(action=="swipe") integer("endY") else y
            for(point in listOf(x to y,ex to ey))
                if(point.first>=lease.width || point.second>=lease.height || lease.region?.contains(point.first,point.second)==false)
                    throw RuntimeFailure("SCREEN_INPUT_INVALID","坐标不在本次观察范围内")
            if(io.deepseekharness.mobile.companion.PetControlProtection.intersects(x,y,ex,ey))
                throw RuntimeFailure("SCREEN_CONTROL_PROTECTED","不能操作桌宠或其控制菜单")
        }
        val duration=if(action=="swipe") integer("durationMs").also {
            if(it !in 80..1000) throw RuntimeFailure("SCREEN_INPUT_INVALID","滑动时长须为 80–1000 毫秒")
        } else 0
        DeviceControlState.stage(false)
        val service=DeepSeekAccessibilityService.current()
        if(service!=null && action=="swipe") throw RuntimeFailure("SCREEN_ACTION_UNSUPPORTED","当前无障碍通道请使用已授权的原生滑动工具")
        if(service!=null && action=="text" && !request.getBoolean("replace"))
            throw RuntimeFailure("SCREEN_ACTION_UNSUPPORTED","无障碍文本通道只支持替换已聚焦字段，请先观察完整文本")
        if(service==null && !shellEnabled()) throw RuntimeFailure("DEVICE_SHELL_DISABLED","请在 Shizuku 设置中开启 AI Shell")
        if(action=="text" && screenForeground()!=request.getString("packageName"))
            throw RuntimeFailure("SCREEN_APP_CHANGED","前台应用变化，未输入文本")
        val repeatKey=if(action=="text") MessageDigest.getInstance("SHA-256").digest(
            (request.getString("packageName")+":"+request.getBoolean("replace")+":"+text).toByteArray()).joinToString(""){"%02x".format(it)} else ""
        if(action=="text") textRepetition.check(repeatKey,lease.textFingerprint,android.os.SystemClock.elapsedRealtime())
        val attempt=screenAttempt.get()
        if(attempt!=null) screenJournal.dispatch(attempt.id,++attempt.step,action)
        DeviceControlState.checkCurrent()
        val receipt=if(service!=null) {
            // Preserve the existing foreground/allowlist/confirmation policy. A denial never falls through to shell.
            if(action=="swipe") throw RuntimeFailure("SCREEN_ACTION_UNSUPPORTED","当前无障碍通道请使用已授权的原生滑动工具")
            val result=service.execute(when(action){"back"->"accessibilityBack";"text"->"inputText";else->"tap"},
                when(action){"back"->"";"text"->text;else->"$x,$y"})
            if(!result.ok) throw RuntimeFailure(result.errorCode ?: "SCREEN_INPUT_UNCERTAIN","无障碍动作未确认，请重新观察")
            JSONObject().put("injected",true).put("channel","native_accessibility").put("action",action)
        } else {
            if(!shellEnabled()) throw RuntimeFailure("DEVICE_SHELL_DISABLED","请在 Shizuku 设置中开启 AI Shell")
            if(action=="text") {
                // Write only the requested content. Never read the user's existing clipboard in the background.
                val clip=android.content.ClipData.newPlainText("Whale 输入",text)
                clip.description.extras=android.os.PersistableBundle().apply {putBoolean("android.content.extra.IS_SENSITIVE",true)}
                context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
                if(screenForeground()!=request.getString("packageName"))
                    throw RuntimeFailure("SCREEN_APP_CHANGED","前台应用变化，未发送粘贴按键")
            }
            DeviceControlState.checkCurrent()
            shizuku.mainScreenAction(JSONObject().put("action",action).put("width",lease.width).put("height",lease.height)
                .put("x",x).put("y",y).put("endX",ex).put("endY",ey).put("durationMs",duration)
                .put("replace",request.optBoolean("replace"))).apply {
                    if(action=="text") put("clipboardReplaced",true).put("textLength",text.length)
                        .put("verification","仅确认粘贴按键已注入，需查看新观察核实文本；没有自动提交")
                }
        }
        if(attempt!=null) screenJournal.injected(attempt.id,attempt.step)
        // A bounded rendering opportunity, not a claim that the target application has finished.
        val value=JSONObject().put("action",receipt)
        try {
            val waitStarted=android.os.SystemClock.elapsedRealtime()
            repeat(if(sequence) 1 else 5) {DeviceControlState.checkCurrent();Thread.sleep(16)}
            value.put("settleMs",android.os.SystemClock.elapsedRealtime()-waitStarted)
            DeviceControlState.checkCurrent()
            val observationRequest=JSONObject().put("settle",true)
            if(request.has("ocr")) observationRequest.put("ocr",request.get("ocr"))
            if(request.has("region")) observationRequest.put("region",request.get("region"))
            val observation=observeScreen(context,observationRequest)
            if(action=="text") textRepetition.observed(repeatKey,textFingerprint(observation),android.os.SystemClock.elapsedRealtime())
            if(attempt!=null) screenJournal.observed(attempt.id,attempt.step,observation)
            value.put("observation",observation)
        } catch(error:Exception) {
            screenLease=null
            value.put("observationError",if(error is RuntimeFailure) error.code else "SCREEN_OBSERVATION_FAILED")
                .put("nextStep","动作已注入；先重新观察，不要直接重放")
        }
        return value
    }

    private val sequenceTickets=io.deepseekharness.mobile.companion.ScreenSequenceTickets {android.os.SystemClock.elapsedRealtime()}
    private fun screenSequence(context:Context,request:JSONObject):JSONObject {
        val operationId=request.optString("operationId")
        sequenceTickets.start(operationId)
        try {
        val port=object:io.deepseekharness.mobile.companion.ScreenSequence.Port {
            override fun check(){DeviceControlState.checkCurrent();sequenceTickets.check(operationId)}
            override fun now()=android.os.SystemClock.elapsedRealtime()
            override fun waitForFrame(){check();Thread.sleep(16);check()}
            override fun observe()=observeScreen(context,JSONObject())
            override fun refine(observation:JSONObject,text:String):JSONObject? {
                val region=io.deepseekharness.mobile.companion.ScreenSequence.refinementRegion(observation,text) ?: return null
                check()
                return observeScreen(context,JSONObject().put("ocr",true).put("region",region))
            }
            override fun foreground():String = screenForeground().also{check()}
            override fun tap(observation:JSONObject,x:Int,y:Int):JSONObject {
                check()
                return actAndObserve(context,JSONObject().put("observationId",observation.getString("observationId"))
                    .put("action","tap").put("x",x).put("y",y),true)
            }
        }
        val result=io.deepseekharness.mobile.companion.ScreenSequence.run(request,port)
        if(request.optBoolean("includeImage") && result.optString("status")=="completed" &&
            result.optJSONObject("observation")?.optBoolean("treeAvailable")!=true) {
            try {
                port.check()
                if(port.foreground()!=request.getString("packageName")) throw RuntimeFailure("SCREEN_APP_CHANGED","前台应用变化")
                val snapshot=captureScreenImage()
                port.check()
                if(port.foreground()!=request.getString("packageName")) throw RuntimeFailure("SCREEN_APP_CHANGED","前台应用变化")
                result.put("snapshot",snapshot)
                // The screenshot owns the newest lease; the previous text frame cannot be acted on.
                result.optJSONObject("observation")?.remove("observationId")
            } catch(error:Exception) {
                port.check();screenLease=null
                result.optJSONObject("observation")?.remove("observationId")
                result.put("snapshotError",if(error is RuntimeFailure) error.code else "SCREEN_CAPTURE_FAILED")
            }
        }
        return result
        } finally {sequenceTickets.finish(operationId)}
    }

    private fun observeImage(request:JSONObject):JSONObject {
        DeviceControlState.checkCurrent()
        val start=android.os.SystemClock.elapsedRealtime()
        val frame=try {shizuku.mainScreenPixels(request)} catch(error:Exception) {
            DeviceControlState.checkCurrent()
            if(error is RuntimeFailure && error.code!="SCREEN_RAW_UNSUPPORTED") throw error
            if(error !is RuntimeFailure && error !is java.io.IOException && error !is IllegalStateException) throw error
            null
        }
        if(frame!=null) {
            var current:io.deepseekharness.mobile.shizuku.MainScreenPixels=frame
            var frames=1
            var stable=false
            try {
                if(request.optBoolean("settle")) {
                    var previous=io.deepseekharness.mobile.companion.ScreenObservation.motionSample(current)
                    while(frames<4 && android.os.SystemClock.elapsedRealtime()-start<650) {
                        DeviceControlState.checkCurrent();Thread.sleep(16);DeviceControlState.checkCurrent()
                        val next=shizuku.mainScreenPixels(request)
                        val geometryMatches=current.width==next.width && current.height==next.height && current.region==next.region
                        current.close();current=next;frames++
                        val pixels=io.deepseekharness.mobile.companion.ScreenObservation.motionSample(current)
                        stable=geometryMatches && io.deepseekharness.mobile.companion.ScreenFrameStability.similar(previous,pixels)
                        if(stable) break
                        previous=pixels
                    }
                }
                val captureMs=android.os.SystemClock.elapsedRealtime()-start
                DeviceControlState.checkCurrent()
                return io.deepseekharness.mobile.companion.ScreenObservation.text(current).put("captureMs",captureMs).apply {
                    if(request.optBoolean("settle")) put("visualStable",stable).put("stabilizationFrames",frames).put("stabilizationMs",captureMs)
                }
            } finally {current.close()}
        }
        DeviceControlState.checkCurrent()
        val capturedAt=System.currentTimeMillis()
        val image=captureScreen()
        if(!image.ok) throw RuntimeFailure(image.errorCode ?: "SCREEN_UNAVAILABLE","无法读取当前截图")
        val captureMs=android.os.SystemClock.elapsedRealtime()-start
        DeviceControlState.checkCurrent()
        return io.deepseekharness.mobile.companion.ScreenObservation.text(image.text,request)
            .put("capturedAtMs",capturedAt).put("captureSource","legacy_png").put("captureMs",captureMs)
    }

    private fun captureScreenImage():JSONObject {
        screenLease=null
        val started=android.os.SystemClock.elapsedRealtime()
        val context=appContext ?: throw RuntimeFailure("SCREEN_UNAVAILABLE","屏幕服务不可用")
        DeviceCommand.requireShellRoute(DeviceCommand.SCREENSHOT,false,shellEnabled())
        DeviceControlState.checkCurrent()
        val frame=try {shizuku.mainScreenPixels(JSONObject())} catch(error:RuntimeFailure) {
            if(error.code!="SCREEN_RAW_UNSUPPORTED") throw error
            null
        } catch(_:java.io.IOException) {null}
        val value=if(frame!=null) frame.use {
            DeviceControlState.checkCurrent()
            val bytes=java.io.ByteArrayOutputStream()
            check(it.bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,82,bytes))
            JSONObject().put("imageBase64",android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP))
                .put("mediaType","image/jpeg").put("width",it.width).put("height",it.height).put("captureSource","shizuku_raw_jpeg")
                .put("capturedAtMs",it.capturedAtMs)
        } else {
            val legacy=captureScreen()
            if(!legacy.ok) throw RuntimeFailure(legacy.errorCode ?: "SCREEN_UNAVAILABLE","截图不可用")
            val encoded=legacy.text.replace(Regex("\\s"),"")
            val bytes=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT)
            val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            if(bounds.outWidth<=0 || bounds.outHeight<=0) throw RuntimeFailure("SCREEN_UNAVAILABLE","截图不可用")
            JSONObject().put("imageBase64",encoded).put("mediaType","image/png").put("width",bounds.outWidth).put("height",bounds.outHeight)
                .put("captureSource","legacy_png")
        }
        DeviceControlState.checkCurrent()
        val lease=ScreenLease(java.util.UUID.randomUUID().toString(),value.getInt("width"),value.getInt("height"),
            DeviceControlState.executionVersion(),started,null)
        screenLease=lease
        return value.put("observationId",lease.id).put("captureMs",android.os.SystemClock.elapsedRealtime()-started)
    }

    private fun captureScreen(): io.deepseekharness.mobile.shizuku.DeviceCommandResult = synchronized(screenshotLock) {
        val id = screenshotSession.get() ?: shizuku.create(DEFAULT_COLUMNS,DEFAULT_ROWS,suppressPublicOutput=true,
            permitted=running::get,onSessionExit={ session -> runner.onSessionExit(session); screenshotSession.compareAndSet(session,null) })
            .also { screenshotSession.set(it) }
        val epoch = ++screenshotEpoch
        DeviceControlState.cancellation { releaseScreenshot() }
        try {
            runner.execute(id,DeviceCommand.SCREENSHOT,"",COMMAND_TIMEOUT_MS).also { if(!it.ok) releaseScreenshot() }
        } finally {
            if (!screenshotCleanup.isShutdown) screenshotCleanup.schedule({
                synchronized(screenshotLock) { if (screenshotEpoch == epoch) releaseScreenshot() }
            },5,TimeUnit.SECONDS)
        }
    }

    override fun stop() {
        running.set(false)
        DeviceControlState.disconnected()
        screenshotCleanup.shutdownNow()
        releaseScreenshot()
        try {
            server.close()
            readiness.close()
        } catch (_: Throwable) {
        }
        activeSockets.forEach(::closeQuietly)
        listOf(executor,controlExecutor,mirrorExecutor).forEach{it.shutdownNow()}
        // 关掉与 server.close() 同时接受的 socket，再等一小段时间，确保运行时清理后
        // 不会有迟到的设备请求重新连上 Shizuku。
        activeSockets.forEach(::closeQuietly)
        try {
            listOf(executor,controlExecutor,mirrorExecutor).forEach{it.awaitTermination(STOP_WAIT_MILLIS, TimeUnit.MILLISECONDS)}
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if(controlExecutor.isTerminated && screenJournalDelegate.isInitialized()) screenJournal.close()
    }

    /** Idle HTTP preconnections wait in the selector, never in a parsing or control thread. */
    private fun acceptLoop() {
        val idle=LinkedHashMap<java.nio.channels.SocketChannel,Long>()
        while(running.get()) {
            try {
                if(idle.isEmpty()) readiness.select() else readiness.select(1000)
                val ready=ArrayList<Pair<java.nio.channels.SocketChannel,Int>>()
                val keys=readiness.selectedKeys().iterator()
                while(keys.hasNext()) {
                    val key=keys.next();keys.remove()
                    if(!key.isValid) continue
                    if(key.isAcceptable) {
                        for(accepted in 0 until 16) {
                            val channel=serverChannel.accept() ?: break
                            if(!running.get()) {channel.close();continue}
                            if(idle.size>=64 || activeSockets.size>=128) {
                                val oldest=idle.keys.firstOrNull()
                                if(oldest==null) {channel.close();continue}
                                idle.remove(oldest);activeSockets.remove(oldest.socket());oldest.close()
                            }
                            channel.configureBlocking(false)
                            activeSockets.add(channel.socket())
                            idle[channel]=android.os.SystemClock.elapsedRealtime()
                            channel.register(readiness,java.nio.channels.SelectionKey.OP_READ)
                        }
                    } else if(key.isReadable) {
                        val channel=key.channel() as java.nio.channels.SocketChannel
                        val first=java.nio.ByteBuffer.allocate(1)
                        val count=runCatching{channel.read(first)}.getOrDefault(-1)
                        if(count!=0) {
                            key.cancel();idle.remove(channel)
                            if(count==1) ready.add(channel to (first.array()[0].toInt() and 255))
                            else {activeSockets.remove(channel.socket());channel.close()}
                        }
                    }
                }
                val now=android.os.SystemClock.elapsedRealtime()
                val expired=idle.entries.iterator()
                while(expired.hasNext()) {
                    val (channel,at)=expired.next()
                    if(now-at>=5000) {expired.remove();activeSockets.remove(channel.socket());channel.close()}
                }
                if(ready.isNotEmpty()) {
                    // Process cancelled registrations before switching these channels to blocking HTTP reads.
                    readiness.selectNow()
                    for((channel,first) in ready) {
                        val socket=channel.socket()
                        try {
                            channel.configureBlocking(true)
                            executor.execute {handle(socket,first)}
                        } catch(_:Exception) {activeSockets.remove(socket);closeQuietly(socket)}
                    }
                }
            } catch(_:java.nio.channels.ClosedSelectorException) {break}
            catch(_:java.nio.channels.ClosedChannelException) {if(!running.get()) break}
            catch(_:Exception) {if(!running.get()) break}
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Throwable) {
        }
    }

    private fun handle(socket: Socket,firstByte:Int) {
        var transferred=false
        try {
            val s=socket
            run {
                s.soTimeout = 1500
                val input = BufferedInputStream(java.io.SequenceInputStream(java.io.ByteArrayInputStream(byteArrayOf(firstByte.toByte())),s.getInputStream()))
                val output = BufferedOutputStream(s.getOutputStream())
                try {
                    val requestLine = readLine(input) ?: return
                    val parts = requestLine.split(" ")
                    if (
                        parts.size != 3 || parts[0] != "POST" || parts[1] != "/device-command" ||
                        parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")
                    ) {
                        respond(output, 405, "{\"ok\":false,\"text\":\"\",\"errorCode\":\"METHOD_NOT_ALLOWED\"}")
                        return
                    }
                    var auth = ""
                    var authSeen = false
                    var contentLength = 0
                    var headerCount = 0
                    while (true) {
                        val line = readLine(input) ?: break
                        if (line.isEmpty()) break
                        if (++headerCount > 32) throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥请求头过多")
                        val idx = line.indexOf(':')
                        if (idx <= 0) throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥请求头格式无效")
                        val name = line.substring(0, idx).trim().lowercase()
                        val value = line.substring(idx + 1).trim()
                        when (name) {
                            "authorization" -> {
                                if (authSeen) throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥认证头重复")
                                authSeen = true
                                auth = value
                            }
                            "content-length" -> {
                                if (contentLength != 0) throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥请求长度重复")
                                contentLength = value.toIntOrNull()?.takeIf { it in 1..MAX_REQUEST_BODY_BYTES }
                                    ?: throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥请求长度无效")
                            }
                            "transfer-encoding" -> throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥不支持分块请求")
                        }
                    }
                    val supplied = auth.removePrefix("Bearer ").toByteArray(StandardCharsets.US_ASCII)
                    if (!auth.startsWith("Bearer ") || !MessageDigest.isEqual(supplied, token.toByteArray(StandardCharsets.US_ASCII))) {
                        respond(output, 401, "{\"ok\":false,\"text\":\"\",\"errorCode\":\"UNAUTHORIZED\"}")
                        return
                    }
                    s.soTimeout=10_000
                    val body = readBody(input, contentLength)
                    val parsed = JSONObject(body)
                    val commandName = parsed.opt("command") as? String
                        ?: throw RuntimeFailure("DEVICE_COMMAND_INVALID", "设备命令格式无效")
                    val param = parsed.opt("param") as? String
                        ?: throw RuntimeFailure("DEVICE_COMMAND_INVALID", "设备参数格式无效")
                    if (commandName.length > 32 || param.length > DeviceFilePolicy.MAX_PARAM_CHARS) {
                        throw RuntimeFailure("DEVICE_COMMAND_INVALID", "设备参数过长")
                    }
                    val admission=DeviceControlState.admissionVersion()
                    val target=if(commandName in setOf("workerRecord","workerRecordChunk")) mirrorExecutor else controlExecutor
                    try {
                        target.execute {
                            try {
                                if(!running.get()) return@execute
                                executeCommand(commandName,param,output,admission)
                            } finally {activeSockets.remove(socket);closeQuietly(socket)}
                        }
                        transferred=true
                    } catch(_:RejectedExecutionException) {
                        respondBusy(output)
                    }
                } catch (error: Throwable) {
                    val code = (error as? RuntimeFailure)?.code ?: "BRIDGE_FAILED"
                    respond(
                        output,
                        200,
                        JSONObject().put("ok", false).put("text", "").put("exitCode", 1)
                            .put("truncated", false).put("errorCode", code).toString(),
                    )
                }
            }
        } catch (_: Throwable) {
            // 连接异常：忽略
        } finally {if(!transferred){activeSockets.remove(socket);closeQuietly(socket)}}
    }

    private fun respondBusy(output:BufferedOutputStream) {
        respond(output,200,JSONObject().put("ok",false).put("text","").put("exitCode",1)
            .put("truncated",false).put("errorCode","DEVICE_BRIDGE_BUSY").toString())
    }

    private fun executeCommand(commandName:String,param:String,output:BufferedOutputStream,admission:Long) {
        try {
            if(commandName in setOf("screenSequence","screenAct","tap","inputText","accessibilityAction","accessibilityBack","shell","launchApp","appLaunch"))
                DeviceControlState.checkAdmission(admission)
            if (commandName == "pluginMarketUpdateProposal") {
                val context = appContext ?: throw RuntimeFailure("MARKET_PROPOSAL_UNAVAILABLE", "更新请求服务不可用")
                val proposal = JSONObject(param)
                val name = proposal.opt("name") as? String ?: throw RuntimeFailure("MARKET_PROPOSAL_INVALID", "插件包名无效")
                val version = if (!proposal.has("suggestedVersion") || proposal.isNull("suggestedVersion")) null else proposal.opt("suggestedVersion") as? String
                    ?: throw RuntimeFailure("MARKET_PROPOSAL_INVALID", "建议版本无效")
                io.deepseekharness.mobile.runtime.MarketProposalStore(context).propose(name, "update", version)
                respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true, 0, "pending-user-review", false, null))
                return
            }
            if (commandName == "pluginMarketProposal") {
                val context = appContext ?: throw RuntimeFailure("MARKET_PROPOSAL_UNAVAILABLE", "安装请求服务不可用")
                io.deepseekharness.mobile.runtime.MarketProposalStore(context).propose(param)
                respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true, 0, "pending-user-review", false, null))
                return
            }
            if (commandName in setOf("workerRecord", "workerRecordChunk", "workerUi", "superagentSettings", "superagentActors", "screenWatch", "screenWatchFrame", "featureAuthor", "featureWorkerAuthor")) {
                val context = appContext ?: throw RuntimeFailure("DEVICE_BRIDGE_UNAVAILABLE", "服务不可用")
                val request = JSONObject(param)
                val value = when (commandName) {
                    "featureWorkerAuthor" -> io.deepseekharness.mobile.companion.WorkerDashboardStore(context).author(request).toString()
                    "featureAuthor" -> {
                        val features = io.deepseekharness.mobile.features.WhaleFeatureStore(context)
                        when (request.getString("action")) {
                            "list" -> org.json.JSONArray(features.list()).toString()
                            "read" -> {
                                val row = requireNotNull(features.read(request.getString("id")))
                                val revision = row.getString("latest")
                                JSONObject(row.getJSONObject("versions").getJSONObject(revision).toString()).put("revision", revision).toString()
                            }
                            "draft" -> features.draft(request).toString()
                            else -> throw IllegalArgumentException("无效的创作操作")
                        }
                    }
                    "workerRecord" -> { io.deepseekharness.mobile.companion.WorkerDashboardStore(context).put(request); "{}" }
                    "workerRecordChunk" -> { io.deepseekharness.mobile.companion.WorkerDashboardStore(context).putChunk(request); "{}" }
                    "superagentSettings" -> io.deepseekharness.mobile.companion.SuperagentSettings(context).runtime().toString()
                    "superagentActors" -> { io.deepseekharness.mobile.companion.SuperagentSettings(context).setUnapproved(request); "{}" }
                    "workerUi" -> {
                        val store = io.deepseekharness.mobile.companion.WorkerDashboardStore(context)
                        when {
                            request.optBoolean("contextMatters") -> io.deepseekharness.mobile.companion.ContextFollowupEvents.pendingMatters(context).toString()
                            request.has("ackMatter") -> { io.deepseekharness.mobile.companion.ContextFollowupEvents.acknowledgeMatter(context,request.getString("ackMatter")); "[]" }
                            request.optBoolean("hostCapacity") -> {
                                val power = context.getSystemService(android.os.PowerManager::class.java)
                                val thermal = if (android.os.Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else 0
                                val memory = android.app.ActivityManager.MemoryInfo()
                                context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(memory)
                                JSONObject().put("thermal", thermal).put("conserve", power.isPowerSaveMode || memory.lowMemory).toString()
                            }
                            request.has("ack") -> { store.acknowledge(request.getString("ack"), request.optString("authorWorkerId").ifBlank { null }); "[]" }
                            request.has("deferred") -> { store.dispatchUnavailable(request.getString("deferred"), true); "[]" }
                            else -> {
                                val batch=store.batch(request.optInt("skip",0),request.optInt("sourceSessionProtocol",0) in 1..2,request.optInt("sourceSessionProtocol",0)==2)
                                if(request.optBoolean("includeMatters")) JSONObject().put("commands",batch)
                                    .put("matters",io.deepseekharness.mobile.companion.ContextFollowupEvents.pendingMatters(context)).toString()
                                else batch.toString()
                            }
                        }
                    }
                    "screenWatch" -> {
                        val watch = io.deepseekharness.mobile.companion.ScreenWatchAccess(context)
                        when (request.getString("action")) {
                            "start" -> watch.start(request.getString("session"))
                            "stop" -> { if (watch.status().getBoolean("active")) watch.requireOwner(request.getString("session")); watch.stop() }
                            "status" -> Unit
                            else -> throw IllegalArgumentException("unknown action")
                        }
                        watch.status().toString()
                    }
                    else -> {
                        val watch = io.deepseekharness.mobile.companion.ScreenWatchAccess(context)
                        val session = request.getString("session"); watch.requireOwner(session)
                        val screenshot = runOneShot(DeviceCommand.SCREENSHOT, "")
                        if (!screenshot.ok || screenshot.truncated) throw RuntimeFailure(screenshot.errorCode ?: "SCREENSHOT_FAILED", "截图失败")
                        watch.frame(session, screenshot.text)
                    }
                }
                respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true, 0, value, false, null))
                return
            }
            if (commandName == "companionNotifications") {
                if (param.length > 1024) throw RuntimeFailure("NOTIFICATIONS_INPUT_INVALID", "通知查询参数过长")
                val context = appContext ?: throw RuntimeFailure("NOTIFICATIONS_UNAVAILABLE", "通知服务不可用")
                val store = io.deepseekharness.mobile.companion.CompanionStore(context)
                val authorized = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                val result = io.deepseekharness.mobile.companion.CompanionNotificationModel.execute(
                    store.notificationModelAccess, param, System.currentTimeMillis(), store.notificationCapture, authorized,
                    io.deepseekharness.mobile.companion.CompanionNotificationService.isConnected(), store.excludedPackages()
                ) { store.read() }
                respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true, 0, result, false, null))
                return
            }
            if (commandName == "workerCoordination") {
                require(param.length <= 16000)
                val context = appContext ?: throw RuntimeFailure("WORKER_UNAVAILABLE", "助手服务不可用")
                io.deepseekharness.mobile.companion.WorkerReportInbox(context).addCoordination(JSONObject(param))
                respondResult(output,io.deepseekharness.mobile.shizuku.DeviceCommandResult(true,0,"{\"recorded\":true}",false,null))
                return
            }
            if (commandName == "companionRecall") {
                require(param.length <= 2048) { "关联召回参数过长" }
                val context = appContext ?: throw RuntimeFailure("COMPANION_SEARCH_UNAVAILABLE", "助手服务不可用")
                val request = JSONObject(param)
                val result = io.deepseekharness.mobile.companion.CompanionRecall(context,request.getString("callerSession"))
                    .recall(request.getString("query"))
                respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true,0,result.toString(),false,null))
                return
            }
            if (commandName == "companionKnowledgeWrite") {
                val context = appContext ?: throw RuntimeFailure("KNOWLEDGE_UNAVAILABLE", "资料服务不可用")
                val result = io.deepseekharness.mobile.companion.CompanionKnowledgeWrite.execute(context, param)
                respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true, 0, result.toString(), false, null))
                return
            }
            if (commandName == "companionKnowledge") {
                if (param.length > 1024) throw RuntimeFailure("KNOWLEDGE_INPUT_INVALID", "资料查询参数过长")
                val context = appContext ?: throw RuntimeFailure("KNOWLEDGE_UNAVAILABLE", "资料服务不可用")
                val store = io.deepseekharness.mobile.companion.CompanionStore(context)
                val knowledge = io.deepseekharness.mobile.companion.CompanionKnowledgeStore(context)
                val request = try { JSONObject(param) } catch (_: Exception) { throw RuntimeFailure("KNOWLEDGE_INPUT_INVALID", "资料查询参数无效") }
                val access = io.deepseekharness.mobile.companion.CompanionAgentAccess(context, request)
                access.requireKnowledge()
                if (request.optString("op") == "hybrid") {
                    val result = io.deepseekharness.mobile.companion.SemanticKnowledgeSearch.execute(context, param)
                    access.requireKnowledge()
                    respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true, 0, result, false, null))
                    return
                }
                if (request.optString("op") == "forget" || request.optString("op") == "undo") {
                    val id = (request.opt("id") as? String)?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
                        ?: throw RuntimeFailure("KNOWLEDGE_INPUT_INVALID", "资料标识无效")
                    val result = if (request.optString("op") == "undo") {
                        JSONObject().put("id", id).put("restored", knowledge.undoRemove(id))
                            .put("notice", "仅恢复资料记录；已删除的派生记忆不会自动恢复。")
                    } else {
                        val row = knowledge.findById(id) ?: throw RuntimeFailure("KNOWLEDGE_NOT_FOUND", "资料已删除或不可用")
                        val text = row.getString("text")
                        if (request.optString("revision") != io.deepseekharness.mobile.companion.CompanionMemorySuppression.digest(text))
                            throw RuntimeFailure("KNOWLEDGE_CHANGED", "资料已变化，请重新检索")
                        val earliestUndoUntil = System.currentTimeMillis() + 10 * 60_000L
                        if (!knowledge.remove(id, text)) throw RuntimeFailure("KNOWLEDGE_CHANGED", "资料已变化，请重新检索")
                        JSONObject().put("id", id).put("deleted", 1).put("undoUntilMs", earliestUndoUntil)
                            .put("originalRetained", true).put("otherRecordsRetained", true)
                            .put("notice", "已删除选中的资料记录及关联派生记忆；原件、其他分段和外部副本仍保留。")
                    }
                    respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true, 0, result.toString(), false, null))
                    return
                }
                if (request.optString("op") == "forgetOriginal") {
                    val id = (request.opt("id") as? String)?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
                        ?: throw RuntimeFailure("KNOWLEDGE_INPUT_INVALID", "资料标识无效")
                    val originalId = (request.opt("originalId") as? String)?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                        ?: throw RuntimeFailure("KNOWLEDGE_INPUT_INVALID", "原件标识无效")
                    val row = knowledge.findById(id) ?: throw RuntimeFailure("KNOWLEDGE_NOT_FOUND", "资料已删除或不可用")
                    val text = row.getString("text")
                    if (row.optString("originalId") != originalId || request.optString("revision") != io.deepseekharness.mobile.companion.CompanionMemorySuppression.digest(text))
                        throw RuntimeFailure("KNOWLEDGE_CHANGED", "资料或原件关联已变化，请重新检索")
                    val removal = knowledge.removeOriginal(originalId, id, text)
                    if (removal.records == 0) throw RuntimeFailure("KNOWLEDGE_CHANGED", "资料已变化，请重新检索")
                    val result = JSONObject().put("originalId", originalId).put("deletedRecords", removal.records).put("objectDeleted", removal.objectDeleted)
                        .put("undoAvailable", false).put("notice", if (removal.objectDeleted) "已删除这份原件及其所有本地资料分段，并抑制关联派生记忆；无法撤销，外部副本和已发送给模型的内容不受影响。" else "已删除这份原件的本地资料分段并抑制关联派生记忆；原件对象未删除或不可用，外部副本和已发送给模型的内容不受影响。")
                    respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true, 0, result.toString(), false, null))
                    return
                }
                if (request.optString("op") == "image") {
                    val id = (request.opt("id") as? String)?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
                        ?: throw RuntimeFailure("KNOWLEDGE_INPUT_INVALID", "资料标识无效")
                    val row = knowledge.findById(id) ?: throw RuntimeFailure("KNOWLEDGE_NOT_FOUND", "资料已删除或不可用")
                    val hash = io.deepseekharness.mobile.companion.CompanionMemorySuppression.digest(row.getString("text"))
                    if (request.optString("revision") != hash) throw RuntimeFailure("KNOWLEDGE_CHANGED", "资料已变化，请重新检索")
                    val originalId = row.optString("originalId").takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                        ?: throw RuntimeFailure("KNOWLEDGE_NOT_FOUND", "资料没有关联的原图片")
                    var image: String? = null
                    if (!knowledge.withUnchangedRecord(id, row.getString("text")) {
                        image = io.deepseekharness.mobile.companion.KnowledgeImageModel.preview(context, originalId)
                    }) throw RuntimeFailure("KNOWLEDGE_CHANGED", "资料已变化，请重新检索")
                    access.requireKnowledge()
                    respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true, 0, checkNotNull(image), false, null))
                    return
                }
                if (request.optString("op") in setOf("status", "retry")) {
                    val id = (request.opt("id") as? String)?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
                        ?: throw RuntimeFailure("KNOWLEDGE_INPUT_INVALID", "资料标识无效")
                    val row = knowledge.findById(id) ?: throw RuntimeFailure("KNOWLEDGE_NOT_FOUND", "资料已删除或不可用")
                    val hash = io.deepseekharness.mobile.companion.CompanionMemorySuppression.digest(row.getString("text"))
                    val revision = request.opt("revision") as? String ?: throw RuntimeFailure("KNOWLEDGE_INPUT_INVALID", "资料版本无效")
                    if (revision != hash) throw RuntimeFailure("KNOWLEDGE_CHANGED", "资料已变化，请重新检索")
                    var status: JSONObject? = null
                    val memoryEnabled = io.deepseekharness.mobile.companion.CompanionMemoryStore(context).enabled
                    if (request.optString("op") == "retry" && !memoryEnabled) throw RuntimeFailure("MEMORY_DISABLED", "请先开启记忆整理")
                    var retryScheduled = false
                    if (!knowledge.withUnchangedRecord(id, row.getString("text")) {
                        if (request.optString("op") == "retry") {
                            retryScheduled = io.deepseekharness.mobile.companion.SharedMemoryJobs.retryRecord(context, id, hash)
                            if (retryScheduled) io.deepseekharness.mobile.companion.SharedMemoryJobs.schedule(context, 0)
                        }
                        status = io.deepseekharness.mobile.companion.SharedMemoryJobs.status(context, id, hash)
                    }) throw RuntimeFailure("KNOWLEDGE_CHANGED", "资料已变化，请重新检索")
                    val result = checkNotNull(status).put("id", id).put("revision", hash).put("retryScheduled", retryScheduled).put("memoryEnabled", memoryEnabled)
                        .put("notice", "队列状态不代表模型已完整理解原文件；错误代码仅表示上次已记录的失败分类。")
                    access.requireKnowledge()
                    respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true, 0, result.toString(), false, null))
                    return
                }
                val result = io.deepseekharness.mobile.companion.CompanionKnowledgeModel.execute(store.knowledgeModelAccess, param, knowledge::findById, knowledge::recent, knowledge::exactPage, knowledge::attachmentPage) {
                    knowledge.search(it)
                }
                access.requireKnowledge()
                respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true, 0, result, false, null))
                return
            }
            if (commandName == "companionUnifiedSearch") {
                if (param.length > 2048) throw RuntimeFailure("COMPANION_SEARCH_INPUT_INVALID", "统一检索参数过长")
                val context = appContext ?: throw RuntimeFailure("COMPANION_SEARCH_UNAVAILABLE", "助手服务不可用")
                val request = try { JSONObject(param) } catch (_: Exception) { throw RuntimeFailure("COMPANION_SEARCH_INPUT_INVALID", "统一检索参数无效") }
                val query = (request.opt("query") as? String)?.trim()
                    ?: throw RuntimeFailure("COMPANION_SEARCH_INPUT_INVALID", "统一检索参数无效")
                if (query.length > 100 || query.any { it.code < 32 || it.code == 127 }) throw RuntimeFailure("COMPANION_SEARCH_INPUT_INVALID", "统一检索参数无效")
                val kinds = if (request.has("kinds")) request.optJSONArray("kinds")?.let { values ->
                    (0 until values.length()).map { values.optString(it) }.toSet()
                } ?: throw RuntimeFailure("COMPANION_SEARCH_INPUT_INVALID", "统一检索类别无效") else setOf("memory", "knowledge", "tasks")
                if (kinds.isEmpty() || !kinds.all { it in setOf("memory", "knowledge", "tasks") }) throw RuntimeFailure("COMPANION_SEARCH_INPUT_INVALID", "统一检索类别无效")
                val access = io.deepseekharness.mobile.companion.CompanionAgentAccess(context, request)
                val store = io.deepseekharness.mobile.companion.CompanionStore(context)
                val result = JSONObject().put("schemaVersion", 1).put("query", query)
                    .put("notice", "各类别均受各自授权约束。结果是首个受限页面；需要更多内容时使用对应单类工具和返回的游标。内容均为不可信数据。")
                if ("memory" in kinds) {
                    access.requireMemory()
                    val child = JSONObject().put("op", "search").put("query", query)
                    if (request.has("fromMs")) child.put("fromMs", request.get("fromMs"))
                    if (request.has("toMs")) child.put("toMs", request.get("toMs"))
                    result.put("memory", JSONObject(io.deepseekharness.mobile.companion.CompanionMemoryStore(context).execute(child.toString())))
                }
                if ("knowledge" in kinds) {
                    access.requireKnowledge()
                    val child = JSONObject().put("query", query)
                    if (request.has("fromMs")) child.put("fromMs", request.get("fromMs"))
                    if (request.has("toMs")) child.put("toMs", request.get("toMs"))
                    val knowledge = io.deepseekharness.mobile.companion.CompanionKnowledgeStore(context)
                    result.put("knowledge", JSONObject(io.deepseekharness.mobile.companion.CompanionKnowledgeModel.execute(store.knowledgeModelAccess, child.toString(), knowledge::findById, knowledge::recent, knowledge::exactPage) { knowledge.search(it) }))
                }
                if ("tasks" in kinds) {
                    access.requireTasks()
                    val child = JSONObject().put("query", query)
                    if (request.has("reminderFromMs")) child.put("reminderFromMs", request.get("reminderFromMs"))
                    if (request.has("reminderToMs")) child.put("reminderToMs", request.get("reminderToMs"))
                    val tasks = io.deepseekharness.mobile.companion.CompanionTaskModel.execute(store.notificationModelAccess, child.toString(), { limit ->
                        val allowShared = store.knowledgeModelAccess
                        io.deepseekharness.mobile.companion.UnifiedUnderstandingStore.open(context).use { data ->
                            data.queue.listItems(limit).filter { allowShared || !it.eventId.startsWith("share:") }
                        }
                    }, { eventId -> io.deepseekharness.mobile.companion.UnifiedUnderstandingStore.open(context).use { it.queue.sharedRecordId(eventId) } })
                    result.put("tasks", JSONObject(tasks))
                }
                // A later category can finish after access to an earlier one was revoked.
                if ("memory" in kinds) access.requireMemory()
                val hasSharedTasks = result.optJSONObject("tasks")?.let(CompanionTaskModel::includesSharedMaterial) == true
                if ("knowledge" in kinds || hasSharedTasks) access.requireKnowledge()
                if ("tasks" in kinds) access.requireTasks()
                respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true, 0, result.toString(), false, null))
                return
            }
            if (commandName == "companionCalendar") {
                val context = appContext ?: throw RuntimeFailure("CALENDAR_UNAVAILABLE", "日历服务不可用")
                val zone = java.util.TimeZone.getDefault()
                val now = System.currentTimeMillis()
                val result = io.deepseekharness.mobile.companion.CompanionCalendarModel.execute(
                    io.deepseekharness.mobile.companion.CompanionStore(context).calendarModelAccess, param, now, zone
                ) { io.deepseekharness.mobile.companion.CompanionCalendar.read(context, now, zone) }
                respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true, 0, result, false, null))
                return
            }
            if (commandName == "companionMemory") {
                if (param.length > 4096) throw RuntimeFailure("MEMORY_INPUT_INVALID", "记忆参数过长")
                val context = appContext ?: throw RuntimeFailure("MEMORY_UNAVAILABLE", "记忆服务不可用")
                val request = try { JSONObject(param) } catch (_: Exception) { throw RuntimeFailure("MEMORY_INPUT_INVALID", "记忆参数无效") }
                val access = io.deepseekharness.mobile.companion.CompanionAgentAccess(context, request)
                if (request.optString("op") == "capabilities") {
                    respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true, 0, access.capabilities(), false, null))
                    return
                }
                access.requireMemory()
                val result = if (request.optString("op") == "hybrid")
                    io.deepseekharness.mobile.companion.SemanticMemorySearch.execute(context, param)
                else io.deepseekharness.mobile.companion.CompanionMemoryStore(context).execute(param,
                    io.deepseekharness.mobile.companion.HarnessBubbleClient.isPetMemoryTurn(request.optString("callerSession")))
                // A remote embedding query can finish after the user revokes access.
                access.requireMemory()
                if (io.deepseekharness.mobile.companion.CompanionMemoryHint.shouldNotify(param, result)) {
                    OverlayBallService.companionPendingMemoryHint()
                }
                respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true, 0, result, false, null))
                return
            }
            if (commandName in setOf("ownerContext","ownerProfile","secretaryTasks")) {
                if(param.length>4096) throw RuntimeFailure("ASSISTANT_INPUT_INVALID","主人档案或事项参数过长")
                val context=appContext ?: throw RuntimeFailure("ASSISTANT_UNAVAILABLE","个人助理不可用")
                val request=JSONObject(param)
                val access=io.deepseekharness.mobile.companion.CompanionAgentAccess(context,request)
                val result=when(commandName) {
                    "ownerContext" -> io.deepseekharness.mobile.companion.OwnerContext(context,request.getString("callerSession")).read()
                    "ownerProfile" -> {
                        access.requireMemory()
                        val memory=io.deepseekharness.mobile.companion.CompanionMemoryStore(context)
                        val value=if(request.optString("action")=="get") memory.profile() else memory.changeProfile(request,
                            fromPetTurn=io.deepseekharness.mobile.companion.HarnessBubbleClient.isPetMemoryTurn(request.getString("callerSession")))
                        access.requireMemory();value
                    }
                    else -> {
                        access.requireMemory()
                        val value=io.deepseekharness.mobile.companion.SecretaryActions.execute(context,request,request.getString("callerSession"))
                        access.requireMemory();value
                    }
                }
                respondResult(output,io.deepseekharness.mobile.shizuku.DeviceCommandResult(true,0,result.toString(),false,null))
                return
            }
            if (commandName == "companionTasks") {
                if (param.length > 1024) throw RuntimeFailure("TASK_INPUT_INVALID", "事项查询参数过长")
                val context = appContext ?: throw RuntimeFailure("TASK_UNAVAILABLE", "事项服务不可用")
                val request = try { JSONObject(param) } catch (_: Exception) { throw RuntimeFailure("TASK_INPUT_INVALID", "事项查询参数无效") }
                val access = io.deepseekharness.mobile.companion.CompanionAgentAccess(context, request)
                access.requireTasks()
                val tasks = io.deepseekharness.mobile.companion.CompanionTaskModel
                val result = if (request.optString("op", "search") == "update") {
                    val update = tasks.parseUpdate(param)
                    val now = System.currentTimeMillis()
                    val changed = io.deepseekharness.mobile.companion.UnifiedUnderstandingStore.open(context).use { store ->
                        val current = store.queue.item(update.id) ?: throw RuntimeFailure("TASK_NOT_FOUND", "事项不存在或已删除")
                        if (current.eventId.startsWith("share:") && !io.deepseekharness.mobile.companion.CompanionStore(context).knowledgeModelAccess)
                            throw RuntimeFailure("KNOWLEDGE_MODEL_DISABLED", "请先允许对话访问本地资料")
                        if (current.state != update.expectedState) throw RuntimeFailure("TASK_CONFLICT", "事项状态已变化，请先重新读取")
                        if (update.expectedRevision != null && update.expectedRevision != tasks.revision(current))
                            throw RuntimeFailure("TASK_CONFLICT", "事项内容或提醒已变化，请先重新读取")
                        val saved = if (update.reminderAtMs != null) store.queue.setItemReminder(update.id, update.expectedState, update.reminderAtMs, now)
                        else store.queue.updateItem(update.id, update.expectedState, update.nextState!!)
                        if (!saved) throw RuntimeFailure("TASK_CONFLICT", "事项状态已变化，请先重新读取")
                        store.queue.item(update.id) ?: throw RuntimeFailure("TASK_CONFLICT", "事项已变化，请先重新读取")
                    }
                    try {
                        if (update.reminderAtMs != null) io.deepseekharness.mobile.companion.CompanionReminder(context).upsertLinked(changed.id, changed.title, update.reminderAtMs, changed.reminderRevision)
                        else if (update.nextState in setOf("done", "cancelled")) io.deepseekharness.mobile.companion.CompanionReminder(context).removeLinked(changed.id)
                    } catch (e: Exception) {
                        if (update.reminderAtMs != null) runCatching { io.deepseekharness.mobile.companion.UnifiedUnderstandingStore.open(context).use { it.queue.disableItemReminder(changed.id, update.reminderAtMs, changed.reminderRevision) } }
                        throw RuntimeFailure("TASK_REMINDER_UNAVAILABLE", "事项已更新，但关联提醒未能安排；已撤销本次提醒状态", e)
                    }
                    val refreshed = io.deepseekharness.mobile.companion.UnifiedUnderstandingStore.open(context).use { store ->
                        val item = store.queue.item(changed.id) ?: throw RuntimeFailure("TASK_NOT_FOUND", "事项不存在或已删除")
                        tasks.updated(item, store.queue.sharedRecordId(item.eventId))
                    }
                    refreshed
                } else {
                    io.deepseekharness.mobile.companion.CompanionTaskModel.execute(
                        io.deepseekharness.mobile.companion.CompanionStore(context).notificationModelAccess,
                        param,
                        { limit ->
                            val allowShared = io.deepseekharness.mobile.companion.CompanionStore(context).knowledgeModelAccess
                            io.deepseekharness.mobile.companion.UnifiedUnderstandingStore.open(context).use { store ->
                                store.queue.listItems(limit).filter { allowShared || !it.eventId.startsWith("share:") }
                            }
                        },
                        { eventId -> io.deepseekharness.mobile.companion.UnifiedUnderstandingStore.open(context).use { it.queue.sharedRecordId(eventId) } },
                    )
                }
                access.requireTasks()
                if (tasks.includesSharedMaterial(JSONObject(result))) access.requireKnowledge()
                respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true, 0, result, false, null))
                return
            }
            if (commandName == "companionRecurring") {
                val context = appContext ?: throw RuntimeFailure("REMINDER_UNAVAILABLE", "提醒服务不可用")
                val result = io.deepseekharness.mobile.companion.CompanionRecurringBridge(context).execute(param)
                respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true, 0, result, false, null))
                return
            }
            if (commandName == "companionReminder") {
                val context = appContext ?: throw RuntimeFailure("REMINDER_UNAVAILABLE", "提醒服务不可用")
                val result = io.deepseekharness.mobile.companion.CompanionReminderBridge(context).execute(param)
                respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(true, 0, result, false, null))
                return
            }
            // 自动化授权与设备能力都不经过 Shell；能力判定复用原生统一策略，不在 AI 插件里另算一套。
            if(commandName=="screenCapture") {
                if(param.isNotEmpty()) throw RuntimeFailure("SCREEN_INPUT_INVALID","截图不接受参数")
                val context=appContext ?: throw RuntimeFailure("SCREEN_UNAVAILABLE","屏幕服务不可用")
                val result=DeviceControlState.execute(context,true,"快速截图") {
                    io.deepseekharness.mobile.shizuku.DeviceCommandResult(true,0,captureScreenImage().toString(),false,null)
                }
                respondResult(output,result)
                return
            }
            if(commandName=="screenSequenceCancel") {
                screenJournal.cancelBeforeStart(param)
                sequenceTickets.cancel(param)
                respondResult(output,io.deepseekharness.mobile.shizuku.DeviceCommandResult(true,0,"cancelled",false,null))
                return
            }
            if(commandName=="screenReceiptAck") {
                screenJournal.received(param)
                respondResult(output,io.deepseekharness.mobile.shizuku.DeviceCommandResult(true,0,"received",false,null))
                return
            }
            if(commandName=="screenRecover") {
                if(param.length>256) throw RuntimeFailure("SCREEN_INPUT_INVALID","恢复参数过长")
                val request=JSONObject(param)
                val id=request.optString("operationId")
                val context=appContext ?: throw RuntimeFailure("SCREEN_UNAVAILABLE","屏幕服务不可用")
                val value=if(id.isEmpty()) screenJournal.recent() else {
                    val record=screenJournal.read(id) ?: throw RuntimeFailure("SCREEN_OPERATION_MISSING","执行记录不存在")
                    if(record.getString("state")=="running") throw RuntimeFailure("SCREEN_OPERATION_RUNNING","操作尚未结束")
                    val result=DeviceControlState.execute(context,true,"核实中断后的页面") {
                        val foreground=screenForeground()
                        val observation=observeScreen(context,JSONObject())
                        if(screenForeground()!=foreground) throw RuntimeFailure("SCREEN_APP_CHANGED","观察期间应用变化，请重新核实")
                        val reconciled=screenJournal.reconcile(id,observation,foreground)
                        io.deepseekharness.mobile.shizuku.DeviceCommandResult(true,0,JSONObject().put("record",reconciled)
                            .put("observation",observation).put("doNotReplay",true)
                            .put("nextStep","已读取当前页面，未知结果仍需判断；不自动继续旧步骤，不把输入回执视为业务成功").toString(),false,null)
                    }
                    JSONObject(result.text)
                }
                respondResult(output,io.deepseekharness.mobile.shizuku.DeviceCommandResult(true,0,value.toString(),false,null))
                return
            }
            if (commandName in setOf("screenObserve","screenAct","screenSequence")) {
                if(param.length>2048) throw RuntimeFailure("SCREEN_INPUT_INVALID","屏幕参数过长")
                val context=appContext ?: throw RuntimeFailure("SCREEN_UNAVAILABLE","屏幕服务不可用")
                val request=JSONObject(param)
                val result=DeviceControlState.execute(context,commandName=="screenObserve",
                    if(commandName=="screenObserve") "快速观察" else "操作并观察") {
                    val value=when(commandName) {
                        "screenObserve"->observeScreen(context,request)
                        "screenSequence"->journaledScreen(commandName,request) {screenSequence(context,request)}.also {if(it.optString("status")!="completed") screenLease=null}
                        else->journaledScreen(commandName,request) {actAndObserve(context,request)}
                    }
                    io.deepseekharness.mobile.shizuku.DeviceCommandResult(true,0,value.toString(),false,null)
                }
                respondResult(output,result)
                return
            }
            if (commandName == "automationPolicy") {
                if (param.isNotEmpty()) throw RuntimeFailure("DEVICE_COMMAND_INVALID", "能力查询不接受参数")
                respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(
                    true, 0, JSONObject().put("schemaVersion", 3).put("allowlistedAutomation", true)
                        .put("shellEnabled", shellEnabled()).put("backgroundTasks", true)
                        .put("virtualScreen", true)
                        .put("directDeviceOperations", true).toString(), false, null,
                ))
                return
            }
            if (commandName == "deviceCapabilities") {
                if (param.isNotEmpty()) throw RuntimeFailure("DEVICE_COMMAND_INVALID", "设备能力查询不接受参数")
                val context = applicationContextOrNull()
                    ?: throw RuntimeFailure("DEVICE_CAPABILITIES_UNAVAILABLE", "暂时无法读取设备能力")
                val shizukuState = shizuku.state()
                val root = io.deepseekharness.mobile.shizuku.RootEngineAccess.status(
                    context,
                    packageChecker = io.deepseekharness.mobile.shizuku.RootEngineAccess.packageChecker(context),
                )
                val facts = DeviceCapabilityFacts(
                    sdkInt = Build.VERSION.SDK_INT,
                    shizukuInstalled = shizukuState.installed,
                    shizukuRunning = shizukuState.running,
                    shizukuPermission = shizukuState.permission,
                    shizukuConnected = shizukuState.connected,
                    shellEnabled = shellEnabled(),
                    accessibilityStatus = AccessibilityAutomationStore.serviceStatus(context),
                    rootEngineEnabled = root.enabled,
                    rootEngineStatus = if (root.enabled) root.kernelSuStatus else null,
                    rootEngineReason = if (root.enabled) root.reason else null,
                    rootSandboxDegraded = root.degraded,
                )
                val entries = org.json.JSONArray().also { array ->
                    DeviceCapabilityPolicy.report(facts).forEach { capability ->
                        array.put(JSONObject()
                            .put("id", capability.id)
                            .put("state", capability.state)
                            .put("reason", capability.reason)
                            .put("action", capability.action ?: JSONObject.NULL)
                            .put("alternative", capability.alternative ?: JSONObject.NULL))
                    }
                }
                respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(
                    true, 0,
                    JSONObject().put("atMs", System.currentTimeMillis())
                        .put("sdkInt", Build.VERSION.SDK_INT)
                        .put("capabilities", entries).toString(),
                    false, null,
                ))
                return
            }
            // 无障碍命令不经过 Shell：由用户手动开启的服务在原生侧再次校验
            // 白名单、前台包名、锁屏状态、敏感窗口和动作频率。
            // automationTask 与 accessibilityTree 同一条闸门：它只做计划式执行的闸门与记账，不执行动作。
            if (commandName in setOf(
                    "accessibilityTree",
                    "accessibilityAction",
                    "accessibilityBack",
                    "tap",
                    "inputText",
                    "automationTask",
                )
            ) {
                val service = DeepSeekAccessibilityService.current()
                val result = service?.execute(commandName, param)
                    ?: io.deepseekharness.mobile.shizuku.DeviceCommandResult(
                        ok = false,
                        exitCode = 1,
                        text = "请先在系统设置中手动开启 Harness 无障碍服务",
                        truncated = false,
                        errorCode = "ACCESSIBILITY_SERVICE_DISABLED",
                    )
                respondResult(output, result)
                return
            }
            if (commandName in setOf("virtualScreenState", "virtualScreenCapture", "virtualScreenAction")) {
                if (!shellEnabled()) throw RuntimeFailure("DEVICE_SHELL_DISABLED", "请在 Shizuku 设置中开启 AI Shell")
                respondResult(output, io.deepseekharness.mobile.virtualscreen.VirtualScreenCommands.execute(commandName, param))
                return
            }
            // 副屏直通 Shell：把会话副屏的 displayId 注入脚本环境后，走与通用命令**同一条**
            // 一次性 Shell 路径。param 是 JSON：{"script":"…","displayId":38}（displayId 可省）。
            // 校验与错误码全部由 VirtualScreenShellPolicy 决定，这里只负责取只读状态并执行。
            if (commandName == VirtualScreenShellPolicy.COMMAND) {
                if (!shellEnabled()) throw RuntimeFailure("DEVICE_SHELL_DISABLED", "请在 Shizuku 设置中开启 AI Shell")
                val request = try {
                    JSONObject(param)
        } catch (error: Throwable) {
                    throw RuntimeFailure("DEVICE_COMMAND_INVALID", "副屏 Shell 参数不是合法 JSON", error)
                }
                // 只认 JSON 字符串与 JSON 数字：`{"script":null}` 这类形状不能变成一段可执行脚本文本。
                val script = request.opt("script") as? String
                val requestedDisplayId = when (val raw = request.opt("displayId")) {
                    null -> null
                    is Number -> raw.toInt()
                    else -> throw RuntimeFailure("DEVICE_COMMAND_INVALID", "displayId 必须是整数")
                }
                val state = VirtualScreenService.current?.state()
                val plan = VirtualScreenShellPolicy.plan(
                    script = script,
                    requestedDisplayId = requestedDisplayId,
                    active = state?.optBoolean("active") == true,
                    sessionDisplayId = state?.optInt("displayId", 0) ?: 0,
                )
                respondResult(output, runOneShot(DeviceCommand.SHELL, plan.command), plan.displayId)
                return
            }
            // 访客侧「一轮任务已完成」的收单点（登记册 §5.5）。
            // 这条命令**只**能触发一条固定文案的通知，不读会话内容、不返回任何用户数据：
            // 访客进程因此无法用它在通知栏或锁屏上写任意文本。
            // 限流命中一律静默忽略（ok=true, accepted=false），不报错刷屏。
            if (commandName == "notify-turn-complete") {
                if (param.length > TurnCompletionPolicy.MAX_PARAM_CHARS) {
                    throw RuntimeFailure("DEVICE_COMMAND_INVALID", "任务完成事件参数过长")
                }
                val receipt = TaskNotification.recordTurnCompleted(
                    applicationContextOrNull(),
                    param,
                )
                respondResult(output, io.deepseekharness.mobile.shizuku.DeviceCommandResult(
                    ok = receipt.ok,
                    exitCode = if (receipt.ok) 0 else 1,
                    text = JSONObject()
                        .put("accepted", receipt.accepted)
                        .put("queued", receipt.queued)
                        .toString(),
                    truncated = false,
                    errorCode = receipt.errorCode,
                ))
                return
            }
            val command = DeviceCommand.fromName(commandName)
                ?: throw RuntimeFailure("DEVICE_COMMAND_INVALID", "设备命令不支持")
            // 根通道（用户在设置里显式打开）时，SHELL 命令由应用进程自己起 `su -c`，
            // 既不建 Shizuku 会话，也不受「AI Shell」这个 Shizuku 通道开关约束——
            // 那条会话的父进程是 UID 2000 的 shell，根命令的归属会变得不确定。
            val rootEngine = DeviceCommand.requireShellRoute(
                command, command == DeviceCommand.SHELL && rootEngineUsable(), shellEnabled(),
            )
            respondResult(output, runOneShot(command, param, rootEngine))
        } catch(error:Throwable) {
            respond(output,200,JSONObject().put("ok",false).put("text","").put("exitCode",1)
                .put("truncated",false).put("errorCode",(error as? RuntimeFailure)?.code ?: "BRIDGE_FAILED").toString())
        }
    }

    /**
     * 一次性设备 Shell 会话：创建 → 执行一条命令 → 关闭。
     *
     * 会话退出（Shell 死亡 / Shizuku 断开）后不会再有输出：在途的设备命令立刻按协议错误收口，
     * 不必空等到 60 秒超时。通用命令与副屏直通命令共用这一条路径，只有命令与脚本文本不同。
     */
    private fun runOneShot(command: DeviceCommand, param: String, rootEngine: Boolean = false): io.deepseekharness.mobile.shizuku.DeviceCommandResult {
        val context = appContext ?: return runOneShotRaw(command,param,rootEngine)
        val observed = command in setOf(DeviceCommand.SCREENSHOT, DeviceCommand.UI_DUMP)
        if (command == DeviceCommand.UI_DUMP) {
            val native = DeepSeekAccessibilityService.current()?.execute("accessibilityTree","")
            if (native?.ok == true) return native.copy(text = "原生无障碍节点树（JSON）：\n" + native.text)
        }
        return if (observed || command in setOf(DeviceCommand.SHELL, DeviceCommand.LAUNCH_APP))
            DeviceControlState.execute(context,observed,if(observed) "读取屏幕" else "设备命令") {
                val result=runOneShotRaw(command,param,rootEngine)
                if(command==DeviceCommand.LAUNCH_APP && result.ok) {
                    DeviceControlState.checkCurrent()
                    val foreground=shizuku.mainScreenForeground()
                    DeviceControlState.checkCurrent()
                    if(foreground!=param) result.copy(ok=false,exitCode=1,errorCode="DEVICE_APP_NOT_FOREGROUND",
                        text=result.text+"\n请求的应用未到前台；请观察系统弹窗或启动限制，不要视为已打开")
                    else result.copy(text=result.text+"\n已核实前台应用："+param)
                } else result
            }
        else runOneShotRaw(command,param,rootEngine)
    }

    private fun runOneShotRaw(
        command: DeviceCommand,
        param: String,
        rootEngine: Boolean = false,
    ): io.deepseekharness.mobile.shizuku.DeviceCommandResult {
        // 根通道不建 Shizuku 会话：会话 ID 对这个分支没有意义（`execute` 会先于任何会话操作返回）。
        if (rootEngine) return runner.execute(ROOT_ENGINE_SESSION_ID, command, param, COMMAND_TIMEOUT_MS, useRootEngine = true)
        if (command == DeviceCommand.SCREENSHOT) return captureScreen()
        val sessionId = shizuku.create(
            DEFAULT_COLUMNS,
            DEFAULT_ROWS,
            suppressPublicOutput = true,
            permitted = running::get,
            onSessionExit = { id -> runner.onSessionExit(id) },
        )
        DeviceControlState.cancellation { shizuku.close(sessionId) }
        try {
            return runner.execute(sessionId, command, param, COMMAND_TIMEOUT_MS)
        } finally {
            try {
                shizuku.close(sessionId)
            } catch (_: Throwable) {
            }
        }
    }

    private fun respondResult(
        output: BufferedOutputStream,
        result: io.deepseekharness.mobile.shizuku.DeviceCommandResult,
        // 只有副屏直通命令会带上它：调用方据此确认这次脚本到底落在哪块屏上。
        displayId: Int? = null,
    ) {
        val errorJson = result.errorCode?.let { JSONObject.quote(it) } ?: "null"
        val textJson = JSONObject.quote(result.text)
        val displayJson = displayId?.let { ",\"displayId\":" + it } ?: ""
        respond(
            output,
            200,
            "{\"ok\":" + result.ok + ",\"exitCode\":" + result.exitCode + ",\"text\":" + textJson + ",\"truncated\":" + result.truncated + displayJson + ",\"errorCode\":" + errorJson + "}",
        )
    }

    private fun readBody(input: BufferedInputStream, contentLength: Int): String {
        if (contentLength !in 1..MAX_REQUEST_BODY_BYTES) throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥请求长度无效")
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var remaining = contentLength
        while (remaining > 0) {
            val n = input.read(chunk, 0, minOf(chunk.size, remaining))
            if (n < 0) throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥请求被截断")
            buffer.write(chunk, 0, n)
            remaining -= n
        }
        return buffer.toString(StandardCharsets.UTF_8.name())
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            if (buffer.size() >= 4096) throw RuntimeFailure("DEVICE_REQUEST_INVALID", "设备桥请求行过长")
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString(StandardCharsets.UTF_8.name())
            if (b == 10) break // LF
            if (b != 13) buffer.write(b) // 丢弃 CR
        }
        return buffer.toString(StandardCharsets.UTF_8.name())
    }

    private fun respond(output: BufferedOutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val statusText = when (status) {
            200 -> "OK"
            401 -> "Unauthorized"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val head = "HTTP/1.1 " + status + " " + statusText + "\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: " + bytes.size + "\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    companion object {
        private const val COMMAND_TIMEOUT_MS = 60_000L

        /** 根通道分支的占位会话标识：该分支不建 Shizuku 会话，这个值不会与任何真实会话重名。 */
        private const val ROOT_ENGINE_SESSION_ID = "root-engine"
        private const val STOP_WAIT_MILLIS = 500L
        private const val DEFAULT_COLUMNS = 80
        private const val DEFAULT_ROWS = 24
        /** 文件上传包含 Base64；仍保持一个受控上限，避免把桥变成无限内存入口。 */
        private const val MAX_REQUEST_BODY_BYTES = 200_000
    }
}
