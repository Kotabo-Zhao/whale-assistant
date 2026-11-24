package io.deepseekharness.mobile.companion

import java.util.Locale

/**
 * 统一执行模式与后台 Worker 生命周期路由器（主对话 + 桌宠共用）。
 *
 * 职责与 [WhaleModelRouter] 彻底解耦：
 * - [WhaleModelRouter] 只负责模型档位选型（FAST / BALANCED / DEEP）；
 * - [WhaleExecutionRouter] 结合 [WhaleNativeWorkerPool] 实时活跃任务快照，决定本轮请求应：
 *   1. [Mode.WORKER_STATUS]   — 零延迟直查活跃/近期 Worker 状态；
 *   2. [Mode.WORKER_CANCEL]   — 精准取消指定活跃 Worker（多任务时安全消歧）；
 *   3. [Mode.WORKER_FOLLOWUP] — 追问透传（deliverFollowup）直接注入运行中 Worker；
 *   4. [Mode.WORKER_CLEAR]    — 清理已完成后台任务与残留状态；
 *   5. [Mode.SPAWN_WORKER]    — 前置直接派发隔离后台 Worker 并立即返回工单回执；
 *   6. [Mode.DIRECT_CHAT]     — 轻量直聊（不触发重工具规划）；
 *   7. [Mode.FOREGROUND_AGENT]— 标准前台流式 Agent 执行。
 */
internal object WhaleExecutionRouter {

    enum class Mode {
        WORKER_STATUS,
        WORKER_CANCEL,
        WORKER_FOLLOWUP,
        WORKER_CLEAR,
        SPAWN_WORKER,
        DIRECT_CHAT,
        FOREGROUND_AGENT,
    }

    data class WorkerSnapshot(
        val id: String,
        val title: String,
        val state: String,
        val stageLabel: String,
        val currentStep: String,
        val elapsedSec: Long,
        val livePreview: String = "",
        val attention: String = "",
        val isActive: Boolean = state == "running" || state == "queued",
    )

    data class Decision(
        val mode: Mode,
        val reason: String,
        val targetWorker: WorkerSnapshot? = null,
        val ambiguousWorkers: List<WorkerSnapshot> = emptyList(),
        val workerTitle: String = "",
        val payload: String = "",
    )

    private val workerNounHints = listOf(
        "后台任务", "后台工单", "后台工作", "后台线程", "worker", "子代理", "子任务", "后台那个", "异步任务", "徽标", "红点", "角标",
    )
    private val statusHints = listOf(
        "怎么样了", "进度", "进展", "状态", "跑完", "完成了吗", "做完了吗", "还在跑", "有哪些", "列表", "查看", "查一下", "status", "progress",
    )
    private val cancelHints = listOf(
        "取消", "停止", "停掉", "终止", "关掉", "别跑了", "不用做了", "cancel", "stop", "abort", "kill",
    )
    private val clearHints = listOf(
        "清理", "清除", "清空", "清掉", "归零", "消掉", "删掉历史", "clear",
    )
    private val followupHints = listOf(
        "补充", "追加", "顺便", "改成", "加上", "再查", "告诉它", "跟它说", "发给后台", "转告", "steer", "followup",
    )
    private val explicitSpawnHints = listOf(
        "在后台", "后台帮我", "后台跑", "后台执行", "后台查", "后台调研", "后台整理", "后台处理",
        "开个worker", "开个 worker", "起个worker", "起个 worker", "派个worker", "派个 worker",
        "异步帮我", "异步执行", "异步调研", "并行帮我", "你后台慢慢", "run in background", "spawn worker",
    )
    private val foregroundActionHints = listOf(
        "打开", "点击", "截屏", "截图", "发微信", "发消息", "写文件", "保存", "记住", "忘记",
        "提醒", "闹钟", "日程", "代码", "脚本", "python", "http://", "https://", "搜索", "查一下",
        "翻译", "分析", "总结", "对比", "重构", "修改", "修复", "计算", "运行", "执行", "安装",
    )

