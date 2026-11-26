package com.gnutux.gtbms.desktop

import com.vanaccess.bms.reports.Advisor
import com.vanaccess.bms.reports.Point
import com.vanaccess.bms.reports.Session
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DesktopTest {
    private fun tmp() = Files.createTempDirectory("gtbms").toFile().apply { deleteOnExit() }

    /** A backup made like the phone's (manifest, database, shared_prefs) is merged once, with its battery settings. */
    @Test fun phoneBackupIsMergedOnce() {
        val phoneDb = File(tmp(), "gt_bms_reports.db")
        Store(phoneDb).apply {
            insert(Session.TYPE_CHARGE, 1_000, 4_000_000, "66:6A", JSONObject("""{"soc_start":69,"soc_end":90,"ah":6.19,"gap_s":3470,"avg_current":23.2}"""),
                listOf(Point(1_000, 77.0, 5.1, 69, null, null, null)))
            insert(Session.TYPE_TRIP, 5_000_000, 6_000_000, "66:6A", JSONObject("""{"soc_start":89,"soc_end":79,"distance_km":7.72,"wh":220.0}"""), emptyList())
        }
        val prefs = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="selected_device_address">66:6A</string>
    <int name="alarm_high_soc@66:6A" value="90" />
    <string name="bms_limits@66:6A">{&quot;ov&quot;:3630,&quot;uv&quot;:2600,&quot;des&quot;:30.0,&quot;n&quot;:23}</string>
</map>"""
        val zip = File(tmp(), "backup.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write("""{"app":"GT-BMS","format":1}""".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("gt_bms_reports.db")); z.write(phoneDb.readBytes()); z.closeEntry()
            z.putNextEntry(ZipEntry("gt_bms_prefs.xml")); z.write(prefs.toByteArray()); z.closeEntry()
        }
        val home = tmp()
        val m = Monitor(home)
        assertEquals(2, m.importPhoneBackup(zip))
        assertEquals(0, m.importPhoneBackup(zip))                       // not twice
        val list = m.store.list()
        assertEquals(2, list.size)
        assertEquals(1, m.store.points(list.first { it.type == Session.TYPE_CHARGE }.id).size)
        assertEquals(90, m.settings.highSoc); assertEquals(3630, m.settings.limits.cellOvMv); assertEquals("66:6A", m.settings.battery)
        // the average current of that charge, over its whole time (1.8.1), not 23.2 A
        assertEquals(5.57, list.first { it.type == Session.TYPE_CHARGE }.avgA, 0.01)
    }

    @Test fun phoneTextsAreShared() {
        assertTrue(Res.has("alarm_high_title")); assertTrue(Res.has("adv_charge_summary"))
        assertEquals("1 h 05 min".length > 0, Res.dur(3900.0).isNotEmpty())
        val t = Res.advice(Advisor.Rec(Advisor.Level.INFO, "charge_summary", listOf(69, 90, 1.2, 5.16, 6.19)))
        assertTrue(t, t.contains("69") && t.contains("5.2") && !t.contains("%1"))
    }
}
