package com.vanaccess.bms.reports

import com.vanaccess.bms.ble.drivers.ParamItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Limits of the user's BWBM-631M-23S40A as read from its 0x19 parameters. */
class AdvisorTest {
    private val lim = BmsLimits(cellOvMv = 3630, cellUvMv = 2600, cellUvReleaseMv = 2900, socLowCalMv = 3050,
        balanceStartMv = 3300, balanceDiffMv = 20, chargeOtC = 65, dischargeOtC = 65, chargeLowC = 0,
        mosOtC = 80, dischargeOcA = 50, designAh = 30.0, cells = 23)

    private fun live(cells: List<Double>, current: Double, soc: Int = 60, rest: Double = 0.0,
                     temps: List<Double> = listOf(20.0)) =
        Advisor.Live(soc, current, cells, temps, listOf(22.0), rest, 5.0, 30, 150, false, 82.8)

    private fun codes(r: List<Advisor.Rec>) = r.map { it.code }

    @Test fun fullChargeNearProtectionIsInfoNotDanger() {
        val cells = List(22) { 3.600 } + 3.625
        val r = Advisor.live(live(cells, 4.0, soc = 99), lim)
        assertTrue("cell_near_ov" in codes(r))
        assertFalse(r.any { it.level == Advisor.Level.CRITICAL })   // a normal LiFePO4 full charge
    }

    @Test fun restDeltaOnlyMidRange() {
        val cells = List(22) { 3.330 } + 3.295                    // 35 mV
        assertTrue("delta_rest" in codes(Advisor.live(live(cells, 0.0, soc = 50, rest = 200.0), lim)))
        assertFalse("delta_rest" in codes(Advisor.live(live(cells, 0.0, soc = 95, rest = 200.0), lim)))
        assertFalse("delta_rest" in codes(Advisor.live(live(cells, 0.0, soc = 50, rest = 30.0), lim)))
    }

    @Test fun loadDeltaIsCritical() {
        val cells = List(22) { 3.250 } + 3.090                    // 160 mV
        val r = Advisor.live(live(cells, -20.0), lim)
        assertEquals("delta_load", r.first().code)
        assertEquals(Advisor.Level.CRITICAL, r.first().level)
    }

    @Test fun emptyCell() {
        val cells = List(22) { 2.95 } + 2.88
        assertTrue("cell_empty" in codes(Advisor.live(live(cells, -2.0, soc = 3), lim)))
    }

    @Test fun temperaturesUseBmsLimits() {
        val cells = List(23) { 3.35 }
        assertTrue("all_ok" in codes(Advisor.live(live(cells, 5.0, temps = listOf(52.0)), lim)))
        val warn = Advisor.live(live(cells, 5.0, temps = listOf(54.0)), lim).first { it.code == "temp_charge" }
        assertEquals(Advisor.Level.WARNING, warn.level)
        val crit = Advisor.live(live(cells, 5.0, temps = listOf(61.0)), lim).first { it.code == "temp_charge" }
        assertEquals(Advisor.Level.CRITICAL, crit.level)
        assertTrue("cold_charge" in codes(Advisor.live(live(cells, 5.0, temps = listOf(2.0)), lim)))
    }

    private fun charge(json: String, start: Long = 0, end: Long = 3_600_000) =
        Session(1, Session.TYPE_CHARGE, start, end, "", JSONObject(json))

    @Test fun afterChargeAdvice() {
        val notFull = charge("""{"soc_start":40,"soc_end":85,"avg_current":3.9,"ah":13.5,"max_delta_mv":15}""")
        assertTrue("balance_needed" in codes(Advisor.afterCharge(notFull, lim, 9, 82.8)))
        assertFalse("balance_needed" in codes(Advisor.afterCharge(notFull, lim, 3, 82.8)))
        val prot = charge("""{"soc_start":60,"soc_end":100,"avg_current":4,"ah":12,"ended_by_protection":true,"max_delta_mv":20}""")
        assertTrue("charger_voltage" in codes(Advisor.afterCharge(prot, lim, null, 82.8)))
    }

    @Test fun measuredHealthOnlyFromFullCycle() {
        val full = charge("""{"start_min_cell":3.04,"reached_full":true,"ah":29.1}""")
        val partial = charge("""{"start_min_cell":3.30,"reached_full":true,"ah":12.0}""")
        val h = Advisor.health(30.0, lim, listOf(partial, full))
        assertEquals(29.1, h.measuredAh!!, 1e-9)
        assertEquals(97, h.measuredSohPct)
        assertTrue(h.bmsReportsDesign)                            // BMS reports 30.0 = design
        assertEquals(null, Advisor.health(30.0, lim, listOf(partial)).measuredAh)
    }

    @Test fun limitsFromParamsAndJson() {
        val p = listOf(
            ParamItem("v", "Cell overvoltage protection (level 1)", "3630 mV"),
            ParamItem("v", "Cell undervoltage release", "2900 mV"),
            ParamItem("t", "Charge over-temperature protection", "65 °C"),
            ParamItem("t", "Charge low-temperature protection", "-5 °C"),
            ParamItem("p", "Design capacity", "30.0 Ah"),
            ParamItem("p", "Cell count", "23"),
        )
        val l = BmsLimits.fromParams(p)
        assertEquals(3630, l.cellOvMv); assertEquals(2900, l.cellUvReleaseMv)
        assertEquals(65, l.chargeOtC); assertEquals(-5, l.chargeLowC)
        assertEquals(30.0, l.designAh!!, 1e-9); assertEquals(23, l.cells)
        assertTrue(l.lfp)
        assertEquals(l, BmsLimits.fromJson(l.toJson()))
    }

    @Test fun cyclesLeftEstimate() {
        assertEquals(2998 to 3000, Advisor.cyclesLeft(2, 0, lim))            // LiFePO4 default
        assertEquals(1498 to 1500, Advisor.cyclesLeft(2, 1500, lim))         // datasheet value wins
        assertEquals(0 to 800, Advisor.cyclesLeft(900, 0, BmsLimits(cellOvMv = 4250)))
        assertEquals(null, Advisor.cyclesLeft(2, 0, BmsLimits()))            // unknown chemistry
    }
}
