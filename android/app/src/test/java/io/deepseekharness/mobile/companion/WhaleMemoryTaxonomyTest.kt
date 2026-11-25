package io.deepseekharness.mobile.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleMemoryTaxonomyTest {

    @Test
    fun `auto classifies memories into 6 canonical domains and extracts entity tags`() {
        assertEquals(WhaleMemoryTaxonomy.Category.CONSTRAINT, WhaleMemoryTaxonomy.classify("绝不要在回复里使用长篇套话"))
        assertEquals(WhaleMemoryTaxonomy.Category.PROJECT, WhaleMemoryTaxonomy.classify("正在重构 Whale Assistant 的 Android 端代码"))
        assertEquals(WhaleMemoryTaxonomy.Category.PEOPLE, WhaleMemoryTaxonomy.classify("同事小王负责后端接口联调"))
        assertEquals(WhaleMemoryTaxonomy.Category.ROUTINE, WhaleMemoryTaxonomy.classify("每周三晚上有固定团队例会"))
        assertEquals(WhaleMemoryTaxonomy.Category.PREFERENCE, WhaleMemoryTaxonomy.classify("平时喜欢喝冰美式不加糖"))

        val tags = WhaleMemoryTaxonomy.extractTags("正在学习 Makalu 5G NR 架构《无线通信基础》")
        assertTrue(tags.any { it.equals("Makalu", ignoreCase = true) })
        assertTrue(tags.any { it == "无线通信基础" })
    }

    @Test
    fun `hybridRank prioritizes pinned items and excludes expired or superseded rows`() {
        val now = 1_700_000_000_000L
        val normal = JSONObject().put("id", "m1").put("text", "正在准备 5G 基带面试项目").put("updatedAt", now - 10_000L)
        val pinned = JSONObject().put("id", "m2").put("text", "正在准备 5G 基带芯片项目").put("pinned", true).put("salience", 5).put("updatedAt", now - 20_000L)
        val expired = JSONObject().put("id", "m3").put("text", "临时 5G 会议提醒").put("expiresAtMs", now - 1_000L)
        val superseded = JSONObject().put("id", "m4").put("text", "旧版 5G 项目计划").put("supersededBy", "m2")

        val ranked = WhaleMemoryTaxonomy.hybridRank(
            rows = listOf(normal, pinned, expired, superseded),
            query = "5G",
            category = "project",
            nowMs = now,
        )
        assertEquals(2, ranked.size)
        assertEquals("m2", ranked[0].getString("id"))
        assertFalse(ranked.any { it.getString("id") in setOf("m3", "m4") })
    }

    @Test
    fun `buildDigest groups top active memories across domains`() {
        val rows = listOf(
            JSONObject().put("id", "1").put("text", "平时喜欢简洁直接的沟通风格"),
            JSONObject().put("id", "2").put("text", "正在推进鲸鱼助手 Android 项目"),
        )
        val digest = WhaleMemoryTaxonomy.buildDigest(rows)
        assertEquals(2, digest.getInt("activeSummaryCount"))
        val cats = digest.getJSONObject("categories")
        assertTrue(cats.has("preference"))
        assertTrue(cats.has("project"))
    }
}
