package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PetFaultMatrixTest {
    private fun stream(text: String, finish: String) =
        "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"$finish\"}]}\ndata: [DONE]\n"

    @Test fun truncatedOrUnterminatedStreamCannotBecomeSuccessfulReply() {
        for (source in listOf(stream("半句", "length"), stream("半句", "unexpected"),
            "data: {\"choices\":[{\"delta\":{\"content\":\"半句\"}}]}\ndata: [DONE]")) {
            assertThrows(IllegalStateException::class.java) { PetDirectChat.readStream(BufferedReader(StringReader(source))) }
        }
    }

    @Test fun oversizedResponseFailsBeforeUnlimitedUiGrowth() {
        assertThrows(IllegalStateException::class.java) {
            PetDirectChat.readStream(BufferedReader(StringReader(stream("鲸".repeat(70_000), "stop"))))
        }
    }

    @Test fun brokenJsonErrorEventsAndExcessToolArgumentsFailClosed() {
        val sources = listOf("data: {broken\ndata: [DONE]",
            "data: {\"error\":{\"message\":\"private-provider-detail\"}}\ndata: [DONE]",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":999}]}}]}\ndata: [DONE]",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"${"x".repeat(9000)}\"}}]}}]}\ndata: [DONE]")
        sources.forEach { source ->
            val error = assertThrows(IllegalStateException::class.java) { PetDirectChat.readStream(BufferedReader(StringReader(source))) }
            assertFalse(error.message.orEmpty().contains("private-provider-detail"))
        }
    }

    @Test fun oversizedHttpBodyAndBrokenTransferDoNotReturnPartialSuccess() {
        for (oversized in listOf(true, false)) {
            val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val executor = Executors.newSingleThreadExecutor()
            val peer = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = socket.getInputStream(); val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) { val c = input.read(); check(c >= 0); header.append(c.toChar()) }
                    val body = if (oversized) "x".repeat(600_000) else "half"
                    runCatching { socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 600000\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body.toByteArray()); flush()
                    } }
                }
            }
            try {
                val error = assertThrows(IllegalStateException::class.java) {
                    PetModelHttp.execute(okhttp3.Request.Builder().url("http://127.0.0.1:${server.localPort}/").build(), 2000) { it.readText() }
                }
                assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(if (oversized) "大小上限" else "连接中断"))
                peer.get(3, TimeUnit.SECONDS)
            } finally { server.close(); executor.shutdownNow() }
        }
    }

    @Test fun httpFailuresAreSpecificDoNotReplayAndNextRequestWorks() {
        val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val codes = listOf(401, 429, 503, 302, 200)
        val peer = executor.submit {
            codes.forEach { code -> server.accept().use { socket ->
                socket.soTimeout = 3000
                val input = socket.getInputStream(); val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) { val c = input.read(); check(c >= 0); header.append(c.toChar()) }
                val body = if (code == 200) "recovered" else "secret-server-details"
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $code Test\r\nContent-Length: ${body.length}\r\nConnection: close\r\nLocation: http://127.0.0.1:${server.localPort}/redirect\r\n\r\n$body".toByteArray()); flush()
                }
            } }
        }
        try {
            val request = okhttp3.Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()
            listOf("凭据", "频繁", "暂时不可用", "重定向").forEach { expected ->
                val error = assertThrows(IllegalStateException::class.java) { PetModelHttp.execute(request, 2000) { it.readText() } }
                assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(expected))
                assertFalse(error.message.orEmpty().contains("secret-server-details"))
            }
            assertEquals("recovered", PetModelHttp.execute(request, 2000) { it.readText() })
            peer.get(5, TimeUnit.SECONDS)
        } finally { server.close(); executor.shutdownNow() }
    }

    @Test fun refusedConnectionReleasesTheSameExecutorForNextTurn() {
        val port = ServerSocket(0).use { it.localPort }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val error = executor.submit<Throwable?> { runCatching {
                PetReplyOperation(2000).run { PetModelHttp.execute(okhttp3.Request.Builder().url("http://127.0.0.1:$port/").build(), 1000) { it.readText() } }
            }.exceptionOrNull() }.get(3, TimeUnit.SECONDS)
            assertNotNull(error)
            assertEquals("next", executor.submit<String> { PetReplyOperation().run { "next" } }.get(1, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }
}
