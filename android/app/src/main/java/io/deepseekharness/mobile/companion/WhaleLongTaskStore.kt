package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 持久化长任务检查点存储（Checkpoint Store）。
 *
 * 将后台 Worker 的原任务、当前阶段（最多 3 阶段 × 20 轮 = 60 轮工具调用）、已完成进度摘要与运行态原子落盘，
 * 确保即使锁屏、内存回收或进程被强杀，重新打开应用后也能自动从最近检查点续跑。
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
    ) {
        fun json(): JSONObject = JSONObject()
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

        companion object {
            fun fromJson(obj: JSONObject): Checkpoint = Checkpoint(
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
            )
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

    /** 构造续跑阶段提示词，避免重新塞入整段冗长历史，保持精简高效。 */
    fun buildStagePrompt(cp: Checkpoint): String {
        if (cp.stage <= 1 && cp.progressDigest.isBlank()) return cp.task
        return buildString {
            append("[长任务断点续跑 · 第 ${cp.stage}/${cp.maxStages} 阶段]\n")
            append("原始任务：${cp.task.take(2000)}\n")
            if (cp.progressDigest.isNotBlank()) {
                append("上一阶段已完成进度与检查点：\n${cp.progressDigest.take(2400)}\n")
            }
            append("请直接从上述进度接着推进未完成部分（避免重复已完成步骤），全部完成后输出清晰完整的最终结论。")
        }
    }
}
