package io.deepseekharness.mobile.companion

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.deepseekharness.mobile.accessibility.DeepSeekAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 教它做一遍（Record & Replay）确定性手机自动化宏引擎 + AI 自愈协议。
 *
 * 1. 录制阶段：挂载在无障碍 CLICK 事件上，自动过滤密码框与本应用，记录三层抗改版锚点
 *    （viewIdResourceName → 语义文案 text/contentDesc → 屏幕相对比例 rx/ry），
 *    并对支付/转账/删除/发送等高危按钮自动打上 requireConfirm 标记；
 * 2. 回放阶段：全程 0 Token 确定性执行；遇到高危步骤暂停求确认；遇到弹窗/改版未命中时
 *    返回 needs_ai_heal 交给大模型单步视觉自愈。
 */
internal object WhaleMacroEngine {
    private const val PREFS = "whale_macros_v1"
    private const val KEY_MACROS = "macros_json"
    private const val MAX_MACROS = 24
    private const val MAX_STEPS = 30

    private val HIGH_RISK_REGEX = Regex("支付|付款|转账|确认下单|立即购买|删除|注销|清空|发送|确认提交")

    private val recordingFlag = AtomicBoolean(false)
    @Volatile private var recordingName: String = ""
    private val recordingSteps = ArrayList<MacroStep>()
    private var lastRecordAtMs = 0L

    data class MacroStep(
        val pkg: String,
        val resourceId: String,
        val text: String,
        val contentDesc: String,
        val rx: Float,
        val ry: Float,
        val delayMs: Long = 900L,
        val requireConfirm: Boolean = false,
    ) {
        fun label(): String = text.ifBlank { contentDesc.ifBlank { resourceId.substringAfterLast('/').ifBlank { "坐标(${(rx * 100).toInt()}%,${(ry * 100).toInt()}%)" } } }

        fun json(): JSONObject = JSONObject()
            .put("pkg", pkg)
            .put("resourceId", resourceId)
            .put("text", text)
            .put("contentDesc", contentDesc)
            .put("rx", rx.toDouble())
            .put("ry", ry.toDouble())
            .put("delayMs", delayMs)
            .put("requireConfirm", requireConfirm)

        companion object {
            fun fromJson(o: JSONObject): MacroStep = MacroStep(
                pkg = o.optString("pkg", ""),
                resourceId = o.optString("resourceId", ""),
                text = o.optString("text", ""),
                contentDesc = o.optString("contentDesc", ""),
                rx = o.optDouble("rx", 0.5).toFloat().coerceIn(0.01f, 0.99f),
                ry = o.optDouble("ry", 0.5).toFloat().coerceIn(0.01f, 0.99f),
                delayMs = o.optLong("delayMs", 900L).coerceIn(250L, 6000L),
                requireConfirm = o.optBoolean("requireConfirm", false),
            )
        }
    }

