package io.deepseekharness.mobile.companion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 融合 `deepseek-ai/dsh-superagent-prototype`（docs/007-architecture.md）四大高可靠工程机制：
 *
 * 1. **relay vs notice 严格分离**：
 *    - `relay`（高优先级唤醒）：Worker 最终成品汇总或提问，写入持久化 [Outbox] 并推送给主对话；
 *    - `notice`（零打扰静默注入）：归档完成、自动回收、策略变更等背景事实，只静默附加到下一轮上下文，绝不唤醒模型或弹打扰气泡。
 * 2. **事务型 Outbox 单出口防丢回执**：
 *    - 每条异步上行消息带稳定 `outboxKey` 与单调顺序，在 WebView 休眠/切后台时保留 `pending`，
 *      前端挂载或恢复时调用 [flushPendingRelays] 秒收，确认渲染后 [settleOutbox] 防重。
 * 3. **未读追问自动回流（Unread Follow-ups Return to Eval）**：
 *    - Worker 结束前检查未消费追问，自动打包回流，杜绝“最后 1 秒追问石沉大海”。
 * 4. **安卓杀后台中断恢复（assignmentInterrupted）& Worker 主动退回（handback）**：
 *    - 派发 Worker 时落盘 SQLite，异常被杀重启后自动以最老优先带 `recovery note` 续跑；
 *    - 每个输入最多允许 `handback` 一次，防止 Router <-> Worker 乒乓死循环。
 */
internal object WhaleSuperagentRelay {

    data class OutboxItem(
        val key: String,
        val source: String,
        val title: String,
        val reply: String,
        val createdAtMs: Long,
    ) {
        fun busPayload(): JSONObject = JSONObject()
            .put("action", "assistant_push")
            .put("outboxKey", key)
            .put("source", source)
            .put("title", title)
            .put("reply", reply)
    }

    data class PersistedWorker(
        val workerId: String,
        val title: String,
        val prompt: String,
        val startedAtMs: Long,
    )

    private const val MAX_NOTICES_PER_SESSION = 6
    private const val RECOVERY_MAX_AGE_MS = 2 * 60 * 60 * 1000L

    private val sessionNotices = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
    private val handedBackKeys = ConcurrentHashMap.newKeySet<String>()
    private val resumedOnce = AtomicBoolean(false)
    @Volatile private var dbHelper: RelayDb? = null

    private fun db(context: Context): RelayDb {
        dbHelper?.let { return it }
        return synchronized(this) {
            dbHelper ?: RelayDb(context.applicationContext).also { dbHelper = it }
        }
    }

    // -------------------------------------------------------------------------
    // 1. Silent Notices (Zero-Wakeup Background Context)
    // -------------------------------------------------------------------------

    fun recordNotice(sessionId: String = "whale-main", text: String) {
        val clean = text.trim().take(240)
        if (clean.isEmpty()) return
        val list = sessionNotices.getOrPut(sessionId) { CopyOnWriteArrayList() }
        if (list.lastOrNull() != clean) {
            list.add(clean)
            while (list.size > MAX_NOTICES_PER_SESSION) {
                list.removeAt(0)
            }
        }
    }

    fun drainNotices(sessionId: String = "whale-main"): List<String> {
        val list = sessionNotices[sessionId] ?: return emptyList()
        if (list.isEmpty()) return emptyList()
        val snapshot = list.toList()
        list.clear()
        return snapshot
    }

    fun formatNoticesForPrompt(notices: List<String>): String {
        if (notices.isEmpty()) return ""
        return buildString {
            append("\n[系统静默背景通知（无需专门回复，仅作上下文参考）]\n")
            notices.forEach { append("- ").append(it).append("\n") }
        }
    }

    // -------------------------------------------------------------------------
    // 2. Durable Outbox (Single Writer + Dedup + Resume Flush)
    // -------------------------------------------------------------------------

    fun enqueueRelay(
        context: Context,
        key: String,
        source: String,
        title: String,
        reply: String,
    ): OutboxItem {
        val item = OutboxItem(
            key = key.trim(),
            source = source.trim(),
            title = title.trim().take(120),
            reply = reply.trim(),
            createdAtMs = System.currentTimeMillis(),
        )
        runCatching { db(context).insertOutbox(item) }
        WhaleAppControlBus.emit(item.busPayload())
        return item
    }

    fun flushPendingRelays(context: Context): Int {
        val pending = runCatching { db(context).listPendingOutbox(limit = 10) }.getOrDefault(emptyList())
        pending.forEach { WhaleAppControlBus.emit(it.busPayload()) }
        return pending.size
    }

    fun settleOutbox(context: Context, key: String): Boolean {
        val clean = key.trim()
        if (clean.isEmpty()) return false
        return runCatching { db(context).markOutboxSettled(clean) }.getOrDefault(false)
    }

    // -------------------------------------------------------------------------
    // 3. Unread Follow-ups Return to Eval (Zero-Drop Race Protection)
    // -------------------------------------------------------------------------

    fun collectOrphanedFollowups(unreadPairs: List<Pair<String, String>>): String {
        val msgs = unreadPairs.map { it.second.trim() }.filter { it.isNotEmpty() }.distinct()
        if (msgs.isEmpty()) return ""
        return msgs.joinToString("\n")
    }

    // -------------------------------------------------------------------------
    // 4. Worker Handback (Once-Per-Input Anti-Loop Guard)
    // -------------------------------------------------------------------------

    fun tryMarkHandedBack(workerId: String, inputText: String): Boolean {
        val fp = "$workerId::${inputText.trim().take(160)}"
        return handedBackKeys.add(fp)
    }

