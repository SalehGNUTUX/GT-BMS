// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble.drivers

/**
 * Daly smart BMS — BLE "Modbus" protocol (Daly app ≥ 2022, devices named DL-xxxx).
 *
 * Request : D2 03 00 00 00 3E D7 B9   (read 62 registers from 0x0000, CRC16/Modbus LE)
 * Response: D2 03 7C <124 data bytes> <crc LE>
 * All fields big-endian. Offsets are relative to the frame start (3 header bytes).
 */
class DalyDriver : BmsDriver {
    override val id = "daly"
    override val displayName = "Daly"
    override val profiles = listOf(
        GattProfile("0000fff0-0000-1000-8000-00805f9b34fb",
            "0000fff2-0000-1000-8000-00805f9b34fb", "0000fff1-0000-1000-8000-00805f9b34fb")
    )
    private val rx = RxBuffer()
    private val cmdInfo = byteArrayOf(0xD2.toByte(), 0x03, 0x00, 0x00, 0x00, 0x3E, 0xD7.toByte(), 0xB9.toByte())

    override fun matchesName(name: String?) = Bytes.startsWithAny(name, listOf("DL-", "DALY"))
    override fun reset() = rx.clear()
    override fun pollCommands() = listOf(cmdInfo)

    override fun onNotification(data: ByteArray): DriverOutput? {
        rx.append(data)
        val b = rx.bytes()
        val start = Bytes.indexOf(b, byteArrayOf(0xD2.toByte(), 0x03))
        if (start < 0) { if (b.size > 300) rx.clear(); return null }
        if (start > 0) rx.drop(start)
        val f = rx.bytes()
        if (f.size < 3) return null
        val len = Bytes.u8(f, 2)
        val total = 3 + len + 2
        if (f.size < total) return null
        val frame = f.copyOfRange(0, total)
        rx.drop(total)
        if (Bytes.crc16Modbus(frame, 0, total - 2) != Bytes.u16le(frame, total - 2)) return null
        if (len < 0x7C) return null
        return decode(frame)
    }

    private fun decode(f: ByteArray): DriverOutput? {
        val h = 3
        val cellCount = Bytes.u16be(f, 98 + h).coerceIn(0, 32)
        val tempCount = Bytes.u16be(f, 100 + h).coerceIn(0, 8)
        val cells = (0 until cellCount).map { Bytes.u16be(f, h + it * 2) / 1000.0 }
        val temps = (0 until tempCount).map { (Bytes.u16be(f, 64 + h + it * 2) - 40).toDouble() }
        val voltage = Bytes.u16be(f, 80 + h) / 10.0
        if (voltage <= 0.0) return null
        val current = (Bytes.u16be(f, 82 + h) - 30000) / 10.0
        val soc = (Bytes.u16be(f, 84 + h) / 10.0).toInt()
        val remain = Bytes.u16be(f, 96 + h) / 10.0
        val cycles = Bytes.u16be(f, 102 + h)
        val problem = (0 until 8).any { Bytes.u8(f, 116 + h + it) != 0 }
        val full = if (soc > 0) remain * 100.0 / soc else 0.0
        val info = basicInfo(
            source = id, voltage = voltage, current = current, soc = soc,
            remainAh = remain, fullAh = full, cycles = cycles, cellCount = cells.size,
            temps = temps, alarms = if (problem) listOf("BMS fault code active") else emptyList(), raw = f
        )
        return DriverOutput(basic = info, cells = cells)
    }
}
