package io.deepseekharness.mobile.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import io.deepseekharness.mobile.shizuku.DeviceCommandResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Whale 高性能原生手机控制引擎 v2（零 PRoot、零外部桥接延迟）。
 *
 * 核心性能与能力升级：
 * 1. 语义剪枝控件树（[WhaleUiTreeCompactor]）：剔除 70%–85% 布局噪声，分配 `#ref` 短编号与中心坐标；
 * 2. 多策略目标定位：`#ref` O(1) 命中 → `viewId` → 可见文本/描述模糊匹配（自动上溯可点击父容器）→ 坐标；
 * 3. 手势自动回退：面对 Flutter / Jetpack Compose / WebView 无 viewId 或忽略 ACTION_CLICK 的控件，自动无缝切换 `dispatchGesture` 触控与滑动；
 * 4. 原子多步批量执行（`fastBatch`）：单次 LLM 轮次内完成最多 8 步连贯操作并回传最新精简控件树，大幅降低多步屏控延迟；
 * 5. 坚守全部系统级安全底线：锁屏/熄屏拦截、受保护系统包拦截、密码/验证码/支付/权限敏感窗口零妥协拦截。
 */
internal class WhaleFastPhoneController(private val service: DeepSeekAccessibilityService) {
    data class ScreenCaptureResult(
        val ok: Boolean,
        val jpeg: ByteArray? = null,
        val width: Int = 0,
        val height: Int = 0,
        val packageName: String = "",
        val error: String? = null,
    )

