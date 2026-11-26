// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.sync

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The phone's side of the sync with the computer: on the home network only, open for a few
 * minutes when the owner asks, guarded by a 6-digit code shown on the phone. Nothing goes to the
 * internet. Plain sockets, so it runs on Android and on the JVM alike.
 *
 *  - discovery: a UDP datagram [SyncProtocol.DISCOVER] to port [SyncProtocol.DISCOVERY_PORT] is
 *    answered with who we are and our HTTP port;
 *  - HTTP/1.1, JSON, header `X-GTBMS-Code` on every request but /v1/hello.
 */
class SyncServer(private val source: Source, private val name: String) {

    /** What the phone offers; called on the server's threads. */
    interface Source {
        fun sessions(sinceMs: Long): List<SyncedSession>
        /** Reports from the computer; returns how many were new. */
        fun accept(list: List<SyncedSession>): Int
        fun battery(): JSONObject
        /** Leave the battery's Bluetooth link to the computer for [minutes]; returns until when. */
        fun yieldLink(minutes: Int): Long
        fun resumeLink()
        /** A computer came and identified itself (for the screen). */
        fun peer(name: String) {}
    }

    val code: String = (SecureRandom().nextInt(900_000) + 100_000).toString()
    @Volatile var port = 0; private set
    @Volatile private var running = false
    private var http: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var wrong = 0

    fun start() {
        running = true
        val s = ServerSocket(0).also { http = it }
        port = s.localPort
        Thread({ serveHttp(s) }, "gtbms-sync-http").apply { isDaemon = true; start() }
        runCatching {
            val u = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(SyncProtocol.DISCOVERY_PORT)) }
            udp = u
            Thread({ serveDiscovery(u) }, "gtbms-sync-udp").apply { isDaemon = true; start() }
        }
    }

    fun stop() {
        running = false
        runCatching { http?.close() }; runCatching { udp?.close() }
    }

    val isRunning get() = running

    private fun hello() = JSONObject().put("app", "GT-BMS").put("proto", SyncProtocol.VERSION).put("name", name).put("port", port)

    private fun serveDiscovery(u: DatagramSocket) {
        val buf = ByteArray(512)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try { u.receive(p) } catch (_: Exception) { if (!running) return else continue }
            if (String(p.data, 0, p.length, Charsets.UTF_8).trim() != SyncProtocol.DISCOVER) continue
            val out = hello().toString().toByteArray(Charsets.UTF_8)
            runCatching { u.send(DatagramPacket(out, out.size, p.address, p.port)) }
        }
    }

    private fun serveHttp(s: ServerSocket) {
        while (running) {
            val c = try { s.accept() } catch (_: Exception) { return }
            Thread({ c.use { handle(it) } }, "gtbms-sync-req").apply { isDaemon = true; start() }
        }
    }

    private fun handle(c: Socket) = runCatching {
        c.soTimeout = 15_000
        val ins = BufferedInputStream(c.getInputStream())
        val req = readRequest(ins) ?: return@runCatching
        val (status, body) = route(req)
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        c.getOutputStream().apply {
            write(("HTTP/1.1 $status\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
            write(bytes); flush()
        }
    }

    class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: String)

    private fun route(r: Request): Pair<String, JSONObject> {
        if (r.path == "/v1/hello") return "200 OK" to hello()
        // A wrong code five times closes the door for this session
        if (wrong >= 5) return "403 Forbidden" to JSONObject().put("error", "closed")
        if (!same(r.headers["x-gtbms-code"] ?: "", code)) { wrong++; return "401 Unauthorized" to JSONObject().put("error", "code") }
        r.headers["x-gtbms-peer"]?.let { source.peer(it.take(64)) }
        return when ("${r.method} ${r.path}") {
            "GET /v1/sessions" -> "200 OK" to SyncCodec.list(source.sessions(r.query["since"]?.toLongOrNull() ?: 0L))
            "POST /v1/sessions" -> "200 OK" to JSONObject().put("added", source.accept(SyncCodec.parseList(JSONObject(r.body))))
            "GET /v1/battery" -> "200 OK" to source.battery()
            "POST /v1/yield" -> "200 OK" to JSONObject().put("until", source.yieldLink(JSONObject(r.body.ifBlank { "{}" }).optInt("minutes", 60).coerceIn(5, 720)))
            "POST /v1/resume" -> { source.resumeLink(); "200 OK" to JSONObject().put("ok", true) }
            else -> "404 Not Found" to JSONObject().put("error", "unknown")
        }
    }

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        private const val MAX_BODY = 64 * 1024 * 1024

        fun readRequest(ins: InputStream): Request? {
            val head = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = ins.read(); if (b < 0) return null
                head.write(b); last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
                if (head.size() > 16_384) return null
            }
            val lines = head.toString("UTF-8").split("\r\n").filter { it.isNotEmpty() }
            val (method, target) = lines.firstOrNull()?.split(" ")?.let { it.getOrNull(0) to it.getOrNull(1) } ?: return null
            if (method == null || target == null) return null
            val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len > MAX_BODY) return null
            val body = ByteArray(len); var off = 0
            while (off < len) { val n = ins.read(body, off, len - off); if (n < 0) break; off += n }
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "").split('&').filter { '=' in it }
                .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            return Request(method, path, query, headers, String(body, 0, off, Charsets.UTF_8))
        }
    }
}

object SyncProtocol {
    const val VERSION = 1
    const val DISCOVERY_PORT = 47321
    const val DISCOVER = "GTBMS-DISCOVER 1"
    /** How long the phone stays open for the computer once asked. */
    const val OPEN_MS = 10 * 60_000L
}
