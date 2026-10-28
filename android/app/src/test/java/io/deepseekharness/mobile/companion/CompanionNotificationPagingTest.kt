package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionNotificationPagingTest {
    @Test fun startsWithTenNewestAndRevealsAllWithoutDuplicates() {
        val first = CompanionNotificationPaging.indices(50, 10)
        assertEquals((49 downTo 40).toList(), first)
        var visible = 10
        repeat(4) { visible = CompanionNotificationPaging.next(visible) }
        assertEquals((49 downTo 0).toList(), CompanionNotificationPaging.indices(50, visible))
        assertEquals(50, CompanionNotificationPaging.next(visible))
    }
    @Test fun handlesEmptySmallAndBoundedLists() {
        assertTrue(CompanionNotificationPaging.indices(0, 10).isEmpty())
        assertEquals(listOf(2, 1, 0), CompanionNotificationPaging.indices(3, 10))
        assertEquals(10, CompanionNotificationPaging.indices(100, 10).size)
        assertTrue(CompanionNotificationPaging.indices(-1, 10).isEmpty())
    }
}