    data class PhoneMacro(
        val id: String,
        val name: String,
        val entryPkg: String,
        val steps: List<MacroStep>,
        val updatedAt: Long,
        val runCount: Int = 0,
    ) {
        fun json(): JSONObject {
            val arr = JSONArray()
            steps.forEach { arr.put(it.json()) }
            return JSONObject()
                .put("id", id)
                .put("name", name)
                .put("entryPkg", entryPkg)
                .put("stepCount", steps.size)
                .put("steps", arr)
                .put("updatedAt", updatedAt)
                .put("runCount", runCount)
        }

        companion object {
            fun fromJson(o: JSONObject): PhoneMacro? {
                val id = o.optString("id").trim()
                val name = o.optString("name").trim()
                val arr = o.optJSONArray("steps") ?: return null
                if (id.isEmpty() || name.isEmpty() || arr.length() == 0) return null
                val list = ArrayList<MacroStep>(arr.length())
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let(MacroStep::fromJson)?.let(list::add)
                }
                if (list.isEmpty()) return null
                return PhoneMacro(
                    id = id,
                    name = name,
                    entryPkg = o.optString("entryPkg", list.first().pkg),
                    steps = list,
                    updatedAt = o.optLong("updatedAt", 0L),
                    runCount = o.optInt("runCount", 0),
                )
            }
        }
    }

    fun isRecording(): Boolean = recordingFlag.get()

    @Synchronized
    fun startRecording(name: String): JSONObject {
        val clean = name.trim().take(36).ifEmpty { "自定义动作" }
        recordingSteps.clear()
        recordingName = clean
        lastRecordAtMs = System.currentTimeMillis()
        recordingFlag.set(true)
        return JSONObject()
            .put("ok", true)
            .put("recording", true)
            .put("name", clean)
            .put("hint", "已开始录制「$clean」。请直接手动操作一遍目标 App，完成后告诉我「停止录制」即可保存为 0 Token 复用宏。")
    }

    @Synchronized
    fun onAccessibilityClick(context: Context, event: AccessibilityEvent) {
        if (!recordingFlag.get()) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == context.packageName || pkg == "com.android.systemui") return
        val src: AccessibilityNodeInfo = event.source ?: return
        if (src.isPassword) return
        if (recordingSteps.size >= MAX_STEPS) return

        val rect = Rect()
        src.getBoundsInScreen(rect)
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1).toFloat()
        val h = dm.heightPixels.coerceAtLeast(1).toFloat()
        val rx = (rect.centerX() / w).coerceIn(0.02f, 0.98f)
        val ry = (rect.centerY() / h).coerceIn(0.02f, 0.98f)
        val resId = src.viewIdResourceName?.trim().orEmpty().take(120)
        val txt = src.text?.toString()?.trim().orEmpty().take(48)
        val desc = src.contentDescription?.toString()?.trim().orEmpty().take(48)
        val now = System.currentTimeMillis()
        val delta = (now - lastRecordAtMs).coerceIn(450L, 4500L)
        lastRecordAtMs = now
        val risky = HIGH_RISK_REGEX.containsMatchIn("$txt $desc")

        recordingSteps.add(
            MacroStep(
                pkg = pkg,
                resourceId = resId,
                text = txt,
                contentDesc = desc,
                rx = rx,
                ry = ry,
                delayMs = delta,
                requireConfirm = risky,
            ),
        )
    }

    @Synchronized
    fun stopRecording(context: Context): JSONObject {
        if (!recordingFlag.getAndSet(false)) {
            return JSONObject().put("ok", false).put("error", "当前没有正在进行的录制")
        }
        val stepsCopy = recordingSteps.toList()
        val name = recordingName.ifBlank { "自定义动作" }
        recordingSteps.clear()
        if (stepsCopy.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "未捕获到有效点击步骤（请确认无障碍服务已开启并在目标 App 中操作）")
        }
        val macro = PhoneMacro(
            id = "mac-${UUID.randomUUID().toString().take(8)}",
            name = name,
            entryPkg = stepsCopy.first().pkg,
            steps = stepsCopy,
            updatedAt = System.currentTimeMillis(),
            runCount = 0,
        )
        val all = list(context).toMutableList()
        all.removeAll { it.name.equals(name, ignoreCase = true) }
        all.add(0, macro)
        saveAll(context, all)
        return JSONObject().put("ok", true).put("macro", macro.json())
    }

    @Synchronized
    fun list(context: Context): List<PhoneMacro> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MACROS, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<PhoneMacro>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let(PhoneMacro::fromJson)?.let(out::add)
        }
        return out
    }

    @Synchronized
    private fun saveAll(context: Context, macros: List<PhoneMacro>) {
        val arr = JSONArray()
        macros.take(MAX_MACROS).forEach { arr.put(it.json()) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MACROS, arr.toString()).apply()
    }

    @Synchronized
    fun delete(context: Context, idOrName: String): Boolean {
        val key = idOrName.trim().lowercase()
        val all = list(context).toMutableList()
        val removed = all.removeAll { it.id.lowercase() == key || it.name.lowercase() == key }
        if (removed) saveAll(context, all)
        return removed
    }

    /** 0 Token 确定性回放录制宏；遇到高危步骤暂停求确认，遇到未命中控件返回 needs_ai_heal 供大模型单步视觉自愈。 */
    fun replay(context: Context, idOrName: String, startIndex: Int = 0, confirmedHighRisk: Boolean = false): JSONObject {
        val key = idOrName.trim().lowercase()
        val all = list(context).toMutableList()
        val mIdx = all.indexOfFirst { it.id.lowercase() == key || it.name.lowercase().contains(key) }
        if (mIdx < 0) return JSONObject().put("ok", false).put("error", "未找到名为「$idOrName」的录制宏")
        val macro = all[mIdx]

        // 首次从第 0 步回放时先拉起入口应用
        if (startIndex == 0 && macro.entryPkg.isNotBlank()) {
            runCatching {
                val launch = context.packageManager.getLaunchIntentForPackage(macro.entryPkg)
                if (launch != null) {
                    launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    Thread.sleep(1_300L)
                }
            }
        }

        val completedLabels = ArrayList<String>()
        for (i in startIndex.coerceAtLeast(0) until macro.steps.size) {
            val step = macro.steps[i]
            if (step.requireConfirm && !confirmedHighRisk) {
                return JSONObject()
                    .put("ok", false)
                    .put("status", "needs_confirmation")
                    .put("macroId", macro.id)
                    .put("stepIndex", i)
                    .put("stepLabel", step.label())
                    .put("completedCount", completedLabels.size)
                    .put("hint", "第 ${i + 1} 步「${step.label()}」涉及高危敏感操作，已安全暂停。请向用户确认后，再以 startIndex=$i, confirmedHighRisk=true 继续。")
            }
            val ok = DeepSeekAccessibilityService.replayMacroStep(
                resourceId = step.resourceId,
                text = step.text,
                contentDesc = step.contentDesc,
                rx = step.rx,
                ry = step.ry,
            )
            if (!ok) {
                return JSONObject()
                    .put("ok", false)
                    .put("status", "needs_ai_heal")
                    .put("macroId", macro.id)
                    .put("failedStepIndex", i)
                    .put("failedStep", step.json())
                    .put("remainingSteps", macro.steps.size - i)
                    .put("completedCount", completedLabels.size)
                    .put("hint", "第 ${i + 1} 步「${step.label()}」未命中（可能遇到广告弹窗或改版）。请截屏执行单步视觉自愈后，再以 startIndex=${i + 1} 续跑剩余步骤。")
            }
            completedLabels.add(step.label())
            Thread.sleep(step.delayMs.coerceIn(350L, 3_000L))
        }

        all[mIdx] = macro.copy(runCount = macro.runCount + 1, updatedAt = System.currentTimeMillis())
        saveAll(context, all)
        return JSONObject()
            .put("ok", true)
            .put("status", "completed")
            .put("macroId", macro.id)
            .put("name", macro.name)
            .put("executedSteps", completedLabels.size)
            .put("tokenCost", 0)
            .put("summary", "已 0 Token 完成「${macro.name}」全部 ${completedLabels.size} 步操作。")
    }
}
