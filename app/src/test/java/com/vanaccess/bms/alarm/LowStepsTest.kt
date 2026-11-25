package com.vanaccess.bms.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LowStepsTest {
    /** Ride through these charges; which ones ring when every alarm is stopped at once? */
    private fun rings(limit: Int, socs: Iterable<Int>): List<Int> {
        var last = 0
        return socs.filter { soc ->
            last = LowSteps.settle(last, soc, limit)
            LowSteps.due(last, soc, limit).also { if (it) last = LowSteps.at(soc, limit) }
        }
    }

    @Test fun remindsAtEachStep() {
        assertEquals(listOf(30, 20, 15, 10, 5), rings(30, 60 downTo 0))
        assertEquals(listOf(18, 15, 10, 5), rings(18, 60 downTo 0))
        assertEquals(listOf(5), rings(5, 60 downTo 0))
        assertEquals(emptyList<Int>(), rings(0, 60 downTo 0))
    }

    @Test fun jitterIsSilentAndChargingRearms() {
        assertEquals(listOf(30), rings(30, listOf(31, 30, 31, 30, 32, 29)))
        assertEquals(listOf(10, 15, 10), rings(30, listOf(10, 16, 15, 11, 10)))
        assertEquals(listOf(30, 20, 30), rings(30, listOf(30, 20, 90, 30)))
    }

    @Test fun connectingLowRingsOnce() = assertEquals(listOf(12, 10), rings(30, listOf(12, 12, 11, 10)))

    @Test fun nextStep() {
        assertEquals(20, LowSteps.next(30, 30)); assertEquals(10, LowSteps.next(12, 30))
        assertEquals(5, LowSteps.next(10, 30)); assertNull(LowSteps.next(4, 30))
    }
}
