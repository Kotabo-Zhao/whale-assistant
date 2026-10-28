package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionBriefTest {
    @Test fun classifiesAndCountsWithoutInventingEntries() {
        val rows = JSONArray().put(JSONObject().put("title", "会议提醒").put("text", "下午三点"))
            .put(JSONObject().put("title", "快递已到").put("text", "请取件"))
            .put(JSONObject().put("title", "其他"))
        assertEquals(mapOf("日程与待办" to 1, "物流与出行" to 1, "其他" to 1), CompanionBrief.counts(rows))
        assertEquals(emptyMap<String, Int>(), CompanionBrief.counts(JSONArray()))
    }
}
