package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionNotificationDedupTest {
    @Test fun suppressesOnlySameKeyAndFullContentWithinWindow() {
        val key = CompanionNotificationDedup.digest("notification-key")
        val content = CompanionNotificationDedup.contentDigest("title", "body")
        val later = 1_000_000L
        assertTrue(CompanionNotificationDedup.isDuplicate(key, content, later, key, content, later - 1))
        assertFalse(CompanionNotificationDedup.isDuplicate(key, content, later, key, content, later - CompanionNotificationDedup.WINDOW_MS))
        assertFalse(CompanionNotificationDedup.isDuplicate(key, content, later, key, content, later + 1))
        assertFalse(CompanionNotificationDedup.isDuplicate(key, content, later, null, content, later - 1))
        assertFalse(CompanionNotificationDedup.isDuplicate(null, content, later, null, content, later - 1))
        assertFalse(CompanionNotificationDedup.isDuplicate(key, content, later, key, CompanionNotificationDedup.contentDigest("title", "changed"), later - 1))
        assertFalse(CompanionNotificationDedup.isDuplicate(key, content, later, CompanionNotificationDedup.digest("other"), content, later - 1))
    }
    @Test fun hashesFullContentAndKeepsFieldsUnambiguous() {
        val long = "a".repeat(500)
        assertNotEquals(CompanionNotificationDedup.contentDigest("title", long + "1"), CompanionNotificationDedup.contentDigest("title", long + "2"))
        assertNotEquals(CompanionNotificationDedup.contentDigest("ab", "c"), CompanionNotificationDedup.contentDigest("a", "bc"))
        assertEquals(64, CompanionNotificationDedup.digest("key")!!.length)
        assertNull(CompanionNotificationDedup.digest(""))
    }
}
