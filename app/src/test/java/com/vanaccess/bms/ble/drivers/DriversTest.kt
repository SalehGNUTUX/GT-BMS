package com.vanaccess.bms.ble.drivers

import org.junit.Assert.*
import org.junit.Test

class DriversTest {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    @Test fun bwbmCommandsMatchVendorApp() {
        val d = BwbmDriver()
        assertEquals("7F 10 02 06 12 57", hex(d.build(0x12)))
        assertEquals("7F 10 02 06 20 49", hex(d.build(0x20)))
        assertEquals("7F 10 02 06 21 48", hex(d.build(0x21)))
    }

    /** Synthetic 23S frame built per the reverse-engineered layout, split in 20-byte notifications. */
    @Test fun bwbmParses23sRealtimeFrame() {
        val n = 23
        val body = mutableListOf<Int>()
        body += listOf(0x7F, 0x10, 0x02, 0x00, 0x12)
        body += listOf(0x10, 0x00, 0x00, 0x00)            // discharging
        val cur = (-125).toShort().toInt() and 0xFFFF       // -12.5 A
        body += listOf(cur and 0xFF, cur shr 8)
        body += n
        repeat(n) { i -> val mv = 3600 + i; body += listOf(mv and 0xFF, mv shr 8) }
        body += listOf(0, 0, 0)                             // balance (3 bytes for 23S)
        body += listOf(2, 25, 0xFE)                         // 2 temps: 25, -2
        body += listOf(1, 31)                               // 1 MOS temp
        body += listOf(12, 0)                               // cycles 12
        body += listOf(300 and 0xFF, 300 shr 8)             // 30.0 Ah remaining
        body += listOf(400 and 0xFF, 400 shr 8)             // 40.0 Ah full
        body += 0xC0                                        // both MOS on
        body += 0                                           // checksum placeholder
        body[3] = body.size
        body[body.size - 1] = (0x100 - (body.dropLast(1).sum() and 0xFF)) and 0xFF
        val frame = ByteArray(body.size) { body[it].toByte() }

        val d = BwbmDriver()
        var out: DriverOutput? = null
        frame.toList().chunked(20).forEach { out = d.onNotification(it.toByteArray()) ?: out }
        val info = out!!.basic!!
        assertEquals(23, out!!.cells!!.size)
        assertEquals((0 until n).sumOf { 3600 + it } / 1000.0, info.packVoltage, 1e-6)
        assertEquals(-12.5, info.current, 1e-6)
        assertEquals(75, info.soc)
        assertEquals(12, info.cycleCount)
        assertEquals(listOf(25.0, -2.0), info.temperatures)
        assertEquals(31.0, info.mosfetTemperature!!, 1e-6)
        assertTrue(info.chargeFetOn && info.dischargeFetOn)
    }

    @Test fun dalyCrc() {
        val c = Bytes.crc16Modbus(byteArrayOf(0xD2.toByte(), 3, 0, 0, 0, 0x3E))
        assertEquals(0xB9D7, c)
    }

    @Test fun autoDetectOrdersByName() {
        val c = BmsDrivers.candidates("auto", null, "BWBM-631M-23S40A",
            listOf("0000ffe0-0000-1000-8000-00805f9b34fb"))
        assertEquals("bwbm", c.first().id)
        val jk = BmsDrivers.candidates("auto", null, "JK_B2A20S",
            listOf("0000ffe0-0000-1000-8000-00805f9b34fb"))
        assertEquals("jk", jk.first().id)
    }

