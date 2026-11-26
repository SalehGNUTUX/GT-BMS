// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

/**
 * What recent trips teach about this vehicle (consumption, energy recovered) and the advice for a
 * saved trip built from it. Shared by the phone and the computer so both say the same thing.
 * Lists are newest first, already limited to the battery in question.
 */
object TripLearn {
    /** The trip's energy was measured all along (no long silence of the BMS). */
    fun energyComplete(s: Session) = !s.b("energy_partial") &&
        (s.data.has("gap_s") || s.d("max_speed") <= 0 || s.d("avg_speed") <= s.d("max_speed"))

    /** (Wh/km, km covered) from [recent] trips (the last 20); null below 2 km. */
    fun consumption(recent: List<Session>): Pair<Double, Double>? {
        val trips = recent.filter { it.type == Session.TYPE_TRIP && it.d("distance_km") >= 0.3 && it.d("wh") > 0 && energyComplete(it) }
        val km = trips.sumOf { it.d("distance_km") }
        if (km < 2.0) return null
        return trips.sumOf { it.d("wh") } / km to km
    }

    /** (Wh recovered per km, % of the energy, km) from [recent] trips (the last 31), [except] left out. */
    fun regen(recent: List<Session>, maxTrips: Int = 30, except: Long = -1): Triple<Double, Double, Double>? {
        val t = recent.filter {
            it.type == Session.TYPE_TRIP && it.id != except && it.data.has("regen_wh") && it.d("distance_km") >= 0.3 && energyComplete(it)
        }.take(maxTrips)
        val km = t.sumOf { it.d("distance_km") }
        if (t.size < 3 || km < 5.0) return null
        val rw = t.sumOf { it.d("regen_wh") }
        return Triple(rw / km, RegenMeter.sharePct(rw, t.sumOf { it.d("wh") }) ?: 0.0, km)
    }

    /** Distance the charge left after the trip would last at the trip's own consumption. */
    fun rangeAfter(s: Session, lim: BmsLimits): Double? {
        val whKm = if (!s.data.isNull("wh_per_km") && s.data.has("wh_per_km")) s.d("wh_per_km") else null
        val design = lim.designAh
        return if (whKm != null && whKm > 0 && design != null) s.i("soc_end") / 100.0 * design * ((s.d("max_v") + s.d("min_v")) / 2) / whKm else null
    }

    /** Everything the phone tells after a trip: [last20] for consumption, [last31] for recovery. */
    fun advice(s: Session, lim: BmsLimits, last20: List<Session>, last31: List<Session>): List<Advisor.Rec> =
        Advisor.afterTrip(s, lim, consumption(last20)?.first, rangeAfter(s, lim), regen(last31, except = s.id)) + DrivingAnalyzer.recs(s)
}
