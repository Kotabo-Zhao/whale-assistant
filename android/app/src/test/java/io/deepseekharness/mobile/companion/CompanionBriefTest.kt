package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionBriefTest {
    @Test fun classifiesAndCountsWithoutInventingEntries() {
        val rows = JSONArray().put(JSONObject().put("title", "会议提醒").put("text", "下午三点"))
            .put(JSONObject().put("title", "快递已到").put("text", "请取件"))
            .put(JSONObject().put("title", "其他"))
        assertEquals(mapOf("日程与待办" to 1, "物流与出行" to 1, "其他" to 1), CompanionBrief.counts(rows))
        assertEquals(emptyMap<String, Int>(), CompanionBrief.counts(JSONArray()))
    }
    @Test fun candidatesKeepSourcesAndNeverInventDueTimes() {
        val rows = JSONArray()
            .put(JSONObject().put("package", "com.example.calendar").put("time", 100L).put("title", "会议提醒").put("text", "明天上午"))
            .put(JSONObject().put("package", "com.example.delivery").put("time", 200L).put("title", "包裹").put("text", "请取件"))
            .put(JSONObject().put("package", "com.example.chat").put("time", 300L).put("title", "消息").put("text", "验证码 123456"))
            .put(JSONObject().put("package", "com.example.other").put("time", 400L).put("title", "天气").put("text", "晴"))
        val candidates = CompanionBrief.candidates(rows)
        assertEquals(2, candidates.size)
        assertEquals("com.example.delivery", candidates[0].packageName)
        assertEquals(200L, candidates[0].time)
        assertEquals("可能需要取件", candidates[0].reason)
        assertEquals(null, candidates[0].notificationKeyHash)
        assertEquals("明天上午", candidates[1].text)
        assertEquals(100L, candidates[1].time)
        assertEquals(emptyList<CompanionBrief.Candidate>(), CompanionBrief.candidates(JSONArray()))
    }
    @Test fun notificationCandidateRetainsOnlyValidHashes() {
        val key = CompanionNotificationDedup.digest("key")!!
        val content = CompanionNotificationDedup.contentDigest("会议", "请回复")
        val rows = JSONArray().put(JSONObject().put("package", "com.example.chat").put("time", 1L).put("title", "会议")
            .put("text", "请回复").put("notificationKeyHash", key).put("notificationContentHash", content))
        val candidate = CompanionBrief.candidates(rows).single()
        assertEquals(key, candidate.notificationKeyHash)
        assertEquals(content, candidate.notificationContentHash)
        rows.getJSONObject(0).put("notificationKeyHash", "invalid")
        assertEquals(null, CompanionBrief.candidates(rows).single().notificationKeyHash)
    }
    @Test fun candidateHintsAreRulesNotAssertionsAndAlertsAreRateLimited() {
        assertEquals("可能需要取件", CompanionBrief.candidateReason("包裹", "请取件"))
        assertEquals(null, CompanionBrief.candidateReason("天气", "晴"))
        assertTrue(CompanionCandidateNotifications.due(1L, 0L))
        assertFalse(CompanionCandidateNotifications.due(1000L, 999L))
        assertFalse(CompanionCandidateNotifications.due(1000L, 2000L))
        assertTrue(CompanionCandidateNotifications.due(1801000L, 1000L))
    }
    @Test fun sourceAppMustBeAValidPackageAndMatchResolvedLauncher() {
        org.junit.Assert.assertTrue(CompanionSourcePolicy.matchesResolved("com.example.reader", "com.example.reader"))
        org.junit.Assert.assertFalse(CompanionSourcePolicy.matchesResolved("com.example.reader", "com.other.app"))
        org.junit.Assert.assertFalse(CompanionSourcePolicy.matchesResolved("com.example.reader", null))
        org.junit.Assert.assertFalse(CompanionSourcePolicy.validPackage("com.example.reader;bad"))
        org.junit.Assert.assertFalse(CompanionSourcePolicy.validPackage("../example"))
        org.junit.Assert.assertFalse(CompanionSourcePolicy.validPackage("a".repeat(200)))
    }
}
