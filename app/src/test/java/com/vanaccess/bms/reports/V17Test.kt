package com.vanaccess.bms.reports

import com.vanaccess.bms.alarm.AlarmCheck
import com.vanaccess.bms.reports.ChargePredictor.Kind
import com.vanaccess.bms.reports.ChargeWatch.Stop
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V17Test {
    private fun cells(max: Double, delta: Double = 0.07) = List(23) { if (it == 8) max else if (it == 11) max - delta else max - 0.035 }

    /** The charge of 2026-11-23 as filmed: 76 % at the start, 6.92 Ah in, 94 % shown, cut at 3.635 V. */
    private fun filmed(): ChargeWatch {
        val w = ChargeWatch(0, 76, 22.8)
        var t = 4_500_000L; var max = 3.43
        // ten minutes of slow rise, then 2.24 mV/s
        repeat(580) { max += 0.00022; t += 1000; w.charging(t, 94, 28.4, 29.9, 5.1, max * 23 - 0.8, cells(max), true, 6.0 + (t - 4_500_000) * 5.1 / 3.6e6) }
        while (max < 3.612) { max += 0.00224; t += 1000; w.charging(t, 94, 28.4, 29.9, 5.1, max * 23 - 0.8, cells(max), true, 6.90) }
        last = t
        return w
    }
    private var last = 0L

    @Test fun jumpAtTheCutIsNotHidden() {
        val w = filmed()
        w.charging(last + 1000, 100, 29.9, 29.9, 5.1, 82.75, cells(3.635), true, 6.92)
        val r = w.stopped(last + 31_000, 100, 29.9, 29.9, false, emptyList(), 3.63)
        assertEquals(Stop.PROTECTION, r.reason)
        assertEquals(94, r.socBefore); assertEquals(100, r.socShown); assertEquals(99, r.socCounted)
        assertEquals(9, r.maxIdx); assertEquals(12, r.minIdx); assertEquals(70, r.deltaMv)
        assertTrue(r.earlyCell!! in 3.45..3.60)
    }

    @Test fun cutAnnouncedInTheLastSecondsNotIn42Minutes() {
        val w = filmed()
        val p = ChargePredictor.predict(94, 29.9, 5.1, ChargeLearn(true), w, 28.4, 3.63)!!
        assertEquals(Kind.CUT_SOON, p.kind); assertEquals(9, p.cell); assertTrue(p.hours * 3600 < 60)
    }

    @Test fun constantCurrentEstimateCountsTheChargePutIn() {
        val w = ChargeWatch(0, 76, 22.8)
        w.charging(60_000, 90, 27.0, 29.9, 5.1, 79.0, cells(3.43), true, 5.5)
        val p = ChargePredictor.predict(90, 29.9, 5.1, ChargeLearn(true), w, 27.0, 3.63)!!
        assertEquals(Kind.CUT, p.kind)
        assertEquals((29.9 * 0.24 - 5.5) / 5.1, p.hours, 1e-6)          // 20 min, the taper formula said 70
        // a charger that tapers keeps the old estimate
        assertEquals(Kind.FULL, ChargePredictor.predict(90, 29.9, 5.1, ChargeLearn(false), w, 27.0, 3.63)!!.kind)
        assertNull(ChargePredictor.predict(90, 29.9, 0.0, ChargeLearn(true), w, 27.0, 3.63))
    }

    @Test fun normalRiseIsNoJump() {
        val w = ChargeWatch(0, 50, 15.0)
        for (k in 1..60) w.charging(k * 10_000L, 50 + k / 12, 15.0 + k * 5.1 * 10 / 3600, 29.9, 5.1, 77.0, cells(3.36), true, k * 5.1 * 10 / 3600)
        // one hour without readings, the BMS went on counting
        w.charging(600_000 + 3_600_000L, 72, 20.9, 29.9, 5.1, 77.5, cells(3.37), true, 0.9)
        val r = w.stopped(4_260_000, 72, 20.9, 29.9, true, emptyList(), 3.63)
        assertEquals(72, r.socBefore); assertEquals(Stop.INTERRUPTED, r.reason)
    }

    @Test fun otherStops() {
        fun run(i: (Int) -> Double, soc: Int, fet: Boolean, alarms: List<String> = emptyList()): Stop {
            val w = ChargeWatch(0, 80, 24.0)
            for (k in 0..300) w.charging(k * 1000L, soc, 0.0, 29.9, i(k), 78.0, cells(3.45), true, 1.0)
            return w.stopped(330_000, soc, 0.0, 29.9, fet, alarms, 3.63).reason
        }
        assertEquals(Stop.CHARGER, run({ if (it < 100) 5.0 else 0.6 }, 97, true))
        assertEquals(Stop.INTERRUPTED, run({ 5.0 }, 85, true))
        // cut at only 81.86 V (history 2026-11-21): the charge switch tells, not a voltage threshold
        assertEquals(Stop.PROTECTION, run({ 5.0 }, 95, false))
        assertEquals(Stop.PROTECTION, run({ 5.0 }, 95, true, listOf("Cell overvoltage")))
        assertEquals(Stop.INTERRUPTED, run({ 5.0 }, 85, true, listOf("Discharge overcurrent")))
    }

    private fun charge(protection: Boolean, full: Boolean, idx: Int = 9, extra: JSONObject.() -> Unit = {}) =
        Session(1, Session.TYPE_CHARGE, 0, 80 * 60_000L, "", JSONObject().put("ended_by_protection", protection)
            .put("reached_full", full).put("max_cell_idx", idx).put("max_delta_mv", 90).apply(extra))

    @Test fun learnsFromPastCharges() {
        val pts = (0..4800 step 2).map { Point(it * 1000L, 76 + it / 4800.0 * 6.7, 5.1, 80, null, null, null) }
        val v5 = ChargeLearn.packBefore(pts)!!
        assertEquals(76 + 4500 / 4800.0 * 6.7, v5, 0.01)
        val l = ChargeLearn.from(listOf(charge(true, true), charge(true, true), charge(false, false)), 23) { pts }
        assertEquals(true, l.cutByProtection); assertEquals(v5 / 23, l.earlyCellV!!, 1e-6)
        assertEquals(3.61, l.copy(earlyCellV = 3.70).alarmCellV(3.63), 1e-9)      // always below the protection
        assertEquals(3.45, ChargeLearn().alarmCellV(3.63), 1e-9)
        assertNull(ChargeLearn.from(emptyList(), 23) { pts }.cutByProtection)
    }

    @Test fun statistics() {
        val trips = List(30) { Session(1, Session.TYPE_TRIP, 0, 0, "", JSONObject().put("min_cell_idx", 12).put("max_current", 40.0)) }
        val s = ChargeStats.from(listOf(charge(true, true), charge(true, true) { put("eta_first_min", 120.0) },
            charge(true, true, 6), charge(false, false)), trips)!!
        assertEquals(4, s.total); assertEquals(3, s.byProtection); assertEquals(0, s.byCharger); assertEquals(1, s.early)
        assertEquals(9, s.cutCell); assertEquals(2, s.cutCellTimes)
        assertEquals(12, s.weakCell); assertEquals(30, s.weakCellTimes); assertEquals(40, s.avgEtaErrorMin)
    }

    @Test fun alarmValues() {
        assertNotNull(AlarmCheck.highVoltage(83.8, 23, 3630))               // above the protection (83.49)
        assertNull(AlarmCheck.highVoltage(81.6, 23, 3630)); assertNull(AlarmCheck.highVoltage(0.0, 23, 3630))
        assertNull(AlarmCheck.highVoltage(14.0, 0, 0))                      // unknown battery: not judged
        assertNull(AlarmCheck.lowVoltage(68.0, 23, 3630, 2500)); assertNotNull(AlarmCheck.lowVoltage(80.0, 23, 3630, 2500))
        assertNotNull(AlarmCheck.cellVoltage(3.63, 3630)); assertNull(AlarmCheck.cellVoltage(3.5, 3630))
        assertEquals(81.6, AlarmCheck.effectiveHighVoltage(83.8, 23, 3630), 1e-9)
        assertEquals(81.0, AlarmCheck.effectiveHighVoltage(81.0, 23, 3630), 1e-9)
    }

    @Test fun recognisesOtherBatteries() {
        val M = BatteryModel
        assertEquals(M.LFP, M.chemistry(3.33, 3.33, 88, true))
        assertEquals(M.LION, M.chemistry(4.10, 3.70, 50, false))
        assertEquals(M.LION, M.chemistry(3.60, 3.60, 40, true))
        assertEquals(M.LTO, M.chemistry(2.40, 2.30, 60, true))
        assertNull(M.chemistry(3.42, 3.42, 95, true))          // full LFP or low Li-ion: proves nothing
        assertNull(M.chemistry(3.33, 3.33, 50, false))         // under load
        // 13S Li-ion e-bike: its own ranges, not those of a 23S LFP
        assertNull(AlarmCheck.highVoltage(53.0, 13, 4250)); assertNotNull(AlarmCheck.highVoltage(81.6, 13, 4250))
        assertEquals(4.20, ChargeLearn().alarmCellV(4.25), 1e-9)
        // 4S LFP
        assertNull(AlarmCheck.highVoltage(14.2, 4, 3650)); assertNotNull(AlarmCheck.highVoltage(14.6, 4, 3650))
        // unknown chemistry: nothing is judged
        assertNull(AlarmCheck.highVoltage(81.6, 13, 0)); assertNull(AlarmCheck.lowVoltage(1.0, 13, 0, null))
        assertEquals(100.0, RangeEstimator.estimate(10.0, 0.0, 50.0, 13, null, 5.0, null, 0.0)!!.km, 1e-9)
        assertTrue(!BmsLimits().lfp && !BmsLimits().lion)
    }

    @Test fun drivingThresholdsFollowTheBattery() {
        // 10 A scooter: 0 → 6 A pulls are shocks for it, though far below 15 A
        val pts = (0 until 60).map { Point(it * 2000L, 36.0, if (it % 10 in 1..4) -6.0 else 0.0, 50, null, null, null) }
        assertEquals(6, DrivingAnalyzer.analyze(pts, 10).shocks)
        assertEquals(0, DrivingAnalyzer.analyze(pts, 50).shocks)
    }

    @Test fun routeFallsInsideTheOsmAndPicture() {
        // ~1.3 km in Rabat, picture of 1500×1800 px at density 6
        val track = listOf(33.9700 to -6.8600, 33.9760 to -6.8550, 33.9800 to -6.8500)
        val p = com.vanaccess.bms.map.OsmAndProjection.of(track, 1500, 1800, 6f)!!
        track.forEach { (lat, lon) -> assertTrue(p.x(lon) in 0f..1500f && p.y(lat) in 0f..1800f) }
        assertTrue(p.x(-6.85) > p.x(-6.86)); assertTrue(p.y(33.98) < p.y(33.97))            // east right, north up
        assertEquals(750f, (p.x(-6.86) + p.x(-6.85)) / 2, 1f)
        assertNull(com.vanaccess.bms.map.OsmAndProjection.of(emptyList(), 10, 10, 1f))
    }
}
