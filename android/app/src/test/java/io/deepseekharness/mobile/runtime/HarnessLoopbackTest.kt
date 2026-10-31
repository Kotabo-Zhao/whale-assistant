package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class HarnessLoopbackTest {
    @Test fun localProbeBypassesConfiguredProxy() {
        val previous = ProxySelector.getDefault()
        val calls = AtomicInteger()
        val proxy = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                calls.incrementAndGet()
                return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1)))
            }
            override fun connectFailed(uri: URI?, address: SocketAddress?, failure: java.io.IOException?) = Unit
        }
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = Thread {
                server.accept().use { socket ->
                    socket.getInputStream().bufferedReader().readLine()
                    socket.getOutputStream().write("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
            worker.start()
            try {
                ProxySelector.setDefault(proxy)
                assertTrue(HarnessLoopback.hasChallenge(server.localPort, 2000, "Basic test"))
                assertEquals(0, calls.get())
            } finally {
                ProxySelector.setDefault(previous)
                server.close()
                worker.join(2000)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { HarnessLoopback.hasChallenge(0, 1000, "Basic test") }
    }

    @Test fun rejectsWrongStatusAndDuplicateChallenges() {
        fun probe(response: String): Boolean = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = Thread {
                server.accept().use { socket ->
                    socket.getInputStream().bufferedReader().readLine()
                    socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                }
            }
            worker.start()
            try { HarnessLoopback.hasChallenge(server.localPort, 2000, "Basic test") }
            finally { server.close(); worker.join(2000) }
        }
        assertFalse(probe("HTTP/1.1 403 Forbidden\r\nWWW-Authenticate: Basic test\r\n\r\n"))
        assertFalse(probe("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic test\r\nWWW-Authenticate: Basic other\r\n\r\n"))
    }
}
