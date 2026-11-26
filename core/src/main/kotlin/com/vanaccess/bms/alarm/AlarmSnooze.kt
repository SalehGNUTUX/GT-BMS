// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.alarm

/** "Remind me later" while charging: sooner the closer the battery is to full. Pure, unit-tested. */
object AlarmSnooze {
    /**
     * Minutes until the next reminder: the time to gain 5 more points (fewer near 100 %) at this
     * current, between 3 and 15 min; by the percentage alone when the current is not known.
     */
    fun minutes(soc: Int, fullAh: Double, amps: Double): Int {
        val pts = (100 - soc).coerceIn(1, 5)
        if (amps > 0.3 && fullAh > 0) return Math.round(pts / 100.0 * fullAh / amps * 60).toInt().coerceIn(3, 15)
        return when { soc >= 98 -> 3; soc >= 90 -> 5; else -> 10 }
    }
}
