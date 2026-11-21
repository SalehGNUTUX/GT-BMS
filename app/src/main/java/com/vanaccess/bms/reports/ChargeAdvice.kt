// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import kotlin.math.floor
import kotlin.math.min

/**
 * Charger and alarm voltage advice derived from the BMS's own cell over-voltage limit.
 *
 * Idea: the charger should finish the charge *below* the BMS protection, so the BMS
 * never has to cut it. And the full-charge alarm must sit below the charger voltage,
 * otherwise it can never be reached.
 *
 *  - LiFePO₄ (cell OV limit ≤ 3.80 V): charge to min(3.60 V, limit − 30 mV) per cell,
 *    alarm at charge target − 50 mV per cell (top of the LFP knee ≈ 99 %).
 *  - Li-ion NMC/NCA (higher limits): charge to min(4.15 V, limit − 50 mV) per cell,
 *    alarm at charge target − 50 mV per cell.
 *
 * Example, 23S LiFePO₄ with 3630 mV protection: protection 83.49 V, charger 82.8 V,
 * alarm 81.6 V.
 */
data class ChargeAdvice(
    val cells: Int,
    val cellOvV: Double,
    val lfp: Boolean,
    val chargeCellV: Double,
    val alarmCellV: Double,
) {
    val protectionPackV get() = cells * cellOvV
    val chargerPackV get() = round1(cells * chargeCellV)
    val alarmPackV get() = floor(cells * alarmCellV * 10) / 10.0

    companion object {
        fun from(cells: Int, cellOvMv: Int): ChargeAdvice? {
            if (cells !in 1..48 || cellOvMv !in 3000..4500) return null
            val ov = cellOvMv / 1000.0
            val lfp = ov <= 3.80
            val charge = if (lfp) min(3.60, ov - 0.03) else min(4.15, ov - 0.05)
            return ChargeAdvice(cells, ov, lfp, charge, charge - 0.05)
        }

        private fun round1(v: Double) = Math.round(v * 10) / 10.0
    }
}
