package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 持久化长任务检查点存储（Checkpoint Store）。
 *
 * 结合 DeepSeek Super-agent 恢复机制：
 * - 将原任务、当前阶段（最多 3 阶段 × 20 轮 = 60 轮）、进度摘要与运行态原子落盘；
 * - 严格区分 `deliveredInputs`（已送达收件箱）与 `readInputIds`（模型已读入上下文），崩溃重启后只重放未读入的用户追问；
 * - 恢复提示词强制要求 Worker 先核对本地已有产出，严禁重复已完成的外部动作。
 */
internal object WhaleLongTaskStore {
    private const val DIR_NAME = "whale-long-tasks"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    private const val MAX_RESUME_ATTEMPTS = 3

    data class Checkpoint(
        val id: String,
        val title: String,
        val task: String,
        val mode: String,
        val ownerSession: String,
        val stage: Int,
        val maxStages: Int,
        val progressDigest: String,
        val resumeCount: Int,
        val state: String,
        val updatedAt: Long,
        val strength: String = "explicit",
        val ruleName: String = "",
        val deliveredInputs: List<Pair<String, String>> = emptyList(),
        val readInputIds: Set<String> = emptySet(),
    ) {
        fun unreadInputs(): List<Pair<String, String>> = deliveredInputs.filter { it.first !in readInputIds }

        fun json(): JSONObject {
            val delArr = JSONArray()
            deliveredInputs.forEach { (inId, txt) ->
                delArr.put(JSONObject().put("id", inId).put("text", txt))
            }
            val readArr = JSONArray()
            readInputIds.forEach { readArr.put(it) }
            return JSONObject()
                .put("id", id)
                .put("title", title)
                .put("task", task)
                .put("mode", mode)
                .put("ownerSession", ownerSession)
                .put("stage", stage)
                .put("maxStages", maxStages)
                .put("progressDigest", progressDigest)
                .put("resumeCount", resumeCount)
                .put("state", state)
                .put("updatedAt", updatedAt)
                .put("strength", strength)
                .put("ruleName", ruleName)
                .put("deliveredInputs", delArr)
                .put("readInputIds", readArr)
        }

        companion object {
            fun fromJson(obj: JSONObject): Checkpoint {
                val delList = ArrayList<Pair<String, String>>()
                val delArr = obj.optJSONArray("deliveredInputs")
                if (delArr != null) {
                    for (i in 0 until delArr.length()) {
                        val item = delArr.optJSONObject(i) ?: continue
                        val inId = item.optString("id")
                        val txt = item.optString("text")
                        if (inId.isNotBlank() && txt.isNotBlank()) delList.add(inId to txt)
                    }
                }
                val readSet = LinkedHashSet<String>()
                val readArr = obj.optJSONArray("readInputIds")
                if (readArr != null) {
                    for (i in 0 until readArr.length()) {
                        val v = readArr.optString(i)
                        if (v.isNotBlank()) readSet.add(v)
                    }
                }
                return Checkpoint(
                    id = obj.getString("id"),
                    title = obj.optString("title"),
                    task = obj.getString("task"),
                    mode = obj.optString("mode", "create"),
                    ownerSession = obj.optString("ownerSession", "session-whale-task-desk"),
                    stage = obj.optInt("stage", 1).coerceAtLeast(1),
                    maxStages = obj.optInt("maxStages", 3).coerceIn(1, 5),
                    progressDigest = obj.optString("progressDigest"),
                    resumeCount = obj.optInt("resumeCount", 0),
                    state = obj.optString("state", "running"),
                    updatedAt = obj.optLong("updatedAt", 0L),
                    strength = obj.optString("strength", "explicit"),
                    ruleName = obj.optString("ruleName", ""),
                    deliveredInputs = delList,
                    readInputIds = readSet,
                )
            }
        }
    }

