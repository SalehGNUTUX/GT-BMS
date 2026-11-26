// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import org.json.JSONObject

/** One saved charge or trip; [data] holds the report fields (see [SessionRecorder]). */
data class Session(
    val id: Long,
    val type: String,          // TYPE_CHARGE | TYPE_TRIP
    val startMs: Long,
    val endMs: Long,
    val battery: String,
    val data: JSONObject,
) {
    fun d(key: String, def: Double = 0.0) = data.optDouble(key, def)
    fun i(key: String, def: Int = 0) = data.optInt(key, def)
    fun b(key: String) = data.optBoolean(key, false)

    /**
     * Current at the largest cell difference; null when it can't be trusted: before 1.7 (no
     * "gap_s") the cells of one reading were paired with the current of another.
     */
    /**
     * Average current of a charge: Ah over its time. Reports before 1.8.1 divided by the time with
     * data only, so a charge mostly out of Bluetooth range looked several times faster.
     */
    val avgA: Double get() {
        val h = (endMs - startMs) / 3_600_000.0
        return if (d("gap_s") >= 30 && h >= 1 / 60.0) d("ah") / h else d("avg_current")
    }

    val deltaA: Double? get() = if (data.has("gap_s") || b("delta_paired")) d("current_at_max_delta") else null

    companion object {
        const val TYPE_CHARGE = "charge"
        const val TYPE_TRIP = "trip"
        const val TYPE_DISCHARGE = "discharge"
    }
}

/** One chart sample of a session (every ~2 s). */
data class Point(
    val tMs: Long, val v: Double, val i: Double, val soc: Int,
    val speedKmh: Double?, val lat: Double?, val lon: Double?,
)
