// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Energy recuperation of a trip: what regenerative braking and descents put back into the
 * battery, told apart reading by reading from the GPS speed:
 *  - braking: current flows in while the speed falls;
 *  - descent: current flows in while the speed holds or rises, nothing drawn from the battery.
 * Also the free descent: speed rising with neither draw nor recovery (coasting downhill).
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
    /** Coasting downhill: speed gained with nothing drawn. */
    var coastS = 0.0; private set
    var coastKm = 0.0; private set

    private class Event {
        var last = 0L; var ah = 0.0; var wh = 0.0; var s = 0.0; var a = 0.0; var w = 0.0; var cell = 0.0
        var vMax = 0.0; var seenSpeed = false
        var brakeWh = 0.0; var hillWh = 0.0; var hillS = 0.0
    }
    private var ev: Event? = null
    private var lastT = 0L
    private var lastV: Double? = null

    /** Total including the event in progress (for the screen). */
    val liveWh get() = wh + (ev?.wh ?: 0.0)

    fun add(now: Long, amps: Double, volts: Double, dtS: Double, speedKmh: Double?, cellV: Double, minA: Double) {
        // Speed trend over this reading (km/h per s); none across a gap
        val trend = lastV?.let { pv -> speedKmh?.let { v -> if (now - lastT in 1..4_000) (v - pv) * 1000.0 / (now - lastT) else null } }
        lastT = now; lastV = speedKmh
        ev?.let { if (now - it.last > if (amps > minA) GAP_MS else PAUSE_MS) close() }
        if (amps <= minA) {
            if (amps >= -minA && speedKmh != null && speedKmh >= COAST_MIN_KMH && trend != null && trend >= RISE_KMHS) {
                coastS += dtS; coastKm += speedKmh * dtS / 3600
            }
            return
        }
        val e = ev ?: Event().also { ev = it }
        e.last = now
        val wh = amps * volts * dtS / 3600
        e.ah += amps * dtS / 3600; e.wh += wh; e.s += dtS
        e.a = max(e.a, amps); e.w = max(e.w, amps * volts); e.cell = max(e.cell, cellV)
        if (speedKmh != null) { e.seenSpeed = true; e.vMax = max(e.vMax, speedKmh) }
        when {
            trend == null -> if (speedKmh != null) e.brakeWh += wh       // first reading: the brake lever, most often
            trend <= -FALL_KMHS -> e.brakeWh += wh
            else -> { e.hillWh += wh; e.hillS += dtS }
        }
    }

    /** The current turned out to be a charger's. */
    fun discardOpen() { ev = null }

    fun close() {
        val e = ev ?: return
        ev = null
        if (e.s <= 0) return
        // Standing still, or far too long to be told from a charger without GPS
        if (if (e.seenSpeed) e.vMax < MOVING_KMH else e.s >= BLIND_S) return
        ah += e.ah; wh += e.wh; seconds += e.s; count++
        peakA = max(peakA, e.a); peakW = max(peakW, e.w); maxCell = max(maxCell, e.cell)
        // A moment of steady speed inside a braking is GPS lag, not a slope
        if (e.hillS < HILL_MIN_S) brakeWh += e.brakeWh + e.hillWh else { brakeWh += e.brakeWh; hillWh += e.hillWh }
    }

    fun write(o: JSONObject) {
        o.put("regen_ah", ah).put("regen_wh", wh).put("regen_s", seconds).put("regen_n", count)
            .put("regen_max_a", peakA).put("regen_max_w", peakW).put("regen_brake_wh", brakeWh)
            .put("regen_hill_wh", hillWh).put("regen_max_cell", maxCell).put("coast_s", coastS).put("coast_km", coastKm)
            .put("regen_v", VERSION)
    }

    fun read(o: JSONObject) {
        ah = o.optDouble("regen_ah", 0.0); wh = o.optDouble("regen_wh", 0.0); seconds = o.optDouble("regen_s", 0.0); count = o.optInt("regen_n")
        peakA = o.optDouble("regen_max_a", 0.0); peakW = o.optDouble("regen_max_w", 0.0); brakeWh = o.optDouble("regen_brake_wh", 0.0)
        hillWh = o.optDouble("regen_hill_wh", 0.0); maxCell = o.optDouble("regen_max_cell", 0.0)
        coastS = o.optDouble("coast_s", 0.0); coastKm = o.optDouble("coast_km", 0.0)
    }

    companion object {
        /** Bumped when the method changes: saved trips are worked out again from their points. */
        const val VERSION = 2
        private const val PAUSE_MS = 2_500L
        private const val GAP_MS = 10_000L
        private const val MOVING_KMH = 3.0
        private const val BLIND_S = 180.0
        private const val HILL_MIN_S = 3.0
        /** Speed falling faster than this (km/h per s, ≈ 0.14 m/s²) is braking. */
        private const val FALL_KMHS = 0.5
        private const val RISE_KMHS = 0.3
        private const val COAST_MIN_KMH = 10.0

        /** Trips recorded before this method: from their saved points (one every ~2 s). */
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
