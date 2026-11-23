package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 鲸鱼助手 Agent 认知增强三件套（零额外大模型依赖、持久化落盘）：
 *
 * 1. [ReflexionLessonStore]：失败经验避坑记忆（Reflexion）。
 *    每次工具失败后自动沉淀「失败工具+场景 → 有效替代方案」，下次直接注入提示词避坑，提成功率且省重试 Token；
 * 2. [MemoryConsolidator]：睡眠期 / 一键记忆提纯与冲突消解。
 *    按 bigram 相似度合并高度重复记忆，按主题前缀识别新旧偏好冲突并保留较新记录、清理过时冗余；
 * 3. [DurableTaskCheckpoint]：跨会话持久化断点续跑与异步高危审批状态机。
 *    支持复杂多步任务在达到步数上限或遇高危操作时保存断点与待审批动作，待用户确认后无缝接续。
 */
internal object WhaleAgentBrainEnhancer {

    // -------------------------------------------------------------------------
    // 1. Reflexion 失败经验避坑记忆库
    // -------------------------------------------------------------------------

    object ReflexionLessonStore {
        private const val PREFS = "whale_reflexion_lessons_v1"
        private const val KEY_LIST = "lessons_json"
        private const val MAX_LESSONS = 32

        data class Lesson(
            val failedTool: String,
            val errorHint: String,
            val workaround: String,
            val hits: Int = 1,
            val updatedAt: Long = System.currentTimeMillis(),
        ) {
            fun json(): JSONObject = JSONObject()
                .put("failedTool", failedTool)
                .put("errorHint", errorHint)
                .put("workaround", workaround)
                .put("hits", hits)
                .put("updatedAt", updatedAt)

            companion object {
                fun fromJson(o: JSONObject): Lesson? {
                    val ft = o.optString("failedTool").trim()
                    val wa = o.optString("workaround").trim()
                    if (ft.isEmpty() || wa.isEmpty()) return null
                    return Lesson(
                        failedTool = ft,
                        errorHint = o.optString("errorHint", "").trim(),
                        workaround = wa,
                        hits = o.optInt("hits", 1).coerceAtLeast(1),
                        updatedAt = o.optLong("updatedAt", 0L),
                    )
                }
            }
        }

