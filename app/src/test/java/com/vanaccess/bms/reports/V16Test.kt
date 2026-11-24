package com.vanaccess.bms.reports

import org.json.JSONObject
import com.vanaccess.bms.reports.CapacityTest.Phase as P
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V16Test {
    private fun pt(t: Long, i: Double, lat: Double? = null) = Point(t, 76.0, i, 50, null, lat, lat)

    @Test fun chargeTimeTo80And100() {
        val r = ChargeTime.estimate(soc = 50, fullAh = 30.0, currentA = 5.0)!!
        assertEquals(1.8, r.to80h!!, 1e-9)                         // 9 Ah at 5 A
        assertEquals(2.4 + 1.2, r.to100h, 1e-9)                    // 12 Ah at 5 A to 90 %, last 3 Ah at 2.5 A
        assertNull(ChargeTime.estimate(85, 30.0, 5.0)!!.to80h)
        assertNull(ChargeTime.estimate(50, 30.0, -3.0))
    }

    @Test fun coldReducesRange() {
        assertEquals(1.0, RangeEstimator.tempFactor(25.0), 1e-9)
        assertEquals(0.85, RangeEstimator.tempFactor(0.0), 1e-9)
        assertEquals(0.90, RangeEstimator.tempFactor(5.0), 1e-9)
        val warm = RangeEstimator.estimate(20.0, 30.0, 76.0, 23, 3.2, 0.0, null, 25.0, 20.0)!!
        val cold = RangeEstimator.estimate(20.0, 30.0, 76.0, 23, 3.2, 0.0, null, 25.0, 0.0)!!
        assertEquals(warm.km * 0.85, cold.km, 1e-6)
    }

    @Test fun capacityTestFullCycle() {

        var st = CapacityTest.start(0)
        st = CapacityTest.update(st, 1000, 20, -5.0, 3.20, 3.25, true, 3.05, 3.63)
        assertEquals(P.DISCHARGE, st.phase)
        st = CapacityTest.update(st, 2000, 3, -2.0, 3.06, 3.10, true, 3.05, 3.63)
        assertEquals(P.READY, st.phase)
        var t = 3000L
        st = CapacityTest.update(st, t, 3, 6.0, 3.10, 3.15, true, 3.05, 3.63)
        assertEquals(P.CHARGING, st.phase)
        repeat(1800) { t += 10_000; st = CapacityTest.update(st, t, 60, 6.0, 3.3, 3.35, true, 3.05, 3.63) }  // 5 h × 6 A
        st = CapacityTest.update(st, t + 10_000, 100, 0.2, 3.5, 3.63, false, 3.05, 3.63)
        assertEquals(P.DONE, st.phase)
        assertEquals(30.0, st.resultAh!!, 0.05)
        assertEquals(st, CapacityTest.State.fromJson(st.toJson()))
    }

    @Test fun capacityTestInterrupted() {
        var st = CapacityTest.State(CapacityTest.Phase.CHARGING, 0, 5.0, 1000)
        st = CapacityTest.update(st, 2000, 60, 0.0, 3.3, 3.35, true, 3.05, 3.63)
        st = CapacityTest.update(st, 2000 + 16 * 60_000L, 60, 0.0, 3.3, 3.35, true, 3.05, 3.63)
        assertEquals(CapacityTest.Phase.FAILED, st.phase)
    }

    @Test fun drivingShocksVersusSmooth() {
        // press-and-release: 0 A → 40 A → 0 A, six times
        val jerky = (0 until 60).map { k -> pt(k * 2000L, if (k % 10 in 1..4) -40.0 else 0.0) }
        val s1 = DrivingAnalyzer.analyze(jerky, 50)
        assertEquals(6, s1.shocks); assertTrue(s1.onOff >= 5)
        // progressive: ramps 2 A per sample then holds 25 A
        val smooth = (0 until 60).map { k -> pt(k * 2000L, -minOf(2.0 * k + 2, 25.0)) }
        assertEquals(0, DrivingAnalyzer.analyze(smooth, 50).shocks)
    }

    @Test fun bestSpeedBand() {
        fun trip(speed: Double, km: Double, wh: Double) = Session(0, Session.TYPE_TRIP, 0, 0, "",
            JSONObject().put("avg_speed", speed).put("distance_km", km).put("wh", wh).put("wh_per_km", wh / km))
        val b = DrivingAnalyzer.bestSpeed(listOf(trip(33.0, 5.0, 105.0), trip(36.0, 4.0, 84.0),
            trip(45.0, 6.0, 168.0), trip(48.0, 5.0, 145.0)))!!
        assertEquals(30, b.fromKmh); assertEquals(21.0, b.bestWhKm, 1e-9)
        assertEquals(40, b.worstFromKmh)
    }

    @Test fun protectionRepeatsFromHistory() {
        fun rec(t: String) = com.vanaccess.bms.ble.drivers.HistoryRecord(t, listOf("Charging", "Cell overvoltage protection"))
        val h = listOf(rec("2026-11-22 21:31:51"), rec("2026-11-21 22:16:28"), rec("2026-11-21 22:04:39"),
            rec("2026-11-21 21:50:00"), rec("2000-01-01 00:59:08"))
        assertEquals(3, DrivingAnalyzer.protectionRepeats(h))
    }

    @Test fun energyCost() {
        assertEquals(0.659, EnergyCost.cost(560.0, 1.0), 0.001)         // 560 Wh into the battery, 85 % charger
        assertEquals(1.75, EnergyCost.fuel(10.0, 2.5, 7.0), 1e-9)       // 10 km × 2.5 L/100 km × 7
    }
}
