package io.deepseekharness.mobile.companion

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PetReplyOperationTest {
    @Test fun lateCancellationCannotTurnPersistedReplyIntoRetryableFailure() {
        val operation = PetReplyOperation()
        assertEquals("saved", operation.run {
            PetReplyOperation.commitReply { operation.cancel(); "saved" }
        })
        operation.cancel()
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test fun cancellationBeforeCommitRetainsPendingInsteadOfSavingReply() {
        val operation = PetReplyOperation()
        var saved = false
        assertThrows(java.io.InterruptedIOException::class.java) {
            operation.run { operation.cancel(); PetReplyOperation.commitReply { saved = true } }
        }
        assertFalse(saved)
    }

    private fun request(server: ServerSocket) = Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()
    @Test fun heartbeatCannotExtendAbsoluteModelDeadline() {
        val server=ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))
        val started=CountDownLatch(1)
        val feed=Thread {
            runCatching { server.accept().use { socket ->
                val output=socket.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray()); output.flush(); started.countDown()
                while(!Thread.currentThread().isInterrupted) { output.write("3\r\n:\n\n\r\n".toByteArray()); output.flush(); Thread.sleep(20) }
            } }
        }.apply { isDaemon=true; start() }
        val begin=System.nanoTime()
        try {
            val error=runCatching { PetModelHttp.execute(request(server),350) { reader -> while(reader.readLine()!=null) { }; "unexpected" } }.exceptionOrNull()
            assertTrue(started.await(1,TimeUnit.SECONDS)); assertNotNull(error)
            assertTrue("心跳不能无限延长请求",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin)<3000)
        } finally { server.close(); feed.interrupt(); feed.join(1000) }
    }
    @Test fun cancellingUnresponsiveHeadersReleasesExecutorAndNextTurn() {
        val server=ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))
        val accepted=CountDownLatch(1)
        val release=CountDownLatch(1)
        val peer=Thread { runCatching { server.accept().use { accepted.countDown(); release.await(5,TimeUnit.SECONDS) } } }.apply { isDaemon=true; start() }
        val executor=Executors.newSingleThreadExecutor()
        val operation=PetReplyOperation(5000)
        try {
            val result=executor.submit<Throwable?> { runCatching { operation.run { PetModelHttp.execute(request(server),5000) { it.readText() } } }.exceptionOrNull() }
            assertTrue(accepted.await(3,TimeUnit.SECONDS))
            operation.cancel()
            assertTrue(result.get(2,TimeUnit.SECONDS)?.message.orEmpty().contains("回复等待已结束"))
            assertEquals("next",executor.submit<String> { PetReplyOperation(1000).run { "next" } }.get(2,TimeUnit.SECONDS))
            assertFalse(executor.submit<Boolean> { Thread.currentThread().isInterrupted }.get())
        } finally { release.countDown(); server.close(); executor.shutdownNow(); peer.join(1000) }
    }
    @Test fun absoluteOperationDeadlineInterruptsHarnessStyleWait() {
        val operation=PetReplyOperation(150)
        val error=runCatching { operation.run { CountDownLatch(1).await(10,TimeUnit.SECONDS) } }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("回复等待超时"))
        assertFalse(Thread.currentThread().isInterrupted)
    }
}
