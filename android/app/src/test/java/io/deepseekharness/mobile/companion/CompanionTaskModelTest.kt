package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CompanionTaskModelTest {
    @Test fun parsesOneOptimisticStateTransition() {
        val update = CompanionTaskModel.parseUpdate("""{"op":"update","id":"00000000-0000-0000-0000-000000000001","expectedState":"candidate","state":"open"}""")
        assertEquals("candidate", update.expectedState)
        assertEquals("open", update.nextState)
        assertNull(update.reminderAtMs)
    }

    @Test fun parsesFutureReminderButRejectsAmbiguousMutation() {
        val update = CompanionTaskModel.parseUpdate("""{"op":"update","id":"00000000-0000-0000-0000-000000000001","expectedState":"open","reminderAtMs":1900000000000}""")
        assertEquals(1_900_000_000_000L, update.reminderAtMs)
        assertThrows(Exception::class.java) {
            CompanionTaskModel.parseUpdate("""{"op":"update","id":"00000000-0000-0000-0000-000000000001","expectedState":"open","state":"done","reminderAtMs":1900000000000}""")
        }
    }
}
