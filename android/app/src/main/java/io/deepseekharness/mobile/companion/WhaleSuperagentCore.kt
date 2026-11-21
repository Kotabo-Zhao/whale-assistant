package io.deepseekharness.mobile.companion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 借鉴 DeepSeek Super-agent（dsh-superagent-prototype）核心架构提炼的原生 Android 调度内核：
 * 1. [WorkerSlots]：3+1 人类优先抢占槽位与同步占位（防止后台自动任务饿死人类即时追问）
 * 2. [isTransientFailure]：408/429/500-504 与网络抖动精准识别 + 指数退避策略
 * 3. [ResourceTruncator]：600 字符截断预览 + `read: resource` 分页按需读取（省 Token）
 * 4. [TicketStage]：结构化 7 态确定性优先级与 `waitsForPerson` 挂起判定
 * 5. [WhaleEffectsQueue]：SQLite 事务型持久副作用队列（`generation + seq` CAS 防覆盖 + `LaterException` 无惩罚延迟）
 * 6. [NoticeCoalescer]：合并键通知 + 最多 20 条点分路径 diff（`changedPaths`）
 * 7. [MandateGuard]：工具参数层硬字数门禁 + 主动接单（`inferred`）防越界约束注入
 * 8. [ProactiveObserveStore]：主动智能 `off / observe / auto` 三档模式与推测执行草稿箱
 */
internal object WhaleSuperagentCore {
    const val TRIGGER_PREVIEW_CHARS = 600
    const val ASSIGNMENT_PREVIEW_CHARS = 1500
    const val BRIEF_NOTE_MAX_CHARS = 240
    const val FAILURES_BEFORE_ERROR = 3

    /** 槽位满载或系统暂停时抛出：队列仅延后 due，不增加失败次数也不触发错误告警。 */
    class LaterException(message: String) : RuntimeException(message)

    /** 同步占位并发槽位管理器：常规任务最多占用 maxWorkers 槽，人类追问（by = "person"）额外享有 +1 专属槽位。 */
    class WorkerSlots(
        private val runningProvider: () -> Collection<String>,
        private val maxWorkers: () -> Int = { 3 },
    ) {
        private val starting = LinkedHashMap<String, String>()

        @Synchronized
        fun used(): Int = (runningProvider().toSet() + starting.keys).size

        @Synchronized
        fun availableLaunches(): Int = (maxWorkers() - used()).coerceAtLeast(0)

        @Synchronized
        fun reserve(ticketId: String, by: String = "launch") {
            if (starting.containsKey(ticketId)) return
            val limit = maxWorkers() + if (by == "person") 1 else 0
            if (used() >= limit) throw LaterException("并发槽位已满（limit=$limit）")
            starting[ticketId] = by
        }

        @Synchronized
        fun free(ticketId: String) {
            starting.remove(ticketId)
        }
    }

