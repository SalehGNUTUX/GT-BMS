// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import com.vanaccess.bms.ble.BmsLink
import com.vanaccess.bms.ble.drivers.BwbmFrames
import com.vanaccess.bms.reports.ChargeSim

/**
 * A pretend battery on a charger, answering the Bestway realtime poll with frames of [ChargeSim].
 * Its clock moves [stepS] seconds per reading (one reading ≈ every 2 s), so a whole charge, its cut
 * and its alarms can be watched in minutes while the analysis sees readings as close as a real link gives.
 */
class SimChargeLink(private val sim: ChargeSim, private val fullAh: Double, private val stepS: Int = 10) : BmsLink {
    override var onNotify: ((ByteArray) -> Unit)? = null
    @Volatile private var virtual = System.currentTimeMillis()

    /** The simulated clock. */
    fun now(): Long = virtual

    override fun write(bytes: ByteArray) {
        // only the realtime poll (…06 12 …) is answered, like the battery
        if (bytes.size < 5 || bytes[4] != 0x12.toByte()) return
        virtual += stepS * 1000L
        sim.step(stepS.toDouble())
        val frame = BwbmFrames.realtime(sim.cells.map { Math.round(it * 1000).toInt() }, sim.current, sim.remainAh, fullAh,
            cycles = 4, temps = listOf(17, 18, 16), mosTemps = listOf(19, 19), chargeFet = !sim.cut, overVoltage = sim.cut)
        BwbmFrames.notifications(frame).forEach { onNotify?.invoke(it) }
    }

    override fun close() {}
}
