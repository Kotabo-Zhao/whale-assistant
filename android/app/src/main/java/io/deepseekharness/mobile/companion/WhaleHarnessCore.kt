package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 借鉴 DeepSeek Harness 开源工程实践提炼的原生 Kotlin Agent 核心原语集合：
 *
 * 1. [RepeatToolGuard]（源自 `repeat-tool-reminder`）：
 *    递归键排序规范化参数 + 连续重复计数（含失败/被拒调用）+ 分级温和→强诊断提醒（3/5/8 次）+ 10 次熔断。
 * 2. [SpillAndPruneManager]（源自 `spill-policy` + `compaction-tool-result-pruner`）：
 *    超预算工具输出先完整落盘再保留「头部 + [...] + 尾部 + 可恢复路径」，按 Unicode 码点安全切分不截断中文/Emoji。
 * 3. [ImageOffloadPolicy]（源自 `compaction-image-offload`）：
 *    多轮视觉/截屏控屏场景下按时间顺序自动卸载最旧输入图片，把视觉预算留给最新画面。
 * 4. [ToolBatchScheduler] & [ToolCallRecovery]（源自 `core/tools/batch.ts` + `core/session/repair.ts`）：
 *    分段并发（连续只读并行组 + 写操作独占屏障）与异常/中止后缺失 `tool_call_id` 协议补齐。
 * 5. [FsObservationGate]（源自 `fs-observation-policy`）：
 *    文件读后写新鲜度守卫（mtime + size 版本校验，防盲改/覆盖外部改动）。
 * 6. [SessionTodoStore]（源自 `tool-todo`）：
 *    结构化整表任务清单追踪，支持复杂多步任务拆解与进度可视化。
 * 7. [PrefixCacheCompactor]（源自 `compaction-basic/summarizer.ts`）：
 *    保留 System Prompt + Tools + 对话前缀不变、仅在尾部追加压缩指令的 KV 缓存友好型结构化摘要协议。
 */
internal object WhaleHarnessCore {

    // -------------------------------------------------------------------------
    // 1. 分层死循环检测器（repeat-tool-reminder）
    // -------------------------------------------------------------------------

