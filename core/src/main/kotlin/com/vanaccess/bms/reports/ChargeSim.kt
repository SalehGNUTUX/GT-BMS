// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import kotlin.math.exp
import kotlin.math.min

/**
 * A LiFePO4 pack on a constant-current charger, for trying the charge screens and alarms without
 * a battery: flat cells up to ~90 %, then the knee, one cell ahead of the others, and the BMS
 * cutting the charge at its over-voltage limit (counter jumping to full, as the owner's pack does).
 * Every value comes from the pack it starts from. Pure.
 */
class ChargeSim(startCellsV: List<Double>, private val fullAh: Double, startSoc: Int, private val amps: Double,
                private val ovV: Double, private val leader: Int = 8) {
    private val offsets = startCellsV.map { it - startCellsV.average() }
    var remainAh = fullAh * startSoc / 100.0; private set
    var cut = false; private set
    var current = amps; private set
    var cells: List<Double> = startCellsV; private set

    /** Advance [dtS] seconds of charging. */
    fun step(dtS: Double) {
        if (cut) { current = 0.0; cells = cells.map { it - (it - 3.35) * 0.02 }; return }
        remainAh = min(fullAh, remainAh + amps * dtS / 3600)
        val soc = remainAh / fullAh
        // flat plateau, then a knee that grows fast near full
        val base = 3.32 + 0.06 * soc + 0.30 * exp((soc - 1.0) * 40)
        cells = offsets.mapIndexed { i, o -> base + o + if (i == leader) 0.012 + 0.12 * exp((soc - 1.0) * 30) else 0.0 }
        if (cells.max() >= ovV) { cut = true; current = 0.0; remainAh = fullAh }
    }
}
