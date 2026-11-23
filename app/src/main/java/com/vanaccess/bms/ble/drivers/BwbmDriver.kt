// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble.drivers

import com.vanaccess.bms.ble.BmsHardwareInfo

/**
 * Bestway / BW "62xx" BMS (BLE names BWBM-…, app package bluetooth.BMS).
 * Reverse-engineered from the vendor app (HomeFragment.RecvCommdataDecode62xx).
 *
 * GATT: service FFE0, characteristic FFE1 for both write and notify.
 *
 * Frame (both directions):
 *   7F <addr=0x10> 02 <LEN = total bytes incl. checksum> <CMD> <data…> <CS>
 *   CS = (0x100 - Σ bytes[0..LEN-2]) & 0xFF   → all bytes sum to 0 mod 256
 *
 * Read-only commands used here:
 *   0x12 realtime data   7F 10 02 06 12 57
 *   0x20 model/HW/SW     7F 10 02 06 20 49
 *   0x21 BMS serial      7F 10 02 06 21 48
 *
 * 0x12 reply (little-endian):
 *   r5..r8 status/alarm bits, r9-10 current int16 ×0.1 A, r11 = N cells,
 *   r12.. N × uint16 cell mV, nb balance bytes (1..4 depending on N),
 *   T + T × int8 cell temps °C, M + M × int8 MOS temps °C,
 *   cycles u16, remaining ×0.1 Ah u16, full ×0.1 Ah u16, MOS byte (0x80 DSG, 0x40 CHG, 0x10 heating)
 *   Pack voltage = Σ cells, SOC = remaining / full (the vendor app computes it the same way).
 */
class BwbmDriver : BmsDriver {
    override val id = "bwbm"
    override val displayName = "Bestway BWBM (7F)"
    override val profiles = listOf(
        GattProfile("0000ffe0-0000-1000-8000-00805f9b34fb",
            "0000ffe1-0000-1000-8000-00805f9b34fb", "0000ffe1-0000-1000-8000-00805f9b34fb")
    )
    override val pollIntervalMs = 1000L
    override val interCommandDelayMs = 500L

    override val features = setOf(Feature.DETAILS, Feature.PARAMS, Feature.HISTORY, Feature.CLOCK)

    private val addr = 0x10
    private val rx = RxBuffer()
    private var csSeenValid = false
    private var csFailStreak = 0
    private var cycle = 0

    /** One-shot identity/parameter reads still to do, with remaining retries. */
    private val pendingInfo = linkedMapOf<Int, Int>()
    /** Commands queued from outside (history download), sent one per cycle. */
    private val queue = ArrayDeque<ByteArray>()
    private val history = BwbmHistory()

    override fun matchesName(name: String?) = Bytes.startsWithAny(name, listOf("BWBM", "BW-", "BW_", "BW"))

    override fun reset() {
        rx.clear(); csFailStreak = 0; cycle = 0; queue.clear(); history.reset()
        pendingInfo.clear()
        // model, BMS serial, pack name/serial, clock, parameters — each retried a few times
        listOf(0x20, 0x21, 0x25, 0x22, 0x19).forEach { pendingInfo[it] = 4 }
    }

    init { reset() }

    /**
     * The BMS answers one request at a time, so each cycle sends at most one "extra" read
     * (identity / params / clock / history) followed by the realtime request.
     */
    override fun pollCommands(): List<ByteArray> {
        cycle++
        val extra: ByteArray? = when {
            queue.isNotEmpty() -> queue.removeFirst()
            pendingInfo.isNotEmpty() -> {
                val cmd = pendingInfo.keys.first()
                val left = pendingInfo.getValue(cmd) - 1
                if (left <= 0) pendingInfo.remove(cmd) else {
                    // rotate so one silent command doesn't block the others
                    pendingInfo.remove(cmd); pendingInfo[cmd] = left
                }
                build(cmd)
            }
            cycle % 15 == 0 -> build(0x22)      // refresh the BMS clock every ~15 s
            else -> null
        }
        return listOfNotNull(extra, build(0x12))
    }

    override fun historyCommands(): List<ByteArray> {
        history.reset()
        val cmds = history.startCommands(this)
        queue.clear(); queue.addAll(cmds)
        return emptyList()
    }

    override fun cancelHistory() {
        history.reset()                                    // ignore further 0x23 replies, queue no "next"
        queue.removeAll { it.size > 4 && it[4] == 0x23.toByte() }
    }