    fun route(
        userMessage: String,
        activeWorkers: List<WorkerSnapshot>,
        recentFinished: List<WorkerSnapshot> = emptyList(),
        hasImages: Boolean = false,
    ): Decision {
        val raw = userMessage.trim()
        if (raw.isEmpty()) return Decision(Mode.DIRECT_CHAT, "empty")
        val lower = raw.lowercase(Locale.US)
        val mentionsWorker = workerNounHints.any { it in lower }
        val allKnown = activeWorkers + recentFinished
        val matchedActive = matchWorkers(raw, lower, activeWorkers)

        // 1. 明确清理已完成后台任务
        if (mentionsWorker && clearHints.any { it in lower } && cancelHints.none { it in lower }) {
            return Decision(Mode.WORKER_CLEAR, "explicit_worker_clear")
        }

        // 2. 取消活跃 Worker（明确提到后台/worker，或明确点名活跃任务标题）
        val asksCancel = cancelHints.any { it in lower }
        if (asksCancel && (mentionsWorker || matchedActive.isNotEmpty() || (activeWorkers.size == 1 && ("刚才那个" in raw || "那个任务" in raw)))) {
            return resolveTarget(
                mode = Mode.WORKER_CANCEL,
                reason = "worker_cancel",
                matched = matchedActive,
                activeWorkers = activeWorkers,
                payload = raw,
            )
        }

        // 3. 追问/转向透传给活跃 Worker
        val asksFollowup = followupHints.any { it in lower }
        if (activeWorkers.isNotEmpty() && asksFollowup && (mentionsWorker || matchedActive.isNotEmpty() || "刚才那个" in raw || "那个任务" in raw)) {
            val cleaned = stripRoutingPrefix(raw)
            return resolveTarget(
                mode = Mode.WORKER_FOLLOWUP,
                reason = "worker_followup",
                matched = matchedActive,
                activeWorkers = activeWorkers,
                payload = cleaned.ifBlank { raw },
            )
        }

        // 4. 查询 Worker 实时状态/进度
        val asksStatus = statusHints.any { it in lower }
        if (asksStatus && (mentionsWorker || matchedActive.isNotEmpty() || (allKnown.isNotEmpty() && ("刚才那个" in raw || "那个任务" in raw)))) {
            val target = when {
                matchedActive.size == 1 -> matchedActive.single()
                activeWorkers.size == 1 -> activeWorkers.single()
                else -> null
            }
            return Decision(
                mode = Mode.WORKER_STATUS,
                reason = "worker_status_query",
                targetWorker = target,
                ambiguousWorkers = if (target == null) activeWorkers else emptyList(),
            )
        }

        // 5. 显式前置派发独立后台 Worker（无需等待 12s 超时降级）
        if (!hasImages && explicitSpawnHints.any { it in lower }) {
            val taskBody = stripSpawnPrefix(raw).ifBlank { raw }
            val title = buildWorkerTitle(taskBody)
            return Decision(
                mode = Mode.SPAWN_WORKER,
                reason = "explicit_background_spawn",
                workerTitle = title,
                payload = taskBody,
            )
        }

        // 6. 轻量直聊 vs 标准前台 Agent
        if (!hasImages && raw.length <= 28 && foregroundActionHints.none { it in lower }) {
            return Decision(Mode.DIRECT_CHAT, "short_conversational_turn")
        }
        return Decision(Mode.FOREGROUND_AGENT, "standard_foreground_agent")
    }