    class RepeatToolGuard(
        private val thresholds: Set<Int> = setOf(3, 5, 8),
        private val hardStopThreshold: Int = 10,
        private val previewCap: Int = 500,
    ) {
        private var lastKey: String? = null
        private var consecutiveCount: Int = 0

        data class Decision(
            val hardBlock: Boolean,
            val reminder: String?,
            val count: Int,
        )

        /** 收到真实用户消息或中途插话（steer）时重置连续链。 */
        fun resetOnUserInput() {
            lastKey = null
            consecutiveCount = 0
        }

        /**
         * 记录一次工具调用（无论成功、失败还是被策略拒绝都应计入），返回是否需要注入提醒或硬熔断。
         */
        fun observe(toolName: String, rawArgs: String): Decision {
            val canonical = canonicalizeJson(rawArgs)
            val key = "$toolName::$canonical"
            if (key == lastKey) {
                consecutiveCount += 1
            } else {
                lastKey = key
                consecutiveCount = 1
            }

            if (consecutiveCount >= hardStopThreshold) {
                return Decision(
                    hardBlock = true,
                    reminder = buildDetailedReminder(toolName, consecutiveCount, previewArgs(canonical, previewCap)),
                    count = consecutiveCount,
                )
            }

            val firstThreshold = thresholds.minOrNull() ?: 3
            val reminder = when {
                consecutiveCount !in thresholds -> null
                consecutiveCount == firstThreshold -> GENTLE_REMINDER
                else -> buildDetailedReminder(toolName, consecutiveCount, previewArgs(canonical, previewCap))
            }
            return Decision(hardBlock = false, reminder = reminder, count = consecutiveCount)
        }

        companion object {
            private const val GENTLE_REMINDER =
                "[系统提醒] 你正在用完全相同的参数连续重复调用同一个工具。请仔细分析上一次返回结果：如果任务尚未完成，请尝试换一种方法、调整参数或先核查现状，不要继续重复相同调用。"

            private fun buildDetailedReminder(toolName: String, count: Int, preview: String): String =
                buildString {
                    appendLine("[系统强警告：检测到连续重复工具调用]")
                    appendLine("- tool: $toolName")
                    appendLine("- consecutive_calls: $count")
                    appendLine("- arguments: $preview")
                    append("连续重复调用没有带来进展。请不要再用完全相同的参数调用该工具；请检查最新结果，切换其他方案、修改参数，或者直接向用户如实汇报当前卡点。")
                }

            private fun previewArgs(canonical: String, cap: Int): String =
                if (canonical.length <= cap) canonical else canonical.take(cap) + "… (+${canonical.length - cap} chars)"

            /** 递归对 JSON Object 的 key 排序，确保字段顺序不同的等价参数生成完全一致的规范串。 */
            internal fun canonicalizeJson(raw: String): String {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return "{}"
                return runCatching {
                    when {
                        trimmed.startsWith("{") -> sortObject(JSONObject(trimmed)).toString()
                        trimmed.startsWith("[") -> sortArray(JSONArray(trimmed)).toString()
                        else -> trimmed
                    }
                }.getOrDefault(trimmed)
            }

            private fun sortObject(obj: JSONObject): JSONObject {
                val out = JSONObject()
                val keys = ArrayList<String>()
                val it = obj.keys()
                while (it.hasNext()) keys.add(it.next())
                keys.sort()
                for (k in keys) {
                    out.put(k, sortAny(obj.opt(k)))
                }
                return out
            }

            private fun sortArray(arr: JSONArray): JSONArray {
                val out = JSONArray()
                for (i in 0 until arr.length()) {
                    out.put(sortAny(arr.opt(i)))
                }
                return out
            }

            private fun sortAny(v: Any?): Any? = when (v) {
                is JSONObject -> sortObject(v)
                is JSONArray -> sortArray(v)
                else -> v
            }
        }
    }

    // -------------------------------------------------------------------------
    // 2. 大输出落盘（Spill）+ 码点安全首尾裁剪（Pruner）
    // -------------------------------------------------------------------------

    object SpillAndPruneManager {
        private const val SPILL_DIR_NAME = "whale-spills"
        private const val MAX_SPILL_FILES = 60
        private const val PRUNE_MARKER = "\n\n[... 已省略中间内容 ...]\n\n"

        private fun spillDir(context: Context): File =
            File(context.applicationContext.filesDir, SPILL_DIR_NAME).apply { mkdirs() }

        /**
         * 按 Unicode 码点安全截取前 N 个码点与后 M 个码点，绝不把代理对（Emoji/生僻汉字）截成乱码。
         */
        internal fun sliceHeadTailCodePoints(text: String, headPoints: Int, tailPoints: Int, marker: String): String {
            val totalPoints = text.codePointCount(0, text.length)
            if (totalPoints <= headPoints + tailPoints + 16) return text
            val headEndIdx = text.offsetByCodePoints(0, headPoints.coerceAtLeast(0))
            val tailStartIdx = text.offsetByCodePoints(text.length, -tailPoints.coerceAtLeast(0))
            if (headEndIdx >= tailStartIdx) return text
            return text.substring(0, headEndIdx) + marker + text.substring(tailStartIdx)
        }

