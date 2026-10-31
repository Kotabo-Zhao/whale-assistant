package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekVisionModelTest {
    private class FakeConnection(private val status: Int, private val response: String) : java.net.HttpURLConnection(java.net.URL("https://example.invalid/")) {
        val requestBody = java.io.ByteArrayOutputStream()
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getOutputStream(): java.io.OutputStream = requestBody
        override fun getResponseCode() = status
        override fun getInputStream(): java.io.InputStream = response.byteInputStream()
    }

    @Test fun sendsImageAsUserContentToVisionModel() {
        val payload = DeepSeekVisionModel.payload(byteArrayOf(1, 2, 3))
        assertEquals("deepseek-flash", payload.getString("model"))
        val message = payload.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        val parts = message.getJSONArray("content")
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals("data:image/jpeg;base64,AQID", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertTrue(!payload.toString().contains("Bearer"))
        assertThrows(IllegalArgumentException::class.java) { DeepSeekVisionModel.payload(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { DeepSeekVisionModel.payload(ByteArray(8 * 1024 * 1024 + 1)) }
    }

    @Test fun readsSuccessfulVisionReplyAndRejectsFailedRequests() {
        val conn = FakeConnection(200, """{"choices":[{"message":{"content":"图片中有一只蓝色鲸鱼。"}}]}""")
        assertEquals("图片中有一只蓝色鲸鱼。", DeepSeekVisionModel("example-key") { conn }.analyze(byteArrayOf(1, 2, 3)))
        assertEquals("Bearer example-key", conn.getRequestProperty("Authorization"))
        assertTrue(conn.requestBody.toString("UTF-8").contains("data:image/jpeg;base64,AQID"))
        assertTrue(conn.closed)
        val rejected = FakeConnection(403, "not used")
        assertThrows(IllegalStateException::class.java) { DeepSeekVisionModel("example-key") { rejected }.analyze(byteArrayOf(1)) }
        assertTrue(rejected.closed)
    }
}
