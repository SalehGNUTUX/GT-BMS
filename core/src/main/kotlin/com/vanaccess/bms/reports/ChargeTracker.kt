// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import com.vanaccess.bms.ble.BmsBasicInfo
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Follows charges from the battery's readings and writes their report, with the same rules and
 * fields as the phone: a charge starts after a steady charging current ([ChargeGate], parked),
 * is "stopped" after 30 s without current and saved after 2 min; a Bluetooth gap keeps it open
 * up to [TripGap.CHARGE_SILENCE_MS]. Pure: the caller gives the time of every reading.
 */
class ChargeTracker(
    /** Cell over-voltage protection (V); [UNKNOWN_OV] when the battery doesn't report it. */
    var cellOvV: Double = UNKNOWN_OV,
    private val onSaved: (start: Long, end: Long, data: JSONObject, points: List<Point>) -> Unit,
) {
    /** The charge in progress, for the screen. */
    class Live(val startMs: Long, val socStart: Int, val ah: Double, val wh: Double, val stopped: Boolean, val watch: ChargeWatch)

    var live: Live? = null; private set
    /** Last reading of a confirmed charge (feeds the alarms); 0 = none. */
    var lastChargingMs = 0L; private set
    var learn = ChargeLearn()
    var targetSoc = 0
    var targetV = 0.0

    private class Rec(val startMs: Long, val socStart: Int, val startMinCell: Double, val remainStart: Double) {
        val watch = ChargeWatch(startMs, socStart, remainStart)
        var endMs = startMs; var socEnd = socStart
        var ah = 0.0; var wh = 0.0; var activeS = 0.0
        var maxV = 0.0; var minV = Double.MAX_VALUE
        var maxCellV = 0.0; var maxCellIdx = 0; var minCellV = Double.MAX_VALUE; var minCellIdx = 0
        var maxDeltaMv = 0; var maxTemp = -100.0; var maxCurrent = 0.0
        var balancingSeen = false; var endedByProtection = false
        var gapMs = 0L; var stop: ChargeWatch.Result? = null; var stopMs = 0L
        var etaFirstMin: Double? = null
        val points = mutableListOf<Point>(); var lastPointMs = 0L
    }

    private val gate = ChargeGate()
    private var rec: Rec? = null
    private var quietSince = 0L
    private var lastMs = 0L
    private var lastRemain = 0.0; private var lastPack = 0.0; private var lastCurrent = 0.0
    private var full = 0.0

    fun onReading(now: Long, info: BmsBasicInfo, cells: List<Double>) {
        val silentMs = if (lastMs == 0L) 0L else now - lastMs
        val dtS = min(silentMs / 1000.0, MAX_DT_S)
        val remainBefore = lastRemain; val packBefore = lastPack; val currentBefore = lastCurrent
        lastMs = now; lastRemain = info.remainCapacity; lastPack = info.packVoltage; lastCurrent = info.current
        full = info.fullCapacity
        val i = info.current
        val k = scale(info.fullCapacity)

        rec?.let { c ->
            if (silentMs > TripGap.CHARGE_SILENCE_MS) { finish(c); return@let }
            if (silentMs > MAX_DT_S * 1000) {
                if (info.remainCapacity < remainBefore - 0.15) { finish(c); return@let }     // used meanwhile
                val ah = TripGap.chargedAh(remainBefore, info.remainCapacity, currentBefore, silentMs / 1000.0) - max(0.0, i) * dtS / 3600.0
                if (ah > 0) { c.ah += ah; c.wh += ah * (packBefore + info.packVoltage) / 2 }
                c.endMs = now; c.gapMs += silentMs; quietSince = 0L
            }
        }

        val charger = gate.update(now, i, if (rec != null) CHG_END_A * k else CHG_START_A * k, false, null)
        if (rec == null && charger) {
            rec = Rec(gate.since, info.soc, cells.minOrNull() ?: 0.0, info.remainCapacity)
            quietSince = 0L
        }
        val c = rec ?: run { live = null; return }
        if (i > CHG_END_A * k) {
            if (charger) lastChargingMs = now
            accumulate(c, now, info, cells, dtS)
            quietSince = 0L
            if (c.stop != null) c.stop = null
            c.watch.charging(now, info.soc, info.remainCapacity, info.fullCapacity, i, info.packVoltage, cells, info.chargeFetOn, c.ah)
            if (c.etaFirstMin == null && now - c.startMs >= 120_000) c.etaFirstMin = predict(info)?.let { (now - c.startMs) / 60_000.0 + it.hours * 60 }
        } else {
            if (quietSince == 0L) quietSince = now
            if (c.stop == null && now - quietSince >= STOP_MS) {
                c.stopMs = quietSince
                c.stop = c.watch.stopped(now, info.soc, info.remainCapacity, info.fullCapacity, info.chargeFetOn, info.alarmTexts ?: emptyList(), cellOvV)
                if (c.stop?.reason == ChargeWatch.Stop.PROTECTION) c.endedByProtection = true
            }
            if (!info.chargeFetOn && c.maxCellV >= cellOvV - 0.01) c.endedByProtection = true
            if (now - quietSince >= SAVE_MS) { c.socEnd = info.soc; finish(c); return }
        }
        live = Live(c.startMs, c.socStart, c.ah, c.wh, c.stop != null, c.watch)
    }

    /** Time left of the charge in progress. */
    fun predict(info: BmsBasicInfo): ChargePredictor.P? =
        ChargePredictor.predict(info.soc, info.fullCapacity, info.current, learn, rec?.watch, info.remainCapacity, cellOvV)

    /** The battery is gone for good (another one, or the app closes): save what there is. */
    fun close() { rec?.let { finish(it) } }

    private fun accumulate(c: Rec, now: Long, info: BmsBasicInfo, cells: List<Double>, dtS: Double) {
        c.endMs = now; c.socEnd = info.soc
        c.ah += info.current * dtS / 3600; c.wh += info.current * info.packVoltage * dtS / 3600; c.activeS += dtS
        c.maxV = max(c.maxV, info.packVoltage); c.minV = min(c.minV, info.packVoltage)
        c.maxCurrent = max(c.maxCurrent, info.current)
        (info.temperatures + info.mosTemperatures).maxOrNull()?.let { c.maxTemp = max(c.maxTemp, it) }
        if (info.balanceBits != 0L) c.balancingSeen = true
        if (cells.size > 1) {
            val mx = cells.max(); val mn = cells.min()
            if (mx > c.maxCellV) { c.maxCellV = mx; c.maxCellIdx = cells.indexOf(mx) + 1 }
            if (mn < c.minCellV) { c.minCellV = mn; c.minCellIdx = cells.indexOf(mn) + 1 }
            c.maxDeltaMv = max(c.maxDeltaMv, Math.round((mx - mn) * 1000).toInt())
        }
        if (now - c.lastPointMs >= POINT_EVERY_MS) { c.lastPointMs = now; c.points += Point(now, info.packVoltage, info.current, info.soc, null, null, null) }
    }

    private fun finish(c: Rec) {
        rec = null; live = null; gate.reset(); quietSince = 0L
        if (c.activeS < 180 || c.ah < 0.05) return
        val stop = c.stop ?: c.watch.lost()
        val d = JSONObject()
        d.put("soc_start", c.socStart).put("soc_end", c.socEnd).put("ah", c.ah).put("wh", c.wh).put("active_s", c.activeS)
            .put("max_v", c.maxV).put("min_v", if (c.minV == Double.MAX_VALUE) 0.0 else c.minV)
            .put("max_cell_v", c.maxCellV).put("max_cell_idx", c.maxCellIdx)
            .put("min_cell_v", if (c.minCellV == Double.MAX_VALUE) 0.0 else c.minCellV).put("min_cell_idx", c.minCellIdx)
            .put("max_delta_mv", c.maxDeltaMv).put("current_at_max_delta", 0.0).put("delta_paired", true)
            .put("max_temp", if (c.maxTemp < -50) JSONObject.NULL else c.maxTemp).put("max_current", c.maxCurrent)
            .put("start_min_cell", c.startMinCell)
            .put("avg_current", (c.activeS + c.gapMs / 1000.0).let { if (it > 0) c.ah * 3600 / it else 0.0 })
            .put("balancing_seen", c.balancingSeen)
            .put("ended_by_protection", c.endedByProtection || c.maxCellV >= cellOvV - 0.005)
            .put("cell_ov_limit", cellOvV).put("reached_full", c.socEnd >= 99).put("gap_s", c.gapMs / 1000)
            .put("source", "desktop")
        stop.let { r ->
            d.put("stop_reason", r.reason.name.lowercase())
            r.alarm?.let { d.put("stop_alarm", it) }
            d.put("soc_before_stop", r.socBefore).put("soc_after_stop", r.socShown)
            r.socCounted?.let { d.put("soc_counted", it) }
            d.put("stop_v", r.packV).put("stop_max_cell", r.maxCell).put("stop_max_idx", r.maxIdx)
                .put("stop_min_cell", r.minCell).put("stop_min_idx", r.minIdx).put("stop_delta_mv", r.deltaMv)
            r.earlyCell?.let { d.put("stop_early_cell", it) }
            if (r.topMain > 0) d.put("top_start", r.topStart).put("top_main", r.topMain).put("top_main_pct", r.topMainPct)
            if (r.reason == ChargeWatch.Stop.PROTECTION && c.gapMs == 0L && c.remainStart > 0 && full > 0)
                d.put("cut_left_ah", full - c.remainStart - c.ah)
        }
        c.etaFirstMin?.let { d.put("eta_first_min", it) }
        if (targetV > 0) d.put("target_v", targetV)
        if (targetSoc > 0) d.put("target_soc", targetSoc)
        onSaved(c.startMs, c.endMs, d, c.points.toList())
    }

    private fun scale(fullAh: Double) = if (fullAh > 0) (fullAh / 30.0).coerceIn(0.5, 5.0) else 1.0

    companion object {
        const val UNKNOWN_OV = 99.0
        private const val CHG_START_A = 0.3
        private const val CHG_END_A = 0.1
        private const val STOP_MS = 30_000L
        private const val SAVE_MS = 120_000L
        private const val MAX_DT_S = 10.0
        private const val POINT_EVERY_MS = 2_000L
    }
}