        @Synchronized
        fun list(context: Context): List<Lesson> {
            val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LIST, "[]").orEmpty()
            val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
            val out = ArrayList<Lesson>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let(Lesson::fromJson)?.let(out::add)
            }
            return out.sortedByDescending { it.updatedAt }
        }

        @Synchronized
        fun recordRecovery(context: Context, failedTool: String, errorHint: String, recoveredTool: String) {
            val ft = failedTool.trim()
            val rt = recoveredTool.trim()
            if (ft.isEmpty() || rt.isEmpty() || ft == rt) return
            val cleanErr = errorHint.replace(Regex("\\s+"), " ").trim().take(48)
            val workaround = "遇到 $ft 报错($cleanErr)时，直接改用 $rt"
            val existing = list(context).toMutableList()
            val idx = existing.indexOfFirst { it.failedTool == ft && it.workaround.contains(rt) }
            if (idx >= 0) {
                val prev = existing[idx]
                existing[idx] = prev.copy(hits = prev.hits + 1, updatedAt = System.currentTimeMillis())
            } else {
                existing.add(0, Lesson(ft, cleanErr, workaround))
            }
            val trimmed = existing.sortedByDescending { it.hits * 1000L + (it.updatedAt / 100_000L) }.take(MAX_LESSONS)
            val arr = JSONArray()
            trimmed.forEach { arr.put(it.json()) }
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LIST, arr.toString()).apply()
        }

        fun matchForTool(context: Context, failedTool: String): String? =
            list(context).firstOrNull { it.failedTool == failedTool }?.workaround

        fun promptHints(context: Context, userQuery: String, maxItems: Int = 2): String {
            val all = list(context)
            if (all.isEmpty()) return ""
            val q = userQuery.lowercase(Locale.ROOT)
            val matched = all.filter { l ->
                l.failedTool.lowercase(Locale.ROOT).split("_").any { tok -> tok.length >= 3 && tok in q } ||
                    l.errorHint.lowercase(Locale.ROOT).take(8).let { it.length >= 3 && it in q }
            }.ifEmpty { all.take(1) }.take(maxItems)
            if (matched.isEmpty()) return ""
            return "[避坑经验] " + matched.joinToString("；") { it.workaround }
        }
    }

    // -------------------------------------------------------------------------
    // 2. 睡眠期 / 一键记忆提纯与冲突消解（纯算法本地零 Token）
    // -------------------------------------------------------------------------

    object MemoryConsolidator {
        private const val PREFS = "whale_memory_consolidator_v1"
        private const val KEY_LAST_RUN = "last_run_ms"
        private const val COOLDOWN_MS = 24 * 60 * 60 * 1000L

        data class MemoryItem(
            val id: String,
            val text: String,
            val updatedAt: Long,
        )

        data class ConsolidationPlan(
            val duplicateIdsToDrop: List<String>,
            val supersededConflictIdsToDrop: List<String>,
            val keptCount: Int,
        )

        private val subjectPrefixes = listOf(
            "称呼", "叫我", "偏好", "喜欢", "不喜欢", "住在", "居住", "所在城市", "常用城市",
            "工作", "职业", "时区", "语言", "作息", "回复风格", "说话风格", "模型偏好",
        )

        /** 纯函数计算去重与冲突消解计划（方便单元测试直接验证）。 */
        fun computePlan(items: List<MemoryItem>, duplicateThreshold: Double = 0.72): ConsolidationPlan {
            if (items.size <= 1) return ConsolidationPlan(emptyList(), emptyList(), items.size)
            // 按时间倒序：越新的记录优先级越高
            val sorted = items.sortedByDescending { it.updatedAt }
            val kept = ArrayList<Pair<MemoryItem, Set<String>>>(sorted.size)
            val dupDrops = ArrayList<String>()
            val conflictDrops = ArrayList<String>()
            val seenSubjects = HashMap<String, MemoryItem>()

            for (item in sorted) {
                val bg = charBigrams(item.text)
                val isDup = bg.size >= 4 && kept.any { (_, prevBg) ->
                    prevBg.size >= 4 && (jaccard(bg, prevBg) >= duplicateThreshold || overlapCoefficient(bg, prevBg) >= 0.85)
                }
                if (isDup) {
                    dupDrops.add(item.id)
                    continue
                }
                val subj = extractSubjectKey(item.text)
                if (subj != null) {
                    val newer = seenSubjects[subj]
                    if (newer != null && newer.text != item.text) {
                        conflictDrops.add(item.id)
                        continue
                    }
                    seenSubjects[subj] = item
                }
                kept.add(item to bg)
            }
            return ConsolidationPlan(dupDrops, conflictDrops, kept.size)
        }

        fun runIfDue(context: Context): JSONObject? {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val last = prefs.getLong(KEY_LAST_RUN, 0L)
            if (System.currentTimeMillis() - last < COOLDOWN_MS) return null
            return consolidate(context, dryRun = false)
        }

        fun consolidate(context: Context, dryRun: Boolean = false): JSONObject {
            val store = CompanionMemoryStore(context.applicationContext)
            val raw = runCatching {
                store.execute(JSONObject().put("op", "list").put("limit", 120).toString())
            }.getOrNull() ?: return JSONObject().put("ok", false).put("error", "无法读取记忆库")
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return JSONObject().put("ok", false)
            val arr = root.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<MemoryItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                val txt = o.optString("text").trim()
                val ts = o.optLong("updatedAt", o.optLong("createdAt", i.toLong()))
                if (id.isNotEmpty() && txt.isNotEmpty()) items.add(MemoryItem(id, txt, ts))
            }
            val plan = computePlan(items)
            var deleted = 0
            if (!dryRun) {
                (plan.duplicateIdsToDrop + plan.supersededConflictIdsToDrop).forEach { id ->
                    val ok = runCatching {
                        val res = store.execute(JSONObject().put("op", "delete").put("id", id).toString())
                        JSONObject(res).optBoolean("ok", true)
                    }.getOrDefault(false)
                    if (ok) deleted += 1
                }
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_LAST_RUN, System.currentTimeMillis()).apply()
            }
            return JSONObject()
                .put("ok", true)
                .put("dryRun", dryRun)
                .put("scanned", items.size)
                .put("mergedDuplicates", plan.duplicateIdsToDrop.size)
                .put("resolvedConflicts", plan.supersededConflictIdsToDrop.size)
                .put("deleted", deleted)
                .put("remaining", plan.keptCount)
        }

        private fun extractSubjectKey(text: String): String? {
            val clean = text.trim()
            for (prefix in subjectPrefixes) {
                if (clean.startsWith(prefix) || clean.contains("用户$prefix") || clean.contains("主人$prefix")) {
                    return prefix
                }
            }
            return null
        }

        private fun charBigrams(text: String): Set<String> {
            val clean = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it in '\u4e00'..'\u9fff' }
            if (clean.length < 2) return emptySet()
            val set = HashSet<String>(clean.length)
            for (i in 0 until clean.length - 1) set.add(clean.substring(i, i + 2))
            return set
        }

        private fun jaccard(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val small = if (a.size <= b.size) a else b
            val large = if (a.size <= b.size) b else a
            var inter = 0
            for (x in small) if (x in large) inter += 1
            val union = a.size + b.size - inter
            return if (union <= 0) 0.0 else inter.toDouble() / union.toDouble()
        }

        private fun overlapCoefficient(a: Set<String>, b: Set<String>): Double {
            val minSize = minOf(a.size, b.size)
            if (minSize <= 0) return 0.0
            val small = if (a.size <= b.size) a else b
            val large = if (a.size <= b.size) b else a
            var inter = 0
            for (x in small) if (x in large) inter += 1
            return inter.toDouble() / minSize.toDouble()
        }
    }

    // -------------------------------------------------------------------------
    // 3. 持久化断点续跑 + 异步高危审批状态机
    // -------------------------------------------------------------------------

    object DurableTaskCheckpoint {
        private fun stateFile(context: Context, sessionId: String): File {
            val dir = File(context.applicationContext.filesDir, "whale-session-state").apply { mkdirs() }
            val safe = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64).ifBlank { "default" }
            return File(dir, "task_fsm_$safe.json")
        }

        fun saveState(
            context: Context,
            sessionId: String,
            goal: String,
            status: String, // "running" | "awaiting_approval" | "paused" | "completed"
            completedSteps: List<String>,
            nextAction: String = "",
            pendingTool: String = "",
            pendingArgsJson: String = "",
        ): JSONObject {
            val obj = JSONObject()
                .put("sessionId", sessionId)
                .put("goal", goal.take(180))
                .put("status", status)
                .put("completedSteps", JSONArray(completedSteps.takeLast(12)))
                .put("nextAction", nextAction.take(200))
                .put("pendingTool", pendingTool)
                .put("pendingArgsJson", pendingArgsJson.take(1200))
                .put("updatedAt", System.currentTimeMillis())
            runCatching { stateFile(context, sessionId).writeText(obj.toString(), Charsets.UTF_8) }
            return obj
        }

        fun loadState(context: Context, sessionId: String): JSONObject? = runCatching {
            val f = stateFile(context, sessionId)
            if (!f.isFile) return null
            if (System.currentTimeMillis() - f.lastModified() > 24 * 60 * 60 * 1000L) return null
            JSONObject(f.readText(Charsets.UTF_8))
        }.getOrNull()

        fun clearState(context: Context, sessionId: String) {
            runCatching { stateFile(context, sessionId).delete() }
        }

        fun promptSummary(context: Context, sessionId: String): String {
            val s = loadState(context, sessionId) ?: return ""
            val status = s.optString("status")
            if (status == "completed" || status.isBlank()) return ""
            val goal = s.optString("goal")
            val next = s.optString("nextAction")
            val pendingTool = s.optString("pendingTool")
            return buildString {
                append("[断点任务状态:$status] 目标:$goal")
                if (pendingTool.isNotBlank()) append(" | 待审批动作:$pendingTool")
                if (next.isNotBlank()) append(" | 下一步:$next")
            }.take(260)
        }
    }
}
