// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble

import kotlin.math.abs

/**
 * PowerQueen BMS uses the JBD/Xiaoxiang BMS protocol (common for Chinese LiFePO4 BMSes).
 *
 * Protocol frame format:
 *   Request:  0xDD | 0xA5 | CmdID | 0x00 | CheckH | CheckL | 0x77
 *   Response: 0xDD | CmdID | Status | DataLen | Data... | CheckH | CheckL | 0x77
 *
 * Key command IDs:
 *   0x03 - Basic info (voltage, current, SOC, temp, protection flags, etc.)
 *   0x04 - Cell voltages
 *   0x05 - Hardware info (version, date, serial)
 */
object BmsProtocol {

    // Service and characteristic UUIDs used by PowerQueen BMS
    // The battery uses a custom Nordic UART-like service
    const val SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb"
    const val CHAR_WRITE_UUID = "0000ff02-0000-1000-8000-00805f9b34fb"
    const val CHAR_NOTIFY_UUID = "0000ff01-0000-1000-8000-00805f9b34fb"

    // Fallback: some models use Nordic UART Service
    const val NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val NUS_RX_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val NUS_TX_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

    // 0xFFE0 profile (used by some PQ-24100B and similar models)
    // Two-characteristic variant: ffe2=write, ffe1=notify/indicate
    const val FFE0_SERVICE_UUID  = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val FFE0_WRITE_UUID    = "0000ffe2-0000-1000-8000-00805f9b34fb"
    const val FFE0_NOTIFY_UUID   = "0000ffe1-0000-1000-8000-00805f9b34fb"
    // Single-characteristic variant: ffe1 is used for both write and notify/indicate

    // 0xFFF0 profile (another JBD variant)
    const val FFF0_SERVICE_UUID  = "0000fff0-0000-1000-8000-00805f9b34fb"
    const val FFF0_WRITE_UUID    = "0000fff2-0000-1000-8000-00805f9b34fb"
    const val FFF0_NOTIFY_UUID   = "0000fff1-0000-1000-8000-00805f9b34fb"

    // 0xFD00 profile (seen on some newer OEM JBD chips)
    const val FD00_SERVICE_UUID  = "0000fd00-0000-1000-8000-00805f9b34fb"
    const val FD00_WRITE_UUID    = "0000fd02-0000-1000-8000-00805f9b34fb"
    const val FD00_NOTIFY_UUID   = "0000fd01-0000-1000-8000-00805f9b34fb"

    val KNOWN_PROFILES: List<Triple<String, String, String>> = listOf(
        Triple(SERVICE_UUID,      CHAR_WRITE_UUID,  CHAR_NOTIFY_UUID),
        Triple(NUS_SERVICE_UUID,  NUS_RX_UUID,      NUS_TX_UUID),
        // FFE0: try ffe2=write, ffe1=notify first (pq-24100B uses this pattern)
        Triple(FFE0_SERVICE_UUID, FFE0_WRITE_UUID,  FFE0_NOTIFY_UUID), // ffe2 = write, ffe1 = notify
        Triple(FFE0_SERVICE_UUID, FFE0_NOTIFY_UUID, FFE0_NOTIFY_UUID), // ffe1 = write + notify (fallback)
        Triple(FFF0_SERVICE_UUID, FFF0_WRITE_UUID,  FFF0_NOTIFY_UUID),
        Triple(FD00_SERVICE_UUID, FD00_WRITE_UUID,  FD00_NOTIFY_UUID),
    )

    // Command IDs (JBD DD-frame protocol)
    const val CMD_BASIC_INFO: Byte = 0x03
    const val CMD_CELL_VOLTAGES: Byte = 0x04
    const val CMD_HARDWARE_INFO: Byte = 0x05

    /**
     * Proprietary PowerQueen poll command confirmed via HCI snoop of OEM app.
     * Write to FFE2 (WRITE_NO_RESPONSE); responses arrive as 19-byte notifications on FFE1.
     */
    val PQ_POLL_COMMAND: ByteArray = byteArrayOf(
        0x00, 0x00, 0x04, 0x01, 0x13, 0x55.toByte(), 0xAA.toByte(), 0x17
    )

