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
    @Test fun bundledModelEmbedsChineseWithoutNetwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vector = SemanticEmbeddings(context).embed(listOf("我喜欢黑咖啡，也常喝美式。 ")).single()
        assertEquals(SemanticEmbeddings.DIMENSIONS, vector.size)
        val norm = kotlin.math.sqrt(vector.sumOf { value -> value.toDouble() * value.toDouble() })
        assertTrue(norm in 0.999..1.001)
    }
}