    fun build(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val f = ByteArray(6 + data.size)
        f[0] = 0x7F; f[1] = addr.toByte(); f[2] = 0x02; f[3] = f.size.toByte(); f[4] = cmd.toByte()
        data.copyInto(f, 5)
        f[f.size - 1] = ((0x100 - Bytes.sum8(f, 0, f.size - 1)) and 0xFF).toByte()
        return f
    }

    override fun onNotification(data: ByteArray): DriverOutput? {
        rx.append(data)
        var out: DriverOutput? = null
        val head = byteArrayOf(0x7F, addr.toByte(), 0x02)
        while (true) {
            val b = rx.bytes()
            val start = Bytes.indexOf(b, head)
            if (start < 0) { if (b.size > 2) rx.drop(b.size - 2); break }
            if (start > 0) { rx.drop(start); continue }
            if (b.size < 5) break
            val len = Bytes.u8(b, 3)
            if (len < 6) { rx.drop(1); continue }
            if (b.size < len) break
            val frame = b.copyOfRange(0, len)
            // Checksum: all bytes sum to 0. Duplicated/missing notifications break it; in that
            // case drop just the header byte and resync on the next 7F 10 02.
            val csOk = Bytes.sum8(frame, 0, len) == 0
            if (csOk) { csSeenValid = true; csFailStreak = 0 } else csFailStreak++
            // Fallback for firmwares that would not fill the checksum at all.
            val accept = csOk || (!csSeenValid && csFailStreak >= 5)
            if (!accept) { rx.drop(1); continue }
            rx.drop(len)
            val cmd = Bytes.u8(frame, 4)
            if (cmd in pendingInfo) pendingInfo.remove(cmd)
            val res = when (cmd) {
                0x12 -> decodeRealtime(frame)
                0x20 -> decodeModel(frame)
                0x21 -> DriverOutput(details = mapOf(DetailKey.SERIAL to ascii(frame, 5, len - 1)))
                0x25 -> decodePack(frame)
                0x22 -> decodeClock(frame)
                0x19 -> BwbmParams.decode(frame)?.let { DriverOutput(params = it) }
                0x23 -> history.onFrame(frame, this)?.also { r -> r.second?.let { queue.addLast(it) } }?.first
                else -> null
            }
            out = JbdDriver.merge(out, res)
        }
        return out
    }

    private fun ascii(f: ByteArray, from: Int, toExclusive: Int): String =
        String(ByteArray(maxOf(0, toExclusive - from)) { f[from + it] }
            .filter { it in 0x20..0x7E }.toByteArray(), Charsets.US_ASCII).trim()

    /** "<model> H:v21 F:V25_R18_…" → model, HW "2.1", SW "2.5_R18_…" (same as the vendor app). */
    private fun decodeModel(f: ByteArray): DriverOutput {
        val text = ascii(f, 5, f.size - 1)
        val parts = text.split(" ").filter { it.isNotBlank() }
        val model = parts.firstOrNull { it.startsWith("BW", ignoreCase = true) } ?: parts.firstOrNull() ?: text
        fun ver(prefix: String): String? = parts.firstOrNull { it.startsWith(prefix, ignoreCase = true) }
            ?.substringAfter(':')?.let { v ->
                val digits = v.drop(1)               // "v21" → "21", "V25_R18…" → "25_R18…"
                val head = digits.takeWhile { it.isDigit() }
                if (head.length >= 2) head[0] + "." + head.substring(1) + digits.substring(head.length) else v
            }
        val d = mutableMapOf(DetailKey.MODEL to model)
        ver("H:")?.let { d[DetailKey.HW] = it }
        ver("F:")?.let { d[DetailKey.SW] = it }
        return DriverOutput(details = d, hardware = BmsHardwareInfo(model))
    }

    private fun decodePack(f: ByteArray): DriverOutput {
        val parts = ascii(f, 5, f.size - 1).split(" ").filter { it.isNotBlank() }
        val d = mutableMapOf<String, String>()
        if (parts.size >= 2) { d[DetailKey.PACK_NAME] = parts[0]; d[DetailKey.PACK_SERIAL] = parts[1] }
        else if (parts.size == 1) d[DetailKey.PACK_SERIAL] = parts[0]
        return DriverOutput(details = d)
    }

    private fun decodeClock(f: ByteArray): DriverOutput? {
        if (f.size < 12) return null
        fun bcd(i: Int): Int { val v = Bytes.u8(f, i); return (v shr 4) * 10 + (v and 0x0F) }
        val txt = "%04d-%02d-%02d %02d:%02d:%02d".format(java.util.Locale.US,
            2000 + bcd(5), bcd(6), bcd(7), bcd(8), bcd(9), bcd(10))
        return DriverOutput(details = mapOf(DetailKey.CLOCK to txt))
    }

