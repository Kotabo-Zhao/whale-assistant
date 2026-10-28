package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.shizuku.ShizukuState

internal data class CompanionReadinessFacts(
    val overlayPermission: Boolean?, val overlayRunning: Boolean,
    val notificationAccess: Boolean?, val notificationsEnabled: Boolean?,
    val accessibility: String?, val batteryExempt: Boolean?, val shizuku: ShizukuState?,
)

/** A status snapshot is not proof of future background delivery or task success. */
internal object CompanionReadiness {
    fun describe(f: CompanionReadinessFacts): String {
        val overlay = when (f.overlayPermission) { true -> if (f.overlayRunning) "已授权；服务运行中，是否显示受系统限制" else "已授权；悬浮服务未运行"; false -> "未授权"; null -> "状态不可用" }
        val capture = when (f.notificationAccess) { true -> "系统授权已开启，监听连接可能受系统限制"; false -> "未授权"; null -> "状态不可用" }
        val notify = when (f.notificationsEnabled) { true -> "应用通知已允许，具体渠道可能受限"; false -> "应用通知未允许"; null -> "状态不可用" }
        val accessibility = when (f.accessibility) { "connected" -> "服务已连接"; "enabled_disconnected" -> "系统已启用，但服务未连接"; "off" -> "系统未启用"; else -> "状态不可用" }
        val battery = when (f.batteryExempt) { true -> "已排除系统电池优化，厂商仍可能限制后台"; false -> "受系统电池优化限制"; null -> "状态不可用" }
        val shizuku = f.shizuku?.let { state -> when {
            !state.installed -> "未检测到安装"
            !state.running -> "未检测到服务；可能未启动或状态读取超时"
            state.permission != "granted" -> "服务已检测到，助手授权未确认"
            !state.connected -> "已授权，设备服务尚未连接"
            else -> "已授权且设备服务已连接"
        } } ?: "状态未获取，请在主界面检查"
        return "悬浮助手：$overlay\n通知读取：$capture\n提醒通知：$notify\n无障碍：$accessibility\nShizuku：$shizuku\n电池：$battery"
    }
}
