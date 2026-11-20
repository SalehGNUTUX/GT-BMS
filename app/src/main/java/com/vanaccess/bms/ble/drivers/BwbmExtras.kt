// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble.drivers

import java.util.Locale

/**
 * Read-only decoding of the BWBM 0x19 parameter frame (102 bytes), labels and scaling as
 * shown by the vendor app (ParamsActivity). Group/label strings are English keys localised
 * by the UI (see ui/L10n).
 */
object BwbmParams {
    const val G_TEMP = "Temperature protection"
    const val G_VOLT = "Voltage protection"
    const val G_CURR = "Current protection"
    const val G_BAL = "Balancing & calibration"
    const val G_PACK = "Pack configuration"

    fun decode(f: ByteArray): List<ParamItem>? {
        if (f.size != 0x66 || Bytes.u8(f, 4) != 0x19) return null
        val out = mutableListOf<ParamItem>()
        fun u8(o: Int) = Bytes.u8(f, o)
        fun s8(o: Int) = f[o].toInt()
        fun u16(o: Int) = Bytes.u16le(f, o)
        fun add(g: String, l: String, v: String) { out += ParamItem(g, l, v) }
        fun c(v: Int) = "$v °C"
        fun mv(o: Int) = "${u16(o)} mV"
        fun v2(o: Int) = String.format(Locale.US, "%.2f V", u16(o) * 0.01)
        fun a1(o: Int) = String.format(Locale.US, "%.1f A", u16(o) * 0.1)

        add(G_TEMP, "MOS over-temperature protection", c(u8(5)))
        add(G_TEMP, "MOS over-temperature release", c(u8(6)))
        add(G_TEMP, "MOS temperature difference protection", c(u8(7)))
        add(G_TEMP, "MOS temperature difference release", c(u8(8)))
        add(G_TEMP, "Charge over-temperature protection", c(u8(9)))
        add(G_TEMP, "Charge over-temperature release", c(u8(10)))
        add(G_TEMP, "Discharge over-temperature protection", c(u8(11)))
        add(G_TEMP, "Discharge over-temperature release", c(u8(12)))
        add(G_TEMP, "Charge low-temperature protection", c(s8(13)))
        add(G_TEMP, "Charge low-temperature release", c(s8(14)))
        add(G_TEMP, "Discharge low-temperature protection", c(s8(15)))
        add(G_TEMP, "Discharge low-temperature release", c(s8(16)))
        add(G_TEMP, "Charge temperature difference protection", c(u8(17)))
        add(G_TEMP, "Charge temperature difference release", c(u8(18)))
        add(G_TEMP, "Discharge temperature difference protection", c(u8(19)))
        add(G_TEMP, "Discharge temperature difference release", c(u8(20)))

        add(G_VOLT, "Pack overvoltage protection", v2(25))
        add(G_VOLT, "Pack overvoltage release", v2(27))
        add(G_VOLT, "Pack undervoltage protection", v2(29))
        add(G_VOLT, "Pack undervoltage release", v2(31))
        add(G_VOLT, "Cell overvoltage protection (level 1)", mv(33))
        add(G_VOLT, "Cell overvoltage release", mv(35))
        add(G_VOLT, "Cell undervoltage protection (level 1)", mv(37))
        add(G_VOLT, "Cell undervoltage release", mv(39))
        add(G_VOLT, "Cell overvoltage protection (level 2)", mv(41))
        add(G_VOLT, "Cell undervoltage protection (level 2)", mv(43))
        add(G_VOLT, "Cell shutdown voltage", mv(53))
        add(G_VOLT, "Cell charge-inhibit voltage", mv(55))
        add(G_VOLT, "Cell charge-inhibit voltage difference", mv(57))

        val cAlm = u16(59); val dAlm = u16(63)
        add(G_CURR, "Charge overcurrent activation", "${(cAlm * 0.1).toInt()} A")
        add(G_CURR, "Charge overcurrent threshold", "${(((u16(61) / 10) / 4 + cAlm / 10) * 10 * 0.1).toInt()} A")
        add(G_CURR, "Discharge overcurrent activation", "${(dAlm * 0.1).toInt()} A")
        add(G_CURR, "Discharge overcurrent threshold", "${(((u16(65) / 10) / 12 + dAlm / 10) * 10 * 0.1).toInt()} A")
        add(G_CURR, "Secondary overcurrent voltage", "${u8(67)} mV")
        add(G_CURR, "Short-circuit trigger voltage", "${u8(68)} mV")
        add(G_CURR, "Secondary overcurrent delay", "${u16(69)} ms")
        add(G_CURR, "Short-circuit delay", "${u16(71)} µs")
        add(G_CURR, "Valid current threshold (low)", a1(81))
        add(G_CURR, "Valid current threshold (high)", a1(83))
        add(G_CURR, "Current sense resistor", "${u16(85)} µΩ")

        add(G_BAL, "Balance start voltage", mv(45))
        add(G_BAL, "Balance voltage difference", mv(47))
        add(G_BAL, "SOC charge correction voltage", mv(49))
        add(G_BAL, "SOC discharge correction voltage", mv(51))

        add(G_PACK, "Design capacity", String.format(Locale.US, "%.1f Ah", u16(23) * 0.1))
        add(G_PACK, "Cell count", "${u8(87)}")
        add(G_PACK, "Cell temperature sensors", "${u8(88)}")
        add(G_PACK, "MOS temperature sensors", "${u8(89)}")
        add(G_PACK, "Empty-state cell impedance", "${u8(21)} mΩ")
        add(G_PACK, "Full-state cell impedance", "${u8(22)} mΩ")
        val fs2 = u8(92)
        add(G_PACK, "Sleep", onOff(fs2 and 0x01 != 0))
        add(G_PACK, "Charging handshake", onOff(fs2 and 0x02 != 0))
        add(G_PACK, "Heating", onOff(fs2 and 0x04 != 0))
        add(G_PACK, "External temperature sensor", onOff(fs2 and 0x80 != 0))
        return out
    }

