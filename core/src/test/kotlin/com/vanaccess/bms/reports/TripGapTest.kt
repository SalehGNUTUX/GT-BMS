package com.vanaccess.bms.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripGapTest {
    private val m = 60_000L

    @Test fun linkLostWhileRidingKeepsTheTrip() {
        // 2026-11-24: battery silent from minute 2, rider goes on to ~8 km
        for (min in 1L..14L) assertFalse(TripGap.shouldEnd(now = (2 + min) * m, lastSampleMs = 2 * m,
            ridingAtLossMs = 2 * m, lastRidingMs = (2 + min) * m - 1000))
        // arrived at minute 16: ends two minutes after the last riding fix
        assertFalse(TripGap.shouldEnd(17 * m, 2 * m, 2 * m, 16 * m))
        assertTrue(TripGap.shouldEnd(18 * m, 2 * m, 2 * m, 16 * m))
    }

    @Test fun walkingAwayFromParkedVehicleStillEndsAfterAMinute() {
        assertFalse(TripGap.shouldEnd(10 * m + 50_000, 10 * m, 9 * m, 9 * m))
        assertTrue(TripGap.shouldEnd(11 * m + 1000, 10 * m, 9 * m, 9 * m))
        // …even if the rider then takes a bus
        assertTrue(TripGap.shouldEnd(11 * m + 1000, 10 * m, 9 * m, 11 * m))
    }

    @Test fun noGpsOrVeryLongSilenceEnds() {
        assertTrue(TripGap.shouldEnd(2 * m + 121_000, 2 * m, 2 * m, 2 * m))
        assertTrue(TripGap.shouldEnd(48 * m, 2 * m, 2 * m, 48 * m))
    }

    @Test fun missingEnergyFromBmsCounter() {
        assertEquals(2.2, TripGap.missingAh(29.2, 27.0, 29.9), 1e-9)
        assertEquals(0.0, TripGap.missingAh(27.0, 29.9, 29.9), 0.0)   // charged / recalibrated meanwhile
        assertEquals(0.0, TripGap.missingAh(0.0, 27.0, 29.9), 0.0)
    }

    @Test fun distanceAcrossFixGap() {
        assertTrue(TripGap.plausible(500.0, 45.0, 40.0, 40.0))
        assertFalse(TripGap.plausible(5000.0, 45.0, 40.0, 40.0))      // 400 km/h jump
        assertFalse(TripGap.plausible(500.0, 200.0, 40.0, 40.0))
    }

    @Test fun shortUnheardEndKeepsTheConsumption() {
        org.junit.Assert.assertEquals(2.19, TripGap.energyKm(false, 2.19, 2.19)!!, 1e-9)
        org.junit.Assert.assertEquals(2.0, TripGap.energyKm(true, 2.19, 2.0)!!, 1e-9)
        org.junit.Assert.assertNull(TripGap.energyKm(true, 8.0, 2.0))
        org.junit.Assert.assertNull(TripGap.energyKm(true, 0.2, 0.2))
    }
}
