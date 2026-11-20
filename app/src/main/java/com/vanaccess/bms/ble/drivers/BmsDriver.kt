// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble.drivers

import com.vanaccess.bms.ble.BmsBasicInfo
import com.vanaccess.bms.ble.BmsHardwareInfo

/** One GATT service + write/notify characteristic pair a BMS may expose. */
data class GattProfile(val service: String, val write: String, val notify: String)

/** Optional capabilities a driver offers beyond live data. */
enum class Feature { DETAILS, PARAMS, HISTORY, CLOCK }

/** Keys of [DriverOutput.details] (values are display strings). */
object DetailKey {
    const val MODEL = "model"
    const val HW = "hw"
    const val SW = "sw"
    const val SERIAL = "serial"
    const val PACK_NAME = "pack_name"
    const val PACK_SERIAL = "pack_serial"
    const val CLOCK = "clock"
    const val MFG_DATE = "mfg_date"
}

/** One read-only BMS parameter row; [group] and [label] are string-resource names or plain text. */
data class ParamItem(val group: String, val label: String, val value: String)

/** One history/event record. [events] are alert keys (see AlertText) or plain text. */
data class HistoryRecord(val time: String, val events: List<String>, val extra: String = "")

/** Result of feeding a notification to a driver (any field may be null). */
data class DriverOutput(
    val basic: BmsBasicInfo? = null,
    val cells: List<Double>? = null,
    val hardware: BmsHardwareInfo? = null,
    val details: Map<String, String>? = null,
    val params: List<ParamItem>? = null,
    val history: List<HistoryRecord>? = null,
    /** True when the history download finished (no more records). */
    val historyDone: Boolean = false
) {
    val isEmpty: Boolean get() = basic == null && cells == null && hardware == null &&
            details == null && params == null && history == null && !historyDone

    fun merge(o: DriverOutput?): DriverOutput {
        if (o == null) return this
        return DriverOutput(
            o.basic ?: basic, o.cells ?: cells, o.hardware ?: hardware,
            if (details == null) o.details else if (o.details == null) details else details + o.details,
            o.params ?: params,
            if (history == null) o.history else if (o.history == null) history else history + o.history,
            historyDone || o.historyDone
        )
    }
}

/**
 * A pluggable BMS protocol.
 *
 * BleService picks a driver either manually (user preference) or automatically:
 * candidates are ordered by name prefix, then by advertised GATT service, and each
 * candidate is probed in turn until one returns valid data. The winning driver id is
 * remembered for the device so the next connection is instant.
 */
interface BmsDriver {
    /** Stable id stored in preferences. */
    val id: String
    /** Human readable name shown in the protocol selector. */
    val displayName: String
    /** GATT profiles to try, in order. */
    val profiles: List<GattProfile>
    /** Delay between poll cycles. */
    val pollIntervalMs: Long get() = 2000L
    /** Delay between two commands inside one poll cycle. */
    val interCommandDelayMs: Long get() = 250L

    /** Extra capabilities (details page, parameters, history, clock). */
    val features: Set<Feature> get() = emptySet()

    /** Commands that start a history download (sent through the poll queue). */
    fun historyCommands(): List<ByteArray> = emptyList()

    /** True when the advertised device name clearly belongs to this BMS family. */
    fun matchesName(name: String?): Boolean

    /** True when the GATT service list contains one of this driver's services. */
    fun matchesServices(serviceUuids: List<String>): Boolean =
        profiles.any { p -> serviceUuids.any { it.equals(p.service, ignoreCase = true) } }

    /** Clear any reassembly buffers / state before (re)starting. */
    fun reset() {}

    /** Commands sent once right after notifications are enabled. */
    fun initCommands(): List<ByteArray> = emptyList()

    /** Commands sent every poll cycle. */
    fun pollCommands(): List<ByteArray>

    /** Feed one notification. Returns parsed data when a complete, valid frame was decoded. */
    fun onNotification(data: ByteArray): DriverOutput?
}

/** Byte helpers shared by drivers. */
internal object Bytes {
    fun u8(d: ByteArray, o: Int): Int = d[o].toInt() and 0xFF
    fun u16be(d: ByteArray, o: Int): Int = (u8(d, o) shl 8) or u8(d, o + 1)
    fun u16le(d: ByteArray, o: Int): Int = u8(d, o) or (u8(d, o + 1) shl 8)
    fun s16be(d: ByteArray, o: Int): Int = u16be(d, o).toShort().toInt()
    fun s16le(d: ByteArray, o: Int): Int = u16le(d, o).toShort().toInt()
    fun u32le(d: ByteArray, o: Int): Long =
        (u16le(d, o).toLong()) or (u16le(d, o + 2).toLong() shl 16)
    fun s32le(d: ByteArray, o: Int): Int = u32le(d, o).toInt()
    fun u32be(d: ByteArray, o: Int): Long =
        (u16be(d, o).toLong() shl 16) or u16be(d, o + 2).toLong()
    fun s32be(d: ByteArray, o: Int): Int = u32be(d, o).toInt()

    fun sum8(d: ByteArray, from: Int, toExclusive: Int): Int {
        var s = 0
        for (i in from until toExclusive) s += u8(d, i)
        return s and 0xFF
    }

    /** CRC-16/MODBUS (poly 0xA001 reflected, init 0xFFFF). */
    fun crc16Modbus(d: ByteArray, from: Int = 0, toExclusive: Int = d.size): Int {
        var crc = 0xFFFF
        for (i in from until toExclusive) {
            crc = crc xor u8(d, i)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    fun indexOf(d: ByteArray, pattern: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..d.size - pattern.size) {
            for (j in pattern.indices) if (d[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    fun hex(d: ByteArray): String = d.joinToString(" ") { "%02X".format(it) }

    fun startsWithAny(name: String?, prefixes: List<String>): Boolean {
        val n = name?.trim()?.uppercase() ?: return false
        return prefixes.any { n.startsWith(it.uppercase()) }
    }
}

/** Growable receive buffer with a hard size cap. */
internal class RxBuffer(private val cap: Int = 2048) {
    private var buf = ByteArray(0)
    val size: Int get() = buf.size
    fun bytes(): ByteArray = buf
    fun append(d: ByteArray) {
        buf += d
        if (buf.size > cap) buf = buf.copyOfRange(buf.size - cap, buf.size)
    }
    fun drop(n: Int) { buf = if (n >= buf.size) ByteArray(0) else buf.copyOfRange(n, buf.size) }
    fun clear() { buf = ByteArray(0) }
}
