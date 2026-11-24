// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import kotlin.math.abs

/**
 * Driving style from a trip's current curve (one sample every ~2 s).
 *
 * Why it matters for lithium: losses grow with the square of the current (I²R), so repeated
 * jumps from ~0 A to a high current waste more energy and heat than a steady current with
 * the same average, and it is at those peaks that a weak cell sags furthest (that is what
 * crosses the cell-difference alarm). Lithium also needs no "rest" between pulls, unlike
 * lead-acid, which recovers some voltage while resting — "press and release" suited lead-acid.
 *
 *  - shocks:   current jumps from < 10 % to ≥ 30 % of the battery's limit within ~4 s (sudden throttle)
 *  - onOff:    full-pull → release cycles (≥ 50 % of the trip's peak, then almost nothing)
 *  - highPct:  share of the driving time at ≥ 60 % of the BMS over-current point
 */
object DrivingAnalyzer {

    data class Style(val shocks: Int, val onOff: Int, val highPct: Int, val highA: Double)

    fun analyze(points: List<Point>, dischargeOcA: Int?): Style {
        val dis = points.map { -it.i }                       // discharge current, positive
        if (dis.size < 5) return Style(0, 0, 0, 0.0)
        val peak = dis.max().coerceAtLeast(1.0)
        // What is "almost nothing" and "a lot" depends on the battery: its discharge limit, else this trip's peak
        val ref = (dischargeOcA?.toDouble() ?: peak).coerceAtLeast(1.0)
        val calm = ref * 0.1; val hard = ref * 0.3
        var shocks = 0; var lastShock = -10
        for (k in 2 until dis.size) {
            val before = minOf(dis[k - 1], dis[k - 2])
            if (before < calm && dis[k] >= hard && k - lastShock >= 3) { shocks++; lastShock = k }
        }
        var onOff = 0; var high = false
        dis.forEach { a ->
            if (!high && a >= peak * 0.5) high = true
            else if (high && a < ref * 0.04) { onOff++; high = false }
        }
        val highA = dischargeOcA?.let { it * 0.6 } ?: (peak * 0.8)
        val driving = dis.count { abs(it) > 1.0 }.coerceAtLeast(1)
        val highPct = dis.count { it >= highA } * 100 / driving
        return Style(shocks, onOff, highPct, highA)
    }

    /**
     * Most economical average-speed band across trips: 10 km/h buckets, mean Wh/km, at least
     * 2 trips and 2 km per bucket. Returns (low km/h, Wh/km of best) and the worst band's Wh/km.
     */
    data class SpeedBand(val fromKmh: Int, val bestWhKm: Double, val worstFromKmh: Int, val worstWhKm: Double)

    fun bestSpeed(trips: List<Session>): SpeedBand? {
        val usable = trips.filter { it.d("distance_km") >= 0.5 && !it.data.isNull("wh_per_km") && it.d("avg_speed") > 5 }
        val bands = usable.groupBy { (it.d("avg_speed") / 10).toInt() * 10 }
            .filter { (_, l) -> l.size >= 2 && l.sumOf { it.d("distance_km") } >= 2.0 }
            .mapValues { (_, l) -> l.sumOf { it.d("wh") } / l.sumOf { it.d("distance_km") } }
        if (bands.size < 2) return null
        val best = bands.minBy { it.value }; val worst = bands.maxBy { it.value }
        return SpeedBand(best.key, best.value, worst.key, worst.value)
    }

    /**
     * Charges cut repeatedly by the BMS protection: in the BMS history, protection trips that
     * follow each other within 3 h (the charger restarts after every release). Returns the
     * largest such run, i.e. how many times one charge was cut.
     */
    fun protectionRepeats(history: List<com.vanaccess.bms.ble.drivers.HistoryRecord>): Int {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val times = history.filter { "Cell overvoltage protection" in it.events && !it.time.startsWith("2000-") }
            .mapNotNull { runCatching { fmt.parse(it.time)?.time }.getOrNull() }.sorted()
        var best = if (times.isEmpty()) 0 else 1; var run = 1
        for (k in 1 until times.size) {
            run = if (times[k] - times[k - 1] <= 3 * 3_600_000L) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    /** Recommendations for one trip (codes rendered by AdviceText). */
    fun recs(s: Session): List<Advisor.Rec> {
        val out = mutableListOf<Advisor.Rec>()
        if (!s.data.has("throttle_shocks")) return out
        val km = s.d("distance_km").coerceAtLeast(0.1)
        val shocks = s.i("throttle_shocks"); val onOff = s.i("onoff_cycles"); val high = s.i("high_current_pct")
        when {
            shocks >= 6 || shocks / km >= 1.0 -> out += Advisor.Rec(Advisor.Level.WARNING, "drive_shocks", listOf(shocks))
            onOff / km >= 2.0 -> out += Advisor.Rec(Advisor.Level.INFO, "drive_onoff", listOf(onOff))
            shocks == 0 && km >= 1 -> out += Advisor.Rec(Advisor.Level.OK, "drive_smooth")
        }
        if (high >= 30) out += Advisor.Rec(Advisor.Level.INFO, "drive_high_current", listOf(high, s.d("high_current_a")))
        return out
    }
}
