// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import org.json.JSONObject
import kotlin.math.abs

/**
 * When the running charge is expected to reach the user's percentage and the end, so both can be
 * announced by the clock when the phone is out of Bluetooth range. Pure, unit-tested.
 */
data class ChargePlan(
    val battery: String, val madeMs: Long, val targetSoc: Int,
    /** The chosen percentage (null = none chosen or already passed). */
    val targetAt: Long?,
    /** A little before the end. */
    val nearAt: Long?,
    val fullAt: Long,
) {
    fun toJson(): String = JSONObject().put("bat", battery).put("made", madeMs).put("soc", targetSoc).put("full", fullAt)
        .apply { targetAt?.let { put("target", it) }; nearAt?.let { put("near", it) } }.toString()

    /** Different enough to set the alarms again. */
    fun differs(o: ChargePlan?): Boolean {
        fun far(a: Long?, b: Long?) = (a == null) != (b == null) || (a != null && b != null && abs(a - b) >= 60_000)
        return o == null || battery != o.battery || targetSoc != o.targetSoc || far(targetAt, o.targetAt) || far(nearAt, o.nearAt) || far(fullAt, o.fullAt)
    }

    companion object {
        const val NEAR_MS = 10 * 60_000L
        /** An alarm this soon after planning says nothing new. */
        private const val MIN_AHEAD_MS = 60_000L

        fun fromJson(s: String?): ChargePlan? = runCatching {
            val o = JSONObject(s!!)
            fun l(k: String) = if (o.has(k)) o.getLong(k) else null
            ChargePlan(o.getString("bat"), o.getLong("made"), o.getInt("soc"), l("target"), l("near"), o.getLong("full"))
        }.getOrNull()

        /**
         * @param targetSoc the percentage of the full-charge alarm (0 = not used)
         * @param hoursToFull from [ChargePredictor]; [taper] = the charger slows down near the end
         */
        fun make(battery: String, now: Long, soc: Int, remainAh: Double, fullAh: Double, amps: Double,
                 targetSoc: Int, hoursToFull: Double, taper: Boolean): ChargePlan? {
            if (fullAh <= 0 || amps <= 0.3 || hoursToFull.isNaN() || hoursToFull < 0 || hoursToFull > 48) return null
            val fullAt = now + (hoursToFull * 3_600_000).toLong()
            val have = if (remainAh > 0) remainAh else soc / 100.0 * fullAh
            fun hoursTo(p: Int): Double {
                val need = p / 100.0 * fullAh - have
                if (!taper || p <= 90) return need / amps
                val above = (p - maxOf(90.0, have / fullAh * 100)) / 100.0 * fullAh
                return (need - above) / amps + above / (amps * 0.5)
            }
            val target = when {
                targetSoc <= 0 || soc >= targetSoc -> null
                targetSoc >= 100 -> fullAt
                else -> minOf(now + (hoursTo(targetSoc) * 3_600_000).toLong(), fullAt)
            }?.takeIf { it - now >= MIN_AHEAD_MS }
            // After the chosen percentage, or before 100 %; never both at the same moment
            val near = (fullAt - NEAR_MS).takeIf { it - now >= MIN_AHEAD_MS && (target == null || targetSoc >= 100 || it - target >= 3 * 60_000) }
            return ChargePlan(battery, now, targetSoc, target, near, fullAt)
        }
    }
}
