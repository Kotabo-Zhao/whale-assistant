package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleNativeRagRetrieverTest {

    @Test
    fun greetingOnlyShortCircuitsSmallTalkButAllowsRealQueries() {
        assertTrue(WhaleNativeRagRetriever.isGreetingOnly("你好"))
        assertTrue(WhaleNativeRagRetriever.isGreetingOnly("hi!"))
        assertTrue(WhaleNativeRagRetriever.isGreetingOnly("谢谢。"))
        assertTrue(WhaleNativeRagRetriever.isGreetingOnly("a"))

        assertFalse(WhaleNativeRagRetriever.isGreetingOnly("帮我查一下上次存的基带笔记"))
        assertFalse(WhaleNativeRagRetriever.isGreetingOnly("今天有哪些待办事项"))
    }

    @Test
    fun parseSnippetsHandlesNullDisplayNameAndNormalizesWhitespace() {
        val items = JSONArray()
            .put(JSONObject().put("displayName", "基带算法笔记").put("excerpt", "MIMO   MMSE\n均衡器公式推导"))
            .put(JSONObject().put("displayName", JSONObject.NULL).put("text", "偏好深色模式和简洁回复"))
            .put(JSONObject().put("displayName", "本地资料").put("snippet", "   "))
            .put(JSONObject().put("displayName", "第三条").put("excerpt", "不应超出 maxItems 截断"))

        val snippets = WhaleNativeRagRetriever.parseSnippets(
            JSONObject().put("items", items).toString(),
            maxItems = 2,
            maxChars = 40,
        )

        assertEquals(2, snippets.size)
        assertEquals("《基带算法笔记》MIMO MMSE 均衡器公式推导", snippets[0])
        assertEquals("偏好深色模式和简洁回复", snippets[1])
        assertFalse(snippets[1].contains("null"))
    }
}