    fun captureScreenJpeg(): ScreenCaptureResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenCaptureResult(false, error = "无障碍截屏需要 Android 11（API 30）及以上系统")
        }
        val pair = requireSafeRoot()
        if (pair == null) {
            val err = safeGuardError()
            return ScreenCaptureResult(false, error = err.text)
        }
        val (_, pkg) = pair
        val metrics = service.resources.displayMetrics
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<ScreenCaptureResult?>(null)
        service.takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val hw = screenshot.hardwareBuffer
                        val cs = screenshot.colorSpace
                        val wrapped = android.graphics.Bitmap.wrapHardwareBuffer(hw, cs)
                        hw.close()
                        if (wrapped == null) {
                            resultRef.set(ScreenCaptureResult(false, error = "无法解码屏幕帧缓冲"))
                            return
                        }
                        val soft = wrapped.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        wrapped.recycle()
                        val maxSide = 1080f
                        val scale = minOf(1f, maxSide / maxOf(soft.width, soft.height).coerceAtLeast(1))
                        val scaled = if (scale < 0.99f) {
                            android.graphics.Bitmap.createScaledBitmap(
                                soft,
                                (soft.width * scale).toInt().coerceAtLeast(1),
                                (soft.height * scale).toInt().coerceAtLeast(1),
                                true,
                            ).also { soft.recycle() }
                        } else {
                            soft
                        }
                        val out = java.io.ByteArrayOutputStream()
                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
                        scaled.recycle()
                        resultRef.set(
                            ScreenCaptureResult(
                                ok = true,
                                jpeg = out.toByteArray(),
                                width = metrics.widthPixels,
                                height = metrics.heightPixels,
                                packageName = pkg,
                            ),
                        )
                    } catch (t: Throwable) {
                        resultRef.set(ScreenCaptureResult(false, error = "截屏压缩失败：${t.message ?: t.javaClass.simpleName}"))
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    resultRef.set(ScreenCaptureResult(false, error = "系统无障碍截屏被拒绝（错误码 $errorCode），可能是受保护画面或截屏频率过快"))
                    latch.countDown()
                }
            },
        )
        val done = latch.await(5, TimeUnit.SECONDS)
        if (!done) return ScreenCaptureResult(false, error = "无障碍截屏超时")
        return resultRef.get() ?: ScreenCaptureResult(false, error = "未获取到截屏结果")
    }
    private data class CachedSnapshot(
        val packageName: String,
        val capturedAtMs: Long,
        val elements: List<WhaleUiTreeCompactor.CompactElement>,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastSnapshot = AtomicReference<CachedSnapshot?>(null)

    fun execute(command: String, param: String): DeviceCommandResult {
        return when (command) {
            "fastTree" -> captureFastTree()
            "fastAct" -> executeSingleAction(runCatching { JSONObject(param.ifBlank { "{}" }) }.getOrElse { JSONObject() })
            "fastBatch" -> executeBatch(param)
            else -> DeviceCommandResult(false, 1, "不支持的快速屏控命令：$command", false, "FAST_CONTROL_INVALID")
        }
    }

    private fun findTargetAppRoot(): AccessibilityNodeInfo? {
        val ownPkg = service.packageName
        for (attempt in 0 until 3) {
            val active = service.rootInActiveWindow
            val activePkg = active?.packageName?.toString().orEmpty()
            if (active != null && activePkg.isNotEmpty() && activePkg != ownPkg && AccessibilityAutomationPolicy.validPackage(activePkg)) {
                return active
            }
            val windows = runCatching { service.windows }.getOrNull().orEmpty()
            val candidate = windows
                .asSequence()
                .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedWith(compareByDescending<android.view.accessibility.AccessibilityWindowInfo> { it.isActive }.thenByDescending { it.layer })
                .mapNotNull { runCatching { it.root }.getOrNull() }
                .firstOrNull { node ->
                    val pkg = node.packageName?.toString().orEmpty()
                    pkg.isNotEmpty() && pkg != ownPkg
                }
            if (candidate != null) return candidate
            if (active != null && activePkg != ownPkg) return active
            if (attempt < 2) SystemClock.sleep(160L)
        }
        return service.rootInActiveWindow
    }

    private fun requireSafeRoot(): Pair<AccessibilityNodeInfo, String>? {
        if (service.isLockedOrScreenOff()) return null
        val root = findTargetAppRoot() ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        if (!AccessibilityAutomationPolicy.validPackage(pkg)) return null
        if (service.containsSensitiveWindow(root)) return null
        return root to pkg
    }

    private fun safeGuardError(): DeviceCommandResult {
        if (service.isLockedOrScreenOff()) {
            return DeviceCommandResult(false, 1, "设备已锁定或屏幕未交互", false, "ACCESSIBILITY_DEVICE_LOCKED")
        }
        val root = findTargetAppRoot()
            ?: return DeviceCommandResult(false, 1, "当前没有可读取的应用窗口", false, "ACCESSIBILITY_WINDOW_UNAVAILABLE")
        val pkg = root.packageName?.toString().orEmpty()
        if (!AccessibilityAutomationPolicy.validPackage(pkg)) {
            return DeviceCommandResult(false, 1, "受保护的系统应用或无效包名，已拒绝自动化：$pkg", false, "ACCESSIBILITY_PACKAGE_DENIED")
        }
        if (service.containsSensitiveWindow(root)) {
            return DeviceCommandResult(false, 1, "检测到密码、验证码、支付或权限敏感窗口，已拒绝自动化", false, "ACCESSIBILITY_SENSITIVE_WINDOW")
        }
        return DeviceCommandResult(false, 1, "窗口不可用", false, "ACCESSIBILITY_WINDOW_UNAVAILABLE")
    }

    private fun captureFastTree(): DeviceCommandResult {
        val t0 = SystemClock.elapsedRealtime()
        val pair = requireSafeRoot() ?: return safeGuardError()
        val (root, pkg) = pair
        val metrics = service.resources.displayMetrics
        val rawNodes = collectRawNodes(root)
        val compact = WhaleUiTreeCompactor.compact(rawNodes, metrics.widthPixels, metrics.heightPixels, MAX_COMPACT_ELEMENTS)
        lastSnapshot.set(CachedSnapshot(pkg, System.currentTimeMillis(), compact))

        val payload = JSONObject()
            .put("packageName", pkg)
            .put("width", metrics.widthPixels)
            .put("height", metrics.heightPixels)
            .put("rawCount", rawNodes.size)
            .put("count", compact.size)
            .put("elapsedMs", (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L))
            .put("elements", WhaleUiTreeCompactor.toJsonArray(compact))
        return DeviceCommandResult(true, 0, payload.toString(), compact.size >= MAX_COMPACT_ELEMENTS, null)
    }

    private fun executeBatch(param: String): DeviceCommandResult {
        val rootJson = runCatching { JSONObject(param.ifBlank { "{}" }) }.getOrNull()
        val stepsArr = rootJson?.optJSONArray("steps")
            ?: runCatching { JSONArray(param) }.getOrNull()
            ?: return DeviceCommandResult(false, 1, "请提供 steps 步骤数组", false, "FAST_BATCH_INVALID")
        val count = stepsArr.length().coerceAtMost(MAX_BATCH_STEPS)
        if (count == 0) return DeviceCommandResult(false, 1, "步骤数组不能为空", false, "FAST_BATCH_EMPTY")

        val results = JSONArray()
        var allOk = true
        for (i in 0 until count) {
            val step = stepsArr.optJSONObject(i) ?: continue
            val res = executeSingleAction(step)
            results.put(
                JSONObject()
                    .put("step", i + 1)
                    .put("action", step.optString("action", ""))
                    .put("ok", res.ok)
                    .put("detail", res.text.take(180)),
            )
            if (!res.ok) {
                allOk = false
                break
            }
            if (i < count - 1) {
                val settleMs = step.optLong("waitMs", DEFAULT_STEP_SETTLE_MS).coerceIn(40L, 1200L)
                SystemClock.sleep(settleMs)
            }
        }
        SystemClock.sleep(100L)
        val afterTree = captureFastTree()
        val out = JSONObject()
            .put("ok", allOk)
            .put("executed", results.length())
            .put("results", results)
        if (afterTree.ok) {
            out.put("screen", runCatching { JSONObject(afterTree.text) }.getOrElse { afterTree.text })
        }
        return DeviceCommandResult(allOk, if (allOk) 0 else 1, out.toString(), false, if (allOk) null else "FAST_BATCH_PARTIAL")
    }

    private fun executeSingleAction(args: JSONObject): DeviceCommandResult {
        val action = args.str("action", "").trim().lowercase()
        if (action == "wait") {
            val ms = args.optLong("ms", 300L).coerceIn(50L, 2000L)
            SystemClock.sleep(ms)
            return DeviceCommandResult(true, 0, "已等待 ${ms}ms", false, null)
        }
        val pair = requireSafeRoot() ?: return safeGuardError()
        val (root, pkg) = pair

        return when (action) {
            "back" -> {
                val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                DeviceCommandResult(ok, if (ok) 0 else 1, if (ok) "已执行返回" else "返回失败", false, if (ok) null else "GLOBAL_BACK_FAILED")
            }
            "home" -> {
                val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                DeviceCommandResult(ok, if (ok) 0 else 1, if (ok) "已回到桌面" else "返回桌面失败", false, if (ok) null else "GLOBAL_HOME_FAILED")
            }
            "recents" -> {
                val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                DeviceCommandResult(ok, if (ok) 0 else 1, if (ok) "已打开最近任务" else "打开最近任务失败", false, if (ok) null else "GLOBAL_RECENTS_FAILED")
            }
            "swipe", "scroll_forward", "scroll_backward" -> performSwipe(args, action)
            "click", "long_click" -> performTapOrClick(root, pkg, args, longClick = action == "long_click")
            "input", "settext" -> performInput(root, pkg, args)
            else -> DeviceCommandResult(false, 1, "不支持的操作类型：$action", false, "FAST_ACTION_UNSUPPORTED")
        }
    }

    private fun performTapOrClick(
        root: AccessibilityNodeInfo,
        pkg: String,
        args: JSONObject,
        longClick: Boolean,
    ): DeviceCommandResult {
        val ref = args.optInt("ref", -1)
        val viewId = args.str("viewId", "").trim()
        val text = args.str("text", "").trim()
        val hasCoords = args.has("x") && args.has("y")

        // Strategy 1: O(1) lookup from latest compact snapshot by #ref
        if (ref > 0) {
            val snap = lastSnapshot.get()
            val hit = snap?.takeIf { it.packageName == pkg }?.elements?.firstOrNull { it.ref == ref }
            if (hit != null) {
                val liveNode = if (hit.viewId.isNotEmpty()) findNodeByViewId(root, hit.viewId) else null
                if (liveNode != null && !liveNode.isPassword && !AccessibilityAutomationPolicy.containsSensitiveText(liveNode.viewIdResourceName)) {
                    val clickable = findClickableAncestor(liveNode)
                    val act = if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
                    if (clickable != null && clickable.performAction(act)) {
                        return DeviceCommandResult(true, 0, "已通过 #${ref} 点击「${hit.label.ifBlank { hit.viewId }}」", false, null)
                    }
                }
                val ok = dispatchTapGesture(hit.cx.toFloat(), hit.cy.toFloat(), if (longClick) 600L else 55L)
                return DeviceCommandResult(
                    ok,
                    if (ok) 0 else 1,
                    if (ok) "已通过手势点击 #${ref} (${hit.cx},${hit.cy})" else "手势点击失败",
                    false,
                    if (ok) null else "GESTURE_TAP_FAILED",
                )
            }
        }

        // Strategy 2: Match by viewId or visible text/contentDescription in live tree
        val matched = when {
            viewId.isNotEmpty() -> findNodeByViewId(root, viewId)
            text.isNotEmpty() -> findNodeByText(root, text)
            else -> null
        }
        if (matched != null) {
            if (matched.isPassword || AccessibilityAutomationPolicy.containsSensitiveText(matched.viewIdResourceName)) {
                return DeviceCommandResult(false, 1, "目标控件属于敏感区域，已拒绝操作", false, "ACCESSIBILITY_SENSITIVE_WINDOW")
            }
            val clickable = findClickableAncestor(matched)
            val act = if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
            if (clickable != null && clickable.performAction(act)) {
                return DeviceCommandResult(true, 0, "已点击「${text.ifBlank { viewId }}」", false, null)
            }
            val rect = Rect().also(matched::getBoundsInScreen)
            if (rect.width() > 0 && rect.height() > 0) {
                val ok = dispatchTapGesture(rect.exactCenterX(), rect.exactCenterY(), if (longClick) 600L else 55L)
                return DeviceCommandResult(
                    ok,
                    if (ok) 0 else 1,
                    if (ok) "已通过手势点击「${text.ifBlank { viewId }}」" else "手势点击失败",
                    false,
                    if (ok) null else "GESTURE_TAP_FAILED",
                )
            }
        }

        // Strategy 3: Explicit (x, y) coordinates gesture tap
        if (hasCoords) {
            val x = args.optDouble("x", -1.0).toFloat()
            val y = args.optDouble("y", -1.0).toFloat()
            if (x >= 0f && y >= 0f) {
                val ok = dispatchTapGesture(x, y, if (longClick) 600L else 55L)
                return DeviceCommandResult(
                    ok,
                    if (ok) 0 else 1,
                    if (ok) "已点击坐标 (${x.toInt()},${y.toInt()})" else "坐标手势点击失败",
                    false,
                    if (ok) null else "GESTURE_TAP_FAILED",
                )
            }
        }

        return DeviceCommandResult(false, 1, "未找到匹配目标控件（请先刷新屏幕控件树或检查 #ref/text）", false, "ACCESSIBILITY_NODE_NOT_FOUND")
    }

    private fun performInput(root: AccessibilityNodeInfo, pkg: String, args: JSONObject): DeviceCommandResult {
        val inputText = args.str("text", "")
        if (inputText.isEmpty() || inputText.length > 512) {
            return DeviceCommandResult(false, 1, "输入文本为空或超过 512 字符", false, "ACCESSIBILITY_ACTION_INVALID")
        }
        if (AccessibilityAutomationPolicy.containsSensitiveText(inputText)) {
            return DeviceCommandResult(false, 1, "输入内容触发敏感信息保护，已拒绝", false, "ACCESSIBILITY_SENSITIVE_WINDOW")
        }

        val ref = args.optInt("ref", -1)
        val viewId = args.str("viewId", "").trim()
        var target: AccessibilityNodeInfo? = when {
            viewId.isNotEmpty() -> findNodeByViewId(root, viewId)
            ref > 0 -> {
                val hit = lastSnapshot.get()?.takeIf { it.packageName == pkg }?.elements?.firstOrNull { it.ref == ref }
                if (hit != null && hit.viewId.isNotEmpty()) findNodeByViewId(root, hit.viewId)
                else {
                    if (hit != null) {
                        dispatchTapGesture(hit.cx.toFloat(), hit.cy.toFloat(), 50L)
                        SystemClock.sleep(100L)
                    }
                    service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                }
            }
            else -> root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditable(root)
        }

        if (target == null) {
            return DeviceCommandResult(false, 1, "未找到可用的输入框控件", false, "ACCESSIBILITY_NODE_NOT_FOUND")
        }
        if (target.isPassword || AccessibilityAutomationPolicy.containsSensitiveText(target.viewIdResourceName)) {
            return DeviceCommandResult(false, 1, "密码输入框禁止自动填充", false, "ACCESSIBILITY_SENSITIVE_WINDOW")
        }
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, inputText)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        return DeviceCommandResult(
            ok,
            if (ok) 0 else 1,
            if (ok) "已输入文本（${inputText.length} 字）" else "输入框拒绝写入文本",
            false,
            if (ok) null else "ACCESSIBILITY_ACTION_REJECTED",
        )
    }

    private fun performSwipe(args: JSONObject, action: String): DeviceCommandResult {
        val metrics = service.resources.displayMetrics
        val w = metrics.widthPixels.toFloat().coerceAtLeast(320f)
        val h = metrics.heightPixels.toFloat().coerceAtLeast(480f)
        val dir = args.str("direction", "").trim().lowercase().ifEmpty {
            if (action == "scroll_backward") "down" else "up"
        }
        val (x1, y1, x2, y2) = when (dir) {
            "down" -> listOf(w * 0.5f, h * 0.30f, w * 0.5f, h * 0.78f)
            "left" -> listOf(w * 0.82f, h * 0.5f, w * 0.18f, h * 0.5f)
            "right" -> listOf(w * 0.18f, h * 0.5f, w * 0.82f, h * 0.5f)
            else -> listOf(w * 0.5f, h * 0.76f, w * 0.5f, h * 0.26f)
        }
        val duration = args.optLong("durationMs", 220L).coerceIn(100L, 800L)
        val ok = dispatchSwipeGesture(x1, y1, x2, y2, duration)
        return DeviceCommandResult(
            ok,
            if (ok) 0 else 1,
            if (ok) "已向 $dir 滑动屏幕" else "滑动手势被系统拒绝",
            false,
            if (ok) null else "GESTURE_SWIPE_FAILED",
        )
    }

    private fun dispatchTapGesture(x: Float, y: Float, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return dispatchGestureSync(GestureDescription.Builder().addStroke(stroke).build())
    }

    private fun dispatchSwipeGesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return dispatchGestureSync(GestureDescription.Builder().addStroke(stroke).build())
    }

    private fun dispatchGestureSync(gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        val dispatched = runCatching { service.dispatchGesture(gesture, callback, mainHandler) }.getOrDefault(false)
        if (!dispatched) return false
        return runCatching { latch.await(1500L, TimeUnit.MILLISECONDS) && completed.get() }.getOrDefault(false)
    }

    private fun collectRawNodes(root: AccessibilityNodeInfo): List<WhaleUiTreeCompactor.RawNode> {
        val out = ArrayList<WhaleUiTreeCompactor.RawNode>(128)
        fun walk(node: AccessibilityNodeInfo?, parentIdx: Int, depth: Int) {
            if (node == null || depth > MAX_WALK_DEPTH || out.size >= MAX_RAW_NODES) return
            val myIdx = out.size
            val rect = Rect().also(node::getBoundsInScreen)
            val editable = node.isEditable
            out.add(
                WhaleUiTreeCompactor.RawNode(
                    index = myIdx,
                    parentIndex = parentIdx,
                    className = node.className?.toString()?.substringAfterLast('.').orEmpty(),
                    viewId = node.viewIdResourceName.orEmpty(),
                    text = if (editable) "" else service.safeText(node.text),
                    contentDesc = if (editable) "" else service.safeText(node.contentDescription),
                    hint = if (editable) "" else service.safeText(node.hintText),
                    clickable = node.isClickable,
                    editable = editable,
                    scrollable = node.isScrollable,
                    visible = node.isVisibleToUser,
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom,
                ),
            )
            for (i in 0 until node.childCount) {
                if (out.size >= MAX_RAW_NODES) break
                walk(node.getChild(i), myIdx, depth + 1)
            }
        }
        walk(root, -1, 0)
        return out
    }

    private fun findNodeByViewId(root: AccessibilityNodeInfo, targetId: String): AccessibilityNodeInfo? {
        val direct = runCatching { root.findAccessibilityNodeInfosByViewId(targetId) }.getOrNull().orEmpty()
        direct.firstOrNull { it.isVisibleToUser }?.let { return it }
        // Support short suffix id (e.g. "btn_search" matching "com.demo:id/btn_search")
        val suffix = "/$targetId"
        var found: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || found != null || depth > MAX_WALK_DEPTH) return
            val id = node.viewIdResourceName.orEmpty()
            if (node.isVisibleToUser && (id == targetId || id.endsWith(suffix))) {
                found = node
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return found
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val needle = query.trim()
        var exact: AccessibilityNodeInfo? = null
        var fuzzy: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || exact != null || depth > MAX_WALK_DEPTH) return
            if (node.isVisibleToUser && !node.isPassword) {
                val t = node.text?.toString()?.trim().orEmpty()
                val d = node.contentDescription?.toString()?.trim().orEmpty()
                if (t.equals(needle, ignoreCase = true) || d.equals(needle, ignoreCase = true)) {
                    exact = node
                    return
                }
                if (fuzzy == null && (t.contains(needle, ignoreCase = true) || d.contains(needle, ignoreCase = true))) {
                    fuzzy = node
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return exact ?: fuzzy
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || found != null || depth > MAX_WALK_DEPTH) return
            if (node.isVisibleToUser && node.isEditable && !node.isPassword) {
                found = node
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return found
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops <= 4) {
            if (cur.isClickable) return cur
            cur = cur.parent
            hops += 1
        }
        return null
    }

    private fun JSONObject.str(key: String, fallback: String = ""): String =
        if (!has(key) || isNull(key)) fallback else optString(key, fallback)

    companion object {
        private const val MAX_WALK_DEPTH = 24
        private const val MAX_RAW_NODES = 320
        private const val MAX_COMPACT_ELEMENTS = 80
        private const val MAX_BATCH_STEPS = 8
        private const val DEFAULT_STEP_SETTLE_MS = 120L
    }
}
