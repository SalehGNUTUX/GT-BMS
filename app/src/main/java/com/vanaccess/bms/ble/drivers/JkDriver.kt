// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble.drivers

import com.vanaccess.bms.ble.BmsHardwareInfo

/**
 * JK-BMS (Jikong) BLE protocol "JK02" — 24S (fw < 11) and 32S (fw ≥ 11) layouts.
 *
 * Request (20 bytes): AA 55 90 EB <addr> <len> <value u32 LE> 00…00 <sum8 of bytes 0..18>
 *   0x97 = device info, 0x96 = start cell-info stream.
 * Response (300 bytes, sent as several notifications): 55 AA EB 90 <type> <counter> … <sum8>
 *   type 0x01 = settings, 0x02 = cell info, 0x03 = device info.
 * Offsets follow the community documentation (esphome-jk-bms / syssi).
 */
class JkDriver : BmsDriver {
    override val id = "jk"
    override val displayName = "JK-BMS (Jikong)"
    override val profiles = listOf(
        GattProfile("0000ffe0-0000-1000-8000-00805f9b34fb",
            "0000ffe1-0000-1000-8000-00805f9b34fb", "0000ffe1-0000-1000-8000-00805f9b34fb")
    )
    override val pollIntervalMs = 5000L
    override val interCommandDelayMs = 400L

    private val rx = RxBuffer(4096)
    private var is32s: Boolean? = null
    private var lastFrameAt = 0L
    private val header = byteArrayOf(0x55, 0xAA.toByte(), 0xEB.toByte(), 0x90.toByte())

    override fun matchesName(name: String?) = Bytes.startsWithAny(name, listOf("JK-", "JK_", "JK", "HELTEC"))

    override fun reset() { rx.clear(); is32s = null; lastFrameAt = 0L }

    override fun initCommands() = listOf(command(0x97), command(0x96))

    /** The BMS streams cell info by itself; re-request only when the stream stalls. */
    override fun pollCommands(): List<ByteArray> =
        if (System.currentTimeMillis() - lastFrameAt > 6000) listOf(command(0x96)) else emptyList()

    private fun command(addr: Int, value: Long = 0, len: Int = 0): ByteArray {
        val f = ByteArray(20)
        f[0] = 0xAA.toByte(); f[1] = 0x55; f[2] = 0x90.toByte(); f[3] = 0xEB.toByte()
        f[4] = addr.toByte(); f[5] = len.toByte()
        for (i in 0 until 4) f[6 + i] = ((value shr (8 * i)) and 0xFF).toByte()
        f[19] = Bytes.sum8(f, 0, 19).toByte()
        return f
    }

    override fun onNotification(data: ByteArray): DriverOutput? {
        // A notification starting with the header begins a new frame.
        if (data.size >= 4 && Bytes.indexOf(data, header) == 0) rx.clear()
        rx.append(data)
        val b = rx.bytes()
        val start = Bytes.indexOf(b, header)
        if (start < 0) { if (b.size > 400) rx.clear(); return null }
        if (start > 0) rx.drop(start)
        val f = rx.bytes()
        if (f.size < 300) return null
        val frame = f.copyOfRange(0, 300)
        rx.drop(300)
        if (Bytes.sum8(frame, 0, 299) != Bytes.u8(frame, 299)) return null
        return when (Bytes.u8(frame, 4)) {
            0x02 -> decodeCells(frame)
            0x03 -> decodeDevice(frame)
            else -> null
        }
    }

    private fun decodeDevice(f: ByteArray): DriverOutput {
        fun str(o: Int, n: Int) = String(f, o, n, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
        val model = str(6, 16)
        val hw = str(22, 8)
        val sw = str(30, 8)
        val major = sw.takeWhile { it.isDigit() }.toIntOrNull() ?: hw.takeWhile { it.isDigit() }.toIntOrNull()
        if (major != null) is32s = major >= 11
        return DriverOutput(hardware = BmsHardwareInfo("$model HW $hw SW $sw"))
    }

    private fun decodeCells(f: ByteArray): DriverOutput? {
        val use32 = is32s ?: guess32s(f)
        val o1 = if (use32) 16 else 0
        val o2 = o1 * 2
        val maxCells = 24 + o1 / 2
        val enabledMask = Bytes.u32le(f, 54 + o1)
        val cells = (0 until maxCells).mapNotNull { i ->
            val v = Bytes.u16le(f, 6 + i * 2) / 1000.0
            val enabled = enabledMask == 0L || (enabledMask shr i) and 1L == 1L
            if (enabled && v > 0.5) v else null
        }
        val voltage = Bytes.u32le(f, 118 + o2) / 1000.0
        if (voltage < 1.0 || voltage > 400.0) return null
        val current = Bytes.s32le(f, 126 + o2) / 1000.0
        val t1 = Bytes.s16le(f, 130 + o2) / 10.0
        val t2 = Bytes.s16le(f, 132 + o2) / 10.0
        val mos = if (use32) Bytes.s16le(f, 112 + o2) / 10.0 else Bytes.s16le(f, 134 + o2) / 10.0
        val errors = Bytes.u16le(f, 136 + o2)
        val soc = Bytes.u8(f, 141 + o2)
        val remain = Bytes.u32le(f, 142 + o2) / 1000.0
        val full = Bytes.u32le(f, 146 + o2) / 1000.0
        val cycles = Bytes.u32le(f, 150 + o2).toInt()
        val soh = Bytes.u8(f, 158 + o2).takeIf { it in 1..100 }
        val chg = Bytes.u8(f, 166 + o2) != 0
        val dsg = Bytes.u8(f, 167 + o2) != 0
        lastFrameAt = System.currentTimeMillis()
        val info = basicInfo(
            source = id, voltage = voltage, current = current, soc = soc,
            remainAh = remain, fullAh = full, cycles = cycles, cellCount = cells.size,
            temps = listOf(t1, t2).filter { it > -40 && it < 120 }, mosTemp = mos,
            chargeFet = chg, dischargeFet = dsg, alarms = alarms(errors), soh = soh, raw = f
        )
        return DriverOutput(basic = info, cells = cells)
    }

    /** Pick the layout whose pack voltage best matches the sum of its cell voltages. */
    private fun guess32s(f: ByteArray): Boolean {
        fun err(o1: Int): Double {
            val o2 = o1 * 2
            val sum = (0 until 24 + o1 / 2).sumOf { Bytes.u16le(f, 6 + it * 2) } / 1000.0
            val pack = Bytes.u32le(f, 118 + o2) / 1000.0
            return if (pack < 1.0) Double.MAX_VALUE else kotlin.math.abs(sum - pack)
        }
        return err(16) < err(0)
    }

    private fun alarms(bits: Int): List<String> {
        val names = listOf(
            "Balance wire resistance", "MOS over-temperature", "Cell count mismatch",
            "Current sensor error", "Cell overvoltage", "Pack overvoltage",
            "Charge overcurrent", "Charge short circuit", "Charge over-temperature",
            "Charge under-temperature", "Internal communication error", "Cell undervoltage",
            "Pack undervoltage", "Discharge overcurrent", "Discharge short circuit",
            "Discharge over-temperature"
        )
        return names.filterIndexed { i, _ -> bits and (1 shl i) != 0 }
    }
}
