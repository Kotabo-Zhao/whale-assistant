package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WhaleCustomToolEngineTest {

    @Test
    fun validatesToolNamesStrictly() {
        assertTrue(WhaleCustomToolEngine.isValidToolName("custom_weather"))
        assertTrue(WhaleCustomToolEngine.isValidToolName("custom_link_budget_v2"))
        assertFalse(WhaleCustomToolEngine.isValidToolName("whale_device_status"))
        assertFalse(WhaleCustomToolEngine.isValidToolName("custom_"))
        assertFalse(WhaleCustomToolEngine.isValidToolName("custom_BadCase"))
        assertFalse(WhaleCustomToolEngine.isValidToolName("custom_1bad"))
    }

    @Test
    fun blocksPrivateAndLoopbackHostsForSsrf() {
        assertTrue(WhaleCustomToolEngine.isHostBlocked("localhost"))
        assertTrue(WhaleCustomToolEngine.isHostBlocked("127.0.0.1"))
        assertTrue(WhaleCustomToolEngine.isHostBlocked("10.0.0.5"))
        assertTrue(WhaleCustomToolEngine.isHostBlocked("192.168.1.1"))
        assertTrue(WhaleCustomToolEngine.isHostBlocked("172.16.0.1"))
        assertTrue(WhaleCustomToolEngine.isHostBlocked("169.254.169.254"))
        assertTrue(WhaleCustomToolEngine.isHostBlocked("::1"))
        assertTrue(WhaleCustomToolEngine.isHostBlocked("router.local"))
        assertFalse(WhaleCustomToolEngine.isHostBlocked("api.open-meteo.com"))
        assertFalse(WhaleCustomToolEngine.isHostBlocked("api.github.com"))
    }

    @Test
    fun rendersTemplatesAndExtractsJsonPaths() {
        val args = JSONObject().put("city", "Shanghai China").put("days", 3)
        assertEquals(
            "https://api.example.com/v1?q=Shanghai+China&d=3",
            WhaleCustomToolEngine.renderTemplate("https://api.example.com/v1?q={{city}}&d={{days}}", args, urlEncode = true),
        )
        assertEquals(
            "prev=hello, city=Shanghai China",
            WhaleCustomToolEngine.renderTemplate("prev={{prev}}, city={{city}}", args, prevOutput = "hello"),
        )

        val payload = JSONObject("""{"current":[{"temp_C":"22","desc":"Sunny"}]}""")
        assertEquals("22", WhaleCustomToolEngine.extractJsonPath(payload, "current.0.temp_C"))
        assertEquals("Sunny", WhaleCustomToolEngine.extractJsonPath(payload, "current.0.desc"))
    }

    @Test
    fun createsListsDeletesAndGeneratesOpenAiSchema() {
        val dir = Files.createTempDirectory("whale-custom-tools-test").toFile()
        val store = File(dir, "whale_custom_tools.json")
        try {
            val created = WhaleCustomToolEngine.createOrUpdate(
                file = store,
                name = "custom_snr_calc",
                description = "计算线性信号与噪声比对应的 dB 值",
                kind = "js",
                params = JSONObject().put("signal", "信号功率").put("noise", "噪声功率"),
                required = listOf("signal", "noise"),
                definition = JSONObject().put("code", "return 10 * Math.log10(Number(args.signal) / Number(args.noise));"),
            )
            assertTrue(created.getBoolean("saved"))
            assertEquals(1, created.getInt("totalCustomTools"))

            val list = WhaleCustomToolEngine.listSummary(store)
            assertEquals(1, list.getInt("count"))

            val record = WhaleCustomToolEngine.findTool(store, "custom_snr_calc")
            assertNotNull(record)
            val schema = WhaleCustomToolEngine.toOpenAiSchema(record!!)
            assertEquals("function", schema.getString("type"))
            assertEquals("custom_snr_calc", schema.getJSONObject("function").getString("name"))

            val deleted = WhaleCustomToolEngine.delete(store, "custom_snr_calc")
            assertTrue(deleted.getBoolean("removed"))
            assertEquals(0, deleted.getInt("remainingCustomTools"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun executesCompositePipelineAndPreventsRecursion() {
        val record = JSONObject()
            .put("name", "custom_morning_brief")
            .put("kind", "composite")
            .put(
                "definition",
                JSONObject().put(
                    "steps",
                    JSONArray()
                        .put(JSONObject().put("tool", "whale_calendar_read").put("args", JSONObject()))
                        .put(JSONObject().put("tool", "whale_voice_speak").put("args", JSONObject().put("text", "今日日程：{{prev}}"))),
                ),
            )

        val calls = mutableListOf<Pair<String, JSONObject>>()
        val res = WhaleCustomToolEngine.executeComposite(record, JSONObject()) { toolName, stepArgs ->
            calls.add(toolName to stepArgs)
            if (toolName == "whale_calendar_read") true to "10点评审会" else true to "ok"
        }

        assertTrue(res.ok)
        assertEquals(2, calls.size)
        assertEquals("whale_calendar_read", calls[0].first)
        assertEquals("今日日程：10点评审会", calls[1].second.getString("text"))

        // Reject recursive composite definition at validation time
        val badDef = JSONObject().put(
            "steps",
            JSONArray().put(JSONObject().put("tool", "custom_morning_brief")),
        )
        var threw = false
        try {
            WhaleCustomToolEngine.validateDefinition("composite", badDef)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun unwrapsWebViewDoubleEncodedJson() {
        val raw = "\"{\\\"ok\\\":true,\\\"result\\\":42}\""
        val parsed = WhaleCustomToolEngine.unwrapWebViewJson(raw)
        assertTrue(parsed.getBoolean("ok"))
        assertEquals(42, parsed.getInt("result"))
    }
}
