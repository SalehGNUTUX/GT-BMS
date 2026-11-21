// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.data

import com.vanaccess.bms.R

/**
 * What the battery powers. Mobile profiles enable GPS trips, range estimates and
 * trip reports; [defaultWhPerKm] is only the starting estimate until real trips are
 * recorded (then the learned consumption is used).
 */
data class VehicleProfile(
    val id: String,
    val labelRes: Int,
    val icon: String,
    val mobile: Boolean,
    val defaultWhPerKm: Double,
    /** Discharge current above which the battery is considered "under load" (A). */
    val loadCurrentA: Double,
) {
    companion object {
        const val STORAGE = "storage"
        val all = listOf(
            VehicleProfile(STORAGE, R.string.vehicle_storage, "🏠", false, 0.0, 5.0),
            VehicleProfile("ebike", R.string.vehicle_ebike, "🚲", true, 12.0, 5.0),
            VehicleProfile("escooter", R.string.vehicle_escooter, "🛴", true, 15.0, 5.0),
            VehicleProfile("emoto", R.string.vehicle_emoto, "🏍️", true, 35.0, 10.0),
            VehicleProfile("ecar", R.string.vehicle_ecar, "🚗", true, 120.0, 20.0),
            VehicleProfile("etrike", R.string.vehicle_etrike, "🛺", true, 25.0, 8.0),
            VehicleProfile("wheelchair", R.string.vehicle_wheelchair, "🦽", true, 8.0, 3.0),
            VehicleProfile("eboat", R.string.vehicle_eboat, "🚤", true, 60.0, 10.0),
        )
        fun byId(id: String?) = all.firstOrNull { it.id == id } ?: all.first()
    }
}
