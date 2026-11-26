// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

/** What past charges of this battery teach about the next one. Pure, unit-tested. */
data class ChargeLearn(
    /** This charger keeps its current until the BMS cuts it (null = no finished charge yet). */
    val cutByProtection: Boolean? = null,
    /** Highest cell about 5 minutes before past cuts, V. */
    val earlyCellV: Double? = null,
    /** Ah still missing to "full" by our count when past charges were cut: they end that much earlier. */
    val leftAh: Double? = null,
) {
    /** Cell voltage for the early alarm, always below the protection. */
    fun alarmCellV(ovV: Double): Double {
        val lfp = BatteryModel.chemistryOf(Math.round(ovV * 1000).toInt()) == BatteryModel.LFP
        val lowest = if (lfp) minOf(3.40, ovV - 0.05) else ovV - 0.15
        return (earlyCellV ?: if (lfp) 3.45 else ovV - 0.05).coerceIn(lowest, ovV - 0.02)
    }

    companion object {
        /**
         * @param charges newest first
         * @param points recorded curve of a charge (for reports saved before cells were learned)
         */
        fun from(charges: List<Session>, cells: Int, points: (Session) -> List<Point>): ChargeLearn {
            val done = charges.filter { finished(it) }.take(3)
            if (done.isEmpty()) return ChargeLearn()
            val cuts = done.filter { it.b("ended_by_protection") }
            val early = cuts.mapNotNull { s ->
                if (s.d("stop_early_cell") > 0) s.d("stop_early_cell") else if (cells > 0) packBefore(points(s))?.let { it / cells } else null
            }.sorted()
            val left = charges.filter { it.data.has("cut_left_ah") }.take(5).map { it.d("cut_left_ah") }.sorted()
            return ChargeLearn(cuts.size * 2 > done.size, early.getOrNull(early.size / 2), left.getOrNull(left.size / 2))
        }

        /** Charges that say something about the charger: ended by it or by the protection. */
        private fun finished(s: Session) = s.b("ended_by_protection") || s.b("reached_full")

        /** Pack voltage 5 minutes before the last charging sample. */
        fun packBefore(pts: List<Point>): Double? {
            val chg = pts.filter { it.i > 0.3 && it.v > 0 }
            val end = chg.lastOrNull()?.tMs ?: return null
            val p = chg.lastOrNull { end - it.tMs >= ChargeWatch.EARLY_MS } ?: return null
            return p.v.takeIf { end - p.tMs <= ChargeWatch.EARLY_MS + 60_000 }
        }
    }
}

/** Time left of a charge, from the best source available at the moment. */
object ChargePredictor {
    enum class Kind { CUT_SOON, CUT, FULL }
    /** @param hours null for CUT_SOON below the resolution of the estimate */
    data class P(val kind: Kind, val hours: Double, val to80h: Double?, val cell: Int)

    /**
     * - last minute(s): the highest cell's rise against the protection;
     * - charger that is cut by the protection: constant current, counted from the charge put
     *   in since the start (the BMS counter lags and then jumps), no taper;
     * - otherwise [ChargeTime] (taper above 90 %).
     */
    fun predict(soc: Int, fullAh: Double, current: Double, learn: ChargeLearn, watch: ChargeWatch?,
                remainAh: Double, ovV: Double): P? {
        if (current <= 0.3 || fullAh <= 0) return null
        val cutSec = watch?.secondsToCut(ovV)
        if (cutSec != null && cutSec <= 300) return P(Kind.CUT_SOON, cutSec / 3600, null, watch.nearestCell)
        if (learn.cutByProtection == true) {
            val amps = watch?.avgCurrent() ?: current
            val bmsMissing = (fullAh - remainAh).coerceAtLeast(0.0)
            val missing = watch?.let {
                // From the counter (0.1 Ah) rather than the whole percent, less what past cuts left out
                val needed = (if (it.remainStart > 0) fullAh - it.remainStart else fullAh * (100 - it.socStart) / 100.0) -
                    (learn.leftAh ?: 0.0).coerceIn(0.0, fullAh * 0.05)
                // readings lost on the way make our count too low: never above what the BMS still wants
                minOf(needed - it.ah, bmsMissing)
            } ?: bmsMissing
            val to80 = if (soc < 80) (80 - soc) / 100.0 * fullAh / amps else null
            return P(Kind.CUT, missing.coerceAtLeast(0.0) / amps, to80, watch?.nearestCell ?: 0)
        }
        val t = ChargeTime.estimate(soc, fullAh, current) ?: return null
        return P(Kind.FULL, t.to100h, t.to80h, 0)
    }
}

/** Figures over many charges and trips. Pure, unit-tested. */
data class ChargeStats(
    val total: Int, val byProtection: Int, val byCharger: Int, val early: Int,
    val avgDeltaMv: Int?, val cutCell: Int?, val cutCellTimes: Int,
    val weakCell: Int?, val weakCellTimes: Int, val trips: Int, val avgEtaErrorMin: Int?,
) {
    companion object {
        fun from(charges: List<Session>, trips: List<Session>): ChargeStats? {
            if (charges.isEmpty()) return null
            val cut = charges.filter { it.b("ended_by_protection") }
            val full = charges.filter { !it.b("ended_by_protection") && it.b("reached_full") }
            val deltas = cut.map { it.i("stop_delta_mv", it.i("max_delta_mv")) }.filter { it > 0 }
            fun mode(l: List<Int>) = l.filter { it > 0 }.groupingBy { it }.eachCount().maxByOrNull { it.value }
            val cutCell = mode(cut.map { it.i("stop_max_idx", it.i("max_cell_idx")) })
            val t = trips.filter { it.i("min_cell_idx") > 0 && it.d("max_current") >= 10 }
            val weak = mode(t.map { it.i("min_cell_idx") })
            val errs = charges.filter { it.data.has("eta_first_min") }
                .map { Math.round(it.d("eta_first_min") - (it.endMs - it.startMs) / 60000.0).toInt() }
            return ChargeStats(charges.size, cut.size, full.size, charges.size - cut.size - full.size,
                deltas.takeIf { it.isNotEmpty() }?.average()?.toInt(), cutCell?.key, cutCell?.value ?: 0,
                weak?.key, weak?.value ?: 0, t.size, errs.takeIf { it.isNotEmpty() }?.map { kotlin.math.abs(it) }?.average()?.toInt())
        }
    }
}