    /** 格式化实时的 Worker 状态汇报卡片（零模型延迟直出）。 */
    fun formatStatusReply(
        activeWorkers: List<WorkerSnapshot>,
        recentFinished: List<WorkerSnapshot>,
        target: WorkerSnapshot? = null,
    ): String {
        if (target != null) {
            return buildString {
                append("后台任务「").append(target.title).append("」当前状态：**").append(target.stageLabel).append("**\n")
                append("- 当前步骤：").append(target.currentStep).append("（已运行 ").append(target.elapsedSec).append("s）\n")
                append("- 任务 ID：`").append(target.id).append("`")
                if (target.attention.isNotBlank()) {
                    append("\n- 等待你确认：").append(target.attention)
                }
                if (target.livePreview.isNotBlank()) {
                    append("\n- 最新进展：").append(target.livePreview.take(220))
                }
            }
        }
        if (activeWorkers.isEmpty() && recentFinished.isEmpty()) {
            return "当前没有运行中或近期完成的后台 Worker 任务。"
        }
        return buildString {
            if (activeWorkers.isNotEmpty()) {
                append("当前有 **").append(activeWorkers.size).append("** 个活跃后台任务：\n")
                activeWorkers.forEachIndexed { idx, w ->
                    append(idx + 1).append(". **").append(w.title).append("**（").append(w.stageLabel)
                        .append(" · ").append(w.currentStep).append(" · ").append(w.elapsedSec).append("s，`")
                        .append(w.id).append("`）\n")
                }
            } else {
                append("当前没有正在运行的后台任务。\n")
            }
            if (recentFinished.isNotEmpty()) {
                append("\n近期已结束：\n")
                recentFinished.take(4).forEach { w ->
                    append("- **").append(w.title).append("**（").append(w.stageLabel).append("，`").append(w.id).append("`）")
                    if (w.livePreview.isNotBlank()) {
                        append("：").append(w.livePreview.replace("\n", " ").take(100))
                    }
                    append("\n")
                }
            }
        }.trim()
    }

    private fun resolveTarget(
        mode: Mode,
        reason: String,
        matched: List<WorkerSnapshot>,
        activeWorkers: List<WorkerSnapshot>,
        payload: String,
    ): Decision {
        if (activeWorkers.isEmpty()) {
            return Decision(mode = mode, reason = "${reason}_none_active", payload = payload)
        }
        if (matched.size == 1) {
            return Decision(mode = mode, reason = "${reason}_matched_single", targetWorker = matched.single(), payload = payload)
        }
        if (matched.isEmpty() && activeWorkers.size == 1) {
            return Decision(mode = mode, reason = "${reason}_only_active", targetWorker = activeWorkers.single(), payload = payload)
        }
        val candidates = if (matched.isNotEmpty()) matched else activeWorkers
        return Decision(mode = mode, reason = "${reason}_ambiguous", ambiguousWorkers = candidates, payload = payload)
    }

    private fun matchWorkers(raw: String, lower: String, workers: List<WorkerSnapshot>): List<WorkerSnapshot> {
        if (workers.isEmpty()) return emptyList()
        return workers.filter { w ->
            val idMatch = w.id.length >= 6 && (lower.contains(w.id.lowercase(Locale.US)) || lower.contains(w.id.take(8).lowercase(Locale.US)))
            val cleanTitle = w.title.trim()
            val titleMatch = cleanTitle.length >= 2 && (
                raw.contains(cleanTitle, ignoreCase = true) ||
                    cleanTitle.split(Regex("[\\s·：:，,、]+")).any { token -> token.length >= 2 && raw.contains(token, ignoreCase = true) }
                )
            idMatch || titleMatch
        }
    }

