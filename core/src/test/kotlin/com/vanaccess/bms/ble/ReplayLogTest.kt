package com.vanaccess.bms.ble

import com.vanaccess.bms.ble.drivers.BwbmDriver
import com.vanaccess.bms.ble.drivers.DriverOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayLogTest {
    /** Real traffic of the owner's 23S battery (device report, 2026-11-26 07:37–07:38). */
    private val packets = ReplayLog.parse(javaClass.getResource("/bwbm-23s-packets.txt")!!.readText())

    @Test fun readsTheReportOldestFirst() {
        assertEquals(14, packets.size)
        assertTrue(packets.first().time < packets.last().time)
        assertTrue(packets.any { !it.received && it.bytes.size == 6 && it.bytes[0] == 0x7F.toByte() })
    }

    @Test fun bestwayParserOnRealTraffic() {
        val d = BwbmDriver()
        var out = DriverOutput()
        packets.filter { it.received }.forEach { p -> out = out.merge(d.onNotification(p.bytes)) }
        val b = out.basic!!; val cells = out.cells!!
        assertEquals(23, cells.size)
        assertTrue(cells.all { it in 3.30..3.35 })
        assertEquals(0.0, b.current, 0.05)
        assertEquals(29.9, b.fullCapacity, 0.05); assertEquals(23.6, b.remainCapacity, 0.05)
        assertEquals(cells.sum(), b.packVoltage, 0.05)
    }
}
