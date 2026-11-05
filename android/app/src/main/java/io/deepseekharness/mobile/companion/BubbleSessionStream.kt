package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject

/** A view of one submitted request, not a second conversation or a second model reply. */
internal class BubbleSessionStream(private val requestId: String) {
    private val events = sortedMapOf<Long, JSONObject>()
    private val blocks = sortedMapOf<Int, String>()
    private var attempt: String? = null
    private var attemptTurn = -1
    private var attemptStep = -1
    private var revision = 0L
    private var nextIndex = 0
    var turn: Int? = null
        private set
    var received = false
        private set
    var currentTurnOwned = false
        private set
    var finished: String? = null
        private set
    var question: JSONObject? = null
        private set
    private var questionCall: String? = null
    val records: List<JSONObject> get() = events.values.toList()

    fun snapshot(value: JSONObject) {
        events.clear(); blocks.clear(); attempt = null; turn = null; received = false; currentTurnOwned = false; finished = null; question = null
        value.optJSONArray("records")?.objects()?.forEach { event(it.getJSONObject("event")) }
        val active = value.optJSONObject("projections")?.optJSONObject("values")?.optJSONObject("userQuestions")?.optJSONArray("active")
        active?.objects()?.lastOrNull { it.optString("callId") == questionCall }?.let { question = it; finished = null }
        val baseline = value.optJSONObject("assistantStream")
        revision = baseline?.optLong("revision") ?: 0
        baseline?.optJSONObject("activeAttempt")?.let {
            attempt = it.getString("attemptId"); attemptTurn = it.getInt("turn"); attemptStep = it.getInt("step"); nextIndex = it.getInt("nextIndex")
            it.optJSONArray("stream")?.objects()?.forEach { row ->
                when (row.optString("type")) {
                    "text-chunks" -> blocks[row.getInt("index")] = (blocks[row.getInt("index")] ?: "") +
                        (row.getJSONArray("texts").let { texts -> (0 until texts.length()).joinToString("") { i -> texts.getString(i) } })
                    "chunk" -> chunk(row.getJSONObject("chunk"))
                }
            }
        }
    }

    fun event(event: JSONObject) {
        events[event.getLong("seq")] = event
        // Recompute request ownership on a snapshot/page too: turn/start precedes user/message.
        var current = -1
        var owned: Int? = null
        for (entry in events.values) {
            val data = entry.optJSONObject("data") ?: continue
            when (entry.optString("type")) {
                "turn/start" -> current = data.getInt("turn")
                "user/message" -> {
                    val source = data.optJSONObject("source")
                    if (source?.optString("rpcId") == requestId ||
                        (questionCall != null && source?.optString("kind") == "user-question-reply" && source.optString("callId") == questionCall)) {
                        received = true; owned = current
                        if (source?.optString("kind") == "user-question-reply") question = null
                    }
                }
            }
        }
        turn = owned
        currentTurnOwned = current >= 0 && owned == current
        val data = event.optJSONObject("data") ?: return
        when (event.optString("type")) {
            "tool/call" -> if (data.optString("name") == "ask_user_question" && data.optInt("turn", current) == turn) {
                val args = runCatching { JSONObject(data.optString("arguments")) }.getOrNull()
                args?.optJSONArray("questions")?.let { question = JSONObject().put("callId", data.getString("callId")).put("questions", it); questionCall = data.getString("callId"); finished = null }
            }
            "tool/result" -> {
                val message = data.optJSONObject("message")
                if (questionCall != null && message != null && message.optString("toolCallId") == questionCall) {
                    val text = message.optJSONArray("content")?.objects()?.filter { it.optString("type") == "text" }?.joinToString("\n") { it.optString("text") }.orEmpty()
                    val result = runCatching { JSONObject(text) }.getOrNull()
                    if (result?.optBoolean("pending") == true || data.optJSONObject("error")?.optString("code") == "TOOL_OUTCOME_UNKNOWN") question?.put("state", "continued")
                    else question = null
                }
            }
            "turn/end" -> if (data.optInt("turn") == turn && question == null) finished = data.optJSONObject("reason")?.optString("kind") ?: "failed"
        }
    }

    /** False requests a fresh baseline; never append a suffix after a missed stream frame. */
    fun frame(frame: JSONObject): Boolean {
        if (frame.getLong("revision") != revision + 1) return false
        revision++
        when (frame.getString("type")) {
            "start" -> { attempt = frame.getString("attemptId"); attemptTurn = frame.getInt("turn"); attemptStep = frame.getInt("step"); nextIndex = 0; blocks.clear() }
            "chunk" -> {
                if (frame.optString("attemptId") != attempt) return true
                if (frame.getInt("index") != nextIndex++) return false
                chunk(frame.getJSONObject("chunk"))
            }
            "end" -> if (frame.optString("attemptId") == attempt) { blocks.clear(); attempt = null }
        }
        return true
    }

    private fun chunk(chunk: JSONObject) {
        val index = chunk.optInt("index")
        when (chunk.optString("type")) {
            "text-delta" -> blocks[index] = (blocks[index] ?: "") + chunk.getString("text")
            "block-end" -> chunk.optJSONObject("block")?.takeIf { it.optString("type") == "text" }?.let { blocks[index] = it.getString("text") }
        }
    }

    fun text(): String {
        val visible = sortedMapOf<Long, JSONObject>()
        for ((seq, event) in events) {
            event.optJSONObject("surfaceOp")?.takeIf { it.optString("op") == "replace" }?.let { op ->
                visible.keys.filter { it in op.getLong("startSeq")..op.getLong("endSeq") }.forEach { visible.remove(it) }
            }
            if (event.optString("type") == "assistant/message") visible[seq] = event
        }
        val messages = visible.values.filter { it.optJSONObject("data")?.optInt("turn") == turn }
        val committed = messages.mapNotNull { it.optJSONObject("data")?.optJSONObject("message")?.optJSONArray("content") }
            .map { parts -> parts.objects().filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") } }.filter { it.isNotBlank() }
        val settled = messages.any { it.getJSONObject("data").optInt("step") == attemptStep }
        val live = if (attemptTurn == turn && !settled) blocks.values.joinToString("\n") else ""
        return (committed + listOf(live).filter { it.isNotBlank() }).joinToString("\n\n")
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
