package io.deepseekharness.mobile.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionStoreTest {
    @Test fun blocksCommonSensitiveNotifications() {
        assertTrue(CompanionStore.sensitive("验证码 483921"))
        assertTrue(CompanionStore.sensitive("Your one-time password is ready"))
        assertTrue(CompanionStore.sensitive("api key: sk-abcdefghijklmnop"))
        assertTrue(CompanionStore.sensitive("订单号 12345678"))
        assertFalse(CompanionStore.sensitive("会议提醒 下午三点开始"))
    }
    @Test fun rejectsCorruptOrMalformedRecordsInsteadOfTreatingThemAsEmpty() {
        val valid = "[{\"package\":\"example\",\"title\":\"标题\",\"text\":\"正文\",\"time\":123}]"
        org.junit.Assert.assertEquals(1, CompanionNotificationRecords.parse(valid).length())
        org.junit.Assert.assertEquals(0, CompanionNotificationRecords.parse("[]").length())
        for (invalid in listOf("{broken", "[null]", "[{\"package\":\"example\"}]", "x".repeat(256_001))) {
            org.junit.Assert.assertThrows(Exception::class.java) { CompanionNotificationRecords.parse(invalid) }
        }
    }
}
