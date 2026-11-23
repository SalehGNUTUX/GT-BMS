// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble

/**
 * 123SmartBMS Gen3 protocol handler.
 *
 * The 123SmartBMS uses a Raytac nRF52810 BLE module as a transparent UART bridge.
 * Protocol has two authentication layers:
 *   1. Module auth: packed BCD PIN sent to NUS RX → 0x0D response
 *   2. BMS auth: ASCII commands D! → PW<pin>! → E! over NUS
 *
 * Data arrives as CR-terminated ASCII lines (U_, V_, C_, T_, E_, H_, M_, B_).
 * All numeric values are hexadecimal. BMS sends exactly 2 lines per E! request,
 * then goes quiet. Must re-authenticate for each data burst (~3s cycle).
 */
object SmartBmsProtocol {

    // Nordic UART Service UUIDs (same as in BmsProtocol)
    const val NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val NUS_RX_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  // write TO BMS
    const val NUS_TX_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  // notify FROM BMS

    // ASCII control characters
    const val ACK: Byte = 0x06
    const val NACK: Byte = 0x15
    const val BELL: Byte = 0x07
    const val CR: Byte = 0x0D

    // ASCII commands
    const val CMD_STOP_DATA = "D!"
    const val CMD_START_DATA = "E!"

    // Temperature offset: raw hex - 272 = degrees Celsius
    private const val TEMP_OFFSET = 272

