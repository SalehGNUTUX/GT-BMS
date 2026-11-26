// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble.drivers

/** Registry of all supported BMS protocols + auto-detection ordering. */
object BmsDrivers {
    const val AUTO = "auto"

    /** Order matters: it is the probe order when nothing else hints at the protocol. */
    val all: List<BmsDriver> = listOf(
        BwbmDriver(),
        JkDriver(),
        JbdDriver(),
        DalyDriver(),
        PowerQueenDriver(),
    )

    fun byId(id: String?): BmsDriver? = all.firstOrNull { it.id == id }

    /** (id, label) pairs for the manual selector, including Auto and 123SmartBMS. */
    fun selectorEntries(autoLabel: String): List<Pair<String, String>> =
        listOf(AUTO to autoLabel) + all.map { it.id to it.displayName } + listOf("123bms" to "123SmartBMS")

    /**
     * Ordered candidates to probe:
     *  1. the manually forced driver (only that one), else
     *  2. the driver remembered for this device,
     *  3. drivers whose name prefix matches,
     *  4. drivers whose GATT service is present,
     *  5. everything else is not tried (it cannot talk to this device anyway).
     */
    fun candidates(
        forcedId: String,
        rememberedId: String?,
        deviceName: String?,
        serviceUuids: List<String>
    ): List<BmsDriver> {
        if (forcedId != AUTO) return listOfNotNull(byId(forcedId))
        val out = LinkedHashSet<BmsDriver>()
        byId(rememberedId)?.let { out += it }
        all.filter { it.matchesName(deviceName) }.forEach { out += it }
        all.filter { it.matchesServices(serviceUuids) }.forEach { out += it }
        return out.toList()
    }
}
