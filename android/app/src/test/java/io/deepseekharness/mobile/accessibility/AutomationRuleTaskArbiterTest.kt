package io.deepseekharness.mobile.accessibility

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AutomationRuleTaskArbiterTest {
    @Test fun pendingTaskBlocksRuleAndIdleRunsIt() {
        var actions = 0
        assertNull(AutomationRuleTaskArbiter.runIfIdle({ true }) { actions++; "ran" })
        assertEquals(0, actions)
        assertEquals("ran", AutomationRuleTaskArbiter.runIfIdle({ false }) { actions++; "ran" })
        assertEquals(1, actions)
    }
    @Test fun taskTransitionWaitsForRuleDispatchAndThenBlocksLaterRules() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val planned = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        var pending = false
        try {
            val first = pool.submit<String?> {
                AutomationRuleTaskArbiter.runIfIdle({ pending }) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); "done" }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val plan = pool.submit { synchronized(AutomationTaskInterruptionStore.lock) { pending = true; planned.countDown() } }
            assertFalse(planned.await(100, TimeUnit.MILLISECONDS))
            release.countDown(); assertEquals("done", first.get(3, TimeUnit.SECONDS)); plan.get(3, TimeUnit.SECONDS)
            assertTrue(planned.await(3, TimeUnit.SECONDS))
            assertNull(AutomationRuleTaskArbiter.runIfIdle({ pending }) { "should not run" })
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun staleGestureCallbackCannotReleaseAnotherGesture() {
        val tracker = AutomationRuleGestureTracker()
        val first = tracker.begin()!!
        assertTrue(tracker.inFlight()); assertNull(tracker.begin())
        tracker.finish(first); val second = tracker.begin()!!
        tracker.finish(first); assertTrue(tracker.inFlight())
        tracker.finish(second); assertFalse(tracker.inFlight())
    }
}
