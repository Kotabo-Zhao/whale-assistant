package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BubbleSessionStreamTest {
    private fun event(seq: Int, type: String, data: JSONObject) = JSONObject().put("seq", seq).put("type", type).put("data", data)
    private fun turn(seq: Int, number: Int) = event(seq, "turn/start", JSONObject().put("turn", number))
    private fun user(seq: Int, id: String) = event(seq, "user/message", JSONObject().put("source", JSONObject().put("kind", "user").put("rpcId", id)))
    private fun answer(seq: Int, number: Int, text: String) = event(seq, "assistant/message", JSONObject().put("turn", number).put("step", 1)
        .put("message", JSONObject().put("id", "m$seq").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))))).put("surfaceOp", "append")
    private fun end(seq: Int, number: Int) = event(seq, "turn/end", JSONObject().put("turn", number).put("reason", JSONObject().put("kind", "completed")))
    private fun started() = BubbleSessionStream("new").apply { event(turn(1, 4)); event(user(2, "new")) }

    @Test fun oldTurnCannotFinishNewRequest() {
        val fold = started()
        fold.event(answer(3, 3, "old")); fold.event(end(4, 3))
        assertNull(fold.finished); assertEquals("", fold.text())
        fold.event(answer(5, 4, "new")); fold.event(end(6, 4))
        assertEquals("new", fold.text()); assertEquals("completed", fold.finished)
    }
    @Test fun snapshotRestoresCompletedRequestWithoutResendingIt() {
        val fold = BubbleSessionStream("new")
        fold.snapshot(JSONObject().put("records", JSONArray(listOf(turn(1, 4), user(2, "new"), answer(3, 4, "done"), end(4, 4))
            .map { JSONObject().put("event", it) })))
        assertTrue(fold.received); assertEquals("completed", fold.finished); assertEquals("done", fold.text())
    }
    @Test fun liveTextSettlesOnceAndReplacementRemovesOldText() {
        val fold = started()
        assertTrue(fold.frame(JSONObject("""{"type":"start","revision":1,"attemptId":"a","turn":4,"step":1}""")))
        assertTrue(fold.frame(JSONObject("""{"type":"chunk","revision":2,"attemptId":"a","index":0,"chunk":{"type":"text-delta","index":0,"text":"hello"}}""")))
        assertEquals("hello", fold.text())
        fold.event(answer(3, 4, "hello"))
        assertEquals("hello", fold.text())
        fold.frame(JSONObject("""{"type":"end","revision":3,"attemptId":"a","index":1,"outcome":{"kind":"committed","seq":3}}"""))
        fold.event(answer(4, 4, "correct").put("surfaceOp", JSONObject().put("op", "replace").put("startSeq", 3).put("endSeq", 3)))
        assertEquals("correct", fold.text())
    }
    @Test fun missedFrameRequiresRebaselineAndAbandonedAttemptIsRemoved() {
        val fold = started()
        assertFalse(fold.frame(JSONObject("""{"type":"start","revision":2,"attemptId":"a","turn":4,"step":1}""")))
        fold.frame(JSONObject("""{"type":"start","revision":1,"attemptId":"a","turn":4,"step":1}"""))
        fold.frame(JSONObject("""{"type":"chunk","revision":2,"attemptId":"a","index":0,"chunk":{"type":"text-delta","index":0,"text":"draft"}}"""))
        fold.frame(JSONObject("""{"type":"end","revision":3,"attemptId":"a","index":1,"outcome":{"kind":"abandoned"}}"""))
        assertEquals("", fold.text())
    }
    @Test fun continuedQuestionWaitsAndAnswerOwnsFollowingTurn() {
        val fold = started()
        val args = JSONObject().put("questions", JSONArray().put(JSONObject().put("id", "file").put("question", "哪个文件？")))
        fold.event(event(3, "tool/call", JSONObject().put("turn", 4).put("name", "ask_user_question").put("callId", "call").put("arguments", args.toString())))
        fold.event(end(4, 4)); assertNull(fold.finished); assertNotNull(fold.question)
        fold.event(turn(5, 5))
        fold.event(event(6, "user/message", JSONObject().put("source", JSONObject().put("kind", "user-question-reply").put("callId", "call"))))
        fold.event(answer(7, 5, "继续完成")); fold.event(end(8, 5))
        assertEquals(5, fold.turn); assertNull(fold.question); assertEquals("继续完成", fold.text()); assertEquals("completed", fold.finished)
    }
    @Test fun firstRequestCanBePersistedBeforeAnyCompletedPair() {
        val state = CompanionBubbleHistory.Snapshot("session-one", "a".repeat(64), emptyList(), """{"requestId":"new","user":"你好","prompt":"你好"}""", true)
        assertEquals(state, CompanionBubbleHistory.decode(CompanionBubbleHistory.encode(state)))
    }
}
