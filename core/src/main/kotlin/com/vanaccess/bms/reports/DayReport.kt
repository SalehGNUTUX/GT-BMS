// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

/**
 * Everything of one calendar day, midnight to midnight (local time; 23 or 25 h on clock
 * changes). A session that crosses midnight is shared between both days by time; its trip is
 * counted on the day it began. Pure, unit-tested.
 */
data class DayReport(
    val startMs: Long, val endMs: Long,
    val trips: List<Session>, val charges: List<Session>,
    val tripCount: Int, val chargeCount: Int,
    val km: Double, val ridingS: Double, val totalS: Double, val maxSpeed: Double,
    val ah: Double, val wh: Double, val whPerKm: Double?, val socUsed: Double,
    val socStart: Int?, val socEnd: Int?,
    val chargedAh: Double, val chargedWh: Double,
    val hardAccel: Int, val shocks: Int, val maxCurrent: Double,
    val minCellV: Double, val minCellIdx: Int, val maxTemp: Double?, val partial: Boolean,
    /** Charge that went down between two sessions: use that nothing recorded (%). */
    val unrecorded: Int,
    /** Charge put in during the day as the BMS shows it (%). */
    val socCharged: Int,
    /** Energy recuperation (braking, descents) of the trips. */
    val regenAh: Double = 0.0, val regenWh: Double = 0.0,
    /** Switched on without moving: time (s) and energy. */
    val standbyS: Double = 0.0, val standbyWh: Double = 0.0,
) {
    val avgSpeed get() = if (ridingS > 0) km / (ridingS / 3600.0) else 0.0

    companion object {
        fun dayStart(ms: Long, tz: TimeZone = TimeZone.getDefault()): Long = Calendar.getInstance(tz).apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun dayEnd(start: Long, tz: TimeZone = TimeZone.getDefault()): Long = Calendar.getInstance(tz).apply {
            timeInMillis = start; add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis

        /** Days that have at least one session, newest first. */
        fun all(sessions: List<Session>, tz: TimeZone = TimeZone.getDefault()): List<DayReport> {
            val starts = sortedSetOf<Long>()
            sessions.forEach { s ->
                var d = dayStart(s.startMs, tz)
                while (d == dayStart(s.startMs, tz) || d < s.endMs) { starts += d; d = dayEnd(d, tz) }
            }
            return starts.sortedDescending().mapNotNull { of(sessions, it, tz) }
        }

        fun of(sessions: List<Session>, start: Long, tz: TimeZone = TimeZone.getDefault()): DayReport? {
            val end = dayEnd(start, tz)
            val inDay = sessions.filter { it.startMs < end && (it.endMs > start || it.startMs >= start) }.sortedBy { it.startMs }
            if (inDay.isEmpty()) return null
            /** Share of the session inside this day. */
            fun part(s: Session): Double {
                val len = (s.endMs - s.startMs).toDouble()
                return if (len <= 0) 1.0 else ((min(s.endMs, end) - max(s.startMs, start)) / len).coerceIn(0.0, 1.0)
            }
            fun socAt(s: Session, t: Long): Int {
                val a = s.i("soc_start"); val b = s.i("soc_end"); val len = s.endMs - s.startMs
                return if (len <= 0) b else Math.round(a + (b - a) * ((t - s.startMs).toDouble() / len)).toInt()
            }
            fun impossible(s: Session) = s.d("max_speed") > 0 && s.d("avg_speed") > s.d("max_speed")
            // One point can be rounding; the session before may be from the day before
            val ordered = sessions.filter { it.startMs < end }.sortedBy { it.startMs }
            val lost = ordered.zipWithNext().filter { (_, b) -> b.startMs >= start }
                .sumOf { (a, b) -> (a.i("soc_end") - b.i("soc_start")).let { if (it >= 2) it else 0 } }
            fun begun(s: Session) = s.startMs >= start
            val out = inDay.filter { it.type != Session.TYPE_CHARGE && it.type != Session.TYPE_STANDBY }
            val standby = inDay.filter { it.type == Session.TYPE_STANDBY }
            val trips = out.filter { it.type == Session.TYPE_TRIP }
            val charges = inDay.filter { it.type == Session.TYPE_CHARGE }
            fun riding(s: Session): Double {
                // Reports before 1.7 could hold less moving time than the distance needs
                return if (impossible(s) || s.d("moving_s") <= 0) (s.endMs - s.startMs) / 1000.0 else s.d("moving_s")
            }
            val withKm = trips.filter { it.d("distance_km") > 0 }
            // Distance without its energy (before 1.7) would make the consumption look better than it was
            val complete = withKm.filter { !it.data.isNull("wh_per_km") && !it.b("energy_partial") && !impossible(it) }
            val ckm = complete.sumOf { it.d("distance_km") * part(it) }
            val low = out.filter { it.d("min_cell_v") > 0 }.minByOrNull { it.d("min_cell_v") }
            return DayReport(start, end, trips, charges,
                trips.count { begun(it) }, charges.count { begun(it) },
                withKm.sumOf { it.d("distance_km") * part(it) }, withKm.sumOf { riding(it) * part(it) },
                trips.sumOf { (it.endMs - it.startMs) / 1000.0 * part(it) }, trips.maxOfOrNull { it.d("max_speed") } ?: 0.0,
                out.sumOf { it.d("ah") * part(it) }, out.sumOf { it.d("wh") * part(it) },
                if (ckm >= 0.1) complete.sumOf { it.d("wh") * part(it) } / ckm else null,
                out.sumOf { (it.i("soc_start") - it.i("soc_end")).coerceAtLeast(0) * part(it) },
                inDay.first().let { socAt(it, max(it.startMs, start)) },
                inDay.maxByOrNull { it.endMs }!!.let { socAt(it, min(it.endMs, end)) },
                charges.sumOf { it.d("ah") * part(it) }, charges.sumOf { it.d("wh") * part(it) },
                trips.filter { begun(it) }.sumOf { it.i("hard_accel") }, trips.filter { begun(it) }.sumOf { it.i("throttle_shocks") },
                out.maxOfOrNull { it.d("max_current") } ?: 0.0, low?.d("min_cell_v") ?: 0.0, low?.i("min_cell_idx") ?: 0,
                inDay.filter { !it.data.isNull("max_temp") && it.data.has("max_temp") }.maxOfOrNull { it.d("max_temp") },
                trips.any { it.b("energy_partial") || impossible(it) }, lost,
                Math.round(charges.sumOf { (it.i("soc_end") - it.i("soc_start")).coerceAtLeast(0) * part(it) }).toInt(),
                trips.sumOf { it.d("regen_ah") * part(it) }, trips.sumOf { it.d("regen_wh") * part(it) },
                standby.sumOf { it.d("active_s") * part(it) }, standby.sumOf { it.d("wh") * part(it) })
        }
    }
}
