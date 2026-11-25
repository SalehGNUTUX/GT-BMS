// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

/**
 * What to do with a trip while the battery is silent (Bluetooth lost). Pure, unit-tested.
 *
 * Silence alone does not mean the rider left: the link can drop in a pocket while riding. If
 * the vehicle was being ridden when the link was lost, GPS decides; if it was parked, the
 * trip ends after one minute as before.
 */
object TripGap {
    /** Faster than walking: GPS alone proves the ride goes on. */
    const val RIDING_KMH = 8.0
    const val PARKED_END_MS = 60_000L
    const val STOPPED_END_MS = 120_000L
    const val MAX_SILENCE_MS = 45 * 60_000L
    private const val RIDING_MEMORY_MS = 15_000L

    /**
     * @param lastSampleMs last battery reading
     * @param ridingAtLossMs last riding evidence (load or riding speed) up to that reading
     * @param lastRidingMs last riding evidence so far, GPS included
     */
    fun shouldEnd(now: Long, lastSampleMs: Long, ridingAtLossMs: Long, lastRidingMs: Long): Boolean {
        val silent = now - lastSampleMs
        if (silent <= PARKED_END_MS) return false
        val wasRiding = lastSampleMs - ridingAtLossMs <= RIDING_MEMORY_MS
        if (!wasRiding || silent > MAX_SILENCE_MS) return true
        return now - maxOf(lastRidingMs, lastSampleMs) >= STOPPED_END_MS
    }

    /**
     * Ah drawn during a silence, from the BMS's own remaining-capacity counter, which keeps
     * counting without us. 0 when it can't be known (charged meanwhile, recalibrated…).
     */
    fun missingAh(remainBefore: Double, remainAfter: Double, fullAh: Double): Double {
        val d = remainBefore - remainAfter
        return if (remainBefore > 0 && remainAfter > 0 && d > 0 && d <= fullAh) d else 0.0
    }

    /**
     * Distance the trip's energy belongs to, for the consumption; null = not known well enough.
     * With the end unheard ([partial]), the part with data counts when it is nearly all of it.
     */
    fun energyKm(partial: Boolean, km: Double, heardKm: Double): Double? = when {
        !partial -> km.takeIf { it >= 0.1 }
        else -> heardKm.takeIf { it >= 0.3 && it >= km * 0.8 }
    }

    /** Can the counter be trusted across the silence (not charged or recalibrated meanwhile)? */
    fun known(remainBefore: Double, remainAfter: Double, fullAh: Double) =
        remainBefore > 0 && remainAfter > 0 && remainBefore - remainAfter in -0.11..fullAh

    /** A charge out of Bluetooth range stays open this long: it usually just goes on. */
    const val CHARGE_SILENCE_MS = 6 * 3_600_000L

    /**
     * Ah charged during a silence: what the BMS counted, but never more than the charge current
     * could deliver (its counter jumps when the protection cuts).
     */
    fun chargedAh(remainBefore: Double, remainAfter: Double, lastCurrent: Double, silentS: Double): Double =
        if (remainBefore <= 0 || remainAfter <= 0) 0.0
        else (remainAfter - remainBefore).coerceIn(0.0, (lastCurrent * silentS / 3600.0 * 1.05).coerceAtLeast(0.0))

    /** Is the distance between two fixes [dtS] apart believable for these speeds? */
    fun plausible(distM: Double, dtS: Double, kmhA: Double, kmhB: Double): Boolean =
        dtS > 0 && dtS <= 120 && distM / dtS * 3.6 <= maxOf(kmhA, kmhB) * 1.5 + 10
}
