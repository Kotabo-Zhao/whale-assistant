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
    @Test fun originalNotificationRequiresExactCurrentContentAndCreator() {
        val key = CompanionNotificationDedup.digest("active-key")!!
        val content = CompanionNotificationDedup.contentDigest("会议", "请回复")
        fun matches(pkg: String = "com.example.chat", currentKey: String = "active-key", text: String = "请回复", creator: String? = "com.example.chat") =
            CompanionNotificationTarget.matches("com.example.chat", key, content, pkg, currentKey, "会议", text, creator)
        assertTrue(matches())
        assertFalse(matches(pkg = "com.other.app"))
        assertFalse(matches(currentKey = "other-key"))
        assertFalse(matches(text = "内容已变化"))
        assertFalse(matches(creator = "com.other.app"))
        assertFalse(matches(creator = null))
        assertFalse(CompanionNotificationTarget.matches("com.example.chat", null, content, "com.example.chat", "active-key", "会议", "请回复", "com.example.chat"))
    }
}