        /**
         * 限制单次工具返回进入上下文的体积：超限时先将全文无损落盘至 `spill/<id>`，
         * 再返回「头部 + 省略提示 + 尾部」，确保末尾报错堆栈完整保留且全文随时可按页回查。
         */
        fun boundToolOutput(
            context: Context,
            toolName: String,
            rawPayload: String,
            maxInlineChars: Int = 6_400,
            headPoints: Int = 3_200,
            tailPoints: Int = 2_400,
        ): String {
            if (rawPayload.length <= maxInlineChars) return rawPayload
            val spillId = saveSpill(context, toolName, rawPayload)
            val omittedApprox = (rawPayload.length - headPoints - tailPoints).coerceAtLeast(1)
            val notice = if (spillId != null) {
                "\n\n[... 输出过长（原长 ${rawPayload.length} 字符，省略约 $omittedApprox 字符）。完整内容已落盘：spill/$spillId ；如需查看中间细节，请调用 whale_read_resource(resource=\"spill/$spillId\", offset=0, limit=2500) ...]\n\n"
            } else {
                "\n\n[... 输出过长（原长 ${rawPayload.length} 字符，省略中间约 $omittedApprox 字符），已保留头部与尾部关键内容 ...]\n\n"
            }
            return sliceHeadTailCodePoints(rawPayload, headPoints, tailPoints, notice)
        }

        /**
         * 零 LLM 成本的历史工具输出首尾裁剪（替代旧版单向 `.take(460)`，保留结尾报错/总结）。
         */
        fun pruneOlderToolResult(
            rawPayload: String,
            thresholdChars: Int = 720,
            headPoints: Int = 320,
            tailPoints: Int = 280,
        ): String {
            if (rawPayload.length <= thresholdChars) return rawPayload
            val marker = "\n…[历史工具输出已首尾精简，原长 ${rawPayload.length} 字符]…\n"
            return sliceHeadTailCodePoints(rawPayload, headPoints, tailPoints, marker)
        }

        private fun saveSpill(context: Context, toolName: String, content: String): String? = runCatching {
            val dir = spillDir(context)
            val safeTool = toolName.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(24)
            val id = "${safeTool}_${UUID.randomUUID().toString().take(8)}"
            File(dir, "$id.txt").writeText(content, Charsets.UTF_8)
            pruneOldSpills(dir)
            id
        }.getOrNull()

        fun readSpill(context: Context, spillId: String): String? = runCatching {
            val safeId = spillId.trim().replace(Regex("[^a-zA-Z0-9_-]"), "")
            if (safeId.isEmpty()) return null
            val file = File(spillDir(context), "$safeId.txt")
            if (file.isFile) file.readText(Charsets.UTF_8) else null
        }.getOrNull()

