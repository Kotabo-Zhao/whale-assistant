package io.deepseekharness.mobile.companion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 借鉴 `billion-context` 的分层可逆上下文账本（面向移动端单会话无限续航与 KV 缓存守护）。
 *
 * 核心设计：
 * 1. **固定块增量折叠（Block = 8 轮）**：不把全量历史揉成一团大摘要，而是每满 8 条老消息折叠成一条
 *    不可变 [RangeRecord]，永远只在尾部追加新块、绝不原地改写已落块摘要，保护 95%+ KV Prefix Cache 命中率；
 * 2. **四类受保护内容**：① 静态 System Prompt；② 最近 6 条活跃原文；③ 进行中任务与未决约束；
 *    ④ 落盘产物文件路径与显式偏好（自动提取保留在 `artifactPaths`）；
 * 3. **可逆召回 + 防震荡护栏**：折叠前的逐字原文完整落盘 SQLite，模型可按需调用
 *    `whale_context_search` / `whale_context_expand(rangeId)` 召回，但单轮硬限制最多展开 1 块
 *    且仅作单轮临时注入，杜绝反复压缩/解压死循环。
 */
internal object WhaleContextLedger {
    const val BLOCK_SIZE = 8
    const val ACTIVE_TAIL_MESSAGES = 6
    const val MAX_EXPANDS_PER_TURN = 1

    data class RangeRecord(
        val rangeId: String,
        val sessionId: String,
        val startIndex: Int,
        val endIndex: Int,
        val summary: String,
        val artifactPaths: List<String>,
        val verbatimMessages: List<Pair<String, String>>,
        val createdAtMs: Long = System.currentTimeMillis(),
    ) {
        fun renderCard(): String = buildString {
            append("[记忆块 ").append(rangeId).append(" | 第 ").append(startIndex + 1).append("–").append(endIndex + 1).append(" 条] ")
            append(summary)
            if (artifactPaths.isNotEmpty()) {
                append(" | 产物：").append(artifactPaths.joinToString(", "))
            }
        }
    }

    data class AssembledWindow(
        val foldedCards: List<RangeRecord>,
        val activeMessages: List<Pair<String, String>>,
        val newlyFolded: List<RangeRecord>,
    )

    /** 防震荡单轮展开计数器：每轮对话硬限制最多展开 1 个记忆块。 */
    class TurnExpandGuard {
        private val count = AtomicInteger(0)
        fun tryAcquire(): Boolean = count.incrementAndGet() <= MAX_EXPANDS_PER_TURN
        fun used(): Int = count.get()
    }

    private val pathRegex = Regex("""(?:[A-Za-z0-9_.\-/]+(?:\.(?:md|json|py|kt|tsx|ts|pdf|docx|xlsx|pptx|csv|png|jpg|html|txt|zip)))""", RegexOption.IGNORE_CASE)
    private val memoryCache = ConcurrentHashMap<String, MutableList<RangeRecord>>()
    @Volatile private var dbHelper: LedgerDb? = null

    private fun db(context: Context): LedgerDb {
        dbHelper?.let { return it }
        return synchronized(this) {
            dbHelper ?: LedgerDb(context.applicationContext).also { dbHelper = it }
        }
    }

    /**
     * 纯确定性增量折叠规划器（无 Android 依赖，便于纯 JVM 单测）：
     * - 保留尾部 [ACTIVE_TAIL_MESSAGES] 条原文不折叠；
     * - 前部每满 [BLOCK_SIZE] 条生成一个不可变 [RangeRecord]，已存在的区间绝不改写。
     */
    fun planIncrementalWindow(
        sessionId: String,
        fullHistory: List<Pair<String, String>>,
        existingRanges: List<RangeRecord>,
        summarizer: (List<Pair<String, String>>) -> String = ::summarizeBlockDeterministically,
    ): AssembledWindow {
        val sortedExisting = existingRanges.sortedBy { it.startIndex }.toMutableList()
        val coveredEnd = sortedExisting.maxOfOrNull { it.endIndex } ?: -1
        val foldableLimit = (fullHistory.size - ACTIVE_TAIL_MESSAGES).coerceAtLeast(0)
        val newlyFolded = mutableListOf<RangeRecord>()

        var cursor = coveredEnd + 1
        while (cursor + BLOCK_SIZE <= foldableLimit) {
            val end = cursor + BLOCK_SIZE - 1
            val slice = fullHistory.subList(cursor, end + 1)
            val rangeId = "r-${sessionId.take(6)}-${cursor + 1}-${end + 1}"
            val summary = runCatching { summarizer(slice).trim() }
                .getOrDefault("")
                .ifBlank { summarizeBlockDeterministically(slice) }
                .take(420)
            val paths = extractArtifactPaths(slice)
            val rec = RangeRecord(
                rangeId = rangeId,
                sessionId = sessionId,
                startIndex = cursor,
                endIndex = end,
                summary = summary,
                artifactPaths = paths,
                verbatimMessages = slice.toList(),
            )
            sortedExisting.add(rec)
            newlyFolded.add(rec)
            cursor = end + 1
        }

        val activeStart = (sortedExisting.maxOfOrNull { it.endIndex + 1 } ?: 0).coerceAtMost(fullHistory.size)
        val activeTail = fullHistory.subList(activeStart, fullHistory.size)
        return AssembledWindow(
            foldedCards = sortedExisting,
            activeMessages = activeTail,
            newlyFolded = newlyFolded,
        )
    }

