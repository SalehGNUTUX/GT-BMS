// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import org.json.JSONObject
import kotlin.math.min

/**
 * Guided capacity test — measures the battery's real capacity (Ah), independent of the BMS.
 *
 * 1. DISCHARGE — use the battery until the lowest cell reaches the BMS's empty-calibration
 *    voltage (≈ 3.05 V/cell on LiFePO₄): that is "empty".
 * 2. READY     — plug in the charger.
 * 3. CHARGING  — every amp-hour going in is counted, uninterrupted, until the charge ends full
 *    (SOC ≥ 99 % with the current tapering, or the BMS cuts at its cell over-voltage limit).
 * 4. DONE      — Ah counted = real capacity; health = capacity ÷ design capacity.
 *
 * FAILED when the charge stops for 15 min before full (unplugged): the count would be wrong.
 * Pure logic (unit-tested); BleService feeds it readings and stores the state.
 */
object CapacityTest {

    enum class Phase { IDLE, DISCHARGE, READY, CHARGING, DONE, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val startedMs: Long = 0,
        val ah: Double = 0.0,
        val lastMs: Long = 0,
        val quietSinceMs: Long = 0,
        val resultAh: Double? = null,
        val finishedMs: Long = 0,
    ) {
        fun toJson(): String = JSONObject().put("p", phase.name).put("s", startedMs).put("a", ah).put("l", lastMs)
            .put("q", quietSinceMs).put("f", finishedMs).apply { resultAh?.let { put("r", it) } }.toString()

        companion object {
            fun fromJson(s: String?): State = try {
                if (s.isNullOrBlank()) State() else JSONObject(s).let { o ->
                    State(Phase.valueOf(o.optString("p", "IDLE")), o.optLong("s"), o.optDouble("a", 0.0), o.optLong("l"),
                        o.optLong("q"), if (o.has("r")) o.getDouble("r") else null, o.optLong("f"))
                }
            } catch (_: Exception) { State() }
        }
    }

    fun start(now: Long) = State(Phase.DISCHARGE, startedMs = now)

    /**
     * @param lowV  empty-calibration cell voltage (V)   @param ovV cell over-voltage protection (V)
     */
    fun update(st: State, now: Long, soc: Int, current: Double, minCell: Double, maxCell: Double,
               chargeFetOn: Boolean, lowV: Double, ovV: Double): State = when (st.phase) {
        Phase.DISCHARGE ->
            if (minCell > 0 && minCell <= lowV + 0.02) st.copy(phase = Phase.READY, lastMs = now) else st
        Phase.READY ->
            if (current > 0.3) st.copy(phase = Phase.CHARGING, ah = 0.0, lastMs = now, quietSinceMs = 0) else st
        Phase.CHARGING -> {
            val dt = if (st.lastMs == 0L) 0.0 else min((now - st.lastMs) / 1000.0, 10.0)
            val ah = st.ah + (if (current > 0.05) current * dt / 3600.0 else 0.0)
            val full = (soc >= 99 && current < 1.0) || (maxCell >= ovV - 0.01 && !chargeFetOn)
            val quiet = if (current > 0.05) 0L else if (st.quietSinceMs == 0L) now else st.quietSinceMs
            when {
                full -> st.copy(phase = Phase.DONE, ah = ah, resultAh = ah, finishedMs = now, lastMs = now)
                quiet != 0L && now - quiet > 15 * 60_000L -> st.copy(phase = Phase.FAILED, ah = ah, finishedMs = now)
                else -> st.copy(ah = ah, lastMs = now, quietSinceMs = quiet)
            }
        }
        else -> st
    }
}