    /** Encode PIN as packed BCD for nRF52810 module-level auth. */
    fun pinToBcd(pin: String): ByteArray {
        val padded = if (pin.length % 2 != 0) "0$pin" else pin
        return ByteArray(padded.length / 2) { i ->
            padded.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Build ASCII auth command: "PW<pin>!" */
    fun pinToAuthCommand(pin: String): String = "PW${pin}!"

    /** Convert ASCII command to bytes with CR terminator. */
    fun commandToBytes(cmd: String): ByteArray =
        "${cmd}\r".toByteArray(Charsets.US_ASCII)

    /**
     * Detect whether a GATT service list indicates a 123SmartBMS device.
     * 123BMS uses NUS as its only data service, plus standard services
     * (Device Info 0x180a, Battery 0x180f, Tx Power 0x1804).
     * PowerQueen uses ff00/ffe0/fff0/fd00 services.
     */
    fun is123SmartBms(serviceUuids: List<String>): Boolean {
        val pqServices = setOf(
            "0000ff00-0000-1000-8000-00805f9b34fb",
            "0000ffe0-0000-1000-8000-00805f9b34fb",
            "0000fff0-0000-1000-8000-00805f9b34fb",
            "0000fd00-0000-1000-8000-00805f9b34fb",
        )
        val hasNus = serviceUuids.any { it.equals(NUS_SERVICE_UUID, ignoreCase = true) }
        val hasPqService = serviceUuids.any { uuid -> pqServices.any { it.equals(uuid, ignoreCase = true) } }
        val hasBatteryService = serviceUuids.any { it.startsWith("0000180f", ignoreCase = true) }
        val hasDeviceInfo = serviceUuids.any { it.startsWith("0000180a", ignoreCase = true) }
        // 123BMS: has NUS + standard services, but NO PQ-specific services
        return hasNus && !hasPqService && (hasBatteryService || hasDeviceInfo)
    }

    /** Decode 123BMS status byte flags. */
    fun decodeStatusFlags(statusByte: Int): List<SmartBmsFlag> = buildList {
        add(SmartBmsFlag(0x01, "Charge Allowed", statusByte))
        add(SmartBmsFlag(0x02, "Discharge Allowed", statusByte))
        if (statusByte and 0x04 != 0) add(SmartBmsFlag(0x04, "Cell Comm Error", statusByte))
        if (statusByte and 0x08 != 0) add(SmartBmsFlag(0x08, "Under Voltage", statusByte))
        if (statusByte and 0x10 != 0) add(SmartBmsFlag(0x10, "Over Voltage", statusByte))
        if (statusByte and 0x20 != 0) add(SmartBmsFlag(0x20, "Under Temperature", statusByte))
        if (statusByte and 0x40 != 0) add(SmartBmsFlag(0x40, "Over Temperature", statusByte))
        if (statusByte and 0x80 != 0) add(SmartBmsFlag(0x80, "SoC Not Calibrated", statusByte))
    }
}

data class SmartBmsFlag(val mask: Int, val name: String, private val flags: Int) {
    val active: Boolean get() = (flags and mask) != 0
    val isError: Boolean get() = mask >= 0x04 // bits 2-7 are error/warning flags
}

/**
 * Accumulates ASCII data lines from 123SmartBMS into a snapshot.
 * Each E! request returns 2 lines; a full rotation takes ~48s for 8 cells.
 */
class SmartBmsState {
    // U line - pack
    var packVoltage: Double = Double.NaN
    var current1: Double = Double.NaN
    var current2: Double = Double.NaN
    var netCurrent: Double = Double.NaN

    // V line - cell range
    var cellVLow: Double = Double.NaN
    var cellVHigh: Double = Double.NaN
    var cellLowNum: Int = -1
    var cellHighNum: Int = -1
    var cellVBalance: Double = Double.NaN

    // C line - per-cell (rotates through cells)
    var numCells: Int = 0
    var statusByte: Int = 0
    val cellVoltages = mutableMapOf<Int, Double>()
    val cellTemps = mutableMapOf<Int, Double>()

    // T line - temperature range
    var tempLow: Int = Int.MIN_VALUE
    var tempLowCell: Int = -1
    var tempHigh: Int = Int.MIN_VALUE
    var tempHighCell: Int = -1
    var tempSensorFlag: String = ""

    // E line - energy + SoC (authoritative SoC source)
    var soc: Int = -1
    var energyStored: Int = 0      // Wh
    var eTotalCollected: Int = 0   // Wh
    var eTodayConsumed: Int = 0    // Wh

    // H line - energy counters
    var todayCollected: Int = 0    // Wh
    var todayConsumed: Int = 0     // Wh
    var totalCollected: Int = 0    // Wh
    var totalConsumed: Int = 0     // Wh

    var updateCount: Int = 0

    val powerWatts: Double get() {
        val v = if (packVoltage.isNaN()) 0.0 else packVoltage
        val i = if (netCurrent.isNaN()) 0.0 else netCurrent
        return kotlin.math.abs(v * i)
    }

    val statusText: String get() {
        val i = if (netCurrent.isNaN()) 0.0 else netCurrent
        return when {
            i > 0.1 -> "Charging"
            i < -0.1 -> "Discharging"
            else -> "Idle / Standby"
        }
    }

    val chargeAllowed: Boolean get() = (statusByte and 0x01) != 0
    val dischargeAllowed: Boolean get() = (statusByte and 0x02) != 0

    val alertFlags: List<SmartBmsFlag> get() =
        SmartBmsProtocol.decodeStatusFlags(statusByte).filter { it.isError && it.active }

    /**
     * Same data model as the other BMS drivers, so 123SmartBMS uses the common battery screen.
     * The BMS reports stored energy (Wh), not Ah: remaining Ah ≈ Wh ÷ pack V, full ≈ remaining ÷ SOC.
     */
    fun toBasicInfo(): BmsBasicInfo? {
        if (soc < 0 || packVoltage.isNaN()) return null
        val remainAh = if (packVoltage > 0) energyStored / packVoltage else 0.0
        val fullAh = if (soc > 0) remainAh * 100.0 / soc else 0.0
        val cells = cellVoltagesList()
        return com.vanaccess.bms.ble.drivers.basicInfo(
            source = "123bms", voltage = packVoltage, current = if (netCurrent.isNaN()) 0.0 else netCurrent,
            soc = soc, remainAh = remainAh, fullAh = fullAh, cellCount = if (numCells > 0) numCells else cells.size,
            temps = cellTemps.toSortedMap().values.toList(),
            chargeFet = chargeAllowed, dischargeFet = dischargeAllowed,
            alarms = alertFlags.map { it.name },
        )
    }

    /** Get ordered cell voltages list (for chart). */
    fun cellVoltagesList(): List<Double> {
        if (cellVoltages.isEmpty()) return emptyList()
        val maxCell = cellVoltages.keys.maxOrNull() ?: return emptyList()
        return (1..maxCell).map { cellVoltages[it] ?: 0.0 }
    }

    fun copy(): SmartBmsState = SmartBmsState().also { c ->
        c.packVoltage = packVoltage; c.current1 = current1
        c.current2 = current2; c.netCurrent = netCurrent
        c.cellVLow = cellVLow; c.cellVHigh = cellVHigh
        c.cellLowNum = cellLowNum; c.cellHighNum = cellHighNum
        c.cellVBalance = cellVBalance; c.numCells = numCells
        c.statusByte = statusByte
        c.cellVoltages.putAll(cellVoltages); c.cellTemps.putAll(cellTemps)
        c.tempLow = tempLow; c.tempLowCell = tempLowCell
        c.tempHigh = tempHigh; c.tempHighCell = tempHighCell
        c.tempSensorFlag = tempSensorFlag; c.soc = soc
        c.energyStored = energyStored; c.eTotalCollected = eTotalCollected
        c.eTodayConsumed = eTodayConsumed; c.todayCollected = todayCollected
        c.todayConsumed = todayConsumed; c.totalCollected = totalCollected
        c.totalConsumed = totalConsumed; c.updateCount = updateCount
    }
}

/**
 * Parses ASCII data lines from the 123SmartBMS and accumulates them into SmartBmsState.
 */
class SmartBmsAsciiParser {

    val state = SmartBmsState()

    /** Parse one CR-stripped data line. Returns true if recognized. */
    fun parseLine(line: String): Boolean {
        if (line.length < 3 || line[1] != '_') return false
        val fields = line.substring(2).split("_")
        return try {
            when (line[0]) {
                'U' -> parseU(fields)
                'V' -> parseV(fields)
                'C' -> parseC(fields)
                'T' -> parseT(fields)
                'E' -> parseE(fields)
                'H' -> parseH(fields)
                'M' -> true // known but not critical
                'B' -> true // known but not critical
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun parseU(f: List<String>): Boolean {
        if (f.size < 4) return false
        state.packVoltage = f[0].toInt(16) * 0.005
        state.current1 = parseSignedCurrent(f[1])
        state.current2 = parseSignedCurrent(f[2])
        state.netCurrent = parseSignedCurrent(f[3])
        return true
    }

    private fun parseV(f: List<String>): Boolean {
        if (f.size < 5) return false
        state.cellVLow = f[0].toInt(16) * 0.005
        state.cellLowNum = f[1].toInt(16)
        state.cellVHigh = f[2].toInt(16) * 0.005
        state.cellHighNum = f[3].toInt(16)
        state.cellVBalance = f[4].toInt(16) * 0.005
        return true
    }

    private fun parseC(f: List<String>): Boolean {
        if (f.size < 6) return false
        val cellNum = f[0].toInt(16)
        val totalCells = f[1].toInt(16)
        val voltage = f[2].toInt(16) * 0.005
        val temp = f[3].toInt(16) - SmartBmsProtocol.run { 272 }
        val statusByte = f[4].toInt(16)
        state.numCells = totalCells
        state.statusByte = statusByte
        state.cellVoltages[cellNum] = voltage
        state.cellTemps[cellNum] = temp.toDouble()
        return true
    }

    private fun parseT(f: List<String>): Boolean {
        if (f.size < 5) return false
        state.tempLow = f[0].toInt(16) - 272
        state.tempLowCell = f[1].toInt(16)
        state.tempHigh = f[2].toInt(16) - 272
        state.tempHighCell = f[3].toInt(16)
        state.tempSensorFlag = f[4]
        return true
    }

    private fun parseE(f: List<String>): Boolean {
        if (f.size < 4) return false
        state.energyStored = f[0].toInt(16)
        state.eTotalCollected = f[1].toInt(16)
        state.eTodayConsumed = f[2].toInt(16)
        state.soc = f[3].toInt(16)
        return true
    }

    private fun parseH(f: List<String>): Boolean {
        if (f.size < 6) return false
        // f[0] and f[3] are flag bytes (0xFF)
        state.todayCollected = f[1].toInt(16)
        state.todayConsumed = f[2].toInt(16)
        state.totalCollected = f[4].toInt(16)
        state.totalConsumed = f[5].toInt(16)
        return true
    }

    private fun parseSignedCurrent(s: String): Double {
        val trimmed = s.trim()
        if (trimmed.startsWith("X") || trimmed == "0000") return 0.0
        val sign = if (trimmed.startsWith("-")) -1.0 else 1.0
        val digits = trimmed.removePrefix("+").removePrefix("-")
        return sign * digits.toInt(16) * 0.125
    }
}
