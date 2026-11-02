package io.deepseekharness.mobile.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Neutral fixtures sent to the already configured provider; never ingests personal notifications. */
@RunWith(AndroidJUnit4::class)
class TaskRecognitionDeviceTest {
    @Test fun liveModelDistinguishesOwnerActionAndCompletedWork() {
        val model = configuredUnderstandingModel(InstrumentationRegistry.getInstrumentation().targetContext)
        val cases = listOf(
            "私信给你：请你在明天下午三点前提交你负责的项目报告。" to true,
            "群公告：小王，请你明天提交报告。其他同事仅供知悉，无需处理。" to false,
            "你的报告已提交并审核通过，全部流程已完成，无需任何操作。" to false,
            "新闻：某公司负责人计划明天发布产品。本文仅为新闻资讯。" to false,
        )
        cases.forEachIndexed { index, (body, expected) ->
            val id = "acceptance-$index"
            val result = model.analyze(id, body)
            UnderstandingModelContract.validate(result, id, body)
            val items = JSONObject(result).getJSONArray("items")
            val own = (0 until items.length()).map { items.getJSONObject(it) }
                .filter { it.optString("owner") == "user" && it.optString("status") == "required" }
            assertEquals("case $index", expected, own.isNotEmpty())
        }
    }
}
