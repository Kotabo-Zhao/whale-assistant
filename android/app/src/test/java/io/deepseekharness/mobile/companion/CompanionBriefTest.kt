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
    @Test fun clockRollbackRestartsCooldownInsteadOfSuppressingAlertsUntilOldTime() {
        val previous = 10_000_000L
        val now = 1_000L
        val rebased = CompanionCandidateNotifications.rebaseAfterClockRollback(now, previous)
        assertEquals(now, rebased)
        assertFalse(CompanionCandidateNotifications.due(now, rebased))
        assertFalse(CompanionCandidateNotifications.due(now + 1_799_999L, rebased))
        assertTrue(CompanionCandidateNotifications.due(now + 1_800_000L, rebased))
        assertEquals(previous, CompanionCandidateNotifications.rebaseAfterClockRollback(previous + 1, previous))
        assertEquals(previous, CompanionCandidateNotifications.rebaseAfterClockRollback(0, previous))
    }
    @Test fun suggestsOnlyOneUnambiguousFutureExplicitLocalTime() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val now = java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val expected = java.time.ZonedDateTime.of(2026, 12, 3, 14, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, CompanionNotificationTimeSuggestion.find("会议 2026-12-03 14:30", now, zone))
        assertEquals(expected, CompanionNotificationTimeSuggestion.find("会议 2026年12月3日 14:30", now, zone))
        assertNull(CompanionNotificationTimeSuggestion.find("明天下午三点", now, zone))
        assertNull(CompanionNotificationTimeSuggestion.find("2026-02-30 14:30", now, zone))
        assertNull(CompanionNotificationTimeSuggestion.find("2025-12-03 14:30", now, zone))
        assertNull(CompanionNotificationTimeSuggestion.find("2026-12-03 14:30 或 2026-12-04 14:30", now, zone))
        val newYork = java.time.ZoneId.of("America/New_York")
        assertNull(CompanionNotificationTimeSuggestion.find("2026-11-01 01:30", now, newYork))
        assertNull(CompanionNotificationTimeSuggestion.find("2026-03-08 02:30", now, newYork))
    }
    @Test fun sourceAppMustBeAValidPackageAndMatchResolvedLauncher() {
        org.junit.Assert.assertTrue(CompanionSourcePolicy.matchesResolved("com.example.reader", "com.example.reader"))
        org.junit.Assert.assertFalse(CompanionSourcePolicy.matchesResolved("com.example.reader", "com.other.app"))
        org.junit.Assert.assertFalse(CompanionSourcePolicy.matchesResolved("com.example.reader", null))
        org.junit.Assert.assertFalse(CompanionSourcePolicy.validPackage("com.example.reader;bad"))
        org.junit.Assert.assertFalse(CompanionSourcePolicy.validPackage("../example"))
        org.junit.Assert.assertFalse(CompanionSourcePolicy.validPackage("a".repeat(200)))
    }

    @Test fun relativeSuggestionUsesRecentCaptureDateAndRejectsAmbiguity() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val captured = java.time.Instant.parse("2026-01-01T15:50:00Z").toEpochMilli()
        val now = captured + 20 * 60_000L // already the next local day
        val expected = java.time.ZonedDateTime.of(2026, 1, 2, 14, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, CompanionNotificationTimeSuggestion.find("明天下午 2:30 开会", now, zone, captured))
        assertNull(CompanionNotificationTimeSuggestion.find("明天 14:30 或 15:30", now, zone, captured))
        assertNull(CompanionNotificationTimeSuggestion.find("明天 14:30 或 三点", now, zone, captured))
        assertNull(CompanionNotificationTimeSuggestion.find("明天 1月3日 14:30", now, zone, captured))
        assertNull(CompanionNotificationTimeSuggestion.find("明天 14:30 后天再确认", now, zone, captured))
        assertNull(CompanionNotificationTimeSuggestion.find("明天 14:30", now, zone, null))
        assertNull(CompanionNotificationTimeSuggestion.find("明天 14:30", now, zone, now + 1))
        assertNull(CompanionNotificationTimeSuggestion.find("明天 14:30", now, zone, now - 24 * 60 * 60 * 1000L - 1))
        assertNull(CompanionNotificationTimeSuggestion.find("今天 14:30", now, zone, captured))
        assertNull(CompanionNotificationTimeSuggestion.find("明天下午 14:30", now, zone, captured))
        val ny = java.time.ZoneId.of("America/New_York")
        val beforeDst = java.time.Instant.parse("2026-03-07T17:00:00Z").toEpochMilli()
        assertNull(CompanionNotificationTimeSuggestion.find("明天 02:30", beforeDst, ny, beforeDst))
        assertNull(CompanionNotificationTimeSuggestion.find("2026-12-03 14:30 或 15:30", now, zone, captured))
    }

    @Test fun chineseRelativeTimesAreSuggestionsOnlyForClearSingleTimes() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val captured = java.time.Instant.parse("2026-01-01T02:00:00Z").toEpochMilli()
        fun local(day: Int, hour: Int, minute: Int) = java.time.ZonedDateTime.of(2026, 1, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
        fun find(text: String) = CompanionNotificationTimeSuggestion.find(text, captured, zone, captured)
        assertEquals(local(2, 15, 30), find("明天下午三点半开会"))
        assertEquals(local(2, 9, 5), find("明天上午九点零五分"))
        assertEquals(local(2, 15, 20), find("明天15点20分"))
        assertEquals(local(2, 12, 0), find("明天中午十二点"))
        assertEquals(local(2, 20, 0), find("明天晚上八点"))
        for (text in listOf("明天晚上两点", "明天上午十二点", "明天下午三点多", "明天三点或者五点", "明天三点一刻", "明天三点五十多分", "明天三点60分", "明天12月3日三点", "明天三点到四点")) {
            assertNull(text, find(text))
        }
    }
}
