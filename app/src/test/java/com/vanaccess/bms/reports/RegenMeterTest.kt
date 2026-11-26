package com.vanaccess.bms.reports

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegenMeterTest {
    private fun RegenMeter.ride(t0: Int, s: Int, amps: Double, speed: (Int) -> Double?) =
        repeat(s) { add((t0 + it) * 1000L, amps, 76.0, 1.0, speed(it), 3.35, 0.3) }

    @Test fun brakingAndDescentAreTold() {
        val m = RegenMeter()
        m.ride(0, 20, -10.0) { 30.0 }
        m.ride(20, 5, 6.0) { 28.0 - 4 * it }           // brakes: speed falls with current in
        m.ride(25, 10, -10.0) { 25.0 }
        m.ride(35, 30, 3.0) { 25.0 + it * 0.4 }        // descent: speed rises with current in
        m.ride(65, 10, 0.0) { 37.0 + it * 0.5 }        // free descent: rising, nothing in or out
        m.ride(75, 5, -8.0) { 40.0 }
        m.close()
        assertEquals(2, m.count); assertEquals(35.0, m.seconds, 1e-6); assertEquals(6.0, m.peakA, 1e-6)
        assertEquals(6.0 * 76 * 5 / 3600, m.brakeWh, 1e-6); assertEquals(3.0 * 76 * 30 / 3600, m.hillWh, 1e-6)
        assertEquals(m.wh, m.brakeWh + m.hillWh, 1e-6)
        assertTrue(m.coastKm > 0.08 && m.coastS >= 9.0)
    }

    @Test fun descentEndingInABrake() {
        val m = RegenMeter()
        m.ride(0, 3, -5.0) { 30.0 }
        m.ride(3, 20, 3.0) { 30.0 + it * 0.5 }         // down the hill
        m.ride(23, 4, 6.0) { 38.0 - 6 * it }           // and brakes at the bottom: one event, split
        m.close()
        assertEquals(1, m.count)
        assertEquals(3.0 * 76 * 20 / 3600, m.hillWh, 1e-6); assertEquals(6.0 * 76 * 4 / 3600, m.brakeWh, 1e-6)
    }

    @Test fun shortPauseInABrakeIsNoSlope() {
        val m = RegenMeter().apply { ride(0, 2, -5.0) { 30.0 }; ride(2, 4, 4.0) { if (it < 2) 30.0 else 30.0 - 5 * (it - 1) }; close() }
        assertEquals(0.0, m.hillWh, 0.0); assertEquals(m.wh, m.brakeWh, 1e-9)
    }

    @Test fun chargerIsNotRecuperation() {
        val parked = RegenMeter().apply { ride(0, 50, 5.0) { 0.4 }; close() }
        assertEquals(0.0, parked.wh, 0.0)
        val blind = RegenMeter().apply { ride(0, 200, 5.0) { null }; close() }
        assertEquals(0.0, blind.wh, 0.0)
        val plugged = RegenMeter().apply { ride(0, 5, 5.0) { null }; discardOpen(); close() }
        assertEquals(0, plugged.count)
    }

    @Test fun withoutGpsItCountsUnsplit() {
        val m = RegenMeter().apply { ride(0, 4, 5.0) { null }; ride(4, 5, -5.0) { null }; close() }
        assertTrue(m.wh > 0); assertEquals(0.0, m.brakeWh + m.hillWh, 0.0)
    }

    @Test fun oldTripsAndSaving() {
        val pts = (0 until 60).map { Point(it * 2000L, 76.0, if (it in 20..23) 5.0 else -8.0, 90, if (it in 20..23) 26.0 - 4 * (it - 20) else 30.0, null, null) }
        val m = RegenMeter.fromPoints(pts)
        assertEquals(1, m.count); assertEquals(5.0 * 76 * 8 / 3600, m.wh, 1e-6)
        val o = JSONObject(); m.write(o)
        assertEquals(m.wh, RegenMeter().apply { read(o) }.wh, 1e-9)
    }

    @Test fun share() {
        assertEquals(10.0, RegenMeter.sharePct(10.0, 90.0)!!, 1e-9)
        assertNull(RegenMeter.sharePct(0.0, 90.0))
    }
}
