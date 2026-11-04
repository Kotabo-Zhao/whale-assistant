package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test

class PetReplyTraceTest {
    @Test fun recordsOnlyStageAndMonotonicDuration() {
        var time = 10L
        val events = mutableListOf<Map<String,String>>()
        val trace = PetReplyTrace({ events.add(it) },{ time })
        assertEquals("private answer",trace.measure(PetReplyTrace.Stage.REPLY) { time = 75; "private answer" })
        assertEquals(mapOf("phase" to "reply","result" to "started","elapsed_ms" to "0"),events[0])
        assertEquals(mapOf("phase" to "reply","result" to "ok","elapsed_ms" to "65"),events[1])
    }
    @Test fun failuresKeepTheOriginalExceptionWithoutRecordingItsText() {
        val events = mutableListOf<Map<String,String>>()
        val trace = PetReplyTrace({ events.add(it) },{ 0 })
        val failure = java.net.SocketTimeoutException("sk-secret https://private.example user-content")
        assertSame(failure,assertThrows(java.net.SocketTimeoutException::class.java) {
            trace.measure(PetReplyTrace.Stage.ROUTE) { throw failure }
        })
        assertEquals("NETWORK_TIMEOUT",events.last()["code"])
        assertEquals("failed",events.last()["result"])
        assertFalse(events.toString().contains("secret"))
        assertFalse(events.toString().contains("private"))
        assertThrows(InterruptedException::class.java) { trace.measure(PetReplyTrace.Stage.HANDOFF) { throw InterruptedException("private") } }
        assertEquals("cancelled",events.last()["result"])
    }
    @Test fun failedDiagnosticStorageDoesNotRepeatOrFailTheAction() {
        var calls = 0
        val trace = PetReplyTrace({ throw IllegalStateException("disk unavailable") },{ 0 })
        assertEquals(42,trace.measure(PetReplyTrace.Stage.SUBMIT) { calls++; 42 })
        assertEquals(1,calls)
    }
}