    fun resetHandbackCacheForTest() {
        handedBackKeys.clear()
        sessionNotices.clear()
    }

    // -------------------------------------------------------------------------
    // 5. Assignment Interrupted Recovery (Process-Death Resilience)
    // -------------------------------------------------------------------------

    fun recordWorkerStarted(context: Context, workerId: String, title: String, prompt: String) {
        runCatching {
            db(context).upsertWorker(
                PersistedWorker(
                    workerId = workerId,
                    title = title.take(120),
                    prompt = prompt.take(4000),
                    startedAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun recordWorkerEnded(context: Context, workerId: String) {
        runCatching { db(context).deleteWorker(workerId) }
    }

    fun buildRecoveryPrompt(worker: PersistedWorker, unreadFollowups: String = ""): String = buildString {
        append("[中断恢复提示] 上次后台任务「").append(worker.title).append("」因应用切后台或重启被系统中断，请检查已有文件产物并继续完成剩余工作，不要重复做已完成步骤。\n\n")
        append("[原任务目标]\n").append(worker.prompt)
        if (unreadFollowups.isNotBlank()) {
            append("\n\n[中断期间用户追加的未读指令]\n").append(unreadFollowups)
        }
    }

    /** 应用启动或引擎首次激活时扫描被系统杀掉的未完成任务，按最老优先自动续跑。 */
    fun resumeInterruptedIfNeeded(context: Context) {
        if (!resumedOnce.compareAndSet(false, true)) return
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val liveIds = WhaleNativeWorkerPool.activeIds()
        val persisted = runCatching { db(app).listWorkersOldestFirst() }.getOrDefault(emptyList())
        persisted.forEach { w ->
            if (w.workerId in liveIds) return@forEach
            if (now - w.startedAtMs > RECOVERY_MAX_AGE_MS) {
                recordWorkerEnded(app, w.workerId)
                recordNotice("whale-main", "超过 2 小时的中断后台任务「${w.title}」已自动归档清理。")
                return@forEach
            }
            val unread = collectOrphanedFollowups(WhaleNativeWorkerPool.unreadFollowups(w.workerId))
            val recoveryPrompt = buildRecoveryPrompt(w, unread)
            val ctrl = WhaleNativeAgentEngine.RunControl()
            recordNotice("whale-main", "检测到后台任务「${w.title}」曾被系统中断，已自动恢复续跑。")
            WhaleNativeWorkerPool.submit(title = w.title, id = w.workerId, priorityBy = "launch") {
                WhaleNativeWorkerPool.attachControl(w.workerId, ctrl)
                try {
                    val res = WhaleNativeAgentEngine(app).send(
                        userMessage = recoveryPrompt,
                        history = emptyList(),
                        sessionId = "whale-worker-${w.workerId}",
                        onProgress = { round, step, preview ->
                            WhaleNativeWorkerPool.updateProgress(w.workerId, round = round, step = step, preview = preview)
                            WhaleNativeLiveNotification.update(app, title = w.title, subtitle = step.take(40), ongoing = true, indeterminate = true)
                        },
                        control = ctrl,
                    )
                    WhaleExecutionRouter.onWorkerFinished(app, w.workerId, w.title, res.reply, ok = true)
                    res.reply
                } catch (err: Throwable) {
                    WhaleExecutionRouter.onWorkerFinished(app, w.workerId, w.title, err.message ?: err.javaClass.simpleName, ok = false)
                    throw err
                }
            }
        }
    }

    private class RelayDb(context: Context) : SQLiteOpenHelper(context, "whale_superagent_relay.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS outbox (
                    outbox_key TEXT PRIMARY KEY,
                    source TEXT NOT NULL,
                    title TEXT NOT NULL,
                    reply TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS active_workers (
                    worker_id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    prompt TEXT NOT NULL,
                    started_at_ms INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

        fun insertOutbox(item: OutboxItem) {
            val cv = ContentValues().apply {
                put("outbox_key", item.key)
                put("source", item.source)
                put("title", item.title)
                put("reply", item.reply)
                put("status", "pending")
                put("created_at_ms", item.createdAtMs)
            }
            writableDatabase.insertWithOnConflict("outbox", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        }

        fun listPendingOutbox(limit: Int): List<OutboxItem> {
            val out = mutableListOf<OutboxItem>()
            readableDatabase.rawQuery(
                "SELECT outbox_key, source, title, reply, created_at_ms FROM outbox WHERE status = 'pending' ORDER BY created_at_ms ASC LIMIT ?",
                arrayOf(limit.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(OutboxItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)))
                }
            }
            return out
        }

        fun markOutboxSettled(key: String): Boolean {
            val cv = ContentValues().apply { put("status", "settled") }
            return writableDatabase.update("outbox", cv, "outbox_key = ?", arrayOf(key)) > 0
        }

        fun upsertWorker(w: PersistedWorker) {
            val cv = ContentValues().apply {
                put("worker_id", w.workerId)
                put("title", w.title)
                put("prompt", w.prompt)
                put("started_at_ms", w.startedAtMs)
            }
            writableDatabase.insertWithOnConflict("active_workers", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }

        fun deleteWorker(workerId: String) {
            writableDatabase.delete("active_workers", "worker_id = ?", arrayOf(workerId))
        }

        fun listWorkersOldestFirst(): List<PersistedWorker> {
            val out = mutableListOf<PersistedWorker>()
            readableDatabase.rawQuery(
                "SELECT worker_id, title, prompt, started_at_ms FROM active_workers ORDER BY started_at_ms ASC",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(PersistedWorker(c.getString(0), c.getString(1), c.getString(2), c.getLong(3)))
                }
            }
            return out
        }
    }
}