    /**
     * Parse 105-byte proprietary PQ/LiTime/Redodo BMS notification.
     * Protocol documented at: github.com/dmytro-tsepilov/pq_bms_bluetooth,
     *                          github.com/calledit/LiTime_BMS_bluetooth,
     *                          github.com/patman15/aiobmsble
     *
     * All multi-byte fields are little-endian.
     * [0-7]   fixed header: 00 00 65 01 93 55 AA 00
     * [8-11]  uint32 pack voltage mV
     * [12-15] uint32 cell-voltage sum mV
     * [16-47] 16 × uint16 cell voltages mV (cells 9-16 are zero on 8-cell pack)
     * [48-51] int32  current mA  (+ = charging, − = discharging)
     * [52-61] 5 × int16 temperatures °C (direct Celsius, signed)
     * [62-63] uint16 remaining capacity × 100 mAh  (÷100 → Ah)
     * [64-67] uint32 design capacity × 100 mAh     (÷100 → Ah)
     * [68-71] uint32 FET/heat state flags
     * [76-79] uint32 protection flags
     * [84-87] uint32 balancer bitmask (1 bit per cell)
     * [88-89] uint16 battery state (0=idle, 1=charging, 4=full/charge-disabled)
     * [90-91] uint16 SOC %
     * [92-95] uint32 SOH %
     * [96-99] uint32 cycle count
     * [104]   uint8  CRC (sum of bytes 0-103 & 0xFF)
     */
    fun parsePqNotification(data: ByteArray): BmsBasicInfo? {
        if (data.size < 19) return null
        if (data[0] != 0x00.toByte() || data[2] != 0x65.toByte() ||
            data[5] != 0x55.toByte() || data[6] != 0xAA.toByte()) return null

        val packVoltageV = getInt32LE(data, 8) / 1000.0

        // Current: int32 LE at [48-51] (NOT [10-11] — confirmed by open-source implementations)
        val currentA = if (data.size >= 52) getInt32LEsigned(data, 48) / 1000.0 else 0.0

        // Up to 5 temperature sensors, int16 LE, direct °C
        val temperatures = buildList {
            for (i in 0 until 5) {
                val off = 52 + i * 2
                if (data.size >= off + 2) add(getInt16LEsigned(data, off).toDouble())
            }
        }.filter { it != 0.0 }   // drop unused sensors (report 0)

        // Remaining capacity: uint16 LE at [62-63] × 0.01 Ah
        val remainAh = if (data.size >= 64) getInt16LE(data, 62) / 100.0 else 0.0

        // Design capacity: uint32 LE at [64-67] × 0.01 Ah
        val fullAh = if (data.size >= 68) getInt32LE(data, 64) / 100.0 else 0.0

        // FET / heat state flags
        val heatState      = if (data.size >= 72) getInt32LE(data, 68) else 0
        val dischargeFetOn = (heatState and 0x80) == 0
        val selfHeaterOn   = (heatState and 0x02) != 0

        // Protection flags
        val protectFlags = if (data.size >= 80) getInt32LE(data, 76) else 0

        // Balancer bitmask (1 bit per cell)
        val balanceBits = if (data.size >= 88) (getInt32LE(data, 84).toLong() and 0xFFFFFFFFL) else 0L

        // SOC
        val soc = if (data.size >= 92) getInt16LE(data, 90) else {
            val cellV = getInt16LE(data, 16) / 1000.0
            estimateSocLiFePO4(cellV).coerceIn(0, 100)
        }

        // Cycle count
        val cycleCount = if (data.size >= 100) getInt32LE(data, 96) else 0

        val cellCount = parsePqCellVoltages(data)?.size ?: 8

        return BmsBasicInfo(
            packVoltage     = packVoltageV,
            current         = currentA,
            remainCapacity  = remainAh,
            fullCapacity    = fullAh,
            cycleCount      = cycleCount,
            soc             = soc,
            cellCount       = cellCount,
            temperatures    = temperatures,
            chargeFetOn     = !selfHeaterOn,   // best proxy we have; charge FET bit not documented
            dischargeFetOn  = dischargeFetOn,
            protectFlags    = protectFlags,
            balanceBits     = balanceBits,
            softwareVersion = "",
            manufactureDate = "",
            rawData         = data
        )
    }

