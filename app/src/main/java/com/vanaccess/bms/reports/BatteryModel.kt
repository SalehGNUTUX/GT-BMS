// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

/**
 * What kind of battery is connected, for BMSs that don't report their limits. Nothing is
 * assumed: the chemistry is recognised from the cell voltages, and until then every feature
 * that depends on it stays off. Pure, unit-tested.
 */
object BatteryModel {
    const val LFP = "lfp"; const val LION = "lion"; const val LTO = "lto"

    /**
     * @param seenMax highest average cell voltage ever seen on this battery
     * @param avgCell average cell voltage now, [rest] = (almost) no current
     * @return the chemistry, or null when this reading proves nothing
     */
    fun chemistry(seenMax: Double, avgCell: Double, soc: Int, rest: Boolean): String? = when {
        avgCell < 1.0 -> null
        seenMax >= 3.75 -> LION                                   // no LFP or LTO cell goes there
        seenMax in 1.5..2.95 && soc >= 20 -> LTO                  // LFP / Li-ion would be empty
        rest && soc in 25..90 && avgCell in 3.20..3.38 -> LFP     // the flat LFP plateau
        rest && soc in 25..90 && avgCell >= 3.45 -> LION
        else -> null
    }

    /** Usual protection of the chemistry (mV), only where the BMS gives none and none was observed. */
    fun typicalOvMv(chem: String?) = when (chem) { LFP -> 3650; LION -> 4250; LTO -> 2850; else -> 0 }

    fun chemistryOf(ovMv: Int): String? = when (ovMv) {
        in 2400..3199 -> LTO; in 3300..3800 -> LFP; in 3900..4500 -> LION; else -> null
    }

    fun nominalCell(chem: String?): Double? = when (chem) { LFP -> 3.2; LION -> 3.6; LTO -> 2.3; else -> null }
}
