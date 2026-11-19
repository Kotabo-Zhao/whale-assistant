package io.deepseekharness.mobile.companion

/**
 * 轻量直连对话 vs PRoot/SuperAgent 重型运行时分流策略（纯函数，便于 JVM 单测）。
 *
 * 目标：日常聊天、问答、本地记忆/资料检索与秘书事项直接走原生 HTTP 直连通道（秒开、零 PRoot 功耗）；
 * 仅在需要 Linux 工具链、Shell/脚本、跨应用屏控或工坊代码创作时，才按需懒唤醒 PRoot 容器。
 */
internal object DirectChatEscalationPolicy {
    enum class Route {
        /** 走轻量原生直连通道：不启动 PRoot/Node，极速响应且低功耗。 */
        DIRECT_NATIVE,

        /** 需按需唤醒 PRoot/SuperAgent 后台 Worker 处理重型工具链或自动化任务。 */
        ESCALATE_PROOT_WORKER,
    }

    private val heavyToolPattern = Regex(
        "(bash|shell|python|node|npm|pnpm|git|curl|终端|命令行|脚本|编译|写代码|运行代码|" +
            "副屏|无障碍|帮我点|自动打开|操作手机|截屏|截图|" +
            "创建一个.{0,8}(功能|工具|小应用)|功能工坊|批量处理|压缩包|格式转换)",
        RegexOption.IGNORE_CASE,
    )

    fun decide(userText: String, runtimeAlreadyRunning: Boolean = false, forceProot: Boolean = false): Route {
        if (forceProot) return Route.ESCALATE_PROOT_WORKER
        val normalized = userText.trim()
        if (normalized.isEmpty()) return Route.DIRECT_NATIVE
        if (heavyToolPattern.containsMatchIn(normalized)) return Route.ESCALATE_PROOT_WORKER
        // 即使 PRoot 已在运行，简单闲聊/问答仍可走直连轻量通道，减少不必要容器唤醒与上下文开销。
        return if (runtimeAlreadyRunning && normalized.length > 600) {
            Route.ESCALATE_PROOT_WORKER
        } else {
            Route.DIRECT_NATIVE
        }
    }
}
