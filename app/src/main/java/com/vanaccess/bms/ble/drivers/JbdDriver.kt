// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble.drivers

import com.vanaccess.bms.ble.BmsBasicInfo
import com.vanaccess.bms.ble.BmsHardwareInfo
import com.vanaccess.bms.ble.BmsProtocol

/**
 * JBD / Xiaoxiang / Overkill Solar "DD A5" protocol (very common Chinese smart BMS).
 *
 * Request : DD A5 <cmd> 00 <chkH> <chkL> 77      chk = 0x10000 - (cmd + 0)
 * Response: DD <cmd> <status> <len> <data…> <chkH> <chkL> 77
 *           chk = 0x10000 - (status + len + Σdata)
 */
class JbdDriver : BmsDriver {
    override val id = "jbd"
    override val displayName = "JBD / Xiaoxiang / Overkill"
    override val profiles = listOf(
        GattProfile(BmsProtocol.SERVICE_UUID, BmsProtocol.CHAR_WRITE_UUID, BmsProtocol.CHAR_NOTIFY_UUID),
        GattProfile(BmsProtocol.FFE0_SERVICE_UUID, BmsProtocol.FFE0_WRITE_UUID, BmsProtocol.FFE0_NOTIFY_UUID),
        GattProfile(BmsProtocol.FFE0_SERVICE_UUID, BmsProtocol.FFE0_NOTIFY_UUID, BmsProtocol.FFE0_NOTIFY_UUID),
        GattProfile(BmsProtocol.FFF0_SERVICE_UUID, BmsProtocol.FFF0_WRITE_UUID, BmsProtocol.FFF0_NOTIFY_UUID),
        GattProfile(BmsProtocol.NUS_SERVICE_UUID, BmsProtocol.NUS_RX_UUID, BmsProtocol.NUS_TX_UUID),
    )
    override val interCommandDelayMs = 400L

    private val rx = RxBuffer()
    private var hwRequested = false

    override fun matchesName(name: String?) = Bytes.startsWithAny(
        name, listOf("JBD", "XIAOXIANG", "SP0", "SP1", "SP2", "SP3", "SP4", "SP5", "DP0", "DP1",
            "GJ-", "OVERKILL", "AP2", "LT-")
    )

    override fun reset() { rx.clear(); hwRequested = false }

    override fun pollCommands(): List<ByteArray> {
        val cmds = mutableListOf(
            BmsProtocol.buildReadRequest(BmsProtocol.CMD_BASIC_INFO),
            BmsProtocol.buildReadRequest(BmsProtocol.CMD_CELL_VOLTAGES)
        )
        if (!hwRequested) { cmds += BmsProtocol.buildReadRequest(BmsProtocol.CMD_HARDWARE_INFO); hwRequested = true }
        return cmds
    }

    override fun onNotification(data: ByteArray): DriverOutput? {
        rx.append(data)
        var out: DriverOutput? = null
        while (true) {
            val b = rx.bytes()
            val start = b.indexOfFirst { it == 0xDD.toByte() }
            if (start < 0) { rx.clear(); break }
            if (start > 0) { rx.drop(start); continue }
            if (b.size < 7) break
            val cmd = Bytes.u8(b, 1)
            val len = Bytes.u8(b, 3)
            if (cmd !in 0x03..0x05) { rx.drop(1); continue }
            val total = len + 7
            if (b.size < total) break
            val frame = b.copyOfRange(0, total)
            if (frame[total - 1] != 0x77.toByte() || !checksumOk(frame)) { rx.drop(1); continue }
            rx.drop(total)
            if (Bytes.u8(frame, 2) != 0) continue
            out = merge(out, decode(cmd, frame))
        }
        return out
    }

    private fun checksumOk(f: ByteArray): Boolean {
        var sum = 0
        for (i in 2 until f.size - 3) sum += Bytes.u8(f, i)
        val chk = (0x10000 - sum) and 0xFFFF
        return chk == Bytes.u16be(f, f.size - 3)
    }