    /** Keys localised by the UI. */
    private fun onOff(b: Boolean) = if (b) "@on" else "@off"
}

/**
 * BWBM history download (cmd 0x23): "23 00" returns the first record, "23 01" the next one.
 * Reply: r5 flag (1 = record, 2 = last), r6..r11 BCD YY MM DD hh mm ss, r14..r17 status bits
 * (same table as live data, except r15 bit4 = "Battery full"), r20.. same layout as live r11..
 */
class BwbmHistory {
    private var active = false
    private var done = false
    private val seen = HashSet<String>()
    private var lastAt = 0L

    fun reset() { active = false; done = false; seen.clear() }

    fun startCommands(d: BwbmDriver): List<ByteArray> {
        active = true; done = false; seen.clear(); lastAt = System.currentTimeMillis()
        return listOf(d.build(0x23, byteArrayOf(0x00)))
    }

    /** Returns (output, next command to queue). */
    fun onFrame(f: ByteArray, d: BwbmDriver): Pair<DriverOutput?, ByteArray?>? {
        if (!active || f.size < 18) return null
        val flag = Bytes.u8(f, 5)
        if (flag != 1 && flag != 2) return null
        val next = if (flag == 1) d.build(0x23, byteArrayOf(0x01)) else null
        fun bcd(i: Int) = "%02X".format(Bytes.u8(f, i))
        val ts = "20${bcd(6)}-${bcd(7)}-${bcd(8)} ${bcd(9)}:${bcd(10)}:${bcd(11)}"
        val events = events(Bytes.u8(f, 14), Bytes.u8(f, 15), Bytes.u8(f, 16), Bytes.u8(f, 17))
        val extra = soc(f)
        val key = ts + events.joinToString()
        val rec = if (seen.add(key)) listOf(HistoryRecord(ts, events, extra)) else emptyList()
        if (flag == 2) { active = false; done = true }
        return DriverOutput(history = rec, historyDone = flag == 2) to next
    }

    private fun soc(f: ByteArray): String {
        return try {
            val n = Bytes.u8(f, 20)
            if (n !in 1..32) return ""
            val nb = when { n <= 8 -> 1; n <= 16 -> 2; n <= 24 -> 3; else -> 4 }
            val a = 21 + 2 * n + nb
            val b = a + Bytes.u8(f, a) + 1
            val c = b + Bytes.u8(f, b) + 1
            if (c + 5 >= f.size) return ""
            val v = (0 until n).sumOf { Bytes.u16le(f, 21 + it * 2) } / 1000.0
            val rem = Bytes.u16le(f, c + 2); val full = Bytes.u16le(f, c + 4)
            val soc = if (full > 0) rem * 100 / full else 0
            String.format(Locale.US, "%.2f V • %d%%", v, soc)
        } catch (_: Exception) { "" }
    }

    companion object {
        fun events(s0: Int, s1: Int, s2: Int, s3: Int): List<String> {
            val out = mutableListOf<String>()
            fun bit(v: Int, b: Int, t: String) { if (v and (1 shl b) != 0) out += t }
            bit(s0, 0, "Charging"); bit(s0, 4, "Discharging")
            bit(s1, 4, "Battery full")
            out += BwbmDriver.alarmTexts(s0, s1, s2, s3)
            return out
        }
    }
}
