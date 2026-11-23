package com.vanaccess.bms.data

import com.vanaccess.bms.ble.BmsBasicInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastReadingTest {
    @Test fun roundTrip() {
        val b = BmsBasicInfo(76.69, -1.5, 26.5, 29.9, 3, 88, 23, listOf(21.0, 22.5), true, true, 0, 0L,
            "1.0", "2025-01-01", ByteArray(0), alarmTexts = listOf("Cell overvoltage protection"),
            mosTemperature = null, sohReported = 99, source = "bwbm", mosTemperatures = listOf(25.0), heating = null)
        val s = LastReading.Snapshot("66:6A:10:59:27:DC", 1_700_000_000_000L, b, List(23) { 3.33 })
        val r = LastReading.fromJson(org.json.JSONObject(LastReading.toJson(s).toString()))!!
        assertEquals(s.address, r.address); assertEquals(s.timeMs, r.timeMs); assertEquals(s.cells, r.cells)
        assertEquals(b.copy(rawData = r.basic.rawData), r.basic)
        assertNull(r.basic.mosTemperature)
    }
}
