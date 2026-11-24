// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import kotlin.math.max

/**
 * Follows one charge, reading by reading: what the displayed SOC was before the BMS jumped it
 * to 100 % at the cut, how fast the highest cell rises, and what stopped the charge.
 *
 * Facts it is built on (23S LiFePO4, 5.1 A charger): the current stays constant until the
 * protection opens the charge switch, the BMS counter then jumps (28.4 → 29.9 Ah) while the
 * charge actually put in points to ≈ 99 %, and the cells rise slowly for an hour and then by
 * > 100 mV per minute in the last one. Pure, unit-tested.
 */
class ChargeWatch(private val startMs: Long, val socStart: Int, private val remainStart: Double) {

    enum class Stop { PROTECTION, CHARGER, INTERRUPTED, LOST }

    data class Result(
        val reason: Stop,
        val alarm: String?,             // BMS alarm active at the stop, if any
        val socBefore: Int,             // SOC shown before the stop (before any jump)
        val socShown: Int,              // SOC shown after it
        val socCounted: Int?,           // from the Ah measured since the start
        val packV: Double,
        val maxCell: Double, val maxIdx: Int, val minCell: Double, val minIdx: Int,
        val deltaMv: Int,
        val earlyCell: Double?,         // highest cell EARLY_MS before the stop
    )

    private class S(val t: Long, val maxCell: Double)

    companion object {
        const val EARLY_MS = 5 * 60_000L
        private const val KEEP_MS = 8 * 60_000L
        private const val SLOPE_MS = 12_000L
        /** Only this close to the protection does the slope predict anything. */
        private const val NEAR_V = 0.10
    }

    private val buf = ArrayDeque<S>()
    private var lastT = 0L
    private var soc = socStart
    private var remain = remainStart
    private var full = 0.0
    private var peakI = 0.0
    private val tailI = ArrayDeque<Pair<Long, Double>>()
    private var packV = 0.0
    private var maxCell = 0.0; private var maxIdx = 0
    private var minCell = 0.0; private var minIdx = 0
    private var socBeforeJump: Int? = null
    private var fetWasOn = false
    var ah = 0.0; private set

    val nearestCell get() = maxIdx
    val highest get() = maxCell

    /** A reading with charge current. [ahSoFar] is the recorder's own count. */
    fun charging(now: Long, socNow: Int, remainNow: Double, fullAh: Double, current: Double, pack: Double,
                 cells: List<Double>, chargeFetOn: Boolean, ahSoFar: Double) {
        noteJump(now, socNow, remainNow, fullAh)
        full = fullAh; ah = ahSoFar; packV = pack; lastT = now
        peakI = max(peakI, current)
        tailI += now to current
        while (tailI.isNotEmpty() && now - tailI.first().first > 120_000) tailI.removeFirst()
        if (chargeFetOn) fetWasOn = true
        if (cells.size > 1) {
            maxCell = cells.max(); maxIdx = cells.indexOf(maxCell) + 1
            minCell = cells.min(); minIdx = cells.indexOf(minCell) + 1
            buf += S(now, maxCell)
            while (buf.isNotEmpty() && now - buf.first().t > KEEP_MS) buf.removeFirst()
        }
    }

    /** More SOC than the current can have put in since the last reading: the BMS recalibrated. */
    private fun noteJump(now: Long, socNow: Int, remainNow: Double, fullAh: Double) {
        if (lastT != 0L && socBeforeJump == null && fullAh > 0) {
            val possible = peakI * (now - lastT) / 3_600_000.0 * 1.3
            val gained = if (remain > 0 && remainNow > 0) remainNow - remain else (socNow - soc) / 100.0 * fullAh
            if (gained > possible + max(0.2, fullAh * 0.015)) socBeforeJump = soc
        }
        soc = socNow; remain = remainNow
    }

    /** Rise of the highest cell over the last seconds, V/s. */
    private fun slope(): Double? {
        val last = buf.lastOrNull() ?: return null
        val first = buf.firstOrNull { last.t - it.t <= SLOPE_MS } ?: return null
        val dt = (last.t - first.t) / 1000.0
        return if (dt >= 5) (last.maxCell - first.maxCell) / dt else null
    }

    /** Seconds until the highest cell reaches [ovV]; null unless it is close and rising. */
    fun secondsToCut(ovV: Double): Double? {
        if (maxCell <= 0 || maxCell < ovV - NEAR_V) return null
        if (maxCell >= ovV) return 0.0
        val s = slope() ?: return null
        if (s <= 0.00005) return null             // not rising: balancing or tapering
        return (ovV - maxCell) / s
    }

    fun counted(fullAh: Double): Int? =
        if (fullAh > 0) (socStart + ah / fullAh * 100).toInt().coerceIn(0, 100) else null

    /** The current is gone. [alarms] are the BMS alarm texts active now; [ovV] the cell protection. */
    fun stopped(now: Long, socNow: Int, remainNow: Double, fullAh: Double, chargeFetOn: Boolean,
                alarms: List<String>, ovV: Double): Result {
        noteJump(now, socNow, remainNow, fullAh)
        val alarm = alarms.firstOrNull { it.contains("overvoltage", true) || it.contains("charge", true) && !it.contains("discharge", true) }
        val tail = tailI.map { it.second }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val reason = when {
            (maxCell > 0 && maxCell >= ovV - 0.015) || alarm != null || (fetWasOn && !chargeFetOn) -> Stop.PROTECTION
            tail <= peakI * 0.5 || socNow >= 99 -> Stop.CHARGER
            else -> Stop.INTERRUPTED
        }
        return result(reason, alarm, socNow, fullAh)
    }

    /** The battery went silent during the charge. */
    fun lost() = result(Stop.LOST, null, soc, full)

    private fun result(r: Stop, alarm: String?, socNow: Int, fullAh: Double) = Result(r, alarm,
        socBeforeJump ?: soc, socNow, counted(fullAh), packV, maxCell, maxIdx, minCell, minIdx,
        Math.round((maxCell - minCell) * 1000).toInt(),
        buf.lastOrNull { lastT - it.t >= EARLY_MS - 5_000 }?.takeIf { lastT - it.t <= EARLY_MS + 60_000 }?.maxCell)
}
