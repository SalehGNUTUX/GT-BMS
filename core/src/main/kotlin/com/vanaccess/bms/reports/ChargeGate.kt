// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

/**
 * Is this a charger, or regenerative braking? Both put a positive current into the battery, but
 * braking lasts seconds and the vehicle moves. Pure, unit-tested.
 */
class ChargeGate {
    /** When the current that is being judged began (0 = none). */
    var since = 0L
        private set
    private var last = 0L
    private var riding = false

    /**
     * @param ridden a trip is running, or the motor drew current a moment ago
     * @param speedKmh GPS speed, null when there is no fresh fix
     * @return true once the current can only come from a charger
     */
    fun update(now: Long, current: Double, startA: Double, ridden: Boolean, speedKmh: Double?): Boolean {
        val gap = last != 0L && now - last > MAX_GAP_MS
        last = now
        if (current <= startA || (speedKmh != null && speedKmh >= MOVING_KMH)) { since = 0L; return false }
        // Two brakings with a lost link between them are not one long charge
        if (since == 0L || gap) { since = now; riding = false }
        // Stays so while this current lasts: the trip itself ends after two idle minutes
        if (ridden) riding = true
        return now - since >= when {
            !riding -> PARKED_MS
            speedKmh != null -> TRIP_STOPPED_MS          // just arrived and plugged in
            else -> TRIP_BLIND_MS                        // no GPS: longer than any braking
        }
    }

    fun reset() { since = 0L; last = 0L; riding = false }

    companion object {
        const val PARKED_MS = 20_000L
        const val TRIP_STOPPED_MS = 60_000L
        const val TRIP_BLIND_MS = 180_000L
        private const val MOVING_KMH = 5.0
        private const val MAX_GAP_MS = 15_000L
    }
}
