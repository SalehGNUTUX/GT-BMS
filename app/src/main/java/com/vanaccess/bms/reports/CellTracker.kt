// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Tracks each cell over time to spot a weak one early, at rest and under load *separately*:
 *  - under load (discharging, at least the vehicle's load current) a cell that sags more has a
 *    higher resistance: the cell itself, or a loose connection;
 *  - at rest (|I| < 1 A, 20–90 % SOC: near full or empty cells spread apart naturally) a cell
 *    that stays low is out of balance or losing capacity.
 * A cell stands out compared with the other cells of the same pack, not with a fixed voltage.
 */
class CellTracker(private val cells: MutableList<Stat> = mutableListOf()) {

    /** [loadLow]/[restLow]: times it was the lowest, out of [loadSeen]/[restSeen] (counted since 1.7). */
    data class Stat(var restMv: Double = 0.0, var loadMv: Double = 0.0, var restN: Int = 0, var loadN: Int = 0,
                    var restLow: Int = 0, var loadLow: Int = 0, var restSeen: Int = 0, var loadSeen: Int = 0)

    /** @param lowestPct null until enough readings were counted */
    data class Row(val cell: Int, val mv: Double, val lowestPct: Int?)

    data class Report(
        val load: List<Row> = emptyList(), val rest: List<Row> = emptyList(),
        val loadCell: Int? = null, val restCell: Int? = null,
    )

    /** [cell] null = no single cell; [peakMv] at [peakA] and [minV] are the worst of those trips. */
    data class TripCell(val cell: Int?, val times: Int, val of: Int, val peakMv: Int, val peakA: Double, val minV: Double)

    fun update(volts: List<Double>, current: Double, loadA: Double, soc: Int) {
        if (volts.size < 2) return
        while (cells.size < volts.size) cells += Stat()
        val avg = volts.average()
        val low = volts.indexOf(volts.min())
        val rest = abs(current) < 1.0 && soc in 20..90
        val load = -current >= loadA
        if (!rest && !load) return
        volts.forEachIndexed { i, v ->
            val dev = (v - avg) * 1000
            val c = cells[i]
            if (rest) { c.restMv = ema(c.restMv, dev, c.restN); c.restN++; c.restSeen++; if (i == low) c.restLow++ }
            else { c.loadMv = ema(c.loadMv, dev, c.loadN); c.loadN++; c.loadSeen++; if (i == low) c.loadLow++ }
        }
    }

    /** First samples average plainly, then a slow EMA (≈ the last 50 samples). */
    private fun ema(old: Double, x: Double, n: Int): Double = if (n < 50) old + (x - old) / (n + 1) else old + (x - old) * 0.02

    /** @param restAlarmMv this battery's own cell-difference alarm value at rest */
    fun report(restAlarmMv: Int): Report {
        fun rows(mv: (Stat) -> Double, n: (Stat) -> Int, lowest: (Stat) -> Int, seen: (Stat) -> Int): List<Row> =
            if ((cells.firstOrNull()?.let(n) ?: 0) < MIN_SAMPLES) emptyList()
            else cells.mapIndexed { i, c -> Row(i + 1, mv(c), if (seen(c) >= MIN_SAMPLES) lowest(c) * 100 / seen(c) else null) }
        val load = rows({ it.loadMv }, { it.loadN }, { it.loadLow }, { it.loadSeen })
        val rest = rows({ it.restMv }, { it.restN }, { it.restLow }, { it.restSeen })
        val l = outlier(load, -8.0); val r = outlier(rest, -restAlarmMv * 0.4)
        fun lowest(a: List<Row>) = a.filter { it.mv < 0 }.sortedBy { it.mv }.take(3)
        return Report(lowest(load), lowest(rest), l?.cell, r?.cell)
    }

    /** The lowest cell, if it is clearly apart from the others (median − 3 × median deviation). */
    private fun outlier(rows: List<Row>, atLeast: Double): Row? {
        if (rows.size < 4) return null
        val w = rows.minBy { it.mv }
        val med = median(rows.map { it.mv })
        val spread = median(rows.map { abs(it.mv - med) }).coerceAtLeast(1.5)
        return w.takeIf { it.mv <= atLeast && it.mv <= med - 3 * spread && (it.lowestPct ?: 100) >= 50 }
    }

    private fun median(l: List<Double>) = l.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

    fun toJson(): String = JSONArray().apply {
        cells.forEach { c ->
            put(JSONObject().put("r", c.restMv).put("l", c.loadMv).put("rn", c.restN).put("ln", c.loadN)
                .put("rl", c.restLow).put("ll", c.loadLow).put("rs", c.restSeen).put("ls", c.loadSeen))
        }
    }.toString()

    companion object {
        const val MIN_SAMPLES = 20

        fun fromJson(s: String?): CellTracker {
            if (s.isNullOrBlank()) return CellTracker()
            return try {
                val a = JSONArray(s)
                CellTracker((0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    // (before 1.7 the "lowest" count mixed rest, load and charging: not taken over)
                    Stat(o.optDouble("r"), o.optDouble("l"), o.optInt("rn"), o.optInt("ln"),
                        o.optInt("rl"), o.optInt("ll"), o.optInt("rs"), o.optInt("ls"))
                }.toMutableList())
            } catch (_: Exception) { CellTracker() }
        }

        /** Recent trips: the cell that was lowest in most of them (if one was), and the worst values. */
        fun fromTrips(trips: List<Session>): TripCell? {
            val t = trips.filter { it.type == Session.TYPE_TRIP && it.i("min_cell_idx") > 0 && it.d("ah") > 0.05 }
            if (t.size < 5) return null
            val top = t.groupingBy { it.i("min_cell_idx") }.eachCount().maxByOrNull { it.value } ?: return null
            val worst = t.maxBy { it.i("max_delta_mv") }
            return TripCell(top.key.takeIf { top.value * 100 >= t.size * 70 }, top.value, t.size,
                worst.i("max_delta_mv"), worst.d("current_at_max_delta"),
                t.filter { it.d("min_cell_v") > 0 }.minOfOrNull { it.d("min_cell_v") } ?: 0.0)
        }
    }
}