    private fun decode(cmd: Int, frame: ByteArray): DriverOutput? = when (cmd) {
        0x03 -> BmsProtocol.parseBasicInfo(frame)?.let { DriverOutput(basic = it.copy(source = id, alarmTexts = jbdAlarms(it.protectFlags))) }
        0x04 -> BmsProtocol.parseCellVoltages(frame)?.let { DriverOutput(cells = it) }
        0x05 -> BmsProtocol.parseHardwareInfo(frame)?.let { DriverOutput(hardware = it) }
        else -> null
    }

    companion object {
        fun jbdAlarms(flags: Int): List<String> {
            val names = listOf(
                "Cell overvoltage", "Cell undervoltage", "Pack overvoltage", "Pack undervoltage",
                "Charge over-temperature", "Charge under-temperature",
                "Discharge over-temperature", "Discharge under-temperature",
                "Charge overcurrent", "Discharge overcurrent", "Short circuit",
                "Front-end IC error", "MOS software lock"
            )
            return names.filterIndexed { i, _ -> flags and (1 shl i) != 0 }
        }

        fun merge(a: DriverOutput?, b: DriverOutput?): DriverOutput? = a?.merge(b) ?: b
    }
}

/**
 * Original VanAccess behaviour: PowerQueen / LiTime / Redodo proprietary 105-byte packets
 * polled with a fixed command; JBD DD-frames are still decoded if they arrive.
 */
class PowerQueenDriver : BmsDriver {
    override val id = "powerqueen"
    override val displayName = "PowerQueen / LiTime / Redodo"
    override val profiles = BmsProtocol.KNOWN_PROFILES.map { GattProfile(it.first, it.second, it.third) }
    override val pollIntervalMs = 1000L
    private val jbd = JbdDriver()

    override fun matchesName(name: String?) =
        Bytes.startsWithAny(name, listOf("P-12", "P-24", "P-48", "PQ", "L-12", "L-24", "L-48", "LITIME", "R-12", "R-24", "REDODO"))

    override fun reset() = jbd.reset()
    override fun pollCommands() = listOf(BmsProtocol.PQ_POLL_COMMAND)

    override fun onNotification(data: ByteArray): DriverOutput? {
        BmsProtocol.parsePqNotification(data)?.let { info ->
            return DriverOutput(basic = info.copy(source = id), cells = BmsProtocol.parsePqCellVoltages(data))
        }
        return jbd.onNotification(data)
    }
}

/** Helper to build a BmsBasicInfo with sensible defaults. */
internal fun basicInfo(
    source: String,
    voltage: Double,
    current: Double,
    soc: Int,
    remainAh: Double = 0.0,
    fullAh: Double = 0.0,
    cycles: Int = 0,
    cellCount: Int = 0,
    temps: List<Double> = emptyList(),
    mosTemp: Double? = null,
    chargeFet: Boolean = true,
    dischargeFet: Boolean = true,
    balanceBits: Long = 0,
    alarms: List<String> = emptyList(),
    soh: Int? = null,
    raw: ByteArray = ByteArray(0),
    version: String = "",
    mosTemps: List<Double> = emptyList(),
    heating: Boolean? = null
) = BmsBasicInfo(
    packVoltage = voltage, current = current, remainCapacity = remainAh, fullCapacity = fullAh,
    cycleCount = cycles, soc = soc.coerceIn(0, 100), cellCount = cellCount, temperatures = temps,
    chargeFetOn = chargeFet, dischargeFetOn = dischargeFet, protectFlags = 0,
    balanceBits = balanceBits, softwareVersion = version, manufactureDate = "", rawData = raw,
    alarmTexts = alarms, mosTemperature = mosTemp, sohReported = soh, source = source,
    mosTemperatures = mosTemps.ifEmpty { listOfNotNull(mosTemp) }, heating = heating
)
