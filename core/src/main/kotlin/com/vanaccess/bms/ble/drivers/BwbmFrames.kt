// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble.drivers

/**
 * Builds Bestway (0x7F) realtime frames, the layout [BwbmDriver] reads: for tests and for the
 * desktop charge simulator. Never sent to a battery.
 */
object BwbmFrames {
    fun realtime(cellsMv: List<Int>, currentA: Double, remainAh: Double, fullAh: Double, cycles: Int = 0,
                 temps: List<Int> = listOf(20), mosTemps: List<Int> = listOf(22),
                 chargeFet: Boolean = true, dischargeFet: Boolean = true, overVoltage: Boolean = false): ByteArray {
        val n = cellsMv.size
        val b = mutableListOf(0x7F, 0x10, 0x02, 0x00, 0x12)
        // status: bit0 charging, bit4 discharging; pack overvoltage = byte 6 bit 6 (see BwbmDriver.alarmTexts)
        b += listOf(when { currentA > 0.05 -> 0x01; currentA < -0.05 -> 0x10; else -> 0 }, if (overVoltage) 0x40 else 0, 0, 0)
        val cur = Math.round(kotlin.math.abs(currentA) * 10).toInt() and 0xFFFF
        b += listOf(cur and 0xFF, cur shr 8)
        b += n
        cellsMv.forEach { b += listOf(it and 0xFF, (it shr 8) and 0xFF) }
        repeat(when { n <= 8 -> 1; n <= 16 -> 2; n <= 24 -> 3; else -> 4 }) { b += 0 }
        b += temps.size; temps.forEach { b += it and 0xFF }
        b += mosTemps.size; mosTemps.forEach { b += it and 0xFF }
        b += listOf(cycles and 0xFF, cycles shr 8)
        val r = Math.round(remainAh * 10).toInt(); val f = Math.round(fullAh * 10).toInt()
        b += listOf(r and 0xFF, r shr 8, f and 0xFF, f shr 8)
        b += (if (chargeFet) 0x40 else 0) or (if (dischargeFet) 0x80 else 0)
        b += 0
        b[3] = b.size
        b[b.size - 1] = (0x100 - (b.dropLast(1).sum() and 0xFF)) and 0xFF
        return ByteArray(b.size) { b[it].toByte() }
    }

    /** Split as a BLE link delivers it (20-byte notifications). */
    fun notifications(frame: ByteArray, mtu: Int = 20): List<ByteArray> = frame.toList().chunked(mtu).map { it.toByteArray() }
}
