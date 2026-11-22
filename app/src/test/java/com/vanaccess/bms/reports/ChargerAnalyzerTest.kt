package com.vanaccess.bms.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargerAnalyzerTest {
    private val lim = BmsLimits(cellOvMv = 3630, chargeOtC = 65, designAh = 30.0, cells = 23, chargeOcA = 50)

    private fun pts(vararg seg: Pair<Int, (Int) -> Pair<Double, Double>>): List<Point> {
        val out = mutableListOf<Point>(); var t = 0L
        seg.forEach { (n, f) -> repeat(n) { k -> val (v, i) = f(k); out += Point(t, v, i, 50, null, null, null); t += 2000 } }
        return out
    }
    private fun codes(r: ChargerAnalyzer.Result) = r.recs.map { it.code }

    /** The user's case: ~4 A, voltage climbs until the BMS cuts at its 3.63 V/cell limit. */
    @Test fun endedByProtection() {
        val p = pts(200 to { k -> (78.0 + k * 0.027) to 4.0 })
        val r = ChargerAnalyzer.analyze(p, lim, endedByProtection = true, socEnd = 100)!!
        assertEquals(ChargerAnalyzer.EndedBy.PROTECTION, r.endedBy)
        assertEquals(4.0, r.currentA, 1e-9)
        assertTrue("charger_above_protection" in codes(r))
        assertTrue("charger_current_ok" in codes(r))          // 0.13C
    }

    /** A correct 82.8 V charger: CC 5 A, then CV with the current tapering to a trickle. */
    @Test fun goodChargerWithTaper() {
        val p = pts(150 to { k -> (78.0 + k * 0.032) to 5.0 }, 100 to { k -> 82.8 to (5.0 - k * 0.048) })
        val r = ChargerAnalyzer.analyze(p, lim, endedByProtection = false, socEnd = 100)!!
        assertEquals(ChargerAnalyzer.EndedBy.CHARGER, r.endedBy)
        assertEquals(82.8, r.cvVoltage!!, 0.01)
        assertTrue("charger_voltage_ok" in codes(r))
    }

    /** A Li-ion charger (4.1 V/cell = 94.3 V) on a LiFePO4 pack must be flagged. */
    @Test fun wrongChemistryCharger() {
        val p = pts(100 to { k -> (85.0 + k * 0.09) to 3.0 }, 60 to { k -> 94.3 to (3.0 - k * 0.045) })
        val r = ChargerAnalyzer.analyze(p, lim, endedByProtection = false, socEnd = 100)!!
        assertEquals(Advisor.Level.CRITICAL, r.recs.first { it.code == "charger_wrong_type" }.level)
    }

    @Test fun tooFewSamples() {
        assertNull(ChargerAnalyzer.analyze(pts(5 to { _ -> 80.0 to 4.0 }), lim, false, 50))
    }
}
