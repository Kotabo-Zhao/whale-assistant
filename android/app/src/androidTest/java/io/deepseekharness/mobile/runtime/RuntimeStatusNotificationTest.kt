package io.deepseekharness.mobile.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the test package's isolated storage, never the target application's runtime. */
@RunWith(AndroidJUnit4::class)
class RuntimeStatusNotificationTest {
    @Test fun stoppingPublishesTheResultingIdlePhase() {
        val context = object : android.content.ContextWrapper(InstrumentationRegistry.getInstrumentation().context) {
            override fun getApplicationContext(): android.content.Context = this
        }
        val status = RuntimeStatus(RuntimeStore(context))
        val phases = mutableListOf<RuntimePhase>()
        status.progressListener = { phases.add(it.phase) }
        status.update(RuntimePhase.STOPPING)
        val idle = status.refreshIdle()
        assertEquals(listOf(RuntimePhase.STOPPING, idle.phase), phases)
    }
}
