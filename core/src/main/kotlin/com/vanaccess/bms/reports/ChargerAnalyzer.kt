// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports


/**
 * Infers what the charger does from one charge's recorded curve (V and I every ~2 s) and
 * judges it against this battery's own limits.
 *
 * A lithium charger works in two phases: constant current (CC) — current flat, voltage
 * rising — then constant voltage (CV) — voltage held, current tapering. From the curve:
 *  - charger current  = typical current of the CC phase;
 *  - charger voltage  = pack voltage while the current tapers (only if a CV phase exists);
 *  - what ended it    = the charger (taper to a trickle), the BMS protection (abrupt cut at
 *    the protection limit — the charger's own voltage is then *higher* than the BMS limit),
 *    or an interruption (unplugged while still charging hard).
 *
 * Pure logic, unit-tested.
 */
object ChargerAnalyzer {

    enum class EndedBy { CHARGER, PROTECTION, INTERRUPTED, UNKNOWN }

    data class Result(
        val currentA: Double,          // CC-phase current
        val cvVoltage: Double?,        // pack voltage during the taper, if one was seen
        val maxVoltage: Double,
        val endedBy: EndedBy,
        val recs: List<Advisor.Rec>,
    )

    /**
     * @param endedByProtection the session flag set by the recorder (charge FET opened at the
     *        cell OV limit).
     */
    fun analyze(points: List<Point>, lim: BmsLimits, endedByProtection: Boolean, socEnd: Int): Result? {
        val chg = points.filter { it.i > 0.3 }
        if (chg.size < 10) return null
        val maxI = chg.maxOf { it.i }
        val maxV = chg.maxOf { it.v }
        // CC phase: samples near the peak current
        val cc = chg.filter { it.i >= maxI * 0.85 }.map { it.i }.sorted()
        val currentA = cc[cc.size / 2]
        // CV phase: voltage within 0.3 V of the top while current has dropped below 70 % of CC
        val cv = chg.filter { it.v >= maxV - 0.3 && it.i <= currentA * 0.7 }
        val cvVoltage = if (cv.size >= 5) cv.map { it.v }.sorted()[cv.size / 2] else null
        val last = chg.takeLast(3).map { it.i }.average()
        val endedBy = when {
            endedByProtection -> EndedBy.PROTECTION
            cvVoltage != null && last <= currentA * 0.25 -> EndedBy.CHARGER
            last >= currentA * 0.6 && socEnd < 98 -> EndedBy.INTERRUPTED
            else -> EndedBy.UNKNOWN
        }
        return Result(currentA, cvVoltage, maxV, endedBy, judge(currentA, cvVoltage, maxV, endedBy, lim))
    }

    private fun judge(currentA: Double, cvV: Double?, maxV: Double, endedBy: EndedBy, lim: BmsLimits): List<Advisor.Rec> {
        val out = mutableListOf<Advisor.Rec>()
        out += if (cvV != null) Advisor.Rec(Advisor.Level.INFO, "charger_seen", listOf(currentA, cvV))
               else Advisor.Rec(Advisor.Level.INFO, "charger_seen_nocv", listOf(currentA, maxV))
        val cells = lim.cells
        val ovMv = lim.cellOvMv
        if (cells != null && cells > 0 && ovMv != null) {
            val advice = ChargeAdvice.from(cells, ovMv)
            val protV = cells * ovMv / 1000.0
            val perCell = (cvV ?: maxV) / cells
            when {
                // A Li-ion (NMC) charger on a LiFePO4 pack — the per-cell target is far too high
                lim.lfp && perCell >= 3.9 -> out += Advisor.Rec(Advisor.Level.CRITICAL, "charger_wrong_type", listOf(perCell))
                endedBy == EndedBy.PROTECTION ->
                    out += Advisor.Rec(Advisor.Level.WARNING, "charger_above_protection", listOf(protV, advice?.chargerPackV ?: protV - 0.7))
                cvV != null && cvV >= protV - 0.05 ->
                    out += Advisor.Rec(Advisor.Level.WARNING, "charger_above_protection", listOf(protV, advice?.chargerPackV ?: protV - 0.7))
                cvV != null && lim.lfp && perCell < 3.45 ->
                    out += Advisor.Rec(Advisor.Level.WARNING, "charger_too_low", listOf(cvV, perCell))
                cvV != null -> out += Advisor.Rec(Advisor.Level.OK, "charger_voltage_ok", listOf(cvV, perCell))
            }
        }
        lim.designAh?.takeIf { it > 0 }?.let { cap ->
            val c = currentA / cap
            val oc = lim.chargeOcA
            when {
                oc != null && currentA >= oc * 0.9 -> out += Advisor.Rec(Advisor.Level.CRITICAL, "charger_current_limit", listOf(currentA, oc))
                c > 1.0 -> out += Advisor.Rec(Advisor.Level.WARNING, "charger_current_high", listOf(currentA, c))
                c > 0.5 -> out += Advisor.Rec(Advisor.Level.INFO, "charger_current_fast", listOf(currentA, c))
                c < 0.05 -> out += Advisor.Rec(Advisor.Level.INFO, "charger_current_slow", listOf(currentA, c))
                else -> out += Advisor.Rec(Advisor.Level.OK, "charger_current_ok", listOf(currentA, c))
            }
        }
        if (endedBy == EndedBy.INTERRUPTED) out += Advisor.Rec(Advisor.Level.INFO, "charger_interrupted")
        return out
    }

    /** Combine several charges: typical current and voltage of "your charger". */
    fun profile(results: List<Result>): Pair<Double, Double?>? {
        if (results.isEmpty()) return null
        val cur = results.map { it.currentA }.sorted()[results.size / 2]
        val volts = results.mapNotNull { it.cvVoltage }.sorted()
        return cur to volts.getOrNull(volts.size / 2)
    }

}
