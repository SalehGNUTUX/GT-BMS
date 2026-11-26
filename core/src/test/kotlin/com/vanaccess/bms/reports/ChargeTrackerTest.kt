package com.vanaccess.bms.reports

import com.vanaccess.bms.ble.drivers.BwbmDriver
import com.vanaccess.bms.ble.drivers.BwbmFrames
import com.vanaccess.bms.ble.drivers.DriverOutput
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeTrackerTest {
    /** The owner's pack (23S, 29.9 Ah, cut at 3.63 V) charged at 5.1 A from 69 %, read through the real parser. */
    @Test fun aChargeCutByTheProtection() {
        val sim = ChargeSim(List(23) { 3.322 + (it % 5) * 0.002 }, 29.9, 69, 5.1, 3.63, leader = 8)
        var saved: JSONObject? = null; var span = 0L to 0L
        val t = ChargeTracker(3.63) { s, e, d, _ -> saved = d; span = s to e }
        val drv = BwbmDriver()
        var now = 1_000_000L
        var sawCutSoon = false; var eta: Double? = null
        repeat(4 * 3600 / 5) {
            sim.step(5.0); now += 5_000
            val frame = BwbmFrames.realtime(sim.cells.map { Math.round(it * 1000).toInt() }, sim.current, sim.remainAh, 29.9,
                chargeFet = !sim.cut, overVoltage = sim.cut)
            var out = DriverOutput()
            BwbmFrames.notifications(frame).forEach { out = out.merge(drv.onNotification(it)) }
            t.onReading(now, out.basic!!, out.cells!!)
            if (t.live != null && eta == null && now > 1_300_000L) eta = t.predict(out.basic!!)?.hours
            if (t.predict(out.basic!!)?.kind == ChargePredictor.Kind.CUT_SOON) sawCutSoon = true
        }
        val d = assertNotNull(saved).let { saved!! }
        assertEquals(69, d.getInt("soc_start")); assertEquals(100, d.getInt("soc_end"))
        assertEquals("protection", d.getString("stop_reason")); assertEquals(9, d.getInt("stop_max_idx"))
        assertTrue(d.getBoolean("ended_by_protection"))
        assertEquals(5.1, d.getDouble("avg_current"), 0.1)
        assertEquals(29.9 * 0.31, d.getDouble("ah"), 0.6)
        assertTrue(sawCutSoon)
        // nothing learned yet: the general estimate (taper above 90 %) is on the long side, never short
        assertTrue(eta!! > 1.6 && eta!! < 2.6)
        assertTrue(span.second - span.first > 3600_000L)
    }

    @Test fun idleReadingsMakeNoCharge() {
        var saved = 0
        val t = ChargeTracker { _, _, _, _ -> saved++ }
        val info = BwbmDriver().let { d -> var o = DriverOutput(); BwbmFrames.notifications(BwbmFrames.realtime(List(23) { 3322 }, 0.0, 23.6, 29.9)).forEach { o = o.merge(d.onNotification(it)) }; o }
        repeat(600) { t.onReading(it * 1000L, info.basic!!, info.cells!!) }
        assertEquals(0, saved); assertEquals(null, t.live)
    }
}
