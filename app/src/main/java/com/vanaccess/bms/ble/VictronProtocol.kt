// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Victron Energy BLE advertisement protocol.
 *
 * Victron devices broadcast encrypted manufacturer-specific data (Company ID 0x02E1).
 * No BLE connection is needed — data is picked up purely from scan advertisements.
 *
 * Manufacturer data layout (bytes returned by Android after stripping the Company ID):
 *   [0-1]  Model ID       (uint16 LE)
 *   [2]    Extra byte     (device-type/model code, varies per device, e.g. 0x75 for SmartSolar)
 *   [3]    Extra byte     (additional static header byte)
 *   [4]    Record type    (0x01=SolarCharger, 0x04=DcDcConverter/Orion, …)
 *   [5-6]  Nonce / IV     (uint16 LE, increments with each advertisement)
 *   [7]    Extra byte     (static per device type)
 *   [8…]   AES-128-CTR encrypted payload
 *
 * Encryption:
 *   Algorithm : AES-128-CTR, NoPadding
 *   Key       : 16-byte advertisement key shown in VictronConnect → Product info
 *               (enter as 32 hex characters, no spaces)
 *   IV block  : 16 bytes = [0x00 × 14, nonce_msb, nonce_lsb]
 *               (matches pycryptodome: nonce=bytes(8), initial_value=uint16LE(bytes[5:7]))
 *
 * Payload byte offsets are based on the victron-ble open-source library and Victron's
 * published BLE advertising specification. Verify values against VictronConnect readouts.
 */
object VictronProtocol {

    const val COMPANY_ID = 0x02E1

    const val TYPE_SOLAR_CHARGER    = 0x01
    const val TYPE_DC_DC_CONVERTER  = 0x04   // Orion-Tr Smart (no current broadcast)
    const val TYPE_ORION_XS         = 0x0F   // Orion XS (has input+output current)

    // Sentinel values meaning "not available" in Victron payloads
    private const val UINT16_NA = 0xFFFF
    private const val INT16_NA  = 0x7FFF

    fun getRecordType(mfrData: ByteArray): Int =
        if (mfrData.size > 4) mfrData[4].toInt() and 0xFF else -1

    /**
     * Decrypt Victron advertisement payload with AES-128-CTR.
     *
     * Victron embeds key[0] at mfrData[7] so the key can be validated without decrypting.
     * If this byte doesn't match the supplied key, decryption is skipped and null is returned
     * (a wrong key would produce random-looking garbage that changes every advertisement).
     *
     * @param advKeyHex  32-char hex advertisement key from VictronConnect → Product info
     * @param mfrData    manufacturer data bytes as returned by Android (company ID stripped)
     * @return decrypted payload bytes, or null on error / key mismatch
     */
    fun decrypt(advKeyHex: String, mfrData: ByteArray): ByteArray? {
        if (mfrData.size < 9) return null   // need 8-byte header + at least 1 encrypted byte
        return try {
            val key = hexToBytes(advKeyHex) ?: return null

            // Key validation: Victron stores key[0] at mfrData[7].
            // Mismatch means the advertisement key is wrong — skip decryption.
            if (key[0] != mfrData[7]) {
                android.util.Log.w("VictronProtocol",
                    "Advertisement key mismatch: mfrData[7]=0x%02X but key[0]=0x%02X. " +
                    "Re-enter the key from VictronConnect → Product info.".format(
                        mfrData[7].toInt() and 0xFF, key[0].toInt() and 0xFF))
                return null
            }

            // Build the 16-byte AES-CTR counter block.
            // victron-ble uses: Counter.new(128, initial_value=uint16LE(mfrData[5:7]), little_endian=True)
            // Little-endian 128-bit counter → nonce bytes go at positions [0] and [1], not [14]/[15].
            val ivBlock = ByteArray(16)   // all zeros
            ivBlock[0] = mfrData[5]       // nonce LSB  (little-endian counter byte 0)
            ivBlock[1] = mfrData[6]       // nonce MSB  (little-endian counter byte 1)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivBlock))
            cipher.doFinal(mfrData.copyOfRange(8, mfrData.size))
        } catch (e: Exception) {
            android.util.Log.w("VictronProtocol", "decrypt failed: ${e.message}")
            null
        }
    }

    /**
     * Returns the expected first byte (key[0]) embedded in the advertisement — i.e. mfrData[7].
     * Use this to tell the user which two hex digits to look for at the start of their key.
     */
    fun expectedKeyFirstByte(mfrData: ByteArray): String =
        if (mfrData.size >= 8) "%02X".format(mfrData[7].toInt() and 0xFF) else "??"

    // ── Solar Charger (MPPT) ─────────────────────────────────────────────────

