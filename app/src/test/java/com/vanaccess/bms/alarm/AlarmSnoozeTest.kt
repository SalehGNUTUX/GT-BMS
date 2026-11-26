package com.vanaccess.bms.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmSnoozeTest {
    @Test fun soonerNearFull() {
        assertEquals(15, AlarmSnooze.minutes(90, 29.9, 5.1))      // 5 points ≈ 18 min → at most 15
        assertEquals(7, AlarmSnooze.minutes(98, 29.9, 5.1))       // 2 points left
        assertEquals(4, AlarmSnooze.minutes(99, 29.9, 5.1))
        assertEquals(3, AlarmSnooze.minutes(95, 29.9, 40.0))
        assertEquals(10, AlarmSnooze.minutes(80, 0.0, 0.0)); assertEquals(5, AlarmSnooze.minutes(92, 0.0, 0.0))
        assertEquals(3, AlarmSnooze.minutes(100, 0.0, 0.0))
    }
}
