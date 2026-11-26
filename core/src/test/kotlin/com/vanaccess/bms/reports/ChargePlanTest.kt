package com.vanaccess.bms.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargePlanTest {
    private val min = 60_000L
    /** The user's charge of 2026-11-24: 43 % (13.0 Ah of 29.9) at 5.1 A, end in 3 h 18. */
    private fun plan(target: Int, soc: Int = 43, remain: Double = 13.0, hours: Double = 3.3, taper: Boolean = false) =
        ChargePlan.make("A", 0, soc, remain, 29.9, 5.1, target, hours, taper)!!
    private fun minutes(t: Long?) = t!! / min

    @Test fun eightyPercentThenNearTheEnd() {
        val p = plan(80)
        assertEquals(128L, minutes(p.targetAt))                 // (23.92 − 13.0) Ah / 5.1 A
        assertEquals(188L, minutes(p.nearAt)); assertEquals(198L, minutes(p.fullAt))
    }

    @Test fun hundredPercentRingsBeforeAndAtTheEnd() {
        val p = plan(100)
        assertEquals(p.fullAt, p.targetAt); assertEquals(188L, minutes(p.nearAt))
    }

    @Test fun percentageAlreadyPassedOrUnused() {
        assertNull(plan(80, soc = 85, remain = 25.5, hours = 0.8).targetAt); assertEquals(38L, minutes(plan(80, 85, 25.5, 0.8).nearAt))
        assertNull(plan(0).targetAt); assertEquals(188L, minutes(plan(0).nearAt))
    }

    @Test fun nothingSillyAtTheVeryEnd() {
        val p = plan(100, soc = 97, remain = 29.0, hours = 0.1)   // 6 min left
        assertNull(p.nearAt); assertEquals(6L, minutes(p.targetAt))
        assertNull(plan(98, soc = 90, remain = 26.9, hours = 0.6).nearAt.takeIf { it != null && it - plan(98, 90, 26.9, 0.6).targetAt!! < 3 * min })
        assertNull(ChargePlan.make("A", 0, 50, 15.0, 29.9, 0.0, 80, 2.0, false)); assertNull(ChargePlan.make("A", 0, 50, 15.0, 29.9, 5.0, 80, Double.NaN, false))
        assertNull(ChargePlan.make("A", 0, 50, 15.0, 0.0, 5.0, 80, 2.0, false))
    }

    @Test fun taperAndMissingCounter() {
        assertTrue(plan(95, hours = 5.0, taper = true).targetAt!! > plan(95, hours = 5.0).targetAt!!)
        assertEquals(128.0, plan(80, remain = 0.0, soc = 43).targetAt!! / min.toDouble(), 3.0)     // from the percentage
        assertTrue(plan(99, hours = 1.0).targetAt!! <= plan(99, hours = 1.0).fullAt)
    }

    @Test fun savedAndCompared() {
        val p = plan(80)
        assertEquals(p, ChargePlan.fromJson(p.toJson())); assertEquals(plan(0), ChargePlan.fromJson(plan(0).toJson()))
        assertNull(ChargePlan.fromJson(null)); assertNull(ChargePlan.fromJson("x"))
        assertTrue(p.differs(null)); assertFalse(p.copy(madeMs = 9, fullAt = p.fullAt + 30_000).differs(p)); assertTrue(p.copy(fullAt = p.fullAt + 2 * min).differs(p))
        assertTrue(p.copy(targetAt = null).differs(p))
    }
}
