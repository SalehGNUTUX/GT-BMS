package com.vanaccess.bms.reports

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CellTrackerExporterTest {

    /** Like the user's pack: cell 12 sags ~150 mV under load, ~15 mV at rest. */
    @Test fun weakCellUnderLoadIsFlagged() {
        val t = CellTracker()
        repeat(40) {
            t.update(List(23) { i -> if (i == 11) 3.315 else 3.330 }, 0.0, 10.0)      // rest
            t.update(List(23) { i -> if (i == 11) 3.050 else 3.200 }, -28.0, 10.0)    // load
        }
        val w = t.weakest()
        assertEquals(12, w.first().cell)
        assertTrue(w.first().concerning)
        assertTrue(w.first().loadMv!! < -100)
        // survives a save/restore
        assertEquals(12, CellTracker.fromJson(t.toJson()).weakest().first().cell)
    }

    @Test fun balancedPackNotConcerning() {
        val t = CellTracker()
        repeat(60) { k -> t.update(List(23) { i -> 3.330 + ((i + k) % 3) * 0.002 }, 0.0, 10.0) }
        assertFalse(t.weakest().any { it.concerning })
    }

    @Test fun notEnoughData() = assertTrue(CellTracker().apply { update(listOf(3.3, 3.2), 0.0, 10.0) }.weakest().isEmpty())

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