    /** Real tail captured from the user's BWBM-631M-23S40A (screen recording 2026-11-20), with a duplicated chunk. */
    @Test fun bwbmResyncsAfterDuplicatedNotification() {
        val d = BwbmDriver()
        fun h(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        // Build a valid frame from the captured layout, then feed chunk 2 twice before a clean copy.
        val head = h("7F 10 02 4C 12 00 00 00 00 00 00 17 02 0D 03 0D 02 0D 01 0D")
        val c2 = h("00 0D 01 0D 01 0D 02 0D FD 0C 00 0D 02 0D 01 0D FE 0C 07 0D")
        val c3 = h("09 0D 08 0D 07 0D 08 0D 0A 0D 0B 0D 0A 0D 08 0D 09 0D 00 00")
        val tail = h("00 03 14 15 14 02 16 16 02 00 C9 00 2C 01 C0 00")
        val frame = head + c2 + c3 + tail
        frame[frame.size - 1] = ((0x100 - (frame.dropLast(1).sumOf { it.toInt() and 0xFF } and 0xFF)) and 0xFF).toByte()
        val goodTail = frame.copyOfRange(60, 76)
        assertNull(listOf(head, c2, c2, c3, goodTail).map { d.onNotification(it) }.lastOrNull { it != null })
        var out: DriverOutput? = null
        listOf(head, c2, c3, goodTail).forEach { out = d.onNotification(it) ?: out }
        val info = out!!.basic!!
        assertEquals(76.641, info.packVoltage, 1e-6)
        assertEquals(67, info.soc)
        assertEquals(2, info.cycleCount)
        assertEquals(listOf(20.0, 21.0, 20.0), info.temperatures)
        assertEquals(22.0, info.mosfetTemperature!!, 1e-6)
        assertEquals(20.1, info.remainCapacity, 1e-6)
        assertEquals(30.0, info.fullCapacity, 1e-6)
    }

    private fun frame(cmd: Int, payload: List<Int>): ByteArray {
        val l = (listOf(0x7F, 0x10, 0x02, 0, cmd) + payload + 0).toMutableList()
        l[3] = l.size
        l[l.size - 1] = (0x100 - (l.dropLast(1).sum() and 0xFF)) and 0xFF
        return ByteArray(l.size) { l[it].toByte() }
    }

    @Test fun bwbmModelClockAndHistory() {
        val d = BwbmDriver()
        val model = "BWBM-631M-23S40A-F-K3662A1 H:v21 F:V25_R18_260605_relea_A2_01_02"
        val m = d.onNotification(frame(0x20, model.map { it.code }))!!.details!!
        assertEquals("BWBM-631M-23S40A-F-K3662A1", m[DetailKey.MODEL])
        assertEquals("2.1", m[DetailKey.HW])
        assertEquals("2.5_R18_260605_relea_A2_01_02", m[DetailKey.SW])

        val c = d.onNotification(frame(0x22, listOf(0x26, 0x11, 0x20, 0x08, 0x55, 0x50)))!!.details!!
        assertEquals("2026-11-20 08:55:50", c[DetailKey.CLOCK])

        // History: start download, then a record "Charging;Core over Voltage;" and a last (flag 2) record
        d.historyCommands()
        val first = d.pollCommands().first()
        assertEquals("7F 10 02 07 23 00 45", hex(first))
        val rec = listOf(0x01, 0x26, 0x11, 0x16, 0x11, 0x59, 0x01, 0, 0, 0x01, 0x10, 0, 0, 0, 0, 0)
        val out = d.onNotification(frame(0x23, rec))!!
        assertEquals("2026-11-16 11:59:01", out.history!!.single().time)
        assertEquals(listOf("Charging", "Cell overvoltage protection"), out.history!!.single().events)
        assertEquals("7F 10 02 07 23 01 44", hex(d.pollCommands().first()))
        val last = d.onNotification(frame(0x23, listOf(0x02) + rec.drop(1)))!!
        assertTrue(last.historyDone)
    }

    @Test fun bwbmParamsDecode() {
        val p = MutableList(96) { 0 }
        p[0] = 85                       // r5 MOS OT protect
        p[8] = 0xF6                     // r13 charge low-temp protect = -10
        p[18] = 300 and 0xFF; p[19] = 300 shr 8          // r23 design capacity 30.0 Ah
        p[20] = 8380 and 0xFF; p[21] = 8380 shr 8        // r25 pack OV 83.80 V
        p[28] = 3650 and 0xFF; p[29] = 3650 shr 8        // r33 cell OV 3650 mV
        p[82] = 23                      // r87 cells
        val out = BwbmDriver().onNotification(frame(0x19, p))!!.params!!
        fun v(l: String) = out.first { it.label == l }.value
        assertEquals("85 °C", v("MOS over-temperature protection"))
        assertEquals("-10 °C", v("Charge low-temperature protection"))
        assertEquals("30.0 Ah", v("Design capacity"))
        assertEquals("83.80 V", v("Pack overvoltage protection"))
        assertEquals("3650 mV", v("Cell overvoltage protection (level 1)"))
        assertEquals("23", v("Cell count"))
    }
}
