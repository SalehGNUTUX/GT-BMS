// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Tracks each cell over time to spot a weak one early.
 *
 * For every sample (one every ~30 s) each cell's deviation from the pack average is folded
 * into a slow moving average — separately at rest (|I| < 1 A) and under load (|I| ≥ the
 * vehicle's load current) — and we count how often the cell is the lowest. A healthy pack
 * spreads "lowest" across cells; a weak cell keeps sagging under load and is lowest most of
 * the time.
 */
class CellTracker(private val cells: MutableList<Stat> = mutableListOf()) {

    data class Stat(var restMv: Double = 0.0, var loadMv: Double = 0.0, var restN: Int = 0, var loadN: Int = 0,
                    var lowest: Int = 0, var samples: Int = 0)

    data class Weak(val cell: Int, val loadMv: Double?, val restMv: Double?, val lowestPct: Int, val concerning: Boolean)

    fun update(volts: List<Double>, current: Double, loadA: Double) {
        if (volts.size < 2) return
        while (cells.size < volts.size) cells += Stat()
        val avg = volts.average()
        val low = volts.indexOf(volts.min())
        val iAbs = abs(current)
        volts.forEachIndexed { i, v ->
            val dev = (v - avg) * 1000
            val c = cells[i]
            c.samples++
            if (i == low) c.lowest++
            when {
                iAbs < 1.0 -> { c.restMv = ema(c.restMv, dev, c.restN); c.restN++ }
                iAbs >= loadA -> { c.loadMv = ema(c.loadMv, dev, c.loadN); c.loadN++ }
            }
        }
    }

    /** First samples average plainly, then a slow EMA (≈ the last 50 samples). */
    private fun ema(old: Double, x: Double, n: Int): Double = if (n < 50) old + (x - old) / (n + 1) else old + (x - old) * 0.02

    /** Up to [max] cells that sit lowest, worst first (needs some data). */
    fun weakest(max: Int = 3): List<Weak> {
        val total = cells.firstOrNull()?.samples ?: 0
        if (total < 10) return emptyList()
        return cells.mapIndexed { i, c ->
            val load = c.loadMv.takeIf { c.loadN >= 10 }
            val rest = c.restMv.takeIf { c.restN >= 10 }
            val score = load ?: rest ?: 0.0
            val pct = c.lowest * 100 / c.samples.coerceAtLeast(1)
            Triple(i, score, Weak(i + 1, load, rest, pct,
                (load != null && load <= -50) || (rest != null && rest <= -20) || (pct >= 60 && total >= 50)))
        }.filter { it.second < 0 }.sortedBy { it.second }.take(max).map { it.third }
    }

    fun toJson(): String = JSONArray().apply {
        cells.forEach { c ->
            put(JSONObject().put("r", c.restMv).put("l", c.loadMv).put("rn", c.restN).put("ln", c.loadN)
                .put("lo", c.lowest).put("n", c.samples))
        }
    }.toString()

    companion object {
        fun fromJson(s: String?): CellTracker {
            if (s.isNullOrBlank()) return CellTracker()
            return try {
                val a = JSONArray(s)
                CellTracker((0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Stat(o.optDouble("r"), o.optDouble("l"), o.optInt("rn"), o.optInt("ln"), o.optInt("lo"), o.optInt("n"))
                }.toMutableList())
            } catch (_: Exception) { CellTracker() }
        }
    }
}
