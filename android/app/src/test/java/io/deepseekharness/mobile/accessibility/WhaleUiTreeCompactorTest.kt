package io.deepseekharness.mobile.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleUiTreeCompactorTest {
    @Test
    fun prunesEmptyLayoutWrappersAndBubblesChildLabelsToClickableParents() {
        val raw = listOf(
            // Empty root layout wrapper (noise)
            WhaleUiTreeCompactor.RawNode(0, -1, "FrameLayout", "", "", "", "", false, false, false, true, 0, 0, 1080, 2400),
            // Empty intermediate layout wrapper (noise)
            WhaleUiTreeCompactor.RawNode(1, 0, "LinearLayout", "", "", "", "", false, false, false, true, 0, 0, 1080, 300),
            // Clickable button container without own text
            WhaleUiTreeCompactor.RawNode(2, 1, "LinearLayout", "com.demo:id/btn_search", "", "", "", true, false, false, true, 40, 100, 1040, 220),
            // Child TextView inside clickable container -> should bubble up into node 2
            WhaleUiTreeCompactor.RawNode(3, 2, "TextView", "", "搜索应用", "", "", false, false, false, true, 60, 120, 400, 200),
            // Offscreen node -> should be pruned
            WhaleUiTreeCompactor.RawNode(4, 0, "Button", "", "屏外按钮", "", "", true, false, false, true, 0, 2500, 200, 2600),
            // Invisible node -> should be pruned
            WhaleUiTreeCompactor.RawNode(5, 0, "Button", "", "隐藏按钮", "", "", true, false, false, false, 40, 400, 200, 500),
        )

        val compact = WhaleUiTreeCompactor.compact(raw, 1080, 2400)
        assertEquals(1, compact.size)
        val el = compact[0]
        assertEquals(1, el.ref)
        assertEquals("搜索应用", el.label)
        assertEquals(540, el.cx)
        assertEquals(160, el.cy)
        assertTrue(el.clickable)
        assertEquals("btn_search", el.json().getString("id"))
    }
}