        private fun pruneOldSpills(dir: File) {
            val files = dir.listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }
            if (files.size > MAX_SPILL_FILES) {
                files.drop(MAX_SPILL_FILES).forEach { runCatching { it.delete() } }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 3. 旧图自动卸载（compaction-image-offload）
    // -------------------------------------------------------------------------

    object ImageOffloadPolicy {
        /**
         * 扫描消息列表中的全部 `image_url` 块，仅保留最新的 [maxRetainedImages] 张图片，
         * 将更早的图片替换为轻量文本占位，防止多轮控屏/发图任务触发视觉 Token 上限。
         * @return 实际卸载的旧图片数量。
         */
        fun offloadOldestImages(messages: JSONArray, maxRetainedImages: Int = 4): Int {
            data class ImageLoc(val msgIdx: Int, val partIdx: Int)
            val locs = ArrayList<ImageLoc>()
            for (i in 0 until messages.length()) {
                val msg = messages.optJSONObject(i) ?: continue
                val role = msg.optString("role", "")
                if (role != "user" && role != "tool") continue
                val parts = msg.optJSONArray("content") ?: continue
                for (j in 0 until parts.length()) {
                    val part = parts.optJSONObject(j) ?: continue
                    if (part.optString("type") == "image_url") {
                        locs.add(ImageLoc(i, j))
                    }
                }
            }
            val excess = locs.size - maxRetainedImages.coerceAtLeast(1)
            if (excess <= 0) return 0
            for (k in 0 until excess) {
                val loc = locs[k]
                val parts = messages.optJSONObject(loc.msgIdx)?.optJSONArray("content") ?: continue
                parts.put(
                    loc.partIdx,
                    JSONObject()
                        .put("type", "text")
                        .put("text", "[历史图片已自动卸载以节省视觉上下文，当前保留最新 $maxRetainedImages 张画面]"),
                )
            }
            return excess
        }
    }

    // -------------------------------------------------------------------------
    // 4. 分段并发调度（batch.ts）与协议安全修复（session/repair.ts）
    // -------------------------------------------------------------------------

    data class ExecutionGroup<T>(
        val parallel: Boolean,
        val items: List<T>,
    )

    object ToolBatchScheduler {
        /**
         * 将同一轮工具调用划分为连续的「只读并行组」和「写操作独占屏障」：
         * 例如 [readA, readB, writeC, readD, readE] ->
         *   Group(parallel=true, [readA, readB]),
         *   Group(parallel=false, [writeC]),
         *   Group(parallel=true, [readD, readE])
         * 既最大化并发吞吐，又保证写操作前后严格有序。
         */
        fun <T> partitionBatches(items: List<T>, isMutating: (T) -> Boolean): List<ExecutionGroup<T>> {
            if (items.isEmpty()) return emptyList()
            val groups = ArrayList<ExecutionGroup<T>>()
            val readBuffer = ArrayList<T>()
            fun flushReads() {
                if (readBuffer.isNotEmpty()) {
                    groups.add(ExecutionGroup(parallel = readBuffer.size >= 2, items = readBuffer.toList()))
                    readBuffer.clear()
                }
            }
            for (item in items) {
                if (isMutating(item)) {
                    flushReads()
                    groups.add(ExecutionGroup(parallel = false, items = listOf(item)))
                } else {
                    readBuffer.add(item)
                }
            }
            flushReads()
            return groups
        }
    }

    object ToolCallRecovery {
        /**
         * 全链路协议校验与修复：
         * 1. 确保 messages 中每一条带 `tool_calls` 的 assistant 消息紧随其后都有且仅有匹配的 `role=tool` 回复；
         * 2. 自动补齐缺失的 `tool_call_id` 占位结果，自动移除悬空/重复/无主的 `role=tool` 消息；
         * 3. 彻底杜绝因压缩、取消或并发异常触发 OpenAI/DeepSeek HTTP 400 `insufficient tool messages` 报错。
         */
        fun sanitizeMessageSequence(messages: JSONArray): Int {
            val cleaned = ArrayList<JSONObject>(messages.length() + 4)
            var patched = 0
            var i = 0
            val n = messages.length()
            while (i < n) {
                val msg = messages.optJSONObject(i)
                if (msg == null) {
                    i += 1
                    patched += 1
                    continue
                }
                val role = msg.optString("role", "")
                if (role == "tool") {
                    // 无前序 assistant(tool_calls) 的悬空 tool 消息直接丢弃
                    patched += 1
                    i += 1
                    continue
                }
                if (role == "assistant") {
                    val rawCalls = msg.optJSONArray("tool_calls")
                    if (rawCalls != null && rawCalls.length() > 0) {
                        val validCalls = JSONArray()
                        val expectedOrder = ArrayList<String>(rawCalls.length())
                        val expectedSet = HashSet<String>(rawCalls.length())
                        for (j in 0 until rawCalls.length()) {
                            val c = rawCalls.optJSONObject(j) ?: continue
                            var cid = c.optString("id", "").trim()
                            if (cid.isEmpty()) {
                                cid = "call_fix_${i}_${j}"
                                c.put("id", cid)
                                patched += 1
                            }
                            if (expectedSet.add(cid)) {
                                expectedOrder.add(cid)
                                validCalls.put(c)
                            } else {
                                patched += 1
                            }
                        }
                        if (validCalls.length() == 0) {
                            msg.remove("tool_calls")
                            cleaned.add(msg)
                            i += 1
                            patched += 1
                            continue
                        }
                        msg.put("tool_calls", validCalls)
                        cleaned.add(msg)
                        // 收集紧随其后的连续 role=tool 回复
                        val matchedTools = LinkedHashMap<String, JSONObject>()
                        var k = i + 1
                        while (k < n) {
                            val next = messages.optJSONObject(k)
                            if (next == null || next.optString("role", "") != "tool") break
                            val tid = next.optString("tool_call_id", "").trim()
                            if (tid in expectedSet && !matchedTools.containsKey(tid)) {
                                matchedTools[tid] = next
                            } else {
                                patched += 1
                            }
                            k += 1
                        }
                        // 严格按 expectedOrder 输出全部 tool 回复，缺失项立即补齐协议占位
                        for (cid in expectedOrder) {
                            val existing = matchedTools[cid]
                            if (existing != null) {
                                cleaned.add(existing)
                            } else {
                                cleaned.add(
                                    JSONObject()
                                        .put("role", "tool")
                                        .put("tool_call_id", cid)
                                        .put(
                                            "content",
                                            JSONObject()
                                                .put("error", "TOOL_NOT_STARTED")
                                                .put("message", "该工具调用因任务中止或上下文压缩未能完成，请按当前状态继续。")
                                                .toString(),
                                        ),
                                )
                                patched += 1
                            }
                        }
                        i = k
                        continue
                    } else if (rawCalls != null) {
                        msg.remove("tool_calls")
                        patched += 1
                    }
                }
                cleaned.add(msg)
                i += 1
            }
            if (patched > 0) {
                while (messages.length() > 0) messages.remove(messages.length() - 1)
                for (item in cleaned) messages.put(item)
            }
            return patched
        }

        fun ensureBalancedToolResults(messages: JSONArray): Int = sanitizeMessageSequence(messages)
    }

    // -------------------------------------------------------------------------
    // 5. 文件读后写新鲜度守卫（fs-observation-policy）
    // -------------------------------------------------------------------------

    object FsObservationGate {
        data class ObservedVersion(val lastModified: Long, val length: Long)

        private val observed = ConcurrentHashMap<String, ObservedVersion>()

        fun recordObserved(file: File) {
            val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            if (file.isFile) {
                observed[key] = ObservedVersion(file.lastModified(), file.length())
            } else {
                observed.remove(key)
            }
        }

        fun forget(file: File) {
            val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            observed.remove(key)
        }

        /**
         * 编辑/删除前检查：若文件自上次读取后被外部修改（mtime 或 size 变化），抛出清晰错误要求先重读。
         */
        fun checkFreshForEdit(file: File, displayPath: String) {
            val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            val prior = observed[key] ?: return // 兼容直接调用，不阻断首次小改动；已读过的严格校验版本
            if (!file.isFile) {
                throw IllegalStateException("FS_NOT_FOUND：文件 $displayPath 已被删除，请先核实目录。")
            }
            if (file.lastModified() != prior.lastModified || file.length() != prior.length) {
                throw IllegalStateException("FS_STALE_VERSION：文件 $displayPath 自上次读取后发生了变更，请先调用 whale_code_read 重新读取最新内容再修改。")
            }
        }
    }

    // -------------------------------------------------------------------------
    // 6. 结构化整表任务追踪器（tool-todo）
    // -------------------------------------------------------------------------

    object SessionTodoStore {
        data class TodoItem(val content: String, val status: String)

        private val lists = ConcurrentHashMap<String, List<TodoItem>>()
        private val validStatuses = setOf("pending", "in_progress", "completed")

        fun write(sessionId: String, rawTodos: JSONArray, context: Context? = null): JSONObject {
            val parsed = ArrayList<TodoItem>(rawTodos.length())
            val seen = HashSet<String>()
            var pending = 0
            var inProgress = 0
            var completed = 0
            for (i in 0 until rawTodos.length()) {
                val obj = rawTodos.optJSONObject(i)
                    ?: throw IllegalArgumentException("todos[$i] 必须是对象 {content, status}")
                val content = obj.optString("content", "").trim()
                val status = obj.optString("status", "pending").trim().lowercase()
                require(content.isNotEmpty()) { "todos[$i].content 不能为空" }
                require(seen.add(content)) { "todos[$i].content 重复：$content" }
                require(status in validStatuses) { "todos[$i].status 无效：$status（仅支持 pending / in_progress / completed）" }
                when (status) {
                    "pending" -> pending += 1
                    "in_progress" -> inProgress += 1
                    "completed" -> completed += 1
                }
                parsed.add(TodoItem(content, status))
            }
            lists[sessionId] = parsed
            val outArr = JSONArray()
            parsed.forEach { item ->
                outArr.put(JSONObject().put("content", item.content).put("status", item.status))
            }
            if (context != null) {
                runCatching {
                    val dir = File(context.applicationContext.filesDir, "whale-session-state").apply { mkdirs() }
                    val safe = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64).ifBlank { "default" }
                    File(dir, "todo_$safe.json").writeText(outArr.toString(), Charsets.UTF_8)
                }
            }
            return JSONObject()
                .put("ok", true)
                .put("todos", outArr)
                .put(
                    "counts",
                    JSONObject()
                        .put("pending", pending)
                        .put("inProgress", inProgress)
                        .put("completed", completed)
                        .put("total", parsed.size),
                )
        }

        fun clear(sessionId: String, context: Context? = null) {
            lists.remove(sessionId)
            if (context != null) {
                runCatching {
                    val safe = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64).ifBlank { "default" }
                    File(File(context.applicationContext.filesDir, "whale-session-state"), "todo_$safe.json").delete()
                }
            }
        }

