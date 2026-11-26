// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import com.vanaccess.bms.sync.SyncCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripLearnTest {
    private fun trip(id: Long, km: Double, wh: Double, regen: Double? = null, partial: Boolean = false) = Session(id, Session.TYPE_TRIP, id * 1000, id * 1000 + 600_000, "66:6A",
        JSONObject().put("distance_km", km).put("wh", wh).put("max_speed", 50).put("avg_speed", 25).put("gap_s", 0)
            .also { o -> regen?.let { o.put("regen_wh", it) }; if (partial) o.put("energy_partial", true) })

    @Test fun learnsFromCompleteTripsOnly() {
        val l = listOf(trip(3, 2.0, 40.0), trip(2, 1.0, 999.0, partial = true), trip(1, 1.0, 20.0))
        val c = TripLearn.consumption(l)!!
        assertEquals(20.0, c.first, 1e-9); assertEquals(3.0, c.second, 1e-9)
        assertNull(TripLearn.consumption(listOf(trip(1, 1.0, 20.0))))
    }

    @Test fun regenNeedsThreeTripsAndFiveKm() {
        val l = listOf(trip(4, 2.0, 40.0, 2.0), trip(3, 2.0, 40.0, 2.0), trip(2, 2.0, 40.0, 2.0), trip(1, 2.0, 40.0, 2.0))
        val r = TripLearn.regen(l, except = 4)!!
        assertEquals(1.0, r.first, 1e-9); assertEquals(6.0, r.third, 1e-9)
        assertNull(TripLearn.regen(l.take(2)))
    }

    @Test fun adviceSaysSomething() {
        val s = trip(9, 0.82, 19.0).also { it.data.put("wh_per_km", 23.5).put("soc_end", 48).put("max_v", 77.0).put("min_v", 75.0) }
        val recs = TripLearn.advice(s, BmsLimits(designAh = 29.9), listOf(s), listOf(s))
        assertTrue(recs.isNotEmpty())
        assertNotNull(TripLearn.rangeAfter(s, BmsLimits(designAh = 29.9)))
    }

    @Test fun liveReadingCrossesTheNetworkWhole() {
        val b = com.vanaccess.bms.ble.BmsBasicInfo(75.69, -1.5, 14.6, 29.9, 4, 48, 23, listOf(17.0, 18.0), true, false, 2, 5L, "1.0", "2025", ByteArray(0),
            listOf("x"), 19.0, 97, "bwbm", listOf(19.0, 19.5), null)
        val back = SyncCodec.parseLive(org.json.JSONObject(SyncCodec.liveJson(SyncCodec.Live(42, "BWBM", "66:6A", true, b, listOf(3.29, 3.30))).toString()))
        assertEquals(b.copy(rawData = back.basic!!.rawData), back.basic)
        assertEquals(listOf(3.29, 3.30), back.cells); assertEquals(42L, back.tMs)
    }
}
