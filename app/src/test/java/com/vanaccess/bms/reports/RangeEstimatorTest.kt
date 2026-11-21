package com.vanaccess.bms.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeEstimatorTest {
    /** The user's screen: 71 % of 30 Ah ≈ 21.3 Ah, 23S LiFePO4, learned 16.5 Wh/km over 2.4 km. */
    @Test fun userCaseWithShortHistoryUsesTypeDefault() {
        val r = RangeEstimator.estimate(21.3, 30.0, 76.39, 23, true, 0.0, 16.5 to 2.4, 30.0)!!
        assertEquals(RangeEstimator.Basis.DEFAULT, r.basis)            // 2.4 km < 5 km
        assertEquals(73.6, r.nominalV, 1e-9)                           // 23 × 3.2 V
        assertEquals(3.0, r.reserveAh, 1e-9)                           // 10 % of 30 Ah
        assertEquals((21.3 - 3.0) * 73.6 / 30.0, r.km, 1e-9)           // ≈ 45 km
    }

    @Test fun learnedAfterFiveKm() {
        val r = RangeEstimator.estimate(21.3, 30.0, 76.39, 23, true, 0.0, 16.5 to 8.0, 30.0)!!
        assertEquals(RangeEstimator.Basis.LEARNED, r.basis)
        assertEquals(16.5, r.whPerKm, 1e-9)
    }

    @Test fun manualValueWins() {
        val r = RangeEstimator.estimate(21.3, 30.0, 76.39, 23, true, 25.0, 16.5 to 50.0, 30.0)!!
        assertEquals(RangeEstimator.Basis.USER, r.basis)
        assertEquals(25.0, r.whPerKm, 1e-9)
    }

    @Test fun belowReserveIsZeroAndNoDataIsNull() {
        assertEquals(0.0, RangeEstimator.estimate(2.0, 30.0, 70.0, 23, true, 0.0, null, 30.0)!!.km, 1e-9)
        assertNull(RangeEstimator.estimate(0.0, 30.0, 70.0, 23, true, 0.0, null, 30.0))
    }
}
