package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleContextBudgeterTest {

    private fun makeTool(name: String, desc: String, required: List<String> = emptyList()): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", desc)
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put("z_param", JSONObject().put("type", "string").put("description", "这是一个非常非常冗长而且带有多余换行的参数描述\n\n用来测试自动截断和空白折叠效果，确保最终输出足够精简不会浪费大量 Token。"))
                                    .put("a_param", JSONObject().put("type", "string").put("description", "短描述")),
                            )
                            .put("required", JSONArray(required)),
                    ),
            )

    @Test
    fun minifyToolSchemasSortsDeterministicallyAndStripsEmptyRequired() {
        val raw = JSONArray()
            .put(makeTool("whale_z", "   这是一个很长的工具说明，超过九十六个字符时应该被安全压缩并且去掉多余换行\n\n同时保证核心功能描述依然清晰可见不会丢失关键语义，这里故意写得非常长用来验证截断逻辑生效。   "))
            .put(makeTool("whale_a", "简短工具", required = listOf("a_param")))
            .put(makeTool("whale_a", "重复工具应去重"))

        val minified = WhaleContextBudgeter.minifyToolSchemas(raw)
        assertEquals(2, minified.length())

        val firstFn = minified.getJSONObject(0).getJSONObject("function")
        val secondFn = minified.getJSONObject(1).getJSONObject("function")
        assertEquals("whale_a", firstFn.getString("name"))
        assertEquals("whale_z", secondFn.getString("name"))

        // whale_a has required=["a_param"], whale_z had empty required=[] which should be stripped
        assertTrue(firstFn.getJSONObject("parameters").has("required"))
        assertFalse(secondFn.getJSONObject("parameters").has("required"))
        assertTrue(secondFn.getString("description").length <= 96)
        assertTrue(minified.toString().length < raw.toString().length)
    }

    @Test
    fun selectRelevantDynamicToolsLimitsTopKAndDefersRest() {
        val all = (1..15).map { idx ->
            when (idx) {
                3 -> makeTool("custom_snr_calc", "计算 5G NR 基带 SNR 与信噪比链路预算")
                7 -> makeTool("custom_weather_city", "查询城市实时天气预报")
                11 -> makeTool("custom_mimo_mmse", "计算 MIMO MMSE 检测器权重矩阵")
                else -> makeTool("custom_misc_$idx", "无关的杂项测试工具编号 $idx")
            }
        }

        val sel = WhaleContextBudgeter.selectRelevantDynamicTools(
            allSchemas = all,
            userQuery = "帮我算一下 5G MIMO 的 SNR 信噪比",
            expandAll = false,
            maxAutoInject = 3,
        )
        val selectedNames = sel.selected.map { it.getJSONObject("function").getString("name") }
        assertTrue("custom_snr_calc" in selectedNames)
        assertTrue("custom_mimo_mmse" in selectedNames)
        assertTrue(sel.selected.size <= 3)
        assertEquals(15 - sel.selected.size, sel.deferredNames.size)
        assertFalse("custom_weather_city" in selectedNames)
    }

    @Test
    fun assembleDynamicContextEnforcesHardBudgetAndDeduplicatesAcrossSections() {
        val sections = listOf(
            WhaleContextBudgeter.ContextSection("owner", 0, 200, "[主人档案] 居住城市=上海；研究方向=5G基带芯片数字IC设计"),
            WhaleContextBudgeter.ContextSection("rag", 2, 400, "[自动 RAG]\n- 居住城市=上海；研究方向=5G基带芯片数字IC设计\n- 喜欢极简高效回复风格，不要多余废话"),
            WhaleContextBudgeter.ContextSection("low", 2, 500, "[低优附录] " + "补充背景信息".repeat(120)),
        )
        val out = WhaleContextBudgeter.assembleDynamicContext(sections, totalBudgetChars = 220)
        assertTrue(out.length <= 225)
        assertTrue(out.contains("5G基带芯片"))
        assertTrue(out.contains("极简高效"))
        // 重复的居住城市/5G基带行不应出现两次
        val occurrences = Regex("5G基带芯片数字IC设计").findAll(out).count()
        assertEquals(1, occurrences)
    }

    @Test
    fun shouldTriggerAutoRagSkipsShortCommandsAndAllowsKnowledgeQueries() {
        assertFalse(WhaleContextBudgeter.shouldTriggerAutoRag("继续"))
        assertFalse(WhaleContextBudgeter.shouldTriggerAutoRag("把主题改成深色"))
        assertFalse(WhaleContextBudgeter.shouldTriggerAutoRag("打开微信"))
        assertTrue(WhaleContextBudgeter.shouldTriggerAutoRag("帮我查一下上次存的基带面试笔记"))
        assertTrue(WhaleContextBudgeter.shouldTriggerAutoRag("总结一下我这个月的核心项目进度与待办"))
    }

    @Test
    fun compactToolPayloadStripsNullsBlanksAndTruncatesLongArrays() {
        val items = JSONArray()
        for (i in 1..25) {
            items.put(
                JSONObject()
                    .put("id", i)
                    .put("title", "条目 $i")
                    .put("emptyNote", "")
                    .put("nullField", JSONObject.NULL)
                    .put("emptyArr", JSONArray()),
            )
        }
        val raw = JSONObject()
            .put("ok", true)
            .put("items", items)
            .put("debugEmpty", "")
            .put("meta", JSONObject())
            .toString()

        val compacted = WhaleContextBudgeter.compactToolPayload(raw)
        assertTrue(compacted.length < raw.length)
        val parsed = JSONObject(compacted)
        assertTrue(parsed.getBoolean("ok"))
        assertFalse(parsed.has("debugEmpty"))
        assertFalse(parsed.has("meta"))
        val compactItems = parsed.getJSONArray("items")
        assertTrue(compactItems.length() < 25)
        assertTrue(compacted.contains("_omittedItems"))
    }

    @Test
    fun compressHistoryStripsWhaleNextTagsAndFoldsOldCodeBlocks() {
        val oldAssistant = "这是早期的长代码回复：\n```kotlin\n${"val x = 1\n".repeat(40)}```\n<!--whale_next:[\"继续\",\"换个方案\"]-->"
        val recentAssistant = "最新回复保留完整内容。\n<!--whale_next:[\"好的\",\"再来\"]-->"
        val history = listOf(
            "user" to "写一段测试代码",
            "assistant" to oldAssistant,
            "user" to "谢谢，接下来聊个新话题",
            "assistant" to recentAssistant,
        )
        val compressed = WhaleContextBudgeter.compressHistory(history, maxTurns = 10, maxTotalChars = 2_000)
        assertEquals(4, compressed.size)
        assertFalse(compressed[1].second.contains("whale_next"))
        assertTrue(compressed[1].second.contains("已折叠历史代码块"))
        assertFalse(compressed[3].second.contains("whale_next"))
        assertTrue(compressed[3].second.contains("最新回复保留完整内容"))
    }

    @Test
    fun turnCompressionStatsCalculatesExactCompressionRatio() {
        val stats = WhaleContextBudgeter.TurnCompressionStats(
            rawSchemaChars = 10_000,
            compactSchemaChars = 3_000,
            rawDynamicCtxChars = 4_000,
            compactDynamicCtxChars = 1_500,
            rawHistoryChars = 2_000,
            compactHistoryChars = 1_000,
            toolPayloadRawChars = 4_000,
            toolPayloadCompactChars = 1_500,
        )
        assertEquals(20_000, stats.rawTotalChars)
        assertEquals(7_000, stats.compactTotalChars)
        assertEquals(13_000, stats.totalSavedChars)
        assertEquals(0.65f, stats.compressionRatio, 0.001f)
    }

    @Test
    fun normalizeHistoryImmutablePreservesByteIdenticalPrefixAcrossTurns() {
        val turn1 = listOf(
            "user" to "帮我写一个快速排序",
            "assistant" to "```kotlin\nfun qsort() {}\n```\n<!--whale_next:[\"继续\",\"优化\"]-->",
        )
        val turn2 = turn1 + listOf(
            "user" to "再加个注释",
            "assistant" to "已补充注释。\n<!--whale_next:[\"好的\"]-->",
        )
        val h1 = WhaleContextBudgeter.normalizeHistoryImmutable(turn1)
        val h2 = WhaleContextBudgeter.normalizeHistoryImmutable(turn2)
        // 前两条消息必须跨轮字节级完全一致，保证 KV Prefix Cache 命中
        assertEquals(h1[0], h2[0])
        assertEquals(h1[1], h2[1])
        assertFalse(h1[1].second.contains("whale_next"))
    }
}
