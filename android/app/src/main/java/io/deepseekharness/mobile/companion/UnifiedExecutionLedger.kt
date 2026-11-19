package io.deepseekharness.mobile.companion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Super Agent 单写者统一执行与事件台账（SQLite WAL + synchronous=FULL）。
 *
 * 解决多组件（domain.json / Worker / Goal / 原生执行）状态分裂问题：
 * 以 operationId 唯一标识每一次外部副作用操作，严格区分 pending / dispatching / completed / unknown / failed；
 * 进程在 dispatching 阶段崩溃重启后，强制标为 unknown 进入只读对账，绝不允许盲目重放。
 */
internal class UnifiedExecutionLedger(context: Context) {
    enum class State(val wire: String) {
        PENDING("pending"),
        DISPATCHING("dispatching"),
        COMPLETED("completed"),
        UNKNOWN("unknown"),
        FAILED("failed"),
        ;

        companion object {
            fun parse(raw: String?): State = entries.firstOrNull { it.wire == raw } ?: UNKNOWN
        }
    }

    data class Entry(
        val operationId: String,
        val ticketId: String,
        val workerId: String,
        val generation: Long,
        val actionKind: String,
        val state: State,
        val summary: String,
        val updatedAtMs: Long,
    )

    private val helper = object : SQLiteOpenHelper(context.applicationContext, "whale_unified_exec_ledger.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.enableWriteAheadLogging()
            db.execSQL("PRAGMA synchronous=FULL")
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS execution_ledger (
                    operation_id TEXT PRIMARY KEY,
                    ticket_id TEXT NOT NULL,
                    worker_id TEXT NOT NULL,
                    generation INTEGER NOT NULL,
                    action_kind TEXT NOT NULL,
                    state TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    updated_at_ms INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ledger_state ON execution_ledger(state, updated_at_ms)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ledger_ticket ON execution_ledger(ticket_id, updated_at_ms)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    fun recordIntent(
        operationId: String,
        ticketId: String,
        workerId: String,
        generation: Long,
        actionKind: String,
        summary: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("operation_id", operationId)
            put("ticket_id", ticketId)
            put("worker_id", workerId)
            put("generation", generation)
            put("action_kind", actionKind)
            put("state", State.PENDING.wire)
            put("summary", summary.take(1000))
            put("updated_at_ms", nowMs)
        }
        db.insertWithOnConflict("execution_ledger", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun transition(
        operationId: String,
        newState: State,
        summary: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("state", newState.wire)
            if (summary != null) put("summary", summary.take(1000))
            put("updated_at_ms", nowMs)
        }
        db.update("execution_ledger", values, "operation_id = ?", arrayOf(operationId)) > 0
    }

    /** 进程重启时将所有遗留 dispatching 状态收敛为 unknown，禁止自动盲重放。 */
    fun markInFlightAsUnknown(nowMs: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("state", State.UNKNOWN.wire)
            put("updated_at_ms", nowMs)
        }
        db.update("execution_ledger", values, "state = ?", arrayOf(State.DISPATCHING.wire))
    }

    /** 仅当操作仍处于 pending 或明确 failed（未发生外部副作用）时允许重试；unknown/dispatching/completed 一律禁止盲重放。 */
    fun canReplay(operationId: String): Boolean = synchronized(lock) {
        val current = get(operationId) ?: return@synchronized true
        current.state == State.PENDING || current.state == State.FAILED
    }

    fun get(operationId: String): Entry? = synchronized(lock) {
        helper.readableDatabase.rawQuery(
            "SELECT operation_id, ticket_id, worker_id, generation, action_kind, state, summary, updated_at_ms FROM execution_ledger WHERE operation_id = ?",
            arrayOf(operationId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else readRow(cursor)
        }
    }

    fun pendingUnknownReconciliations(limit: Int = 50): List<Entry> = synchronized(lock) {
        val bounded = limit.coerceIn(1, 200)
        val out = ArrayList<Entry>()
        helper.readableDatabase.rawQuery(
            "SELECT operation_id, ticket_id, worker_id, generation, action_kind, state, summary, updated_at_ms FROM execution_ledger WHERE state = ? ORDER BY updated_at_ms DESC LIMIT ?",
            arrayOf(State.UNKNOWN.wire, bounded.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) out += readRow(cursor)
        }
        out
    }

    private fun readRow(cursor: android.database.Cursor) = Entry(
        operationId = cursor.getString(0),
        ticketId = cursor.getString(1),
        workerId = cursor.getString(2),
        generation = cursor.getLong(3),
        actionKind = cursor.getString(4),
        state = State.parse(cursor.getString(5)),
        summary = cursor.getString(6),
        updatedAtMs = cursor.getLong(7),
    )

    companion object {
        private val lock = Any()
    }
}
