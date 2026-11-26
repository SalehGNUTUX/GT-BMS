// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import kotlin.math.abs

/**
 * Recommendations engine. Pure logic (no Android types) so it is unit-tested; the UI turns
 * each [Rec.code] into localised text (see ui/AdviceText).
 *
 * Principle: thresholds come from the battery's own BMS limits ([BmsLimits]) whenever
 * they are known, never from generic numbers that may not match the pack. For example a
 * LiFePO₄ cell normally ends a full charge at 3.60–3.63 V, so "cell > 3.60 V" is not a
 * danger sign; "within 20 mV of *this* BMS's protection" is a useful heads-up.
 */
object Advisor {

    enum class Level { CRITICAL, WARNING, INFO, OK }

    data class Rec(val level: Level, val code: String, val args: List<Any> = emptyList())

    // Generic fallbacks used only when the BMS does not report a limit.
    private const val DEF_CHARGE_OT = 55
    private const val DEF_DISCHARGE_OT = 60
    private const val DEF_MOS_OT = 80
    private const val DEF_CHARGE_LOW = 0

    data class Live(
        val soc: Int,
        val current: Double,
        val cells: List<Double>,
        val cellTemps: List<Double>,
        val mosTemps: List<Double>,
        val restSeconds: Double,          // how long |I| < 1 A
        val loadCurrentA: Double,
        val restDeltaMv: Int,
        val loadDeltaMv: Int,
        val lastChargeEndedByProtection: Boolean,
        val chargerAdviceV: Double?,
    )

    fun live(x: Live, lim: BmsLimits): List<Rec> {
        val out = mutableListOf<Rec>()
        val charging = x.current > 0.3
        val iAbs = abs(x.current)
        if (x.cells.size >= 2) {
            val mx = x.cells.max(); val mn = x.cells.min()
            val iMx = x.cells.indexOf(mx) + 1; val iMn = x.cells.indexOf(mn) + 1
            val delta = ((mx - mn) * 1000).toInt()
            val mxMv = (mx * 1000).toInt(); val mnMv = (mn * 1000).toInt()

            // Empty: at/below the BMS under-voltage release point
            val critLow = lim.cellUvReleaseMv ?: if (lim.lfp) 2900 else null
            if (!charging && critLow != null && mnMv <= critLow) {
                out += Rec(Level.CRITICAL, "cell_empty", listOf(iMn, mn, x.soc))
            } else if (!charging && (x.soc <= 10 || (lim.socLowCalMv != null && mnMv <= lim.socLowCalMv))) {
                out += Rec(Level.WARNING, "battery_low", listOf(x.soc))
            }
            // Near the BMS over-voltage protection while charging: a heads-up, not a danger
            lim.cellOvMv?.let { ov ->
                if (charging && mxMv >= ov - 20) out += Rec(Level.INFO, "cell_near_ov", listOf(iMx, mx, ov / 1000.0))
            }
            if (iAbs >= x.loadCurrentA && delta >= x.loadDeltaMv) {
                // Serious only when the lowest cell is close to this BMS's protection; else worth a check
                val thin = lim.cellUvMv != null && mnMv <= lim.cellUvMv + 100
                out += Rec(if (thin) Level.CRITICAL else Level.WARNING, "delta_load", listOf(delta, iAbs, iMn))
            } else if (x.restSeconds >= 120 && x.soc in 20..90 && delta >= x.restDeltaMv) {
                out += Rec(Level.WARNING, "delta_rest", listOf(delta, iMn, lim.balanceStartV))
            }
        }

        // Temperatures against the BMS's own protection points
        val tMax = x.cellTemps.maxOrNull()
        if (tMax != null) {
            val limit = if (charging) lim.chargeOtC ?: DEF_CHARGE_OT else lim.dischargeOtC ?: DEF_DISCHARGE_OT
            val code = if (charging) "temp_charge" else "temp_discharge"
            when {
                tMax >= limit - 5 -> out += Rec(Level.CRITICAL, code, listOf(tMax, limit))
                tMax >= limit - 12 -> out += Rec(Level.WARNING, code, listOf(tMax, limit))
            }
        }
        val tMin = x.cellTemps.minOrNull()
        if (charging && tMin != null && tMin <= (lim.chargeLowC ?: DEF_CHARGE_LOW) + 3) {
            out += Rec(Level.WARNING, "cold_charge", listOf(tMin, lim.chargeLowC ?: DEF_CHARGE_LOW))
        }
        x.mosTemps.maxOrNull()?.let { m ->
            val limit = lim.mosOtC ?: DEF_MOS_OT
            when {
                m >= limit - 5 -> out += Rec(Level.CRITICAL, "temp_mos", listOf(m, limit))
                m >= limit - 12 -> out += Rec(Level.WARNING, "temp_mos", listOf(m, limit))
            }
        }

        if (x.lastChargeEndedByProtection && x.chargerAdviceV != null) {
            out += Rec(Level.INFO, "charger_voltage", listOf(x.chargerAdviceV))
        }
        if (out.none { it.level == Level.CRITICAL || it.level == Level.WARNING }) out += Rec(Level.OK, "all_ok")
        return out.sortedBy { it.level.ordinal }
    }

