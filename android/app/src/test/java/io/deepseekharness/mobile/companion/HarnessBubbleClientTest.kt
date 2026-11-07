package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.HarnessAccess
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HarnessBubbleClientTest {
    @Test fun closedPortReleasesBothReadinessWaitsWithoutSubmitting() {
        val port = ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val client = HarnessBubbleClient(HarnessAccess("http://127.0.0.1:$port/","u","p"))
        val start = System.nanoTime()
        var submitted = false
        assertThrows(IllegalStateException::class.java) { client.followReplyWithImages("session-one","req") { submitted=true } }
        assertFalse(submitted)
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start) < 5)
    }

    @Test fun unaryUsesRuntimeGatewayEnvelopeAndLoopbackAuth() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newSingleThreadExecutor()
        val work = pool.submit {
            server.accept().use { socket ->
                socket.soTimeout = 10000
                val input = socket.getInputStream(); val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) { val c = input.read(); check(c >= 0); header.append(c.toChar()) }
                val length = header.lines().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                val bytes = ByteArray(length); java.io.DataInputStream(input).readFully(bytes)
                val body = JSONObject(String(bytes, Charsets.UTF_8))
                assertTrue(header.startsWith("POST /api/session/create "))
                assertTrue(header.toString().contains("Basic " + Base64.getEncoder().encodeToString("u:p".toByteArray())))
                assertEquals("session/create", body.getString("method"))
                assertTrue(body.getJSONObject("payload").getJSONObject("args").has("request"))
                val response = JSONObject().put("result", JSONObject().put("ok", true).put("value", JSONObject().put("sessionId", "one"))).toString().toByteArray()
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(response); flush() }
            }
        }
        try {
            assertEquals("one", HarnessBubbleClient(HarnessAccess("http://127.0.0.1:${server.localPort}/", "u", "p")).call("session/create", JSONObject()).getString("sessionId"))
            work.get(10, TimeUnit.SECONDS)
        } finally { server.close(); pool.shutdownNow() }
    }
    @Test fun exchangesMobileTokenForBothRuntimeCookies() {
        val server = ServerSocket(0, 3, java.net.InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newSingleThreadExecutor()
        val token = "A".repeat(43)
        val work = pool.submit {
            repeat(3) { index ->
                server.accept().use { socket ->
                    socket.soTimeout = 10000
                    val input = socket.getInputStream(); val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) { val c = input.read(); check(c >= 0); header.append(c.toChar()) }
                    val result = when (index) {
                        0 -> { assertTrue(header.startsWith("GET /?dshMobileToken=$token ")); "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${server.localPort}/?token=native\r\nSet-Cookie: dsh_browser_session=$token; Path=/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" }
                        1 -> { assertTrue(header.toString().contains("dsh_browser_session=$token")); "HTTP/1.1 303 See Other\r\nLocation: ./\r\nSet-Cookie: dsh_abc=v1.body.signature; Path=/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" }
                        else -> {
                            assertTrue(header.toString().contains("dsh_browser_session=$token; dsh_abc=v1.body.signature"))
                            val length = header.lines().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                            val bytes = ByteArray(length); java.io.DataInputStream(input).readFully(bytes)
                            val body = "{\"result\":{\"ok\":true,\"value\":{\"sessionId\":\"one\"}}}"
                            "HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                        }
                    }
                    socket.getOutputStream().apply { write(result.toByteArray()); flush() }
                }
            }
        }
        try {
            val client = HarnessBubbleClient(HarnessAccess("http://127.0.0.1:${server.localPort}/", "u", "p", token))
            assertEquals("one", client.call("session/create", JSONObject()).getString("sessionId"))
            work.get(10, TimeUnit.SECONDS)
        } finally { server.close(); pool.shutdownNow() }
    }
    @Test fun rejectsNonLoopbackCredentialTarget() {
        assertThrows(IllegalArgumentException::class.java) { HarnessBubbleClient(HarnessAccess("http://example.com:8080/", "u", "p")) }
    }
    @Test fun followsCurrentGatewayStreamUntilTurnEnd() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newSingleThreadExecutor()
        val work = pool.submit {
            server.accept().use { socket ->
                socket.soTimeout = 10000
                val input = socket.getInputStream(); val output = socket.getOutputStream()
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) { val c = input.read(); check(c >= 0); header.append(c.toChar()) }
                val key = header.lines().first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray()); output.flush()
                fun readOpen(): JSONObject {
                val first = input.read(); val second = input.read(); check(first and 15 == 1)
                var size = second and 127
                if (size == 126) size = (input.read() shl 8) or input.read()
                val mask = ByteArray(4); java.io.DataInputStream(input).readFully(mask)
                val bytes = ByteArray(size); java.io.DataInputStream(input).readFully(bytes)
                return JSONObject(String(ByteArray(size) { i -> (bytes[i].toInt() xor mask[i % 4].toInt()).toByte() }))
                }
                val eventsOpen = readOpen()
                assertEquals("\$events", eventsOpen.getString("endpoint"))
                val open = readOpen()
                assertEquals("session/follow", open.getString("endpoint"))
                assertEquals("one", open.getJSONObject("payload").getJSONObject("args").getJSONObject("request").getJSONObject("address").getString("sessionId"))
                val id = open.getString("streamId")
                fun send(value: JSONObject, stream: String = id) {
                    val data = JSONObject().put("type", "item").put("streamId", stream).put("value", value).toString().toByteArray()
                    output.write(0x81); if (data.size < 126) output.write(data.size) else { output.write(126); output.write(data.size shr 8); output.write(data.size and 255) }; output.write(data); output.flush()
                }
                send(JSONObject().put("type", "ready").put("clientId", "client"), eventsOpen.getString("streamId"))
                assertTrue(open.getJSONObject("payload").getJSONObject("args").getJSONObject("request").getBoolean("assistantStream"))
                send(JSONObject().put("type", "snapshot").put("records", org.json.JSONArray()).put("cursor", -1).put("hasMore", false))
                send(JSONObject().put("type", "event").put("event", JSONObject().put("seq", 0).put("type", "turn/start").put("data", JSONObject().put("turn", 1))))
                send(JSONObject().put("type", "event").put("event", JSONObject().put("seq", 1).put("type", "user/message").put("data", JSONObject().put("source", JSONObject().put("kind", "user").put("rpcId", "request")))))
                Thread.sleep(100)
                val text = JSONObject().put("type", "text").put("text", "你好呀")
                val picture = JSONObject().put("type", "image").put("attachment", JSONObject().put("attachmentId", "sha256:" + "a".repeat(64)))
                val event = JSONObject().put("seq", 2).put("type", "assistant/message").put("data", JSONObject().put("turn", 1).put("step", 1).put("message", JSONObject().put("content", org.json.JSONArray().put(text).put(picture))))
                send(JSONObject().put("type", "event").put("event", event))
                val toolImage = JSONObject().put("type", "image").put("attachment", JSONObject().put("attachmentId", "sha256:" + "b".repeat(64)))
                val toolEvent = JSONObject().put("seq", 3).put("type", "tool/result").put("data", JSONObject().put("turn", 1).put("message", JSONObject().put("content", org.json.JSONArray().put(toolImage))))
                send(JSONObject().put("type", "event").put("event", toolEvent))
                send(JSONObject().put("type", "event").put("event", JSONObject().put("seq", 4).put("type", "turn/end").put("data", JSONObject().put("turn", 1).put("reason", JSONObject().put("kind", "completed")))))
            }
        }
        try {
            val client = HarnessBubbleClient(HarnessAccess("http://127.0.0.1:${server.localPort}/", "u", "p"))
            val reply = client.followReplyWithImages("one", "request") {}
            assertEquals("你好呀", reply.text)
            assertEquals(listOf("sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64)), reply.images)
            work.get(10, TimeUnit.SECONDS)
        } finally { server.close(); pool.shutdownNow() }
    }
}
