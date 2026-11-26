package com.vanaccess.bms.sync

import com.vanaccess.bms.reports.Point
import com.vanaccess.bms.reports.Session
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

class SyncTest {
    private class Phone : SyncServer.Source {
        val have = mutableListOf(SyncedSession(Session(0, Session.TYPE_TRIP, 1_000, 2_000, "66:6A", JSONObject().put("distance_km", 7.72)),
            listOf(Point(1_000, 76.0, -10.0, 89, 25.0, 33.97, -6.86))))
        var yieldedFor = 0; var peers = mutableListOf<String>()
        override fun sessions(sinceMs: Long) = have.filter { it.session.endMs > sinceMs }
        override fun accept(list: List<SyncedSession>): Int {
            val keys = have.map { SyncCodec.key(it.session) }.toSet()
            val add = list.filter { SyncCodec.key(it.session) !in keys }
            have += add; return add.size
        }
        override fun battery() = JSONObject().put("address", "66:6A").put("bms_cell_ov_mv", 3630)
        override fun yieldLink(minutes: Int): Long { yieldedFor = minutes; return 123L }
        override fun resumeLink() { yieldedFor = 0 }
        override fun peer(name: String) { peers += name }
    }

    @Test fun phoneAndComputerExchangeReports() {
        val phone = Phone()
        val server = SyncServer(phone, "Infinix").apply { start() }
        try {
            val c = SyncClient("127.0.0.1", server.port, server.code, "mx")
            val got = c.sessions()
            assertEquals(1, got.size); assertEquals(7.72, got[0].session.data.getDouble("distance_km"), 0.0)
            assertEquals(33.97, got[0].points[0].lat!!, 0.0)
            val charge = SyncedSession(Session(0, Session.TYPE_CHARGE, 5_000, 9_000, "66:6A", JSONObject().put("source", "desktop")), emptyList())
            assertEquals(1, c.send(listOf(charge))); assertEquals(0, c.send(listOf(charge)))
            assertEquals(3630, c.battery().getInt("bms_cell_ov_mv"))
            assertEquals(123L, c.yieldLink(90)); assertEquals(90, phone.yieldedFor)
            c.resumeLink(); assertEquals(0, phone.yieldedFor)
            assertTrue("mx" in phone.peers)
        } finally { server.stop() }
    }

    @Test fun wrongCodeIsRefusedThenTheDoorCloses() {
        val server = SyncServer(Phone(), "Infinix").apply { start() }
        try {
            val bad = SyncClient("127.0.0.1", server.port, "000000")
            repeat(5) { try { bad.battery(); fail() } catch (e: SyncClient.Refused) { assertEquals(401, e.status) } }
            try { SyncClient("127.0.0.1", server.port, server.code).battery(); fail() } catch (e: SyncClient.Refused) { assertEquals(403, e.status) }
        } finally { server.stop() }
    }

    @Test fun computerFindsThePhone() {
        val server = SyncServer(Phone(), "Infinix").apply { start() }
        try {
            val found = SyncClient.discover(1000, listOf(InetAddress.getByName("127.0.0.1")))
            assertEquals(server.port, found.single().port); assertEquals("Infinix", found.single().name)
        } finally { server.stop() }
    }
}
