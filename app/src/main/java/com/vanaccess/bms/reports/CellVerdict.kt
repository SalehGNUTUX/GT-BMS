// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import kotlin.math.abs

/**
 * How serious the cell differences are, in plain terms, judged by this battery's own limits:
 * its cell-difference alarm values and its under-voltage protection. Pure, unit-tested.
 */
object CellVerdict {
    enum class Level { UNKNOWN, NORMAL, ACCEPTABLE, CHECK, DANGER }
    /** Facts the verdict rests on, shown to the user. */
    enum class Why { LOAD_SMALL, LOAD_LARGE, LOAD_HUGE, REST_FINE, REST_LOW, REST_FAR, PEAK_OVER_ALARM, MARGIN_OK, MARGIN_THIN }

    data class V(val level: Level, val cell: Int?, val why: List<Why>)

    fun of(r: CellTracker.Report, trips: CellTracker.TripCell?, loadAlarmMv: Int, restAlarmMv: Int, cellUvMv: Int?): V {
        val known = r.load.isNotEmpty() || r.rest.isNotEmpty() || trips != null
        if (!known) return V(Level.UNKNOWN, null, emptyList())
        val why = mutableListOf<Why>()
        var level = Level.NORMAL
        fun raise(l: Level) { if (l > level) level = l }

        val loadMv = r.load.firstOrNull { it.cell == r.loadCell }?.mv
        when {
            loadMv != null && -loadMv >= loadAlarmMv -> { why += Why.LOAD_HUGE; raise(Level.DANGER) }
            loadMv != null && -loadMv >= loadAlarmMv / 3.0 -> { why += Why.LOAD_LARGE; raise(Level.CHECK) }
            loadMv != null || trips?.cell != null -> { why += Why.LOAD_SMALL; raise(Level.ACCEPTABLE) }
        }
        val restMv = r.rest.firstOrNull { it.cell == r.restCell }?.mv
        when {
            restMv != null && abs(restMv) >= restAlarmMv * 2 -> { why += Why.REST_FAR; raise(Level.DANGER) }
            restMv != null -> { why += Why.REST_LOW; raise(Level.CHECK) }
            r.rest.isNotEmpty() && level != Level.NORMAL -> why += Why.REST_FINE
        }
        if (trips != null) {
            if (trips.peakMv >= loadAlarmMv) { why += Why.PEAK_OVER_ALARM; raise(Level.CHECK) }
            if (cellUvMv != null && trips.minV > 0) {
                if (trips.minV * 1000 <= cellUvMv + 100) { why += Why.MARGIN_THIN; raise(Level.DANGER) }
                else if (level != Level.NORMAL) why += Why.MARGIN_OK
            }
        }
        return V(level, r.loadCell ?: r.restCell ?: trips?.cell, why)
    }
}