    private fun decodeRealtime(f: ByteArray): DriverOutput? {
        val len = f.size
        if (len < 14) return null
        val n = Bytes.u8(f, 11)
        if (n !in 1..32) return null
        val nb = when { n <= 8 -> 1; n <= 16 -> 2; n <= 24 -> 3; else -> 4 }
        val a = 12 + 2 * n + nb
        if (a >= len - 1) return null
        val t = Bytes.u8(f, a)
        val b = a + t + 1
        if (b >= len - 1) return null
        val m = Bytes.u8(f, b)
        val c = b + m + 1
        if (c + 6 >= len) return null

        val cells = (0 until n).map { Bytes.u16le(f, 12 + it * 2) / 1000.0 }
        if (cells.any { it < 0.5 || it > 5.0 }) return null
        val balance = (0 until nb).fold(0L) { acc, i -> acc or (Bytes.u8(f, 12 + 2 * n + i).toLong() shl (8 * i)) }
        val cellTemps = (0 until t).map { f[a + 1 + it].toDouble() }   // signed int8, °C
        val mosTemps = (0 until m).map { f[b + 1 + it].toDouble() }
        val cycles = Bytes.u16le(f, c)
        val remain = Bytes.u16le(f, c + 2) / 10.0
        val full = Bytes.u16le(f, c + 4) / 10.0
        val mos = Bytes.u8(f, c + 6)

        val s0 = Bytes.u8(f, 5); val s1 = Bytes.u8(f, 6); val s2 = Bytes.u8(f, 7); val s3 = Bytes.u8(f, 8)
        var current = Bytes.s16le(f, 9) / 10.0
        // Normalise sign: + = charging, − = discharging (status bits are authoritative).
        if (s0 and 0x01 != 0) current = kotlin.math.abs(current)
        else if (s0 and 0x10 != 0) current = -kotlin.math.abs(current)

        val voltage = cells.sum()
        val soc = if (full > 0) (remain * 100.0 / full).toInt() else 0

        val info = basicInfo(
            source = id, voltage = voltage, current = current, soc = soc,
            remainAh = remain, fullAh = full, cycles = cycles, cellCount = n,
            temps = cellTemps, mosTemp = mosTemps.maxOrNull(),
            chargeFet = mos and 0x40 != 0, dischargeFet = mos and 0x80 != 0,
            balanceBits = balance, alarms = alarmTexts(s0, s1, s2, s3), raw = f,
            mosTemps = mosTemps, heating = mos and 0x10 != 0
        )
        return DriverOutput(basic = info, cells = cells)
    }

    companion object {
    fun alarmTexts(s0: Int, s1: Int, s2: Int, s3: Int): List<String> {
        val out = mutableListOf<String>()
        fun bit(v: Int, b: Int, text: String) { if (v and (1 shl b) != 0) out += text }
        bit(s0, 1, "Charge overcurrent protection")
        bit(s0, 3, "Charge MOS fault")
        bit(s0, 5, "Discharge overcurrent protection")
        bit(s0, 6, "Discharge short circuit")
        bit(s0, 7, "Discharge MOS fault")
        bit(s1, 0, "Cell voltage sense line open")
        bit(s1, 1, "Cell temperature sensor open")
        bit(s1, 2, "MOS temperature sensor open")
        bit(s1, 3, "AFE fault")
        bit(s1, 5, "Cell low voltage")
        bit(s1, 6, "Pack overvoltage")
        bit(s1, 7, "Pack undervoltage")
        bit(s2, 0, "MOS over-temperature")
        bit(s2, 1, "MOS temperature difference")
        bit(s2, 2, "Charge over-temperature")
        bit(s2, 3, "Discharge over-temperature")
        bit(s2, 4, "Charge under-temperature")
        bit(s2, 5, "Discharge under-temperature")
        bit(s2, 6, "Charge temperature difference")
        bit(s2, 7, "Discharge temperature difference")
        bit(s3, 0, "Charging disabled by command")
        bit(s3, 1, "Discharging disabled by command")
        bit(s3, 4, "Thermal runaway warning")
        bit(s3, 5, "Cell voltage difference too high")
        bit(s3, 6, "Low-voltage charge inhibit")
        bit(s3, 7, "Deep undervoltage")
        return out
    }
    }
}
