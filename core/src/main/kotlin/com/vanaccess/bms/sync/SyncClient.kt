// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** The computer's side: finds phones on the home network and talks to the one paired with its code. */
class SyncClient(private val host: String, private val port: Int, private val code: String, private val peerName: String = "computer") {

    data class Found(val host: String, val port: Int, val name: String)

    fun sessions(sinceMs: Long = 0): List<SyncedSession> = SyncCodec.parseList(call("GET", "/v1/sessions?since=$sinceMs"))
    fun send(list: List<SyncedSession>): Int = call("POST", "/v1/sessions", SyncCodec.list(list).toString()).optInt("added")
    fun battery(): JSONObject = call("GET", "/v1/battery")
    fun yieldLink(minutes: Int): Long = call("POST", "/v1/yield", JSONObject().put("minutes", minutes).toString()).optLong("until")
    fun resumeLink() { call("POST", "/v1/resume", "{}") }

    class Refused(val status: Int, msg: String) : Exception(msg)

    private fun call(method: String, path: String, body: String? = null): JSONObject {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 5_000); s.soTimeout = 60_000
            val b = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            s.getOutputStream().apply {
                write(("$method $path HTTP/1.1\r\nHost: $host\r\nX-GTBMS-Code: $code\r\nX-GTBMS-Peer: $peerName\r\n" +
                    "Content-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray())
                write(b); flush()
            }
            val ins = BufferedInputStream(s.getInputStream())
            val text = ins.readBytes().toString(Charsets.UTF_8)
            val status = text.substringAfter(' ').substringBefore(' ').toIntOrNull() ?: 0
            val json = JSONObject(text.substringAfter("\r\n\r\n").ifBlank { "{}" })
            if (status != 200) throw Refused(status, json.optString("error", "http $status"))
            return json
        }
    }

    companion object {
        /** Phones with the sync open on this network (UDP broadcast, [waitMs] to collect answers). */
        fun discover(waitMs: Int = 1500, targets: List<InetAddress> = listOf(InetAddress.getByName("255.255.255.255"))): List<Found> {
            val out = LinkedHashMap<String, Found>()
            DatagramSocket().use { u ->
                u.broadcast = true; u.soTimeout = 250
                val msg = SyncProtocol.DISCOVER.toByteArray()
                targets.forEach { runCatching { u.send(DatagramPacket(msg, msg.size, it, SyncProtocol.DISCOVERY_PORT)) } }
                val until = System.currentTimeMillis() + waitMs
                val buf = ByteArray(1024)
                while (System.currentTimeMillis() < until) {
                    val p = DatagramPacket(buf, buf.size)
                    try { u.receive(p) } catch (_: SocketTimeoutException) { continue }
                    runCatching {
                        val o = JSONObject(String(p.data, 0, p.length, Charsets.UTF_8))
                        if (o.optString("app") == "GT-BMS") out[p.address.hostAddress] = Found(p.address.hostAddress, o.getInt("port"), o.optString("name"))
                    }
                }
            }
            return out.values.toList()
        }
    }
}
