// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.alarm

import com.vanaccess.bms.reports.BatteryModel
import com.vanaccess.bms.reports.ChargeAdvice

/**
 * Are the alarm values possible for *this* battery? Limits come from its own cell count and
 * protections; unknown limits are not checked. 0 always means "this condition is off".
 */
object AlarmCheck {
    private class Span(val low: Double, val full: Double, val empty: Double)

    /** Sensible cell voltages for the chemistry behind this protection; null = unknown, not judged. */
    private fun span(ovMv: Int): Span? = when (BatteryModel.chemistryOf(ovMv)) {
        BatteryModel.LFP -> Span(3.30, 3.35, 2.50)
        BatteryModel.LION -> Span(3.70, 3.90, 2.80)
        BatteryModel.LTO -> Span(2.30, 2.45, 1.80)
        else -> null
    }

    /** Allowed range, or null when the value is fine. */
    fun highVoltage(v: Double, cells: Int, ovMv: Int): Pair<Double, Double>? {
        val sp = span(ovMv)
        if (v == 0.0 || cells <= 0 || sp == null) return null
        val r = cells * sp.low to cells * ovMv / 1000.0 - 0.1
        return if (v in r.first..r.second) null else r
    }

    fun lowVoltage(v: Double, cells: Int, ovMv: Int, uvMv: Int?): Pair<Double, Double>? {
        val sp = span(ovMv)
        if (v == 0.0 || cells <= 0 || sp == null) return null
        val r = cells * (uvMv?.let { it / 1000.0 } ?: sp.empty) to cells * sp.full
        return if (v in r.first..r.second) null else r
    }

    fun cellVoltage(v: Double, ovMv: Int): Pair<Double, Double>? {
        val sp = span(ovMv)
        if (v == 0.0 || sp == null) return null
        val r = sp.low to ovMv / 1000.0 - 0.01
        return if (v in r.first..r.second) null else r
    }

    fun highSoc(p: Int) = p == 0 || p in 10..100
    fun lowSoc(p: Int) = p == 0 || p in 5..80

    /** A full-charge voltage at or above the protection can never be reached: use the advised one. */
    fun effectiveHighVoltage(v: Double, cells: Int, ovMv: Int): Double {
        if (v <= 0) return 0.0
        val advice = ChargeAdvice.from(cells, ovMv) ?: return v
        return if (v >= advice.protectionPackV - 0.05) advice.alarmPackV else v
    }
}