    /**
     * Parse decrypted SmartSolar MPPT payload (record type 0x01).
     *
     * Byte layout of decrypted data (bit-packed, all fields byte-aligned except load):
     *   [0]      charge_state          uint8
     *   [1]      charger_error         uint8
     *   [2-3]    battery_voltage       int16  LE, 10 mV/LSB  → ÷100 V   NA=0x7FFF
     *   [4-5]    battery_current       int16  LE, 100 mA/LSB → ÷10  A   NA=0x7FFF
     *   [6-7]    yield_today           uint16 LE, 10 Wh/LSB  → ×10  Wh  NA=0xFFFF
     *   [8-9]    solar_power           uint16 LE, 1 W/LSB              NA=0xFFFF
     *   bits80-88 external_device_load uint9,    100 mA/LSB → ÷10  A   NA=0x1FF
     */
    // Valid SmartSolar charge states per Victron spec
    private val VALID_CHARGE_STATES = setOf(0,1,2,3,4,5,6,7,9,11,245,246,247,248,249,252,255)

    fun parseSolarCharger(d: ByteArray): SmartSolarData? {
        if (d.size < 10) return null
        val chargeState = d[0].toInt() and 0xFF
        if (chargeState !in VALID_CHARGE_STATES) return null
        return try {
            val error       = d[1].toInt() and 0xFF
            val rawV     = uint16LE(d, 2)
            // Voltage sanity: real batteries are < 100 V (raw < 10000); anything higher is garbage
            if (rawV != INT16_NA && rawV > 10000) return null
            val rawI     = int16LE(d, 4)
            val rawYield = uint16LE(d, 6)
            val rawPower = uint16LE(d, 8)
            // external_device_load: 9-bit unsigned at bits 80-88 (bytes 10 low + byte 11 bit 0)
            val rawLoad  = if (d.size >= 12) (uint16LE(d, 10) and 0x1FF) else 0x1FF
            SmartSolarData(
                chargeState     = chargeState,
                chargeStateText = solarChargeStateText(chargeState),
                error           = error,
                batteryVoltage  = if (rawV     == INT16_NA)  null else rawV * 0.01,
                batteryCurrent  = if (rawI     == INT16_NA)  null else rawI * 0.1,
                yieldToday      = if (rawYield == UINT16_NA) null else rawYield * 10.0,
                solarPower      = if (rawPower == UINT16_NA) null else rawPower,
                loadCurrent     = if (rawLoad  == 0x1FF)     null else rawLoad * 0.1
            )
        } catch (e: Exception) { null }
    }

    // ── Orion-Tr Smart DC-DC Converter (record type 0x04) ─────────────────────

    /**
     * Parse decrypted Orion-Tr Smart payload (record type 0x04).
     * This device does NOT broadcast current — bytes [6-9] are off_reason.
     *
     * Byte layout of decrypted data:
     *   [0]    device_state    uint8
     *   [1]    charger_error   uint8
     *   [2-3]  input_voltage   uint16 LE, 10 mV/LSB → ÷100 V
     *   [4-5]  output_voltage  int16  LE, 10 mV/LSB → ÷100 V  (sentinel 0x7FFF)
     *   [6-9]  off_reason      uint32 LE
     */
    fun parseOrion(d: ByteArray): OrionData? {
        if (d.size < 6) return null
        return try {
            val state = d[0].toInt() and 0xFF
            val error = d[1].toInt() and 0xFF
            val rawVin  = uint16LE(d, 2)
            val rawVout = uint16LE(d, 4)
            OrionData(
                deviceState     = state,
                deviceStateText = orionStateText(state),
                error           = error,
                inputVoltage    = if (rawVin  == UINT16_NA || rawVin  == INT16_NA) null else rawVin  * 0.01,
                outputVoltage   = if (rawVout == UINT16_NA || rawVout == INT16_NA) null else rawVout * 0.01,
                outputCurrent   = null,   // Orion-Tr Smart does not broadcast current
                inputCurrent    = null
            )
        } catch (e: Exception) { null }
    }

    // ── Orion XS DC-DC Converter (record type 0x0F) ────────────────────────────

