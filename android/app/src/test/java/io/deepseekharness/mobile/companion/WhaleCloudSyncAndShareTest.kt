package io.deepseekharness.mobile.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleCloudSyncAndShareTest {

    @Test
    fun cloudSyncEncryptsAndDecryptsRoundTripWithAes256Gcm() {
        val plain = JSONObject()
            .put("version", 1)
            .put("note", "鲸鱼助手端到端加密同步测试 🐳")
            .toString()
        val encrypted = WhaleCloudSync.encryptPayload(plain, "strong-pass-2026")
        assertTrue(encrypted.startsWith("WHALE_E2EE_V1:"))
        assertNotEquals(plain, encrypted)
        val decrypted = WhaleCloudSync.decryptPayload(encrypted, "strong-pass-2026")
        assertEquals(plain, decrypted)
    }

    @Test
    fun shareCodeCodecEncodesAndDecodesWorkshopAndToolPackets() {
        val payload = JSONObject()
            .put("name", "custom_snr_calc")
            .put("description", "计算 5G 基带 SNR")
            .put("kind", "python")
        val code = WhaleShareCodeCodec.encodePacket("custom_tool", payload)
        assertTrue(code.startsWith("WHALE1:"))
        val (kind, decoded) = WhaleShareCodeCodec.decodePacket(code)
        assertEquals("custom_tool", kind)
        assertEquals("custom_snr_calc", decoded.getString("name"))
        assertEquals("计算 5G 基带 SNR", decoded.getString("description"))
    }
}
