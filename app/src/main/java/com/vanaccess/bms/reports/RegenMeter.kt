// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Energy recuperation of a trip: what regenerative braking and descents put back into the
 * battery. Braking and descents are told apart by the GPS speed, so that split is an estimate.
 * Pure, unit-tested.
 */
class RegenMeter {
    var ah = 0.0; private set
    var wh = 0.0; private set
    var seconds = 0.0; private set
    var count = 0; private set
    var peakA = 0.0; private set
    var peakW = 0.0; private set
    var brakeWh = 0.0; private set
    var hillWh = 0.0; private set
    var maxCell = 0.0; private set

    private class Event(val t0: Long) {
        var last = t0; var ah = 0.0; var wh = 0.0; var s = 0.0; var a = 0.0; var w = 0.0; var cell = 0.0
        var v0: Double? = null; var v1 = 0.0; var vMax = 0.0
    }
    private var ev: Event? = null

    /** Total including the event in progress (for the screen). */
    val liveWh get() = wh + (ev?.wh ?: 0.0)

    fun add(now: Long, amps: Double, volts: Double, dtS: Double, speedKmh: Double?, cellV: Double, minA: Double) {
        ev?.let { if (now - it.last > if (amps > minA) GAP_MS else PAUSE_MS) close() }
        if (amps <= minA) return
        val e = ev ?: Event(now).also { ev = it }
        e.last = now
        e.ah += amps * dtS / 3600; e.wh += amps * volts * dtS / 3600; e.s += dtS
        e.a = max(e.a, amps); e.w = max(e.w, amps * volts); e.cell = max(e.cell, cellV)
        speedKmh?.let { if (e.v0 == null) e.v0 = it; e.v1 = it; e.vMax = max(e.vMax, it) }
    }

    /** The current turned out to be a charger's. */
    fun discardOpen() { ev = null }

    fun close() {
        val e = ev ?: return
        ev = null
        if (e.s <= 0) return
        val v0 = e.v0
        // Standing still, or far too long to be told from a charger without GPS
        if (if (v0 != null) e.vMax < MOVING_KMH else e.s >= BLIND_S) return
        ah += e.ah; wh += e.wh; seconds += e.s; count++
        peakA = max(peakA, e.a); peakW = max(peakW, e.w); maxCell = max(maxCell, e.cell)
        if (v0 != null) {
            val decel = (v0 - e.v1) / 3.6 / e.s
            if (e.s >= HILL_S && decel < BRAKE_MPS2) hillWh += e.wh else brakeWh += e.wh
        }
    }

    fun write(o: JSONObject) {
        o.put("regen_ah", ah).put("regen_wh", wh).put("regen_s", seconds).put("regen_n", count)
            .put("regen_max_a", peakA).put("regen_max_w", peakW).put("regen_brake_wh", brakeWh)
            .put("regen_hill_wh", hillWh).put("regen_max_cell", maxCell)
    }

    fun read(o: JSONObject) {
        ah = o.optDouble("regen_ah", 0.0); wh = o.optDouble("regen_wh", 0.0); seconds = o.optDouble("regen_s", 0.0); count = o.optInt("regen_n")
        peakA = o.optDouble("regen_max_a", 0.0); peakW = o.optDouble("regen_max_w", 0.0); brakeWh = o.optDouble("regen_brake_wh", 0.0)
        hillWh = o.optDouble("regen_hill_wh", 0.0); maxCell = o.optDouble("regen_max_cell", 0.0)
    }

    companion object {
        private const val PAUSE_MS = 2_500L
        private const val GAP_MS = 10_000L
        private const val MOVING_KMH = 3.0
        private const val BLIND_S = 180.0
        private const val HILL_S = 6.0
        private const val BRAKE_MPS2 = 0.25

        /** Trips recorded before this was measured: from their saved points (one every ~2 s). */
        fun fromPoints(points: List<Point>, minA: Double = 0.3) = RegenMeter().apply {
            var last = 0L
            points.filter { it.v > 0 }.forEach { p ->
                val dt = if (last == 0L) 0.0 else min((p.tMs - last) / 1000.0, 10.0)
                last = p.tMs
                add(p.tMs, p.i, p.v, dt, p.speedKmh, 0.0, minA)
            }
            close()
        }

        /** Share of the energy the motor took that came back (%), null when nothing was used. */
        fun sharePct(regenWh: Double, netWh: Double): Double? =
            (netWh + regenWh).takeIf { it > 0 && regenWh > 0 }?.let { regenWh * 100 / it }
    }
}
