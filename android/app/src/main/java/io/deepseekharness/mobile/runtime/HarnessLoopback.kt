package io.deepseekharness.mobile.runtime

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/** Readiness uses a direct, bounded loopback HTTP exchange, independent of HTTP proxy hooks. */
internal object HarnessLoopback {
    fun hasChallenge(port: Int, timeoutMs: Int, expected: String): Boolean {
        require(port in 1..65535 && timeoutMs > 0)
        Socket(Proxy.NO_PROXY).use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            socket.soTimeout = timeoutMs
            val request = "GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAccept: text/html\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            val input = socket.getInputStream()
            val buffer = java.io.ByteArrayOutputStream()
            var marker = 0
            while (buffer.size() < 8192 && marker < 4) {
                val byte = input.read()
                if (byte < 0 || byte > 127 || byte == 0) return false
                buffer.write(byte)
                marker = when {
                    marker == 0 && byte == 13 -> 1
                    marker == 1 && byte == 10 -> 2
                    marker == 2 && byte == 13 -> 3
                    marker == 3 && byte == 10 -> 4
                    byte == 13 -> 1
                    else -> 0
                }
            }
            if (marker != 4) return false
            val lines = buffer.toString("US-ASCII").split("\r\n")
            if (!lines.first().matches(Regex("HTTP/1\\.[01] 401(?: .*)?"))) return false
            val challenges = lines.drop(1).mapNotNull { line ->
                val at = line.indexOf(':')
                if (at > 0 && line.substring(0, at).equals("WWW-Authenticate", ignoreCase = true))
                    line.substring(at + 1).trim() else null
            }
            return challenges.size == 1 && challenges.single() == expected
        }
    }
}