    /**
     * Parse decrypted Orion XS payload (record type 0x0F).
     * Different field order from Orion-Tr Smart; includes both input & output current.
     *
     * Byte layout of decrypted data:
     *   [0]    device_state    uint8
     *   [1]    charger_error   uint8
     *   [2-3]  output_voltage  uint16 LE, 10 mV/LSB → ÷100 V
     *   [4-5]  output_current  uint16 LE, 100 mA/LSB → ÷10 A
     *   [6-7]  input_voltage   uint16 LE, 10 mV/LSB → ÷100 V
     *   [8-9]  input_current   uint16 LE, 100 mA/LSB → ÷10 A
     *   [10-13] off_reason     uint32 LE
     */
    fun parseOrionXS(d: ByteArray): OrionData? {
        if (d.size < 10) return null
        return try {
            val state = d[0].toInt() and 0xFF
            val error = d[1].toInt() and 0xFF
            val rawVout = uint16LE(d, 2)
            val rawIout = uint16LE(d, 4)
            val rawVin  = uint16LE(d, 6)
            val rawIin  = uint16LE(d, 8)
            OrionData(
                deviceState     = state,
                deviceStateText = orionStateText(state),
                error           = error,
                inputVoltage    = if (rawVin  == UINT16_NA) null else rawVin  * 0.01,
                outputVoltage   = if (rawVout == UINT16_NA) null else rawVout * 0.01,
                outputCurrent   = if (rawIout == UINT16_NA) null else rawIout * 0.1,
                inputCurrent    = if (rawIin  == UINT16_NA) null else rawIin  * 0.1
            )
        } catch (e: Exception) { null }
    }

    // ── State text helpers ───────────────────────────────────────────────────

    fun solarChargeStateText(s: Int) = when (s) {
        0   -> "Off"
        1   -> "Low power"
        2   -> "Fault"
        3   -> "Bulk"
        4   -> "Absorption"
        5   -> "Float"
        6   -> "Storage"
        7   -> "Equalize (manual)"
        9   -> "Inverting"
        11  -> "Power supply"
        245 -> "Starting up"
        246 -> "Repeated absorption"
        247 -> "Equalize (auto)"
        248 -> "Battery safe"
        249 -> "Active"
        252 -> "External control"
        255 -> "N/A"
        else -> "Unknown ($s)"
    }

    fun orionStateText(s: Int) = when (s) {
        0   -> "Off"
        1   -> "Low power"
        2   -> "Fault"
        3   -> "Bulk"
        4   -> "Absorption"
        5   -> "Float"
        6   -> "Storage"
        252 -> "External control"
        else -> "Unknown ($s)"
    }

    fun solarErrorText(e: Int) = when (e) {
        0  -> "No error"
        1  -> "Battery temperature too high"
        2  -> "Battery voltage too high"
        17 -> "Charger temperature too high"
        18 -> "Charger over-current"
        19 -> "Charger current reversed"
        20 -> "Bulk time limit exceeded"
        21 -> "Current sensor issue"
        26 -> "Terminals overheated"
        28 -> "Converter issue"
        29 -> "Over-charge protection"
        30 -> "Power switch malfunction"
        33 -> "Input voltage too high (solar)"
        34 -> "Input current too high (solar)"
        38 -> "Input shutdown (bat voltage too high)"
        39 -> "Input shutdown (current flow while off)"
        40 -> "PV input failed to shutdown"
        65 -> "Communication warning"
        66 -> "Incompatible device"
        67 -> "BMS connection lost"
        68 -> "Network misconfigured"
        116 -> "Calibration data lost"
        117 -> "Incompatible firmware"
        119 -> "Settings data invalid"
        else -> "Error $e"
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private fun uint16LE(d: ByteArray, offset: Int): Int =
        (d[offset].toInt() and 0xFF) or ((d[offset + 1].toInt() and 0xFF) shl 8)

    private fun int16LE(d: ByteArray, offset: Int): Int {
        val u = uint16LE(d, offset)
        return if (u > 32767) u - 65536 else u
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val s = hex.replace(" ", "").replace(":", "").lowercase()
        if (s.length != 32) return null
        return try {
            ByteArray(16) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: Exception) { null }
    }
}

// ── Data classes ─────────────────────────────────────────────────────────────

data class SmartSolarData(
    val chargeState:     Int,
    val chargeStateText: String,
    val error:           Int,
    val batteryVoltage:  Double?,   // V   — null = not available
    val batteryCurrent:  Double?,   // A   — positive = charging; null = not available
    val yieldToday:      Double?,   // Wh  — null = not available
    val solarPower:      Int?,      // W   — null = not available
    val loadCurrent:     Double?,   // A   — external device load; null = not available
    val timestamp: Long = System.currentTimeMillis()
) {
    val errorText: String get() = VictronProtocol.solarErrorText(error)
    val inputPower: Int? get() = solarPower
}

data class OrionData(
    val deviceState:     Int,
    val deviceStateText: String,
    val error:           Int,
    val inputVoltage:    Double?,   // V  (vehicle / starter battery side)
    val outputVoltage:   Double?,   // V  (service / leisure battery side)
    val outputCurrent:   Double?,   // A  — null on Orion-Tr Smart (not broadcast)
    val inputCurrent:    Double?,   // A  — only on Orion XS
    val timestamp: Long = System.currentTimeMillis()
) {
    val outputPower: Double?
        get() = if (outputVoltage != null && outputCurrent != null)
            outputVoltage * outputCurrent else null
    val isRunning: Boolean get() = deviceState != 0 && deviceState != 2
}
