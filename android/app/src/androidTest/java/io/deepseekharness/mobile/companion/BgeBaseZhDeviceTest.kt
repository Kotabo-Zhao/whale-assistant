package io.deepseekharness.mobile.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a real Android device to prove the bundled ONNX asset can embed Chinese locally. */
@RunWith(AndroidJUnit4::class)
class BgeBaseZhDeviceTest {
    @Test fun idleModelReleasesAndCanBeReopened() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val model = SemanticEmbeddings(inst.targetContext)
        val first = model.embed(listOf("语义检索")).single()
        val active = SemanticEmbeddings::class.java.getDeclaredField("runtime").apply { isAccessible = true }
        val loadedPss = android.os.Debug.getPss()
        val until = android.os.SystemClock.elapsedRealtime() + 75_000
        while (active.get(null) != null && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(500)
        assertTrue("空闲后仍持有推理会话", active.get(null) == null)
        Thread.sleep(500)
        inst.sendStatus(0, android.os.Bundle().apply {
            putLong("loadedPssKiB", loadedPss); putLong("idlePssKiB", android.os.Debug.getPss())
        })
        val reopened = model.embed(listOf("语义检索")).single()
        assertTrue(first.indices.all { kotlin.math.abs(first[it] - reopened[it]) < 0.0001f })
    }
    @Test fun ranksChineseParaphrasesOnDevice() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val model = SemanticEmbeddings(inst.targetContext)
        val corpus = listOf("周五晚上七点在青岚天文台集合看星星。", "项目预算上限是二十万元，报销需要发票。", "番茄鸡蛋汤先炒蛋再倒入热水。",
            "小红书笔记提到徒步时要带雨衣和登山杖。", "快递周末放在小区北门的储物柜。", "换手机后需要重新绑定公交卡。")
        val queries = listOf("什么时候去观测星空", "这项工程最多能花多少钱", "怎么做西红柿蛋花汤", "户外远足需要什么装备")
        val start = android.os.SystemClock.elapsedRealtime()
        val pssBefore = android.os.Debug.getPss()
        val docs = model.embed(corpus)
        val queryVectors = model.embed(queries)
        val ranks = queryVectors.mapIndexed { i, query ->
            docs.indices.sortedByDescending { j -> query.indices.sumOf { k -> query[k].toDouble() * docs[j][k] } }.indexOf(i) + 1
        }
        inst.sendStatus(0, android.os.Bundle().apply {
            putString("ranks", ranks.joinToString()); putLong("corpusQueryMs", android.os.SystemClock.elapsedRealtime() - start)
            putLong("pssBeforeKiB", pssBefore); putLong("pssWithModelKiB", android.os.Debug.getPss())
        })
        assertTrue(ranks.toString(), ranks.all { it in 1..3 })
    }
    @Test fun bundledModelEmbedsChineseWithoutNetwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vector = SemanticEmbeddings(context).embed(listOf("我喜欢黑咖啡，也常喝美式。 ")).single()
        assertEquals(SemanticEmbeddings.DIMENSIONS, vector.size)
        val norm = kotlin.math.sqrt(vector.sumOf { value -> value.toDouble() * value.toDouble() })
        assertTrue(norm in 0.999..1.001)
    }
}
