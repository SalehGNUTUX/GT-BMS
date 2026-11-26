// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

/**
 * The vehicle is switched on but not going anywhere: its controller, display and lights draw a
 * small steady current (less than riding, more than the BMS itself) and nothing moves. Learns
 * the vehicle's own standby current from these readings. Pure, unit-tested.
 */
class StandbyWatch {
    /** When the vehicle was found on and still (0 = not). */
    var onSince = 0L; private set
    /** This vehicle's standby current (A), learned. */
    var amps = 0.0; private set
    private var candidate = 0L
    private var last = 0L

    /**
     * @param current battery current (− = drawn)   @param speedKmh GPS speed, null without a fix
     * @param ridingA what starts a trip for this battery: standby is below it
     */
    fun update(now: Long, current: Double, speedKmh: Double?, ridingA: Double): Boolean {
        val gap = last != 0L && now - last > MAX_GAP_MS
        last = now
        val draw = -current
        val still = speedKmh == null || speedKmh < STILL_KMH
        if (draw < MIN_A || draw >= ridingA || !still) { candidate = 0L; onSince = 0L; return false }
        if (candidate == 0L || gap) candidate = now
        if (onSince == 0L && now - candidate >= CONFIRM_MS) onSince = candidate
        if (onSince != 0L) amps = if (amps == 0.0) draw else amps * 0.9 + draw * 0.1
        return onSince != 0L
    }

    fun reset() { onSince = 0L; candidate = 0L; last = 0L }

    companion object {
        /** Below this the BMS reads its own consumption or noise. */
        const val MIN_A = 0.15
        const val CONFIRM_MS = 60_000L
        private const val STILL_KMH = 3.0
        private const val MAX_GAP_MS = 15_000L
    }
}
