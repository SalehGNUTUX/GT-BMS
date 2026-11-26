package com.vanaccess.bms.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeGateTest {
    /** Feed one reading per second; true if a charger was ever confirmed. */
    private fun run(s: Int, trip: Boolean, g: ChargeGate = ChargeGate(), t0: Long = 1_000, speed: (Int) -> Double?, amps: (Int) -> Double): Boolean =
        (0 until s).map { g.update(t0 + it * 1000L, amps(it), 0.3, trip, speed(it)) }.any { it }

    @Test fun brakingInTrafficIsNoCharge() {
        // ten minutes: ride, brake 6 s at +4 A, stop, ride again (2026-11-25, alarms at 96, 95 and 92 %)
        assertFalse(run(600, true, speed = { if (it % 40 < 30) 25.0 else 1.0 }, amps = { if (it % 40 in 24..31) 4.0 else -10.0 }))
        assertFalse(run(600, true, speed = { null }, amps = { if (it % 40 in 24..31) 4.0 else -10.0 }))     // GPS off
        assertFalse(run(600, false, speed = { null }, amps = { if (it % 40 in 24..31) 4.0 else -10.0 }))    // trip not begun yet
    }

    @Test fun longDescentIsNoCharge() {
        assertFalse(run(300, true, speed = { 30.0 }, amps = { 6.0 }))
        assertFalse(run(150, true, speed = { null }, amps = { 6.0 }))
        assertFalse(run(300, false, speed = { 30.0 }, amps = { 6.0 }))
    }

    @Test fun chargerIsFoundParkedAndOnArrival() {
        val g = ChargeGate()
        assertTrue(run(25, false, g, speed = { null }, amps = { 5.1 })); assertEquals(1_000L, g.since)
        assertFalse(run(55, true, speed = { 0.0 }, amps = { 5.1 })); assertTrue(run(65, true, speed = { 0.0 }, amps = { 5.1 }))
        assertTrue(run(185, true, speed = { null }, amps = { 5.1 }))
    }

    @Test fun lostLinkDoesNotJoinTwoBrakings() {
        val g = ChargeGate()
        assertFalse(g.update(1_000, 4.0, 0.3, false, null))
        assertFalse(g.update(61_000, 4.0, 0.3, false, null)); assertEquals(61_000L, g.since)
        assertTrue(run(25, false, g, 62_000, { null }, { 5.0 }))
    }

    @Test fun tripEndingMeanwhileDoesNotShortenTheWait() {
        // no GPS, long descent: the trip ends as "idle" after two minutes, the current is the same one
        val g = ChargeGate()
        val r = (0 until 190).map { g.update(1_000 + it * 1000L, 5.0, 0.3, it < 120, null) }
        assertFalse(r.take(175).any { it }); assertTrue(r.last())
        // the next current, parked, is judged as parked
        g.update(200_000, 0.0, 0.3, false, null)
        assertTrue(run(25, false, g, 201_000, { null }, { 5.0 }))
    }
}
