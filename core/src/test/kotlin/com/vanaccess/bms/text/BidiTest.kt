// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.text

import org.junit.Assert.assertEquals
import org.junit.Test

class BidiTest {
    private val L = Bidi.LRI; private val F = Bidi.FSI; private val P = Bidi.PDI

    @Test fun eachPartKeptWhole() {
        assertEquals("${L}0.41 km$P  •  ${F}1 د$P", Bidi.value("0.41 km  •  1 د"))
        assertEquals("${L}20$P  •  ${L}56 s$P", Bidi.join("20", null, " ", "56 s"))
    }

    @Test fun rangeStaysInTimeOrder() {
        val r = Bidi.range("63%", "50%")
        assertEquals("${L}63%$P – ${L}50%$P", r)
        assertEquals(r, Bidi.part(r))                     // not wrapped again (that would put 63 on the left in Arabic)
    }

    @Test fun shortDurationsSayMinutesOrSeconds() {
        val hm = "%1\$d س %2\$02d د"; val m = "%1\$d د"; val s = "%1\$d ث"
        assertEquals("${F}1 د$P", Bidi.duration(61.0, hm, m, s))
        assertEquals("${F}56 ث$P", Bidi.duration(56.0, hm, m, s))
        assertEquals("${F}1 س 05 د$P", Bidi.duration(3900.0, hm, m, s))
        assertEquals("${L}2h 00m$P", Bidi.duration(7200.0, "%1\$dh %2\$02dm", "%1\$d min", "%1\$d s"))
    }
}