    private fun dir(context: Context): File = File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }
    private fun file(context: Context, id: String): File {
        val safe = id.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
        return File(dir(context), "$safe.json")
    }

    @Synchronized
    fun save(context: Context, cp: Checkpoint) {
        runCatching {
            val target = file(context, cp.id)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(cp.json().toString(), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(cp.json().toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    @Synchronized
    fun read(context: Context, id: String): Checkpoint? = runCatching {
        val f = file(context, id)
        if (!f.isFile) return null
        Checkpoint.fromJson(JSONObject(f.readText(Charsets.UTF_8)))
    }.getOrNull()

    /** 记录一条已投递给该长任务的追加追问（持久化防丢，待模型读入后标记为 readInputIds）。 */
    @Synchronized
    fun appendDeliveredInput(context: Context, id: String, inputId: String, text: String) {
        val existing = read(context, id) ?: return
        if (existing.deliveredInputs.any { it.first == inputId }) return
        save(
            context,
            existing.copy(
                deliveredInputs = existing.deliveredInputs + (inputId to text.take(1200)),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 标记指定输入已读入模型上下文并完成一轮汇报（崩溃重启不再重复重放）。 */
    @Synchronized
    fun markInputsRead(context: Context, id: String, inputIds: Collection<String>) {
        if (inputIds.isEmpty()) return
        val existing = read(context, id) ?: return
        save(
            context,
            existing.copy(
                readInputIds = existing.readInputIds + inputIds,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    @Synchronized
    fun markTerminal(context: Context, id: String, state: String) {
        val f = file(context, id)
        if (state in setOf("done", "cancelled", "failed")) {
            runCatching { f.delete() }
        }
    }

    /** 扫描因进程被杀而中断的 running 检查点（24 小时内且未超最大续跑次数）。 */
    @Synchronized
    fun listResumable(context: Context, activeIds: Set<String>, nowMs: Long = System.currentTimeMillis()): List<Checkpoint> {
        val out = mutableListOf<Checkpoint>()
        val files = dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
        for (f in files) {
            val cp = runCatching { Checkpoint.fromJson(JSONObject(f.readText(Charsets.UTF_8))) }.getOrNull()
            if (cp == null) {
                runCatching { f.delete() }
                continue
            }
            if (cp.state != "running" || nowMs - cp.updatedAt > MAX_AGE_MS || cp.resumeCount >= MAX_RESUME_ATTEMPTS) {
                if (cp.state != "running" || nowMs - cp.updatedAt > MAX_AGE_MS) runCatching { f.delete() }
                continue
            }
            if (cp.id !in activeIds) out.add(cp)
        }
        return out.sortedBy { it.updatedAt }
    }

    /** 构造续跑阶段提示词：支持 600 字摘要指针化、重启自检防重放、未读追问精准重放与主动规则降级守卫。 */
    fun buildStagePrompt(cp: Checkpoint): String {
        val unread = cp.unreadInputs()
        val inferredGuard = if (cp.strength == "inferred") {
            WhaleSuperagentCore.MandateGuard.inferredMandatePrompt(cp.ruleName.ifBlank { "主动规则" }) + "\n\n"
        } else {
            ""
        }
        if (cp.stage <= 1 && cp.progressDigest.isBlank() && cp.resumeCount == 0 && unread.isEmpty()) {
            return inferredGuard + cp.task
        }
        return buildString {
            append(inferredGuard)
            append("[长任务断点续跑 · 第 ${cp.stage}/${cp.maxStages} 阶段")
            if (cp.resumeCount > 0) append(" · 崩溃恢复第 ${cp.resumeCount} 次")
            append("]\n")
            append("原始任务：${cp.task.take(WhaleSuperagentCore.ASSIGNMENT_PREVIEW_CHARS)}\n")
            if (cp.progressDigest.isNotBlank()) {
                val trunc = WhaleSuperagentCore.ResourceTruncator.truncate(
                    cp.progressDigest,
                    WhaleSuperagentCore.TRIGGER_PREVIEW_CHARS,
                    "checkpoint/${cp.id}",
                )
                append("上一阶段检查点摘要：\n${trunc.optString("text")}\n")
                if (trunc.has("read")) {
                    append("（完整进度共 ${trunc.getInt("chars")} 字，如需细节可调用 whale_read_resource(resource=\"${trunc.getString("read")}\") 分页查看）\n")
                }
            }
            if (unread.isNotEmpty()) {
                append("【用户中途补充/追问（尚未读入，请优先纳入本阶段执行）】\n")
                unread.forEach { (_, txt) -> append("- ").append(txt.take(600)).append("\n") }
            }
            append("【恢复守则】请先核对已有文件/工坊工具/待办等本地产出，严禁重复已完成的外部动作；直接接着推进未完成部分，全部完成后输出简洁完整的最终结论。")
        }
    }
}
