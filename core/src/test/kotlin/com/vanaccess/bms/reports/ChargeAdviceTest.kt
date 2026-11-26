package com.vanaccess.bms.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeAdviceTest {
    /** The user's BWBM-631M-23S40A: 23S LiFePO4, cell OV protection 3630 mV. */
    @Test fun lfp23s() {
        val a = ChargeAdvice.from(23, 3630)!!
        assertTrue(a.lfp)
        assertEquals(83.49, a.protectionPackV, 1e-6)
        assertEquals(82.8, a.chargerPackV, 1e-6)      // 3.60 V per cell
        assertEquals(81.6, a.alarmPackV, 1e-6)        // 3.55 V per cell, rounded down
        assertTrue(a.alarmPackV < a.chargerPackV && a.chargerPackV < a.protectionPackV)
    }

    @Test fun lowerProtectionLimitStaysBelowIt() {
        val a = ChargeAdvice.from(16, 3600)!!           // 3.57 V per cell target
        assertEquals(3.57, a.chargeCellV, 1e-9)
        assertTrue(a.chargerPackV < a.protectionPackV)
    }

    @Test fun nmc() {
        val a = ChargeAdvice.from(13, 4250)!!
        assertEquals(4.15, a.chargeCellV, 1e-9)
        assertEquals(54.0, a.chargerPackV, 1e-6)
    }

    @Test fun unknownLimits() {
        assertNull(ChargeAdvice.from(0, 3630))
        assertNull(ChargeAdvice.from(23, 0))
    }
}
