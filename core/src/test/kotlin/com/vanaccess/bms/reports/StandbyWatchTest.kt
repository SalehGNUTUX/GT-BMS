package com.vanaccess.bms.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyWatchTest {
    private fun run(w: StandbyWatch, s: Int, t0: Long = 1_000_000, speed: Double? = null, amps: (Int) -> Double): Boolean =
        (0 until s).map { w.update(t0 + it * 1000L, amps(it), speed, 1.0) }.last()

    @Test fun switchedOnAndStill() {
        val w = StandbyWatch()
        assertFalse(run(w, 50) { -0.3 }); assertTrue(run(w, 20, 1_050_000) { -0.3 })
        assertEquals(1_000_000L, w.onSince); assertEquals(0.3, w.amps, 0.01)
    }

    @Test fun notStandby() {
        assertFalse(run(StandbyWatch(), 300) { 0.0 })                       // switched off: the BMS alone reads 0
        assertFalse(run(StandbyWatch(), 300) { -0.1 })                      // noise
        assertFalse(run(StandbyWatch(), 300) { -8.0 })                      // riding
        assertFalse(run(StandbyWatch(), 300) { 5.1 })                       // charging
        assertFalse(run(StandbyWatch(), 300, speed = 20.0) { -0.4 })        // rolling downhill, little draw
        val w = StandbyWatch(); run(w, 120) { -0.3 }
        assertFalse(w.update(1_121_000, -6.0, 15.0, 1.0)); assertEquals(0L, w.onSince)   // rides off
    }
}