    /**
     * Extract individual cell voltages: 8 × uint16 LE at [16-31], in mV → V.
     * Cells 9-16 ([32-47]) exist in the format but are zero on an 8-cell pack.
     */
    fun parsePqCellVoltages(data: ByteArray): List<Double>? {
        if (data.size < 32) return null
        if (data[0] != 0x00.toByte() || data[2] != 0x65.toByte()) return null
        return (0 until 8).map { i -> getInt16LE(data, 16 + i * 2) / 1000.0 }
                          .filter { it > 0.5 }
    }

    /** Little-endian unsigned 16-bit */
    private fun getInt16LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    /** Little-endian signed 16-bit */
    private fun getInt16LEsigned(data: ByteArray, offset: Int): Int {
        val u = getInt16LE(data, offset)
        return if (u > 32767) u - 65536 else u
    }

    /** Little-endian unsigned 32-bit (returned as Int; reinterpret bits for unsigned use if needed) */
    private fun getInt32LE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8) or
        ((data[offset + 2].toInt() and 0xFF) shl 16) or
        ((data[offset + 3].toInt() and 0xFF) shl 24)

    /** Little-endian signed 32-bit */
    private fun getInt32LEsigned(data: ByteArray, offset: Int): Int = getInt32LE(data, offset)

    /** Piecewise-linear SOC fallback from LiFePO4 cell voltage (used only if packet < 92 bytes) */
    private fun estimateSocLiFePO4(cellV: Double): Int {
        val curve = arrayOf(
            3.65 to 100, 3.45 to 90, 3.40 to 80,
            3.35 to 70,  3.33 to 60, 3.32 to 50,
            3.30 to 40,  3.28 to 30, 3.25 to 20,
            3.20 to 10,  3.10 to 5,  3.00 to 0
        )
        if (cellV >= curve.first().first) return curve.first().second
        if (cellV <= curve.last().first)  return curve.last().second
        for (i in 0 until curve.size - 1) {
            val (v1, s1) = curve[i]
            val (v2, s2) = curve[i + 1]
            if (cellV <= v1 && cellV >= v2) {
                val frac = (cellV - v2) / (v1 - v2)
                return (s2 + frac * (s1 - s2)).toInt()
            }
        }
        return 0
    }

    /** Build a read request frame: DD A5 cmdId 00 checkH checkL 77 */
    fun buildReadRequest(cmdId: Byte): ByteArray {
        val data = byteArrayOf(0xDD.toByte(), 0xA5.toByte(), cmdId, 0x00)
        val checksum = calculateChecksum(byteArrayOf(cmdId, 0x00))
        return data + checksum + byteArrayOf(0x77.toByte())
    }

    private fun calculateChecksum(data: ByteArray): ByteArray {
        var sum = 0
        for (b in data) sum += (b.toInt() and 0xFF)
        val crc = (0x10000 - sum) and 0xFFFF
        return byteArrayOf(((crc shr 8) and 0xFF).toByte(), (crc and 0xFF).toByte())
    }

    fun verifyChecksum(frame: ByteArray): Boolean {
        if (frame.size < 7) return false
        // Data bytes are bytes 1 to (len-3): cmdId + status + dataLen + data bytes
        val dataForCheck = frame.slice(1 until frame.size - 3).toByteArray()
        val expectedCheck = calculateChecksum(dataForCheck)
        val actualCheck = frame.slice(frame.size - 3 until frame.size - 1).toByteArray()
        return expectedCheck.contentEquals(actualCheck)
    }

    /** Parse basic info response (CMD 0x03) */
    fun parseBasicInfo(data: ByteArray): BmsBasicInfo? {
        if (data.size < 4) return null
        // Find the DD 03 00 header in the buffer (handles partial packets)
        val start = findFrameStart(data, 0x03) ?: return null
        val frame = data.copyOfRange(start, data.size)
        if (frame.size < 7) return null
        if (frame[0] != 0xDD.toByte() || frame[2] != 0x00.toByte()) return null

        val dataLen = frame[3].toInt() and 0xFF
        if (frame.size < dataLen + 7) return null

        val d = frame.copyOfRange(4, 4 + dataLen)
        if (d.size < 23) return null

        return try {
            val packVoltage = getInt16(d, 0) * 10  // mV (10mV units)
            val current = getInt16Signed(d, 2) * 10  // mA (10mA units)
            val remainCap = getInt16(d, 4) * 10  // mAh (10mAh units)
            val fullCap = getInt16(d, 6) * 10    // mAh
            val cycleCount = getInt16(d, 8)
            // bytes 10-11 = manufacture date (BCD)
            val manufactureDate = decodeDate(getInt16(d, 10))
            val balanceBits1 = getInt16(d, 12)
            val balanceBits2 = getInt16(d, 14)
            val protectFlags = getInt16(d, 16)
            val softwareVersion = String.format("%d.%d",
                (d[18].toInt() and 0xFF) shr 4,
                d[18].toInt() and 0x0F)
            // bytes 18=SW version byte, 19=SOC%, 20=FET control, 21=cell count, 22=NTC count
            val socPercent = d[19].toInt() and 0xFF
            val fetControl = if (d.size > 20) d[20].toInt() and 0xFF else 0
            val cellCount = if (d.size > 21) d[21].toInt() and 0xFF else 0
            val ntcCount = if (d.size > 22) d[22].toInt() and 0xFF else 0

            // NTC temperature readings (tenths of Kelvin, offset 2731 = 273.1K = 0°C)
            val temperatures = mutableListOf<Double>()
            for (i in 0 until ntcCount) {
                val rawTemp = getInt16(d, 23 + i * 2)
                temperatures.add((rawTemp - 2731) / 10.0)
            }

            val chargeFetOn = (fetControl and 0x01) != 0
            val dischargeFetOn = (fetControl and 0x02) != 0

            BmsBasicInfo(
                packVoltage = packVoltage / 1000.0,        // V
                current = current / 1000.0,                 // A
                remainCapacity = remainCap / 1000.0,        // Ah
                fullCapacity = fullCap / 1000.0,            // Ah
                cycleCount = cycleCount,
                soc = socPercent,
                cellCount = cellCount,
                temperatures = temperatures,
                chargeFetOn = chargeFetOn,
                dischargeFetOn = dischargeFetOn,
                protectFlags = protectFlags,
                balanceBits = balanceBits1.toLong() or (balanceBits2.toLong() shl 16),
                softwareVersion = softwareVersion,
                manufactureDate = manufactureDate,
                rawData = d
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Parse cell voltages response (CMD 0x04) */
    fun parseCellVoltages(data: ByteArray): List<Double>? {
        val start = findFrameStart(data, 0x04) ?: return null
        val frame = data.copyOfRange(start, data.size)
        if (frame.size < 7) return null
        if (frame[0] != 0xDD.toByte() || frame[2] != 0x00.toByte()) return null

        val dataLen = frame[3].toInt() and 0xFF
        if (frame.size < dataLen + 7) return null

        val d = frame.copyOfRange(4, 4 + dataLen)
        val cellCount = dataLen / 2
        return (0 until cellCount).map { i ->
            getInt16(d, i * 2) / 1000.0  // Convert mV to V
        }
    }

    /** Parse hardware info response (CMD 0x05) */
    fun parseHardwareInfo(data: ByteArray): BmsHardwareInfo? {
        val start = findFrameStart(data, 0x05) ?: return null
        val frame = data.copyOfRange(start, data.size)
        if (frame.size < 7) return null
        if (frame[0] != 0xDD.toByte() || frame[2] != 0x00.toByte()) return null

        val dataLen = frame[3].toInt() and 0xFF
        if (frame.size < dataLen + 7) return null

        val d = frame.copyOfRange(4, 4 + dataLen)
        return try {
            BmsHardwareInfo(
                hardwareVersion = String(d).trim()
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun findFrameStart(data: ByteArray, cmdId: Byte): Int? {
        for (i in data.indices) {
            if (data[i] == 0xDD.toByte() && i + 1 < data.size && data[i + 1] == cmdId) {
                return i
            }
        }
        return null
    }

    private fun getInt16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun getInt16Signed(data: ByteArray, offset: Int): Int {
        val unsigned = getInt16(data, offset)
        return if (unsigned > 32767) unsigned - 65536 else unsigned
    }

    private fun decodeDate(raw: Int): String {
        val year = 2000 + ((raw shr 9) and 0x7F)
        val month = (raw shr 5) and 0x0F
        val day = raw and 0x1F
        return "$year-%02d-%02d".format(month, day)
    }

    /** Decode PQ/LiTime/Redodo protection flag bits (bytes [76-79] uint32 LE) */
    fun decodeProtectionFlags(flags: Int): List<ProtectionFlag> {
        return listOf(
            ProtectionFlag(0x0004,  "Cell Overvoltage", flags),
            ProtectionFlag(0x0020,  "Cell Undervoltage", flags),
            ProtectionFlag(0x0040,  "Charge Overcurrent", flags),
            ProtectionFlag(0x0080,  "Discharge Overcurrent", flags),
            ProtectionFlag(0x0100,  "High Temperature (Charge)", flags),
            ProtectionFlag(0x0200,  "High Temperature (Discharge)", flags),
            ProtectionFlag(0x0400,  "Low Temperature (Charge)", flags),
            ProtectionFlag(0x0800,  "Low Temperature (Discharge)", flags),
            ProtectionFlag(0x4000,  "Short Circuit Protection", flags),
        ).filter { it.active }
    }

    fun getBatteryStatusText(info: BmsBasicInfo): String {
        return when {
            info.current > 0.1 -> "Charging"
            info.current < -0.1 -> "Discharging"
            else -> "Idle / Standby"
        }
    }

    fun getPowerWatts(info: BmsBasicInfo): Double {
        return abs(info.packVoltage * info.current)
    }
}

data class ProtectionFlag(val mask: Int, val name: String, private val flags: Int) {
    val active: Boolean get() = (flags and mask) != 0
}

data class BmsBasicInfo(
    val packVoltage: Double,       // Volts
    val current: Double,           // Amperes (+charge, -discharge)
    val remainCapacity: Double,    // Ah
    val fullCapacity: Double,      // Ah
    val cycleCount: Int,
    val soc: Int,                  // State of Charge %
    val cellCount: Int,
    val temperatures: List<Double>, // Celsius
    val chargeFetOn: Boolean,
    val dischargeFetOn: Boolean,
    val protectFlags: Int,
    val balanceBits: Long,
    val softwareVersion: String,
    val manufactureDate: String,
    val rawData: ByteArray,
    /** Driver-decoded alarm texts; when set, replaces the PQ bit decoding. */
    val alarmTexts: List<String>? = null,
    /** Explicit MOSFET temperature when the driver knows which probe it is. */
    val mosTemperature: Double? = null,
    /** Explicit state of health when the BMS reports one. */
    val sohReported: Int? = null,
    /** Driver id that produced this data. */
    val source: String = "",
    /** All MOSFET temperature probes (°C), when the driver knows them. */
    val mosTemperatures: List<Double> = emptyList(),
    /** Self-heater active (null = not reported). */
    val heating: Boolean? = null
) {
    val soh: Int get() = sohReported ?: if (fullCapacity > 0) ((remainCapacity / fullCapacity) * 100).toInt().coerceIn(0, 100) else 0
    val powerWatts: Double get() = BmsProtocol.getPowerWatts(this)
    val statusText: String get() = BmsProtocol.getBatteryStatusText(this)
    val cellTemperature: Double? get() = temperatures.firstOrNull()
    val mosfetTemperature: Double? get() = mosTemperature ?: if (temperatures.size > 1) temperatures[1] else null
    val protectionFlags: List<ProtectionFlag> get() =
        alarmTexts?.map { ProtectionFlag(1, it, 1) } ?: BmsProtocol.decodeProtectionFlags(protectFlags)
}

data class BmsHardwareInfo(
    val hardwareVersion: String
)
