package io.deepseekharness.mobile.companion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import io.deepseekharness.mobile.accessibility.AccessibilityAutomationStore
import io.deepseekharness.mobile.accessibility.DeepSeekAccessibilityService
import io.deepseekharness.mobile.shizuku.DeviceCommandResult
import io.deepseekharness.mobile.shizuku.DeviceShellAccess
import io.deepseekharness.mobile.virtualscreen.VirtualScreenCommands
import io.deepseekharness.mobile.virtualscreen.VirtualScreenService
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * 副屏与无障碍一键诊断、自愈与智能控制增强引擎：
 * 1. 统一检测 Shizuku / 无障碍（含僵尸态 enabled_disconnected）/ 副屏三路健康状态；
 * 2. Shizuku 就绪时，可一键免进系统设置自动启用或重连无障碍服务、同步放行空默认白名单并开启设备 Shell；
 * 3. 支持按应用中文名自动解析包名启动副屏，以及按按钮文字自动定位并点击副屏控件。
 */
internal object WhaleAutoHealBridge {
    private fun serviceFlatName(context: Context): String =
        ComponentName(context.packageName, DeepSeekAccessibilityService::class.java.name).flattenToString()

    private fun isSystemAccessibilityEnabled(context: Context): Boolean {
        val enabled = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        if (enabled.isBlank()) return false
        val full = serviceFlatName(context)
        val short = "${context.packageName}/.accessibility.DeepSeekAccessibilityService"
        return enabled.split(':').any { it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true) }
    }

    fun isShizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun runShizukuShell(command: String, timeoutSec: Long = 8L): Pair<Boolean, String> {
        if (!isShizukuReady()) return false to "Shizuku 未运行或未授权"
        return runCatching {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            m.isAccessible = true
            val proc = m.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val ok = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!ok) {
                proc.destroyForcibly()
                return false to "Shizuku 命令超时"
            }
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            val err = proc.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            (proc.exitValue() == 0) to (if (out.isNotEmpty()) out else err)
        }.getOrElse { e ->
            false to (e.message ?: e.javaClass.simpleName)
        }
    }

    fun health(context: Context): JSONObject {
        val app = context.applicationContext
        val shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val shizukuAuth = shizukuRunning && runCatching {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val shellEnabled = DeviceShellAccess.enabled(app)

        val sysA11y = isSystemAccessibilityEnabled(app)
        val a11yConnected = DeepSeekAccessibilityService.current() != null
        val a11yZombie = sysA11y && !a11yConnected
        val whitelistOn = AccessibilityAutomationStore.whitelistEnabled(app)
        val whitelistCount = AccessibilityAutomationStore.allowedPackages(app).size

        val vsService = VirtualScreenService.current
        val vsState = vsService?.state() ?: JSONObject()
        val vsRunning = vsService != null && vsState.optString("sessionId").isNotBlank()

        return JSONObject()
            .put("ok", true)
            .put("shizuku", JSONObject()
                .put("running", shizukuRunning)
                .put("authorized", shizukuAuth)
                .put("shellEnabled", shellEnabled)
                .put("ready", shizukuAuth && shellEnabled))
            .put("accessibility", JSONObject()
                .put("systemEnabled", sysA11y)
                .put("connected", a11yConnected)
                .put("zombie", a11yZombie)
                .put("whitelistEnabled", whitelistOn)
                .put("whitelistCount", whitelistCount)
                .put("ready", a11yConnected))
            .put("virtualScreen", JSONObject()
                .put("running", vsRunning)
                .put("sessionId", vsState.optString("sessionId"))
                .put("component", vsState.optString("component"))
                .put("ready", shizukuAuth && shellEnabled))
    }

    /** 一键自愈：自动开启设备 Shell、通过 Shizuku 重启/激活无障碍僵尸连接，并可选放行空默认白名单。 */
    fun autoHeal(context: Context, relaxWhitelist: Boolean = true): JSONObject {
        val app = context.applicationContext
        val actions = JSONArray()
        val shizukuOk = isShizukuReady()

        if (relaxWhitelist && AccessibilityAutomationStore.whitelistEnabled(app)) {
            val allowed = AccessibilityAutomationStore.allowedPackages(app)
            if (allowed.isEmpty()) {
                val relaxed = runCatching {
                    AccessibilityAutomationStore.setAllowedPackages(app, allowed.toList(), null, whitelistEnabled = false)
                    true
                }.getOrDefault(false)
                if (relaxed) {
                    actions.put("检测到无障碍白名单为空且处于拦截态，已自动关闭限制（支付/密码等敏感页仍保持系统级保护）")
                }
            }
        }

        val needA11yRevive = DeepSeekAccessibilityService.current() == null
        if (needA11yRevive && shizukuOk) {
            val comp = serviceFlatName(app)
            val shortComp = "${app.packageName}/.accessibility.DeepSeekAccessibilityService"
            runShizukuShell("pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS", 5L)
            val (_, currentList) = runShizukuShell("settings get secure enabled_accessibility_services", 5L)
            val cleaned = currentList
                .takeUnless { it == "null" }
                .orEmpty()
                .split(':')
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals(comp, ignoreCase = true) && !it.equals(shortComp, ignoreCase = true) }
            val withoutUs = cleaned.joinToString(":")
            val withUs = (cleaned + comp).joinToString(":")

            // 先摘除再挂回，强制 Android SystemServer 重新绑定僵尸无障碍服务
            runShizukuShell("settings put secure enabled_accessibility_services '$withoutUs'", 5L)
            runCatching { Thread.sleep(220L) }
            val (putOk, _) = runShizukuShell(
                "settings put secure enabled_accessibility_services '$withUs' && settings put secure accessibility_enabled 1",
                5L,
            )
            if (putOk) {
                var bound = false
                for (i in 0 until 8) {
                    if (DeepSeekAccessibilityService.current() != null) {
                        bound = true
                        break
                    }
                    runCatching { Thread.sleep(180L) }
                }
                actions.put(if (bound) "已通过 Shizuku 自动激活并重连无障碍服务" else "已写入系统无障碍配置，等待系统绑定生效")
            }
        }

        val latest = health(app)
        val nextStep = when {
            !shizukuOk && DeepSeekAccessibilityService.current() == null ->
                "Shizuku 尚未运行：请先在 Shizuku 中启动无线调试，或手动在系统无障碍设置中开启「鲸鱼助手」。"
            !shizukuOk ->
                "无障碍已就绪；如需使用后台副屏，请先启动 Shizuku 并授权。"
            DeepSeekAccessibilityService.current() == null ->
                "无障碍服务暂未响应，请打开系统无障碍设置确认未被省电策略限制。"
            else ->
                "Shizuku、无障碍与副屏已全部就绪，可以直接使用。"
        }

        return JSONObject()
            .put("ok", true)
            .put("actions", actions)
            .put("nextStep", nextStep)
            .put("health", latest)
    }

    /** 支持按应用中文名/关键词或包名自动解析可启动包名（例如 "小红书" -> com.xingin.xhs）。 */
    fun resolveAppPackage(context: Context, query: String): String {
        val raw = query.trim()
        if (raw.isEmpty()) return raw
        if (raw.contains('.') && !raw.contains(' ')) return raw
        val pm = context.applicationContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        val q = raw.lowercase()
        val exact = apps.firstOrNull {
            it.loadLabel(pm)?.toString()?.trim()?.lowercase() == q
        }
        val partial = exact ?: apps.firstOrNull {
            val label = it.loadLabel(pm)?.toString()?.trim()?.lowercase().orEmpty()
            label.contains(q) || it.activityInfo.packageName.lowercase().contains(q)
        }
        return partial?.activityInfo?.packageName ?: raw
    }

    /** 副屏增强：支持直接传按钮文字 text，自动读副屏控件树找坐标并点击。 */
    fun smartVirtualScreenTapByText(sessionId: String, targetText: String): DeviceCommandResult {
        val clean = targetText.trim()
        if (clean.isEmpty()) return DeviceCommandResult(false, 1, "缺少目标文字 text", false, "VIRTUAL_SCREEN_INVALID_ARGUMENT")
        val treeRes = VirtualScreenCommands.execute(
            "virtualScreenAction",
            JSONObject().put("sessionId", sessionId).put("action", "tree").toString(),
        )
        if (!treeRes.ok) return treeRes
        val bounds = findCenterInTreeJson(treeRes.text, clean)
            ?: return DeviceCommandResult(false, 1, "副屏当前界面未找到包含「$clean」的控件", false, "VIRTUAL_SCREEN_NODE_NOT_FOUND")
        val tapReq = JSONObject()
            .put("sessionId", sessionId)
            .put("action", "tap")
            .put("x", bounds.first)
            .put("y", bounds.second)
        return VirtualScreenCommands.execute("virtualScreenAction", tapReq.toString())
    }

    private fun findCenterInTreeJson(rawTree: String, target: String): Pair<Int, Int>? {
        val low = target.lowercase()
        val root = runCatching { JSONObject(rawTree) }.getOrNull() ?: return null
        val nodes = root.optJSONArray("nodes") ?: root.optJSONObject("data")?.optJSONArray("nodes")
        if (nodes != null) {
            for (i in 0 until nodes.length()) {
                val n = nodes.optJSONObject(i) ?: continue
                val label = "${n.optString("text")} ${n.optString("contentDescription")} ${n.optString("desc")}".lowercase()
                if (label.contains(low)) {
                    val cx = n.optInt("cx", n.optInt("x", -1))
                    val cy = n.optInt("cy", n.optInt("y", -1))
                    if (cx >= 0 && cy >= 0) return cx to cy
                    val b = n.optJSONObject("bounds")
                    if (b != null) {
                        val x = (b.optInt("left") + b.optInt("right")) / 2
                        val y = (b.optInt("top") + b.optInt("bottom")) / 2
                        if (x >= 0 && y >= 0) return x to y
                    }
                }
            }
        }
        // 兼容文本格式坐标 "[left,top][right,bottom]"
        val regex = Regex("""${Regex.escape(target)}[^\n]{0,120}\[(\d+),(\d+)\]\[(\d+),(\d+)\]""", RegexOption.IGNORE_CASE)
        val m = regex.find(rawTree) ?: return null
        val cx = (m.groupValues[1].toInt() + m.groupValues[3].toInt()) / 2
        val cy = (m.groupValues[2].toInt() + m.groupValues[4].toInt()) / 2
        return cx to cy
    }
}
