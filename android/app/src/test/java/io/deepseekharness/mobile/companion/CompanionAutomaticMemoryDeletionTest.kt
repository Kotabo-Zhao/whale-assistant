package io.deepseekharness.mobile.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionAutomaticMemoryDeletionTest {
    @Test fun notificationDeletionScopesSuppressionToItsSource() {
        assertEquals("notification:com.example.app", CompanionAutomaticMemoryDeletion.sourceKey(JSONObject().put("source", "auto_notification_group").put("sourcePackage", "com.example.app")))
        assertNull(CompanionAutomaticMemoryDeletion.sourceKey(JSONObject().put("source", "auto_notification_group").put("sourcePackage", "../unsafe")))
    }
    @Test fun explicitUserMemoryDoesNotSuppressAutomaticSources() {
        assertNull(CompanionAutomaticMemoryDeletion.sourceKey(JSONObject().put("source", "user_edit")))
        assertEquals("usage", CompanionAutomaticMemoryDeletion.sourceKey(JSONObject().put("source", "auto_usage")))
    }
}
