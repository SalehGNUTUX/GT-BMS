// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.alarm

/**
 * Low-battery reminders: at the owner's percentage, then at each lower step (20, 15, 10, 5).
 * Stopping the alarm silences it until the next step. Pure, unit-tested.
 */
object LowSteps {
    private val BELOW = listOf(20, 15, 10, 5)
    private const val HYSTERESIS = 2

    fun steps(limit: Int) = if (limit <= 0) emptyList() else listOf(limit) + BELOW.filter { it < limit }

    /** The lowest step reached at this charge; 0 = above all of them. */
    fun at(soc: Int, limit: Int) = steps(limit).filter { soc <= it }.minOrNull() ?: 0

    /** The next reminder after this charge, null when there is none. */
    fun next(soc: Int, limit: Int) = steps(limit).filter { it < soc && it < (at(soc, limit).takeIf { s -> s > 0 } ?: Int.MAX_VALUE) }.maxOrNull()

    /** The step already announced ([last], 0 = none), after the charge moved to [soc]. */
    fun settle(last: Int, soc: Int, limit: Int): Int = when {
        last == 0 || soc > limit + HYSTERESIS || limit <= 0 -> 0
        soc > last + HYSTERESIS -> at(soc, limit).let { if (it == 0) limit else it }      // charged a little
        else -> last
    }

    /** Is there a step below the one already announced? */
    fun due(last: Int, soc: Int, limit: Int): Boolean = at(soc, limit).let { it > 0 && (last == 0 || it < last) }
}