    /** Advice after a finished charge. [daysSinceFull] = days since the last charge that reached 100 %. */
    fun afterCharge(s: Session, lim: BmsLimits, daysSinceFull: Int?, chargerAdviceV: Double?): List<Rec> {
        val out = mutableListOf<Rec>()
        val hours = (s.endMs - s.startMs) / 3_600_000.0
        out += Rec(Level.INFO, "charge_summary", listOf(s.i("soc_start"), s.i("soc_end"), hours, s.avgA, s.d("ah")))
        if (s.b("ended_by_protection") && chargerAdviceV != null) out += Rec(Level.INFO, "charger_voltage", listOf(chargerAdviceV))
        val full = s.b("reached_full") || s.b("ended_by_protection")
        if (!full && daysSinceFull != null && daysSinceFull >= 7) {
            out += Rec(Level.WARNING, "balance_needed", listOf(daysSinceFull, lim.balanceStartV))
        }
        if (s.i("max_delta_mv") >= 50) out += Rec(Level.WARNING, "charge_delta", listOf(s.i("max_delta_mv")))
        val ot = lim.chargeOtC ?: DEF_CHARGE_OT
        if (!s.data.isNull("max_temp") && s.d("max_temp") >= ot - 15) out += Rec(Level.WARNING, "charge_hot", listOf(s.d("max_temp"), ot))
        lim.designAh?.takeIf { it > 0 }?.let { cap ->
            val c = s.avgA / cap
            if (c > 0.5) out += Rec(Level.INFO, "fast_charge", listOf(c))
        }
        return out
    }

    /**
     * Advice after a trip. [avgWhKm] = learned average before this trip; [rangeKm] = what
     * the remaining charge still covers at this trip's consumption.
     */
    fun afterTrip(s: Session, lim: BmsLimits, avgWhKm: Double?, rangeKm: Double?, regenAvg: Triple<Double, Double, Double>? = null): List<Rec> {
        val out = mutableListOf<Rec>()
        val km = s.d("distance_km")
        if (km >= 0.1 && !s.data.isNull("wh_per_km")) {
            val whKm = s.d("wh_per_km")
            out += Rec(Level.INFO, "trip_summary", listOf(km, whKm, s.d("ah_per_km"), s.d("avg_speed")))
            if (avgWhKm != null && avgWhKm > 0) {
                val diff = (whKm - avgWhKm) / avgWhKm * 100
                if (diff >= 15) out += Rec(Level.INFO, "trip_above_avg", listOf(diff, avgWhKm))
                else if (diff <= -15) out += Rec(Level.OK, "trip_below_avg", listOf(-diff, avgWhKm))
            }
        } else if (km >= 0.1) {
            out += Rec(Level.INFO, "trip_summary_partial", listOf(km, s.d("wh"), s.d("ah")))
        } else {
            out += Rec(Level.INFO, "trip_summary_nogps", listOf(s.d("wh"), s.d("ah")))
        }
        RegenMeter.sharePct(s.d("regen_wh"), s.d("wh"))?.takeIf { s.d("regen_wh") >= 1 }?.let { pct ->
            out += Rec(Level.OK, "regen", listOf(s.d("regen_wh"), pct))
            // Against this vehicle's own habit
            regenAvg?.let { (_, avgPct, _) ->
                if (avgPct > 0.2 && pct >= avgPct * 1.5) out += Rec(Level.OK, "regen_above", listOf(pct, avgPct))
                else if (avgPct > 0.2 && pct <= avgPct * 0.5) out += Rec(Level.INFO, "regen_below", listOf(pct, avgPct))
            }
            // Recuperation charges the battery: the charge protections apply to it too
            lim.chargeOcA?.takeIf { it > 0 }?.let { oc ->
                if (s.d("regen_max_a") >= oc * 0.8) out += Rec(Level.WARNING, "regen_current", listOf(s.d("regen_max_a"), oc)) }
            lim.cellOvMv?.let { ov ->
                if (s.d("regen_max_cell") >= ov / 1000.0 - 0.05) out += Rec(Level.WARNING, "regen_full", listOf(s.d("regen_max_cell"), ov / 1000.0)) }
        }
        // A vehicle that hardly recovers anything over many km: say it once it is known
        if (s.d("regen_wh") < 1 && regenAvg != null && regenAvg.second < 0.5 && regenAvg.third >= 20)
            out += Rec(Level.INFO, "regen_weak", listOf(regenAvg.third, regenAvg.second))
        if (s.d("coast_km") >= 0.2) out += Rec(Level.OK, "coast", listOf(s.d("coast_km")))
        rangeKm?.takeIf { it > 0 }?.let { out += Rec(Level.INFO, "trip_range", listOf(it)) }
        lim.dischargeOcA?.takeIf { it > 0 }?.let { oc ->
            if (s.d("max_current") >= oc * 0.8) out += Rec(Level.WARNING, "trip_peak_current", listOf(s.d("max_current"), oc))
        }
        if (s.i("max_delta_mv") >= 100) out += s.deltaA?.let { Rec(Level.WARNING, "trip_delta", listOf(s.i("max_delta_mv"), it)) } ?: Rec(Level.WARNING, "trip_delta_mv", listOf(s.i("max_delta_mv")))
        return out
    }