    private val PASSING_REGEX = Regex(
        listOf(
            "ECONNRESET", "ECONNREFUSED", "ETIMEDOUT", "EAI_AGAIN", "ENOTFOUND", "EPIPE",
            "socket hang up", "fetch failed", "network error", "overloaded", "rate limit",
            "timed out", "connection reset", "broken pipe", "\\b(?:408|429|50[0-4])\\b",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    /** 判断 HTTP 状态码或异常文本是否属于可静默退避重试的瞬时网络/服务抖动。 */
    fun isTransientFailure(statusCode: Int? = null, errorText: String? = null): Boolean {
        if (statusCode != null && (statusCode == 408 || statusCode == 429 || statusCode in 500..504)) {
            return true
        }
        return !errorText.isNullOrBlank() && PASSING_REGEX.containsMatchIn(errorText)
    }

    /** 指数退避延迟（默认 1.5s -> 3s -> 6s，上限 60s）。 */
    fun backoffMs(attemptIndex: Int, baseMs: Long = 1500L, maxMs: Long = 60_000L): Long {
        val shift = attemptIndex.coerceIn(0, 10)
        return (baseMs * (1L shl shift)).coerceAtMost(maxMs)
    }

    /** 600 字上下文指针化截断与按需分页读取。 */
    object ResourceTruncator {
        fun truncate(text: String, limit: Int = TRIGGER_PREVIEW_CHARS, resourceId: String): JSONObject {
            val clean = text.trim()
            return if (clean.length <= limit) {
                JSONObject().put("text", clean)
            } else {
                JSONObject()
                    .put("text", clean.take(limit) + "…")
                    .put("chars", clean.length)
                    .put("read", resourceId)
            }
        }

        fun readPage(fullText: String, offset: Int = 0, limit: Int = 2000): JSONObject {
            val safeOffset = offset.coerceIn(0, fullText.length)
            val safeLimit = limit.coerceIn(200, 6000)
            val end = (safeOffset + safeLimit).coerceAtMost(fullText.length)
            val obj = JSONObject()
                .put("content", fullText.substring(safeOffset, end))
                .put("offset", safeOffset)
                .put("totalChars", fullText.length)
            if (end < fullText.length) {
                obj.put("nextOffset", end)
            }
            return obj
        }
    }

    /** 结构化 7 态 Ticket Stage（确定性从上到下优先匹配，杜绝自由文本猜测）。 */
    enum class TicketStage(val code: String, val label: String, val needsHuman: Boolean) {
        PERSON_DELIVERY("person/delivery", "投递失败", true),
        PERSON_ANSWER("person/answer", "待你回答", true),
        PERSON_REVIEW("person/review", "待你审阅", true),
        PERSON_BLOCKED("person/blocked", "已受阻", true),
        PERSON_FAILED("person/failed", "执行失败", true),
        WORKING_QUEUED("working/queued", "排队中", false),
        IDLE("idle", "等待触发", false),
        WORKING("working", "执行中", false),
    }

    fun computeStage(
        deliveryFailed: Boolean = false,
        hasAttention: Boolean = false,
        reportState: String = "",
        hasHumanMessageSinceReport: Boolean = false,
        assignmentState: String = "running",
    ): TicketStage = when {
        deliveryFailed -> TicketStage.PERSON_DELIVERY
        hasAttention -> TicketStage.PERSON_ANSWER
        reportState == "ready" -> TicketStage.PERSON_REVIEW
        reportState == "blocked" && !hasHumanMessageSinceReport -> TicketStage.PERSON_BLOCKED
        reportState == "failed" && !hasHumanMessageSinceReport -> TicketStage.PERSON_FAILED
        assignmentState == "pending" || assignmentState == "queued" -> TicketStage.WORKING_QUEUED
        reportState == "waiting" || assignmentState == "ended" || assignmentState == "completed" -> TicketStage.IDLE
        else -> TicketStage.WORKING
    }

    /** `waitsForPerson` 谓词：受阻/失败且用户尚未回复前，自动挂起所有自动重试与自动输入。 */
    fun waitsForPerson(reportState: String, hasHumanMessageSinceReport: Boolean): Boolean =
        (reportState == "blocked" || reportState == "failed") && !hasHumanMessageSinceReport

    /** 合并键通知与最多 20 条点分路径 diff 计算。 */
    object NoticeCoalescer {
        fun changedPaths(before: JSONObject?, after: JSONObject?, prefix: String = "", limit: Int = 20): List<String> {
            val out = ArrayList<String>()
            collectDiff(before, after, prefix, out, limit)
            return out
        }

        private fun collectDiff(a: Any?, b: Any?, path: String, out: MutableList<String>, limit: Int) {
            if (out.size >= limit) return
            if (a == b || (a != null && b != null && a.toString() == b.toString())) return
            if (a is JSONObject && b is JSONObject) {
                val keys = LinkedHashSet<String>()
                a.keys().forEachRemaining { keys.add(it) }
                b.keys().forEachRemaining { keys.add(it) }
                for (k in keys) {
                    if (out.size >= limit) break
                    val nextPath = if (path.isEmpty()) k else "$path.$k"
                    collectDiff(a.opt(k), b.opt(k), nextPath, out, limit)
                }
                return
            }
            if (path.isNotEmpty()) out.add(path)
        }

        fun coalesceKeyForSettings(block: String): String = "settings:$block"
        fun receiptKey(prefix: String = "receipt"): String = "$prefix:${UUID.randomUUID()}"
    }

    /** 工具参数层硬字数门禁 + 主动接单防越界约束。 */
    object MandateGuard {
        fun validateBriefText(text: String, fieldName: String, maxChars: Int = BRIEF_NOTE_MAX_CHARS): String? {
            val clean = text.trim()
            if (clean.isEmpty()) return "$fieldName 不能为空"
            if (clean.length > maxChars) {
                return "$fieldName 超过 $maxChars 字上限（当前 ${clean.length} 字），请精简后重试。"
            }
            return null
        }

        fun inferredMandatePrompt(ruleName: String): String =
            "【主动规则受限授权 · $ruleName】本任务由主动规则自动触发，非用户显式指令。" +
                "请先独立核验这是否属于低风险、无外部副作用的轻量准备/内部整理；" +
                "一旦涉及发送外部消息、修改系统关键设置、消费资金或需要用户拍板，请立刻停手并输出草稿/说明，绝不越界执行。"
    }

    /** SQLite 事务型持久副作用队列（移植 `effects.ts` 的 `generation + seq` CAS 与 `Later` 机制）。 */
    class WhaleEffectsQueue(context: Context) {
        data class EffectRow(
            val kind: String,
            val key: String,
            val payload: String,
            val seq: Long,
            val generation: Int,
            val attempts: Int,
            val dueMs: Long,
        )

        private val dbHelper = object : SQLiteOpenHelper(context.applicationContext, "whale_effects_v1.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS whale_effect (
                        kind TEXT NOT NULL,
                        key TEXT NOT NULL,
                        value TEXT NOT NULL,
                        seq INTEGER NOT NULL,
                        generation INTEGER NOT NULL DEFAULT 1,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        error TEXT NOT NULL DEFAULT '',
                        due_ms INTEGER NOT NULL,
                        since_ms INTEGER NOT NULL,
                        PRIMARY KEY (kind, key)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_whale_effect_kind_due ON whale_effect(kind, due_ms, seq)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }

        @Synchronized
        fun enqueue(kind: String, key: String, payload: JSONObject, nowMs: Long = System.currentTimeMillis()) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                val curSeq = db.rawQuery("SELECT IFNULL(MAX(seq), 0) FROM whale_effect", null).use { c ->
                    if (c.moveToFirst()) c.getLong(0) else 0L
                }
                val nextSeq = curSeq + 1L
                db.execSQL(
                    """
                    INSERT INTO whale_effect (kind, key, value, seq, generation, attempts, error, due_ms, since_ms)
                    VALUES (?, ?, ?, ?, 1, 0, '', ?, ?)
                    ON CONFLICT(kind, key) DO UPDATE SET
                        value = excluded.value,
                        generation = generation + 1,
                        attempts = 0,
                        error = '',
                        due_ms = excluded.due_ms
                    """.trimIndent(),
                    arrayOf(kind, key, payload.toString(), nextSeq, nowMs, nowMs),
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        @Synchronized
        fun listDue(kind: String, nowMs: Long = System.currentTimeMillis(), limit: Int = 8): List<EffectRow> {
            val db = dbHelper.readableDatabase
            val out = ArrayList<EffectRow>()
            db.rawQuery(
                "SELECT kind, key, value, seq, generation, attempts, due_ms FROM whale_effect WHERE kind = ? AND due_ms <= ? ORDER BY seq ASC LIMIT ?",
                arrayOf(kind, nowMs.toString(), limit.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        EffectRow(
                            kind = c.getString(0),
                            key = c.getString(1),
                            payload = c.getString(2),
                            seq = c.getLong(3),
                            generation = c.getInt(4),
                            attempts = c.getInt(5),
                            dueMs = c.getLong(6),
                        ),
                    )
                }
            }
            return out
        }

        @Synchronized
        fun complete(row: EffectRow): Boolean {
            val db = dbHelper.writableDatabase
            val deleted = db.delete(
                "whale_effect",
                "kind = ? AND key = ? AND seq = ? AND generation = ?",
                arrayOf(row.kind, row.key, row.seq.toString(), row.generation.toString()),
            )
            return deleted > 0
        }

        @Synchronized
        fun deferLater(row: EffectRow, delayMs: Long = 2000L, nowMs: Long = System.currentTimeMillis()) {
            val db = dbHelper.writableDatabase
            val cv = ContentValues().apply { put("due_ms", nowMs + delayMs) }
            db.update(
                "whale_effect",
                cv,
                "kind = ? AND key = ? AND seq = ? AND generation = ?",
                arrayOf(row.kind, row.key, row.seq.toString(), row.generation.toString()),
            )
        }

        @Synchronized
        fun recordFailure(row: EffectRow, error: String, nowMs: Long = System.currentTimeMillis()) {
            val db = dbHelper.writableDatabase
            val delay = backoffMs(row.attempts, baseMs = 2000L, maxMs = 60_000L)
            val cv = ContentValues().apply {
                put("attempts", row.attempts + 1)
                put("error", error.take(600))
                put("due_ms", nowMs + delay)
            }
            db.update(
                "whale_effect",
                cv,
                "kind = ? AND key = ? AND seq = ? AND generation = ?",
                arrayOf(row.kind, row.key, row.seq.toString(), row.generation.toString()),
            )
        }
    }

    /** 主动智能 `off / observe / auto` 三档模式与推测执行草稿箱（默认 `observe` 影子观察，先出草稿不越权执行）。 */
    object ProactiveObserveStore {
        private const val PREFS = "whale_proactive_observe_v1"
        private const val KEY_MODE = "mode"
        private const val KEY_DRAFTS = "drafts"

        fun getMode(context: Context): String {
            val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODE, "observe") ?: "observe"
            return if (raw in setOf("off", "observe", "auto")) raw else "observe"
        }

        fun setMode(context: Context, mode: String): String {
            val normalized = if (mode in setOf("off", "observe", "auto")) mode else "observe"
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODE, normalized).apply()
            return normalized
        }

        @Synchronized
        fun recordSpeculativeDraft(
            context: Context,
            source: String,
            title: String,
            draftAction: String,
            reason: String,
        ): JSONObject {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = runCatching { JSONArray(prefs.getString(KEY_DRAFTS, "[]") ?: "[]") }.getOrDefault(JSONArray())
            val entry = JSONObject()
                .put("id", "obs-${UUID.randomUUID().toString().take(8)}")
                .put("source", source.take(60))
                .put("title", title.take(120))
                .put("draftAction", draftAction.take(800))
                .put("reason", reason.take(240))
                .put("status", "would_accept")
                .put("createdAt", System.currentTimeMillis())
            val next = JSONArray()
            next.put(entry)
            for (i in 0 until arr.length().coerceAtMost(29)) {
                next.put(arr.get(i))
            }
            prefs.edit().putString(KEY_DRAFTS, next.toString()).apply()
            return entry
        }

        fun listDrafts(context: Context, limit: Int = 12): JSONArray {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = runCatching { JSONArray(prefs.getString(KEY_DRAFTS, "[]") ?: "[]") }.getOrDefault(JSONArray())
            val out = JSONArray()
            for (i in 0 until arr.length().coerceAtMost(limit)) out.put(arr.get(i))
            return out
        }

        @Synchronized
        fun dismissDraft(context: Context, id: String): Boolean {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = runCatching { JSONArray(prefs.getString(KEY_DRAFTS, "[]") ?: "[]") }.getOrDefault(JSONArray())
            val next = JSONArray()
            var removed = false
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optString("id") == id) {
                    removed = true
                } else {
                    next.put(item)
                }
            }
            if (removed) prefs.edit().putString(KEY_DRAFTS, next.toString()).apply()
            return removed
        }
    }
}
