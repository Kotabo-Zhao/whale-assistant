package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.junit.Assert.*
import org.junit.Test

class ScreenSequenceTicketsTest {
    private val id="00000000-0000-0000-0000-000000000001"
    @Test fun cancellationBeforeAdmissionCannotRevive() {
        val tickets=ScreenSequenceTickets{0L};tickets.cancel(id)
        assertEquals("SCREEN_SEQUENCE_CANCELLED",assertThrows(RuntimeFailure::class.java){tickets.start(id)}.code)
    }
    @Test fun runningAndFinishedOperationsAreNeverReplayed() {
        val tickets=ScreenSequenceTickets{0L};tickets.start(id);tickets.check(id)
        assertThrows(RuntimeFailure::class.java){tickets.start(id)}
        tickets.finish(id);assertThrows(RuntimeFailure::class.java){tickets.check(id)}
        assertThrows(RuntimeFailure::class.java){tickets.start(id)}
    }
    @Test fun cancellingRevokesOnlyItsOwnOperation() {
        val second=id.dropLast(1)+"2"
        val tickets=ScreenSequenceTickets{0L};tickets.start(id);tickets.start(second)
        tickets.cancel(id);assertThrows(RuntimeFailure::class.java){tickets.check(id)};tickets.check(second)
    }
    @Test fun fullTableFailsClosedAndExpiresOnlyInactiveEntries() {
        var time=0L;val tickets=ScreenSequenceTickets{time}
        tickets.start(id)
        repeat(255){tickets.cancel("00000000-0000-0000-0001-"+it.toString().padStart(12,'0'))}
        val extra=id.dropLast(1)+"3"
        assertThrows(RuntimeFailure::class.java){tickets.start(extra)}
        time=600001;tickets.start(extra);tickets.check(id)
    }
}
