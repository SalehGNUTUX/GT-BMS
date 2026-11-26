// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble

/**
 * The "Last packets" part of a GT-BMS device report, read back: lets the parsers (and the desktop
 * version) be tried on a real battery's traffic without the battery. Pure, unit-tested.
 */
object ReplayLog {
    data class Packet(val time: String, val received: Boolean, val bytes: ByteArray)

    private val HEAD = Regex("""^\[([0-9:.]+)]\s+(RX|TX)\s+\d+B""")

    /** Packets oldest first (the report lists the newest first). */
    fun parse(text: String): List<Packet> {
        val out = mutableListOf<Packet>()
        var head: MatchResult? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            HEAD.find(line)?.let { head = it; return@forEach }
            val h = head ?: return@forEach
            if (line.startsWith("HEX:")) {
                val bytes = line.removePrefix("HEX:").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    .map { it.toInt(16).toByte() }.toByteArray()
                out += Packet(h.groupValues[1], h.groupValues[2] == "RX", bytes)
                head = null
            }
        }
        return out.reversed()
    }
}
