// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

/**
 * Time left while charging, to 80 % and to 100 %.
 *
 * Up to ~90 % a lithium charge is constant-current, so time = missing Ah ÷ current. The last
 * stretch is slower (constant-voltage taper, balancing), so the part above 90 % is counted
 * at half the current. Returns hours, null when not charging or the data is missing.
 */
object ChargeTime {
    data class Result(val to80h: Double?, val to100h: Double)

    fun estimate(soc: Int, fullAh: Double, currentA: Double): Result? {
        if (currentA <= 0.3 || fullAh <= 0 || soc >= 100) return null
        fun hoursTo(target: Int): Double {
            if (soc >= target) return 0.0
            val ccPart = (minOf(target, 90) - soc).coerceAtLeast(0) / 100.0 * fullAh / currentA
            val tailPart = (target - maxOf(soc, 90)).coerceAtLeast(0) / 100.0 * fullAh / (currentA * 0.5)
            return ccPart + tailPart
        }
        return Result(if (soc < 80) hoursTo(80) else null, hoursTo(100))
    }
}