    /** Advice after a stationary discharge (no trip tracking). */
    fun afterDischarge(s: Session, lim: BmsLimits): List<Rec> {
        val out = mutableListOf<Rec>()
        val hours = (s.endMs - s.startMs) / 3_600_000.0
        out += Rec(Level.INFO, "discharge_summary", listOf(s.d("ah"), s.d("wh"), s.i("soc_start"), s.i("soc_end"), hours, s.d("avg_power")))
        lim.dischargeOcA?.takeIf { it > 0 }?.let { oc ->
            if (s.d("max_current") >= oc * 0.8) out += Rec(Level.WARNING, "trip_peak_current", listOf(s.d("max_current"), oc))
        }
        if (s.i("max_delta_mv") >= 100) out += s.deltaA?.let { Rec(Level.WARNING, "trip_delta", listOf(s.i("max_delta_mv"), it)) } ?: Rec(Level.WARNING, "trip_delta_mv", listOf(s.i("max_delta_mv")))
        if (s.i("soc_end") <= 10) out += Rec(Level.WARNING, "battery_low", listOf(s.i("soc_end")))
        return out
    }

    /** Weekly summary numbers; [tip] picks chemistry-appropriate storage advice. */
    data class Week(val charges: Int, val trips: Int, val km: Double, val whOut: Double, val cycles: Int?, val sohPct: Int?)

    fun weekly(w: Week, lim: BmsLimits): List<Rec> = listOfNotNull(
        Rec(Level.INFO, "week_summary", listOf(w.charges, w.trips, w.km, w.whOut)),
        w.cycles?.let { Rec(Level.INFO, "week_cycles", listOf(it)) },
        w.sohPct?.let { Rec(Level.INFO, "week_soh", listOf(it)) },
        if (lim.lfp) Rec(Level.INFO, "tip_lfp") else if (lim.lion) Rec(Level.INFO, "tip_liion") else null,
    )

    /**
     * Battery health. BMS-reported = full / design capacity (many BMS just report the design
     * value, so 100 % may mean "not learned"). Measured = Ah charged in a charge that began
     * at the BMS's empty-calibration voltage and ended full — the only cycle where our own
     * counting is independent of the BMS's SOC.
     */
    /**
     * Remaining charge cycles — an estimate, not a measurement: rated cycle life (the user's
     * datasheet value, else a typical value for the chemistry) minus the cycles the BMS counted.
     * Returns (remaining, rated), or null when neither the rating nor the chemistry is known.
     */
    fun cyclesLeft(used: Int, userRated: Int, lim: BmsLimits): Pair<Int, Int>? {
        val rated = when {
            userRated > 0 -> userRated
            lim.lfp -> 3000
            lim.lion -> 800
            else -> return null
        }
        return (rated - used).coerceAtLeast(0) to rated
    }

    data class Health(val bmsSohPct: Int?, val bmsReportsDesign: Boolean, val measuredAh: Double?, val measuredSohPct: Int?)

    fun health(fullAh: Double?, lim: BmsLimits, charges: List<Session>): Health {
        val design = lim.designAh?.takeIf { it > 0 }
        val bmsSoh = if (design != null && fullAh != null && fullAh > 0) (fullAh / design * 100).toInt() else null
        val reportsDesign = design != null && fullAh != null && abs(fullAh - design) < 0.05
        val lowCal = lim.socLowCalMv ?: if (lim.lfp) 3050 else null
        val qualifying = charges.firstOrNull { s ->
            val startMin = s.d("start_min_cell", 0.0)
            lowCal != null && startMin > 0 && startMin * 1000 <= lowCal + 50 &&
                (s.b("reached_full") || s.b("ended_by_protection"))
        }
        val measured = qualifying?.d("ah")
        return Health(bmsSoh, reportsDesign, measured,
            if (measured != null && design != null) (measured / design * 100).toInt() else null)
    }
}
