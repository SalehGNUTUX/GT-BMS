// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import com.vanaccess.bms.ble.drivers.ParamItem
import org.json.JSONObject

/**
 * The battery's own protection limits, read from the BMS parameters (Bestway 0x19).
 * Recommendations use these instead of fixed numbers, so advice matches the pack.
 * Null fields mean "not reported by this BMS".
 */
data class BmsLimits(
    val cellOvMv: Int? = null,          // cell over-voltage protection (level 1)
    val cellUvMv: Int? = null,          // cell under-voltage protection (level 1)
    val cellUvReleaseMv: Int? = null,
    val socLowCalMv: Int? = null,       // SOC discharge-correction voltage (≈ empty point)
    val balanceStartMv: Int? = null,
    val balanceDiffMv: Int? = null,
    val chargeOtC: Int? = null,
    val dischargeOtC: Int? = null,
    val chargeLowC: Int? = null,
    val mosOtC: Int? = null,
    val dischargeOcA: Int? = null,
    val designAh: Double? = null,
    val cells: Int? = null,
) {
    val lfp: Boolean get() = (cellOvMv ?: 0) in 1..3800

    fun toJson(): String = JSONObject().apply {
        fun p(k: String, v: Any?) { if (v != null) put(k, v) }
        p("ov", cellOvMv); p("uv", cellUvMv); p("uvr", cellUvReleaseMv); p("socl", socLowCalMv)
        p("bs", balanceStartMv); p("bd", balanceDiffMv); p("cot", chargeOtC); p("dot", dischargeOtC)
        p("clt", chargeLowC); p("mot", mosOtC); p("doc", dischargeOcA); p("des", designAh); p("n", cells)
    }.toString()

    companion object {
        fun fromJson(s: String?): BmsLimits {
            if (s.isNullOrBlank()) return BmsLimits()
            return try {
                val o = JSONObject(s)
                fun i(k: String) = if (o.has(k)) o.getInt(k) else null
                BmsLimits(i("ov"), i("uv"), i("uvr"), i("socl"), i("bs"), i("bd"), i("cot"), i("dot"),
                    i("clt"), i("mot"), i("doc"), if (o.has("des")) o.getDouble("des") else null, i("n"))
            } catch (_: Exception) { BmsLimits() }
        }

        /** Parse the labelled parameter rows produced by the BWBM driver. */
        fun fromParams(params: List<ParamItem>): BmsLimits {
            fun num(label: String): Double? = params.firstOrNull { it.label == label }?.value
                ?.trim()?.substringBefore(' ')?.toDoubleOrNull()
            fun int(label: String) = num(label)?.toInt()
            return BmsLimits(
                cellOvMv = int("Cell overvoltage protection (level 1)"),
                cellUvMv = int("Cell undervoltage protection (level 1)"),
                cellUvReleaseMv = int("Cell undervoltage release"),
                socLowCalMv = int("SOC discharge correction voltage"),
                balanceStartMv = int("Balance start voltage"),
                balanceDiffMv = int("Balance voltage difference"),
                chargeOtC = int("Charge over-temperature protection"),
                dischargeOtC = int("Discharge over-temperature protection"),
                chargeLowC = int("Charge low-temperature protection"),
                mosOtC = int("MOS over-temperature protection"),
                dischargeOcA = int("Discharge overcurrent activation"),
                designAh = num("Design capacity"),
                cells = int("Cell count"),
            )
        }
    }
}
