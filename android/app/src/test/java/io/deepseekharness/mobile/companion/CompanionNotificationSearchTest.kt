package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionNotificationSearchTest {
    @Test fun matchesContentPackageAndAppNameNewestFirstWithoutChangingRecords() {
        fun row(pkg: String, title: String, body: String, time: Long) = JSONObject().put("package", pkg).put("title", title).put("text", body).put("time", time)
        val rows = JSONArray().put(row("com.example.mail", "会议", "Monday", 2))
            .put(row("com.example.mail", "回信", "Meeting", 1)).put(row("com.example.shop", "快递", "到货", 3))
        val before = rows.toString()
        var calls = 0
        fun search(query: String) = CompanionNotificationSearch.find(rows, query) { calls++; if (it.endsWith("mail")) "邮箱" else "商店" }
        assertEquals(listOf(2L, 1L), search("邮箱").map { it.row.getLong("time") })
        assertEquals(2, calls)
        assertEquals(1, search("meeting").size)
        assertEquals(1, search("example.shop").size)
        assertEquals(before, rows.toString())
        assertThrows(IllegalArgumentException::class.java) { search("   ") }
        assertThrows(IllegalArgumentException::class.java) { search("x".repeat(101)) }
    }
    @Test fun unavailableAppLabelFallsBackToPackage() {
        val rows = JSONArray().put(JSONObject().put("package", "com.example.app").put("title", "title").put("text", "body").put("time", 1))
        val result = CompanionNotificationSearch.find(rows, "example") { error("uninstalled") }
        assertEquals("com.example.app", result.single().sourceName)
    }
}