    private fun stripSpawnPrefix(raw: String): String = raw
        .replace(Regex("^(请|帮我|麻烦)?(在后台|后台|异步|并行)?(帮我|替我|去|慢慢)?(开个\\s*worker|起个\\s*worker|派个\\s*worker|跑一下|执行一下|执行|调研|整理|处理)?[：:，,\\s]*", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun stripRoutingPrefix(raw: String): String = raw
        .replace(Regex("^(请|帮我)?(给|向|让|告诉)?(刚才那个|那个)?(后台任务|后台工单|worker|后台那个|任务)(补充一下|补充|追加|转告|顺便)?[：:，,\\s]*", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun buildWorkerTitle(task: String): String {
        val singleLine = task.replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length <= 28) singleLine else singleLine.take(28) + "…"
    }

    /** 前台流式执行期间的并发插话 3 路精准分流：叫停 / 旁路快答 / 实时纠偏。 */
    enum class ConcurrentAction { CANCEL, SIDE_LANE, STEER }

    private val concurrentCancelRegex = Regex(
        "^(算了|停|停下|停止|别做了|不用做了|取消|中止|stop|cancel|abort)[！!。.~～\\s]*$",
        RegexOption.IGNORE_CASE,
    )
    private val steeringVerbHints = listOf(
        "改成", "换成", "加上", "补充", "顺便", "别用", "不要", "改一下", "再加", "重点", "先别", "重新", "调整", "按周", "按天", "用中文", "用英文",
    )
    private val sideLaneQuestionHints = listOf(
        "几点", "星期几", "周几", "天气", "汇率", "翻译", "什么是", "是谁", "怎么读", "啥意思", "什么意思", "帮我算", "等于多少",
    )

    fun routeConcurrent(userMessage: String): ConcurrentAction {
        val raw = userMessage.trim()
        if (raw.isEmpty()) return ConcurrentAction.STEER
        val lower = raw.lowercase(Locale.US)
        if (concurrentCancelRegex.matches(raw)) return ConcurrentAction.CANCEL
        if (steeringVerbHints.any { it in lower }) return ConcurrentAction.STEER
        if (raw.length <= 42 && (sideLaneQuestionHints.any { it in lower } || raw.endsWith("?") || raw.endsWith("？"))) {
            return ConcurrentAction.SIDE_LANE
        }
        return ConcurrentAction.STEER
    }

    /**
     * Super Agent 上行回传与自动回收（Worker -> Router -> Coordinator -> 主对话）：
     * - 绝不直接把原始 Worker 流水或内部 ID 抛给用户；
     * - 仅提炼核心结论（<=400 字）与落盘产物指针，经主助手口吻推送回当前主对话；
     * - 推送后立即自动 GC（清理内存池、磁盘任务台与实况通知），彻底杜绝僵尸任务与残留徽标。
     */
    fun onWorkerFinished(
        context: android.content.Context,
        workerId: String,
        title: String,
        rawReply: String,
        ok: Boolean,
    ) {
        val app = context.applicationContext
        val artifacts = WhaleContextLedger.extractArtifactPaths(listOf("assistant" to rawReply))
        val digest = rawReply.trim().replace(Regex("\\n{3,}"), "\n\n").take(900)
        val coordinatorText = buildString {
            if (ok) {
                append("刚才托付给我的「**").append(title).append("**」已经整理好了：\n\n")
                append(digest.ifBlank { "已完成处理。" })
                if (artifacts.isNotEmpty()) {
                    append("\n\n相关产物：`").append(artifacts.joinToString("`、`")).append("`")
                }
            } else {
                append("刚才在后台处理「**").append(title).append("**」时遇到了一点状况：")
                append(digest.take(240).ifBlank { "执行未能完成" })
                append("。如果你愿意，直接告诉我怎么调整，我马上继续处理。")
            }
        }
        // 1. 通过应用控制总线静默回流到当前主对话（单一会话无感体验）
        WhaleAppControlBus.emit(
            org.json.JSONObject()
                .put("action", "assistant_push")
                .put("source", "coordinator_synthesis")
                .put("title", title)
                .put("reply", coordinatorText),
        )
        // 2. 推送主助手口吻的轻提醒并结束常驻实况进度
        WhaleNativeLiveNotification.update(
            app,
            title = if (ok) "已整理好：$title" else "需要确认：$title",
            subtitle = digest.replace("\n", " ").take(72),
            ongoing = false,
            indeterminate = false,
        )
        // 3. 用完即焚自动 GC：从内存池、磁盘任务台与长任务表回收，避免任何残留红点
        WhaleNativeWorkerPool.remove(workerId)
        runCatching { WorkerDashboardStore(app).forceCancelWorker(workerId) }
        WhaleLongTaskStore.markTerminal(app, workerId, if (ok) "completed" else "failed")
        WhaleAppControlBus.emit(org.json.JSONObject().put("action", "refresh"))
    }
}
