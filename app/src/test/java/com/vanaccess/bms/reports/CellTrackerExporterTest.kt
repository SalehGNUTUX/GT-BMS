package com.vanaccess.bms.reports

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CellTrackerExporterTest {

    /** Like the user's pack: cell 12 ≈ 27 mV low under load, cell 9 often lowest at rest by 2 mV. */
    private fun pack(i: Int, load: Boolean, k: Int) = when {
        load -> if (i == 11) 3.173 else 3.200 + (i % 5 - 2) * 0.003 + (k % 3) * 0.0005
        else -> if (i == 8) 3.328 else 3.330 + (i % 4) * 0.001
    }

    @Test fun weakCellUnderLoadIsNotHiddenByRestReadings() {
        val t = CellTracker()
        repeat(40) { k -> t.update(List(23) { pack(it, true, k) }, -25.0, 10.0, 70) }
        repeat(400) { k -> t.update(List(23) { pack(it, false, k) }, 0.0, 10.0, 70) }   // parked most of the time
        val r = t.report(30)
        assertEquals(12, r.loadCell); assertEquals(100, r.load.first().lowestPct)
        assertEquals(null, r.restCell)                                                 // 2 mV means nothing
        assertEquals(9, r.rest.first().cell)
        assertEquals(12, CellTracker.fromJson(t.toJson()).report(30).loadCell)     // save / restore
    }

    @Test fun severeAndRestOutliers() {
        val t = CellTracker()
        repeat(30) { k ->
            t.update(List(23) { if (it == 11) 3.050 else 3.200 + (it + k) % 3 * 0.002 }, -28.0, 10.0, 60)
            t.update(List(23) { if (it == 4) 3.300 else 3.330 + (it + k) % 3 * 0.001 }, 0.0, 10.0, 60)
        }
        val r = t.report(30)
        assertEquals(12, r.loadCell); assertEquals(5, r.restCell)
    }

    @Test fun chargingAndFullBatteryAreLeftOut() {
        val t = CellTracker()
        repeat(60) {
            t.update(List(23) { if (it == 11) 3.45 else 3.55 }, 12.0, 10.0, 99)         // charging is no load
            t.update(List(23) { if (it == 11) 3.40 else 3.48 }, 0.0, 10.0, 100)         // full: cells spread
        }
        assertEquals(CellTracker.Report(), t.report(30))
    }

    @Test fun balancedPackNothingStandsOut() {
        val t = CellTracker()
        repeat(60) { k ->
            t.update(List(23) { i -> 3.330 + ((i + k) % 3) * 0.002 }, 0.0, 10.0, 60)
            t.update(List(23) { i -> 3.200 + ((i + k) % 3) * 0.004 }, -20.0, 10.0, 60)
        }
        val r = t.report(30); assertEquals(null, r.loadCell); assertEquals(null, r.restCell)
    }

    @Test fun statsSavedBefore17() {
        // no separate counts yet: the averages alone still show the cell
        val old = "[" + (1..23).joinToString(",") { i -> "{\"r\":0.5,\"l\":${if (i == 12) -27 else (i % 5) - 1},\"rn\":400,\"ln\":90,\"lo\":${if (i == 9) 200 else 10},\"n\":500}" } + "]"
        val r = CellTracker.fromJson(old).report(30)
        assertEquals(12, r.loadCell); assertEquals(null, r.load.first().lowestPct)
    }

    @Test fun tripsDecideToo() {
        fun trip(c: Int) = Session(1, Session.TYPE_TRIP, 0, 0, "", JSONObject().put("min_cell_idx", c).put("ah", 1.0))
        assertEquals(12, CellTracker.fromTrips(List(30) { trip(12) })!!.cell)
        assertEquals(null, CellTracker.fromTrips(List(10) { trip(it % 3 + 1) })!!.cell)
        assertEquals(null, CellTracker.fromTrips(List(4) { trip(12) }))
    }

    @Test fun notEnoughData() = assertEquals(CellTracker.Report(), CellTracker().apply { update(listOf(3.3, 3.2), 0.0, 10.0, 50) }.report(30))

    private fun rep(load: Double? = null, rest: Double? = null) = CellTracker.Report(
        listOf(CellTracker.Row(12, load ?: -5.0, 60)), listOf(CellTracker.Row(9, rest ?: -2.0, 45)), load?.let { 12 }, rest?.let { 9 })
    private fun level(r: CellTracker.Report, t: CellTracker.TripCell? = null, uv: Int? = 2600) = CellVerdict.of(r, t, 150, 30, uv).level

    @Test fun howSerious() {
        val L = CellVerdict.Level.values().associateBy { it.name }
        assertEquals(L["UNKNOWN"], level(CellTracker.Report()))
        assertEquals(L["NORMAL"], level(rep()))
        assertEquals(L["ACCEPTABLE"], level(rep(load = -27.0)))
        assertEquals(L["CHECK"], level(rep(load = -60.0)))
        assertEquals(L["CHECK"], level(rep(rest = -20.0)))
        assertEquals(L["DANGER"], level(rep(load = -160.0))); assertEquals(L["DANGER"], level(rep(rest = -70.0)))
        // the user's battery: −27 mV on average, 227 mV once at 45 A, 3.04 V lowest → worth a check, not dangerous
        val mine = CellTracker.TripCell(12, 30, 30, 227, 44.8, 3.04)
        val v = CellVerdict.of(rep(load = -27.0), mine, 150, 30, 2600)
        assertEquals(L["CHECK"], v.level); assertEquals(12, v.cell)
        assertTrue(CellVerdict.Why.MARGIN_OK in v.why && CellVerdict.Why.REST_FINE in v.why && CellVerdict.Why.PEAK_OVER_ALARM in v.why)
        assertEquals(L["DANGER"], level(rep(), mine.copy(minV = 2.68)))
        assertEquals(L["ACCEPTABLE"], level(rep(), mine.copy(peakMv = 90), null))        // other battery, no limits known
    }

    @Test fun slightlyLowAtRest() {
        // cell 12 at −11 mV, the next at −2: below the limit (−12) but not "like the others"
        fun r(mv12: Double) = CellTracker.Report(listOf(CellTracker.Row(12, -27.0, 90)),
            listOf(CellTracker.Row(12, mv12, 60), CellTracker.Row(9, -2.0, 30)), 12, null)
        val v = CellVerdict.of(r(-11.0), null, 150, 30, 2600)
        assertEquals(CellVerdict.Level.ACCEPTABLE, v.level); assertEquals(-11.0, v.restSlightMv!!, 1e-9)
        assertTrue(CellVerdict.Why.REST_SLIGHT in v.why && CellVerdict.Why.REST_FINE !in v.why)
        assertTrue(CellVerdict.Why.REST_FINE in CellVerdict.of(r(-3.0), null, 150, 30, 2600).why)
    }

    @Test fun currentOfOldDifferencesIsNotShown() {
        fun trip(extra: String) = Session(1, Session.TYPE_TRIP, 0, 0, "",
            JSONObject("""{"min_cell_idx":12,"ah":1.0,"max_delta_mv":227,"current_at_max_delta":11.0$extra}"""))
        assertEquals(null, CellTracker.fromTrips(List(5) { trip("") })!!.peakA)
        assertEquals(11.0, CellTracker.fromTrips(List(5) { trip(",\"gap_s\":0") })!!.peakA!!, 1e-9)
        assertEquals("trip_delta_mv", Advisor.afterTrip(trip(""), BmsLimits(), null, null).last().code)
        assertEquals("trip_delta", Advisor.afterTrip(trip(",\"gap_s\":0"), BmsLimits(), null, null).last().code)
    }

    @Test fun csvAndGpx() {
        val s = Session(7, Session.TYPE_TRIP, 0, 600_000, "",
            JSONObject("""{"soc_start":95,"soc_end":88,"wh":142.0,"distance_km":4.55,"wh_per_km":31.3}"""))
        val csv = Exporter.csv(listOf(s)).lines()
        assertTrue(csv[0].startsWith("id,type,start,end,duration_min,soc_start"))
        assertTrue(csv[1].startsWith("7,trip,"))
        assertTrue(csv[1].contains(",10.0,95,88,"))
        val pts = listOf(Point(0, 80.0, -10.0, 95, 30.0, 33.5731, -7.5898), Point(2000, 80.0, -10.0, 95, null, null, null))
        val gpx = Exporter.gpx(listOf(s to pts))
        assertTrue(gpx.contains("""<trkpt lat="33.573100" lon="-7.589800">"""))
        assertEquals(1, Regex("<trkpt").findAll(gpx).count())          // the point without GPS is skipped
        assertTrue(gpx.contains("<speed>8.33</speed>"))
    }
}