        fun snapshotPrompt(sessionId: String, context: Context? = null): String {
            val items = lists[sessionId] ?: if (context != null) {
                runCatching {
                    val safe = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64).ifBlank { "default" }
                    val f = File(File(context.applicationContext.filesDir, "whale-session-state"), "todo_$safe.json")
                    // 超过 2 小时的旧任务表自动过期，防止污染新话题
                    if (!f.isFile || System.currentTimeMillis() - f.lastModified() > 2 * 60 * 60 * 1000L) emptyList() else {
                        val arr = JSONArray(f.readText(Charsets.UTF_8))
                        (0 until arr.length()).mapNotNull { i ->
                            val o = arr.optJSONObject(i) ?: return@mapNotNull null
                            val c = o.optString("content").trim()
                            val s = o.optString("status", "pending").trim()
                            if (c.isNotEmpty()) TodoItem(c, s) else null
                        }.also { if (it.isNotEmpty()) lists[sessionId] = it }
                    }
                }.getOrDefault(emptyList())
            } else emptyList()
            if (items.isEmpty() || items.all { it.status == "completed" }) return ""
            return buildString {
                appendLine("[当前任务清单]")
                items.forEach { item ->
                    val mark = when (item.status) {
                        "completed" -> "[x]"
                        "in_progress" -> "[/]"
                        else -> "[ ]"
                    }
                    appendLine("- $mark ${item.content}")
                }
            }.trim()
        }
    }

    // -------------------------------------------------------------------------
    // 7. KV 缓存友好型结构化摘要压缩（compaction-basic/summarizer.ts）
    // -------------------------------------------------------------------------

    object PrefixCacheCompactor {
        const val COMPACTION_CHAR_THRESHOLD = 24_000

        const val COMPACTION_INSTRUCTION =
            "请作为上下文压缩引擎，将上方已完成的对话与工具调用压缩为一份紧凑的结构化检查点（<compacted-summary>），以便无缝续跑：\n" +
                "1. 用户核心目标与约束\n" +
                "2. 已查明的关键事实、文件路径、参数与数值\n" +
                "3. 已遇到的报错与解决办法\n" +
                "4. 下一步待办动作\n" +
                "规则：只输出精简要点，不要调用任何工具，不要解释你在压缩上下文。"

        fun estimateMessagesChars(messages: JSONArray): Int {
            var total = 0
            for (i in 0 until messages.length()) {
                total += messages.optJSONObject(i)?.toString()?.length ?: 0
            }
            return total
        }

        fun wrapCheckpoint(summaryText: String): String =
            "以下是前序多轮工具执行的自动压缩检查点，请将其视为已确立的背景并直接继续当前任务，无需重复确认：\n\n<compacted-summary>\n${summaryText.trim()}\n</compacted-summary>"

        fun saveCheckpoint(context: Context, sessionId: String, summaryText: String) {
            runCatching {
                val dir = File(context.applicationContext.filesDir, "whale-session-state").apply { mkdirs() }
                val safe = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64).ifBlank { "default" }
                File(dir, "cp_$safe.txt").writeText(summaryText.trim().take(6_000), Charsets.UTF_8)
            }
        }

        fun clearCheckpoint(context: Context, sessionId: String) {
            runCatching {
                val safe = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64).ifBlank { "default" }
                File(File(context.applicationContext.filesDir, "whale-session-state"), "cp_$safe.txt").delete()
            }
        }

        fun loadCheckpoint(context: Context, sessionId: String, maxAgeMs: Long = 30 * 60 * 1000L): String = runCatching {
            val safe = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64).ifBlank { "default" }
            val f = File(File(context.applicationContext.filesDir, "whale-session-state"), "cp_$safe.txt")
            if (f.isFile && System.currentTimeMillis() - f.lastModified() <= maxAgeMs) {
                f.readText(Charsets.UTF_8).trim()
            } else ""
        }.getOrDefault("")

        /**
         * 话题相关性守卫：防止旧任务压缩检查点（如“谈薪 Excel”）在用户已切换新话题（如“翻 GitHub 项目”）
         * 并发出“好呀直接做”等短接续句时污染当前轮次导致串号。
         */
        fun isCheckpointRelevant(
            checkpointText: String,
            recentHistory: List<Pair<String, String>>,
            userQuery: String,
        ): Boolean {
            if (checkpointText.isBlank()) return false
            val recentWindow = (recentHistory.takeLast(2).map { it.second } + userQuery).joinToString(" ").trim()
            if (recentWindow.isEmpty()) return false
            val cpTokens = extractTopicTokens(checkpointText)
            val recentTokens = extractTopicTokens(recentWindow)
            if (cpTokens.isEmpty() || recentTokens.isEmpty()) return false
            var overlap = 0
            for (t in recentTokens) {
                if (t in cpTokens) overlap += 1
                if (overlap >= 2) return true
            }
            return false
        }

        private fun extractTopicTokens(text: String): Set<String> {
            val lower = text.lowercase()
            val stop = setOf("我们", "你们", "这个", "那个", "一下", "直接", "可以", "好的", "好呀", "继续", "现在", "已经", "需要", "帮我", "进行", "处理", "任务", "结果", "内容")
            val out = HashSet<String>()
            Regex("[a-z0-9_.-]{3,}").findAll(lower).forEach { m -> out.add(m.value) }
            val cjk = lower.filter { it in '\u4e00'..'\u9fff' }
            for (i in 0 until cjk.length - 1) {
                val bg = cjk.substring(i, i + 2)
                if (bg !in stop) out.add(bg)
            }
            return out
        }
    }

    // -------------------------------------------------------------------------
    // 8. 进程内分叉上下文前缀（subagent-fork-in-process）
    // -------------------------------------------------------------------------

    object ContextForkHelper {
        /**
         * 从共享对话记录中提取最近已完成轮次的精简对话前缀，供后台 Worker 继承上下文（Fork 模式）。
         */
        fun buildForkPrefix(context: Context, maxTurns: Int = 8, maxChars: Int = 3_200): String {
            val arr = runCatching { WhaleSharedChatStore.list(context) }.getOrDefault(JSONArray())
            if (arr.length() == 0) return ""
            val start = (arr.length() - maxTurns).coerceAtLeast(0)
            val sb = StringBuilder("[继承自父会话的近期上下文前缀]\n")
            for (i in start until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val role = item.optString("role", "")
                val text = item.optString("text", "").trim()
                if (text.isEmpty()) continue
                val label = if (role == "user") "用户" else "助手"
                val line = "$label：${text.take(420)}\n"
                if (sb.length + line.length > maxChars) break
                sb.append(line)
            }
            return sb.toString().trim()
        }
    }
}