    /** 快速确定性摘要（零模型延迟、作为主路径或小模型回退）：提取用户核心诉求、关键结论与落盘产物。 */
    fun summarizeBlockDeterministically(slice: List<Pair<String, String>>): String {
        val userTopics = slice.filter { it.first == "user" }
            .map { it.second.replace(Regex("\\s+"), " ").trim().take(48) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(3)
        val lastAssistant = slice.lastOrNull { it.first == "assistant" }?.second
            ?.replace(Regex("\\s+"), " ")?.trim()?.take(140).orEmpty()
        return buildString {
            if (userTopics.isNotEmpty()) append("议题：").append(userTopics.joinToString("；"))
            if (lastAssistant.isNotEmpty()) {
                if (isNotEmpty()) append("。结论：")
                append(lastAssistant)
            }
        }.ifBlank { "已完成 ${slice.size} 条历史对话归档。" }
    }

    fun extractArtifactPaths(slice: List<Pair<String, String>>): List<String> =
        slice.asSequence()
            .flatMap { pathRegex.findAll(it.second).map { m -> m.value } }
            .filter { it.length in 4..120 && !it.startsWith("http", ignoreCase = true) }
            .distinct()
            .take(6)
            .toList()

    /** 关键词检索已折叠的历史记忆块（同时匹配摘要、产物路径与原文）。 */
    fun searchInRecords(records: List<RangeRecord>, query: String, limit: Int = 5): List<RangeRecord> {
        val tokens = query.trim().lowercase(Locale.US)
            .split(Regex("[\\s,，。；;、]+"))
            .filter { it.length >= 2 }
        if (tokens.isEmpty()) return records.takeLast(limit)
        return records
            .map { rec ->
                val haystack = (
                    rec.summary + " " +
                        rec.artifactPaths.joinToString(" ") + " " +
                        rec.verbatimMessages.joinToString(" ") { it.second }
                    ).lowercase(Locale.US)
                val score = tokens.count { it in haystack }
                rec to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /** Android 端持久化入口：加载会话已折叠块、按固定块增量折叠并落盘 SQLite。 */
    fun assembleAndPersist(
        context: Context,
        sessionId: String,
        fullHistory: List<Pair<String, String>>,
        summarizer: (List<Pair<String, String>>) -> String = ::summarizeBlockDeterministically,
    ): AssembledWindow {
        val existing = loadRanges(context, sessionId)
        val plan = planIncrementalWindow(sessionId, fullHistory, existing, summarizer)
        if (plan.newlyFolded.isNotEmpty()) {
            saveRanges(context, sessionId, plan.newlyFolded)
        }
        return plan
    }

    fun loadRanges(context: Context, sessionId: String): List<RangeRecord> {
        memoryCache[sessionId]?.let { return it.toList() }
        val loaded = runCatching { db(context).load(sessionId) }.getOrDefault(emptyList())
        memoryCache[sessionId] = loaded.toMutableList()
        return loaded
    }

    fun saveRanges(context: Context, sessionId: String, records: List<RangeRecord>) {
        if (records.isEmpty()) return
        val list = memoryCache.getOrPut(sessionId) { mutableListOf() }
        synchronized(list) {
            records.forEach { rec ->
                if (list.none { it.rangeId == rec.rangeId }) list.add(rec)
            }
        }
        runCatching { db(context).upsertAll(records) }
    }

    fun clearSession(context: Context, sessionId: String) {
        memoryCache.remove(sessionId)
        runCatching { db(context).deleteSession(sessionId) }
    }

    fun search(context: Context, sessionId: String, query: String, limit: Int = 5): JSONArray {
        val matches = searchInRecords(loadRanges(context, sessionId), query, limit)
        return JSONArray().apply {
            matches.forEach { rec ->
                put(
                    JSONObject()
                        .put("rangeId", rec.rangeId)
                        .put("range", "${rec.startIndex + 1}-${rec.endIndex + 1}")
                        .put("summary", rec.summary)
                        .put("artifacts", JSONArray(rec.artifactPaths)),
                )
            }
        }
    }

    fun expand(context: Context, sessionId: String, rangeId: String): JSONObject? {
        val rec = loadRanges(context, sessionId).firstOrNull { it.rangeId == rangeId } ?: return null
        val arr = JSONArray()
        rec.verbatimMessages.forEach { (role, text) ->
            arr.put(JSONObject().put("role", role).put("text", text.take(1_600)))
        }
        return JSONObject()
            .put("rangeId", rec.rangeId)
            .put("range", "${rec.startIndex + 1}-${rec.endIndex + 1}")
            .put("summary", rec.summary)
            .put("ephemeralNote", "此展开内容仅作本轮临时核对，不会永久写回上下文。")
            .put("messages", arr)
    }

    private class LedgerDb(context: Context) : SQLiteOpenHelper(context, "whale_context_ledger.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS folded_ranges (
                    range_id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    start_idx INTEGER NOT NULL,
                    end_idx INTEGER NOT NULL,
                    summary TEXT NOT NULL,
                    artifacts_json TEXT NOT NULL,
                    verbatim_json TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_folded_session ON folded_ranges(session_id, start_idx)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

        fun load(sessionId: String): List<RangeRecord> {
            val out = mutableListOf<RangeRecord>()
            readableDatabase.rawQuery(
                "SELECT range_id, start_idx, end_idx, summary, artifacts_json, verbatim_json, created_at_ms FROM folded_ranges WHERE session_id = ? ORDER BY start_idx ASC",
                arrayOf(sessionId),
            ).use { c ->
                while (c.moveToNext()) {
                    val arts = mutableListOf<String>()
                    val aArr = runCatching { JSONArray(c.getString(4)) }.getOrNull()
                    if (aArr != null) for (i in 0 until aArr.length()) arts.add(aArr.optString(i))
                    val msgs = mutableListOf<Pair<String, String>>()
                    val mArr = runCatching { JSONArray(c.getString(5)) }.getOrNull()
                    if (mArr != null) {
                        for (i in 0 until mArr.length()) {
                            val obj = mArr.optJSONObject(i) ?: continue
                            msgs.add(obj.optString("r", "user") to obj.optString("t", ""))
                        }
                    }
                    out.add(
                        RangeRecord(
                            rangeId = c.getString(0),
                            sessionId = sessionId,
                            startIndex = c.getInt(1),
                            endIndex = c.getInt(2),
                            summary = c.getString(3),
                            artifactPaths = arts,
                            verbatimMessages = msgs,
                            createdAtMs = c.getLong(6),
                        ),
                    )
                }
            }
            return out
        }

        fun upsertAll(records: List<RangeRecord>) {
            val w = writableDatabase
            w.beginTransaction()
            try {
                records.forEach { rec ->
                    val msgs = JSONArray()
                    rec.verbatimMessages.forEach { (r, t) -> msgs.put(JSONObject().put("r", r).put("t", t)) }
                    val cv = ContentValues().apply {
                        put("range_id", rec.rangeId)
                        put("session_id", rec.sessionId)
                        put("start_idx", rec.startIndex)
                        put("end_idx", rec.endIndex)
                        put("summary", rec.summary)
                        put("artifacts_json", JSONArray(rec.artifactPaths).toString())
                        put("verbatim_json", msgs.toString())
                        put("created_at_ms", rec.createdAtMs)
                    }
                    w.insertWithOnConflict("folded_ranges", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                }
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        }

        fun deleteSession(sessionId: String) {
            writableDatabase.delete("folded_ranges", "session_id = ?", arrayOf(sessionId))
        }
    }
}
