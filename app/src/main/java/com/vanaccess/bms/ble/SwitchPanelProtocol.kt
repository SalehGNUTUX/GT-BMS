// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble

/**
 * Ai-Tanker 8-channel BLE switch panel protocol.
 *
 * Transport: ISSC Transparent UART (service 55535343-fe7d-4ae5-8fa9-9fafd205e455)
 *   Write char : 49535343-8841-43f4-a8d4-ecbe34729bb3  (WRITE_NR)
 *   Notify char: 49535343-1e4d-4bd9-ba61-23c647249616  (READ|NOTIFY)
 *   CCCD       : 00002902-0000-1000-8000-00805f9b34fb
 *
 * Frame format:
 *   Command  (phone → device): AA 55 0E [14 bytes payload]             55 AA  (19 bytes)
 *   Response (device → phone): AA 55 0E [14 bytes payload] [checksum]  55 AA  (20 bytes)
 *
 * Command payload (14 bytes):
 *   [0]     0x01            direction flag
 *   [1]     channel_mask    bitmask: bit0=CH1 … bit7=CH8
 *   [2-10]  0x01 × 9        padding
 *   [11]    0x88            constant
 *   [12]    state_flag      0x02=set-channels, 0x01=power-off-all
 *   [13]    echo_token      last checksum byte received from device notification
 *
 * Response/Notification payload (14 bytes):
 *   [0]     0x02            direction flag
 *   [1]     channel_mask    bitmask reflecting current switch state
 *   [2]     0x0A            notification type
 *   [3]     voltage_raw     supply voltage = raw × 0.2 V
 *   [4-12]  0x01 × 9        padding
 *   [13]    state_flag      0x02=active, 0x01=standby
 * Checksum byte (after payload): (sum(payload[0..13]) - 7) & 0xFF
 *
 * Token handshake: the phone must echo the last notification's checksum byte in
 * command[13]. The device validates this before applying the command.
 *
 * Protocol verified against btsnoop capture: all 8 channels toggled ON/OFF,
 * then full power-off. Checksum formula confirmed on 6 consecutive notifications.
 */
object SwitchPanelProtocol {

    const val SERVICE_UUID      = "55535343-fe7d-4ae5-8fa9-9fafd205e455"
    const val WRITE_CHAR_UUID   = "49535343-8841-43f4-a8d4-ecbe34729bb3"
    const val NOTIFY_CHAR_UUID  = "49535343-1e4d-4bd9-ba61-23c647249616"
    const val CCCD_UUID         = "00002902-0000-1000-8000-00805f9b34fb"

    const val NUM_CHANNELS = 8

    /** Build a "set channels" command frame.
     *  @param channelMask bitmask of channels to turn ON (bit0=CH1 … bit7=CH8)
     *  @param echoToken   last checksum byte received from the device (0x00 if none yet)
     */
    fun buildSetChannels(channelMask: Int, echoToken: Byte): ByteArray {
        val payload = ByteArray(14).apply {
            this[0]  = 0x01
            this[1]  = (channelMask and 0xFF).toByte()
            for (i in 2..10) this[i] = 0x01
            this[11] = 0x88.toByte()
            this[12] = 0x02
            this[13] = echoToken
        }
        return buildFrame(payload)
    }

    /** Build a "power off all channels" command frame. */
    fun buildPowerOff(echoToken: Byte): ByteArray {
        val payload = ByteArray(14).apply {
            this[0]  = 0x01
            this[1]  = 0x00
            for (i in 2..10) this[i] = 0x01
            this[11] = 0x88.toByte()
            this[12] = 0x01              // power-off flag
            this[13] = echoToken
        }
        return buildFrame(payload)
    }

    /** Parse an inbound notification frame. Returns null if the frame is malformed. */
    fun parseNotification(raw: ByteArray): SwitchPanelData? {
        // Locate AA 55 header
        val start = findHeader(raw) ?: return null
        val lengthIdx = start + 2
        if (lengthIdx >= raw.size) return null
        val payloadLen = raw[lengthIdx].toInt() and 0xFF        // always 14 = 0x0E
        val payloadStart = lengthIdx + 1
        if (payloadStart + payloadLen + 1 > raw.size) return null  // need payload + checksum

        val payload = raw.copyOfRange(payloadStart, payloadStart + payloadLen)
        if (payload.size < 14) return null
        if ((payload[0].toInt() and 0xFF) != 0x02) return null     // direction flag

        val channelMask = payload[1].toInt() and 0xFF
        val voltageRaw  = payload[3].toInt() and 0xFF
        val stateFlag   = payload[13].toInt() and 0xFF

        val checksum = raw[payloadStart + payloadLen]
        val computed  = computeChecksum(payload)
        if (checksum != computed) return null   // discard bad frames

        return SwitchPanelData(
            channelMask  = channelMask,
            voltage      = voltageRaw * 0.2,
            isPoweredOn  = stateFlag == 0x02,
            echoToken    = checksum
        )
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun buildFrame(payload: ByteArray): ByteArray {
        // Command frames do NOT include a checksum — just header + payload + footer
        return byteArrayOf(0xAA.toByte(), 0x55, 0x0E) + payload + byteArrayOf(0x55, 0xAA.toByte())
    }

    private fun findHeader(raw: ByteArray): Int? {
        for (i in 0 until raw.size - 1) {
            if (raw[i] == 0xAA.toByte() && raw[i + 1] == 0x55.toByte()) return i
        }
        return null
    }

    fun computeChecksum(payload: ByteArray): Byte {
        val sum = payload.sumOf { it.toInt() and 0xFF }
        return ((sum - 7) and 0xFF).toByte()
    }
}

// ── Data class ────────────────────────────────────────────────────────────────

data class SwitchPanelData(
    val channelMask: Int,       // bitmask: bit0=CH1 … bit7=CH8
    val voltage:     Double,    // supply voltage in V (0.2 V/LSB)
    val isPoweredOn: Boolean,   // false = standby/power-off state
    val echoToken:   Byte,      // checksum to echo back in next command
    val timestamp:   Long = System.currentTimeMillis()
) {
    fun isChannelOn(channel: Int): Boolean =
        channel in 1..8 && (channelMask shr (channel - 1)) and 1 == 1

    fun withChannel(channel: Int, on: Boolean): Int {
        val bit = 1 shl (channel - 1)
        return if (on) channelMask or bit else channelMask and bit.inv()
    }
}
