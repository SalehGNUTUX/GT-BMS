// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

/**
 * Remaining range for vehicle profiles.
 *
 *   usable energy (Wh) = (remaining Ah − reserve Ah) × nominal pack voltage
 *   range (km)         = usable energy ÷ consumption (Wh/km)
 *
 * - Nominal voltage (3.2 V/cell LiFePO₄, 3.7 V/cell Li-ion) instead of the resting voltage:
 *   under load the pack runs below its resting voltage, so resting × Ah overstates energy.
 * - Reserve: the last [RESERVE_PCT] of the full capacity is kept back (sag near empty, BMS
 *   cut-off, getting home).
 * - Consumption, in priority order: the user's own value (Settings), the average learned
 *   from trips once at least [MIN_LEARN_KM] km are recorded, else the vehicle-type default.
 */
object RangeEstimator {
    const val RESERVE_PCT = 10
    const val MIN_LEARN_KM = 5.0

    enum class Basis { USER, LEARNED, DEFAULT }

    data class Result(
        val remainAh: Double,
        val reserveAh: Double,
        val nominalV: Double,
        val usableWh: Double,
        val whPerKm: Double,
        val basis: Basis,
        val learnedKm: Double,
        val km: Double,
        /** Share of capacity available at the battery's temperature (1.0 = no cold penalty). */
        val tempFactor: Double = 1.0,
        val tempC: Double? = null,
    )

    /**
     * Cold reduces what a lithium battery can deliver (slower chemistry, more voltage sag).
     * Approximate usable-capacity factor by the coldest cell sensor: 100 % at ≥ 20 °C,
     * 95 % at 10 °C, 85 % at 0 °C, 70 % at −10 °C, 55 % at −20 °C (linear in between).
     */
    fun tempFactor(tempC: Double?): Double {
        if (tempC == null) return 1.0
        val pts = listOf(-20.0 to 0.55, -10.0 to 0.70, 0.0 to 0.85, 10.0 to 0.95, 20.0 to 1.0)
        if (tempC <= pts.first().first) return pts.first().second
        if (tempC >= pts.last().first) return 1.0
        for (i in 0 until pts.size - 1) {
            val (t1, f1) = pts[i]; val (t2, f2) = pts[i + 1]
            if (tempC in t1..t2) return f1 + (f2 - f1) * (tempC - t1) / (t2 - t1)
        }
        return 1.0
    }

    /**
     * @param cells cell count (0 if unknown → the measured pack voltage is used)
     * @param lfp chemistry (LiFePO₄ = true)
     * @param learned (Wh/km, km covered) from trips, if any
     */
    fun estimate(
        remainAh: Double, fullAh: Double, packV: Double, cells: Int, lfp: Boolean,
        userWhKm: Double, learned: Pair<Double, Double>?, defaultWhKm: Double,
        tempC: Double? = null,
    ): Result? {
        if (remainAh <= 0 || packV <= 0) return null
        val (whKm, basis) = when {
            userWhKm > 0 -> userWhKm to Basis.USER
            learned != null && learned.second >= MIN_LEARN_KM && learned.first > 0 -> learned.first to Basis.LEARNED
            defaultWhKm > 0 -> defaultWhKm to Basis.DEFAULT
            else -> return null
        }
        val nominalV = if (cells > 0) cells * (if (lfp) 3.2 else 3.7) else packV
        val reserveAh = if (fullAh > 0) fullAh * RESERVE_PCT / 100.0 else 0.0
        val factor = tempFactor(tempC)
        val usableWh = ((remainAh - reserveAh).coerceAtLeast(0.0)) * nominalV * factor
        return Result(remainAh, reserveAh, nominalV, usableWh, whKm, basis, learned?.second ?: 0.0, usableWh / whKm,
            factor, tempC)
    }
}
