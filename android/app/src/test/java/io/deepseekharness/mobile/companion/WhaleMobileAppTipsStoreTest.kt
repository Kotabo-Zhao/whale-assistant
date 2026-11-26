package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleMobileAppTipsStoreTest {

    @Test
    fun `merges deduplicated tips and shortcuts per package and formats dynamic prompt`() {
        var k = WhaleMobileAppTipsStore.AppKnowledge("com.tencent.mm")
        k = WhaleMobileAppTipsStore.mergeTip(k, "发送消息前先点击底部输入框聚焦")
        k = WhaleMobileAppTipsStore.mergeTip(k, "发送消息前先点击底部输入框聚焦") // duplicate ignored
        k = WhaleMobileAppTipsStore.mergeShortcut(k, "发微信", "点击搜索 -> 输入联系人 -> 点击第一项 -> 输入并发送")

        assertEquals(1, k.tips.size)
        assertEquals(1, k.shortcuts.size)

        val prompt = WhaleMobileAppTipsStore.buildDynamicPrompt(k)
        assertTrue(prompt.contains("com.tencent.mm"))
        assertTrue(prompt.contains("避坑 Tips"))
        assertTrue(prompt.contains("可复用 Shortcuts"))
        assertTrue(prompt.contains("发微信"))
    }
}
