// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.data

import android.content.Context

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("gt_bms_prefs", Context.MODE_PRIVATE)

    fun registerListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(l)

    var selectedDeviceAddress: String?
        get() = prefs.getString("selected_device_address", null)
        set(value) = prefs.edit().putString("selected_device_address", value).apply()

    var selectedDeviceName: String?
        get() = prefs.getString("selected_device_name", null)
        set(value) = prefs.edit().putString("selected_device_name", value).apply()

    /** Battery type: "powerqueen" or "123bms". Null means not yet detected. */
    var batteryType: String?
        get() = prefs.getString("battery_type", null)
        set(value) = prefs.edit().putString("battery_type", value).apply()

    /** PIN for 123SmartBMS authentication (both module and BMS layers). */
    var bmsPin: String?
        get() = prefs.getString("bms_pin", null)
        set(value) = prefs.edit().putString("bms_pin", value).apply()

    val is123Bms: Boolean get() = batteryType == "123bms"

    /** Address the remembered [batteryType] was detected on. */
    var batteryTypeAddress: String?
        get() = prefs.getString("battery_type_address", null)
        set(value) = prefs.edit().putString("battery_type_address", value).apply()

    /** Protocol selection: "auto" or a driver id (see BmsDrivers). */
    var bmsProtocol: String
        get() = prefs.getString("bms_protocol", "auto") ?: "auto"
        set(value) = prefs.edit().putString("bms_protocol", value).apply()

    // ── Saved batteries (fast re-connect list) ───────────────────────────────
    data class SavedBattery(val address: String, val name: String, val protocol: String, val lastSeen: Long)

    val savedBatteries: List<SavedBattery>
        get() {
            val raw = prefs.getString("saved_batteries", null) ?: return emptyList()
            return try {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    SavedBattery(o.getString("a"), o.optString("n"), o.optString("p"), o.optLong("t"))
                }.sortedByDescending { it.lastSeen }
            } catch (_: Exception) { emptyList() }
        }

    private fun writeSaved(list: List<SavedBattery>) {
        val arr = org.json.JSONArray()
        list.forEach { b ->
            arr.put(org.json.JSONObject().put("a", b.address).put("n", b.name).put("p", b.protocol).put("t", b.lastSeen))
        }
        prefs.edit().putString("saved_batteries", arr.toString()).apply()
    }

    fun upsertSavedBattery(address: String?, name: String?, protocol: String) {
        if (address.isNullOrBlank()) return
        val others = savedBatteries.filter { it.address != address }
        writeSaved(listOf(SavedBattery(address, name ?: address, protocol, System.currentTimeMillis())) + others.take(19))
    }

    fun removeSavedBattery(address: String) = writeSaved(savedBatteries.filter { it.address != address })

    fun savedProtocolFor(address: String?): String? = savedBatteries.firstOrNull { it.address == address }?.protocol

    // ── Self-update ──────────────────────────────────────────────────────────
    // New key in 1.6: periodic checking is switched on for everyone, also for those who had turned it off
    var autoCheckUpdates: Boolean
        get() = prefs.getBoolean("auto_check_updates_v16", true)
        set(value) = prefs.edit().putBoolean("auto_check_updates_v16", value).apply()

    /** Hours between automatic update checks (6, 12, 24, 168). */
    var updateIntervalH: Int
        get() = prefs.getInt("update_interval_h", 12)
        set(value) = prefs.edit().putInt("update_interval_h", value).apply()

    /** Version whose in-app banner was closed with ✕ (shown again for the next version). */
    var dismissedUpdate: String
        get() = prefs.getString("dismissed_update", "") ?: ""
        set(value) = prefs.edit().putString("dismissed_update", value).apply()

    var allowPrerelease: Boolean
        get() = prefs.getBoolean("allow_prerelease", false)
        set(value) = prefs.edit().putBoolean("allow_prerelease", value).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0L)
        set(value) = prefs.edit().putLong("last_update_check", value).apply()

    var lastNotifiedVersion: String?
        get() = prefs.getString("last_notified_version", null)
        set(value) = prefs.edit().putString("last_notified_version", value).apply()

    // ── Vehicle & trips ──────────────────────────────────────────────────────
    /** One of [VehicleProfile.ids]. */
    var vehicleType: String
        get() = prefs.getString("vehicle_type", VehicleProfile.STORAGE) ?: VehicleProfile.STORAGE
        set(value) = prefs.edit().putString("vehicle_type", value).apply()

    val vehicle: VehicleProfile get() = VehicleProfile.byId(vehicleType)

    /** Record trips automatically (needs a mobile vehicle profile and location permission). */
    var tripsEnabled: Boolean
        get() = prefs.getBoolean("trips_enabled", true)
        set(value) = prefs.edit().putBoolean("trips_enabled", value).apply()

    /** User's own consumption estimate in Wh/km; 0 = use the profile default. */
    var consumptionWhKm: Float
        get() = prefs.getFloat("consumption_wh_km", 0f)
        set(value) = prefs.edit().putFloat("consumption_wh_km", value).apply()

    /** Last known BMS cell over-voltage protection (mV) and cell count, for charge advice. */
    var bmsCellOvMv: Int
        get() = prefs.getInt("bms_cell_ov_mv", 0)
        set(value) = prefs.edit().putInt("bms_cell_ov_mv", value).apply()

    var bmsCellCount: Int
        get() = prefs.getInt("bms_cell_count", 0)
        set(value) = prefs.edit().putInt("bms_cell_count", value).apply()

    /** Full BMS limits (JSON of reports.BmsLimits), for recommendations while offline. */
    var bmsLimitsJson: String?
        get() = prefs.getString("bms_limits", null)
        set(value) = prefs.edit().putString("bms_limits", value).apply()

    /** Rated cycle life from the datasheet; 0 = default by chemistry (LFP 3000, Li-ion 800). */
    var ratedCycles: Int
        get() = prefs.getInt("rated_cycles", 0)
        set(value) = prefs.edit().putInt("rated_cycles", value).apply()

    /** Per-alarm tone URI ("" = default for that alarm). Keys: high, low, delta. */
    fun alarmTone(kind: String): String = prefs.getString("alarm_tone_$kind", "") ?: ""
    fun setAlarmTone(kind: String, uri: String?) = prefs.edit().putString("alarm_tone_$kind", uri ?: "").apply()

    /**
     * Short alarm (rings 5 s then stops by itself) instead of ringing until dismissed.
     * Default: short for the cell-difference alarm (it can fire while riding), continuous for the others.
     */
    fun alarmShort(kind: String): Boolean = prefs.getBoolean("alarm_short_$kind", kind == "delta")
    fun setAlarmShort(kind: String, short: Boolean) = prefs.edit().putBoolean("alarm_short_$kind", short).apply()

    /** Time of the last BMS reading, persisted for "last update: N min ago". */
    var lastDataMs: Long
        get() = prefs.getLong("last_data_ms", 0L)
        set(value) = prefs.edit().putLong("last_data_ms", value).apply()

    /** Live tab design: classic (default), ring, compact, ride. */
    var liveDesign: String
        get() = prefs.getString("live_design", "classic") ?: "classic"
        set(value) = prefs.edit().putString("live_design", value).apply()

    /** Latest reading for the widget / notification. */
    data class LiveSummary(val soc: Int, val voltage: Double, val current: Double, val rangeKm: Double?, val timeMs: Long)

    var liveSummary: LiveSummary?
        get() {
            val raw = prefs.getString("live_summary", null) ?: return null
            return try {
                val o = org.json.JSONObject(raw)
                LiveSummary(o.getInt("s"), o.getDouble("v"), o.getDouble("i"),
                    if (o.has("r")) o.getDouble("r") else null, o.getLong("t"))
            } catch (_: Exception) { null }
        }
        set(value) {
            val o = value?.let {
                org.json.JSONObject().put("s", it.soc).put("v", it.voltage).put("i", it.current).put("t", it.timeMs)
                    .apply { it.rangeKm?.let { r -> put("r", r) } }.toString()
            }
            prefs.edit().putString("live_summary", o).apply()
        }

    /** Per-cell long-term stats (reports.CellTracker JSON) and the battery they belong to. */
    var cellStatsJson: String?
        get() = prefs.getString("cell_stats", null)
        set(value) = prefs.edit().putString("cell_stats", value).apply()

    var cellStatsAddress: String?
        get() = prefs.getString("cell_stats_addr", null)
        set(value) = prefs.edit().putString("cell_stats_addr", value).apply()

    var balanceReminder: Boolean
        get() = prefs.getBoolean("balance_reminder", true)
        set(value) = prefs.edit().putBoolean("balance_reminder", value).apply()

    var lastBalanceReminderMs: Long
        get() = prefs.getLong("last_balance_reminder_ms", 0L)
        set(value) = prefs.edit().putLong("last_balance_reminder_ms", value).apply()

    /** Last Battery sub-tab (CELLS, REPORTS, INFO, HISTORY) — restored by the Battery button. */
    var lastBatteryTab: String
        get() = prefs.getString("last_battery_tab", "CELLS") ?: "CELLS"
        set(value) = prefs.edit().putString("last_battery_tab", value).apply()

    /** Guided capacity test state (reports.CapacityTest JSON). */
    var capacityTestJson: String?
        get() = prefs.getString("capacity_test", null)
        set(value) = prefs.edit().putString("capacity_test", value).apply()

    // ── Energy cost ──────────────────────────────────────────────────────────
    /** Electricity price per kWh (0 = cost not shown). */
    var pricePerKwh: Float
        get() = prefs.getFloat("price_kwh", 0f)
        set(value) = prefs.edit().putFloat("price_kwh", value).apply()

    var currency: String
        get() = prefs.getString("currency", "MAD") ?: "MAD"
        set(value) = prefs.edit().putString("currency", value).apply()

    /** Optional fuel comparison (0 = off). */
    var fuelPrice: Float
        get() = prefs.getFloat("fuel_price", 0f)
        set(value) = prefs.edit().putFloat("fuel_price", value).apply()

    var fuelL100: Float
        get() = prefs.getFloat("fuel_l100", 0f)
        set(value) = prefs.edit().putFloat("fuel_l100", value).apply()

    // ── Smart summaries ──────────────────────────────────────────────────────
    var notifySessionSummary: Boolean
        get() = prefs.getBoolean("notify_session_summary", true)
        set(value) = prefs.edit().putBoolean("notify_session_summary", value).apply()

    var notifyWeekly: Boolean
        get() = prefs.getBoolean("notify_weekly", true)
        set(value) = prefs.edit().putBoolean("notify_weekly", value).apply()

    var lastWeeklyMs: Long
        get() = prefs.getLong("last_weekly_ms", 0L)
        set(value) = prefs.edit().putLong("last_weekly_ms", value).apply()

    // ── Cell difference alarm ────────────────────────────────────────────────
    var alarmDeltaEnabled: Boolean
        get() = prefs.getBoolean("alarm_delta_enabled", true)
        set(value) = prefs.edit().putBoolean("alarm_delta_enabled", value).apply()

    /** Max cell difference at rest (mV), judged only between 20 % and 90 % SOC. */
    var alarmDeltaRestMv: Int
        get() = prefs.getInt("alarm_delta_rest_mv", 30)
        set(value) = prefs.edit().putInt("alarm_delta_rest_mv", value).apply()

    /** Max cell difference under load (mV). */
    var alarmDeltaLoadMv: Int
        get() = prefs.getInt("alarm_delta_load_mv", 150)
        set(value) = prefs.edit().putInt("alarm_delta_load_mv", value).apply()

    // ── Battery alarms ───────────────────────────────────────────────────────
    var alarmHighEnabled: Boolean
        get() = prefs.getBoolean("alarm_high_enabled", false)
        set(value) = prefs.edit().putBoolean("alarm_high_enabled", value).apply()

    /** SOC % at or above which the full-charge alarm fires (0 = SOC check off). */
    var alarmHighSoc: Int
        get() = prefs.getInt("alarm_high_soc", 100)
        set(value) = prefs.edit().putInt("alarm_high_soc", value).apply()

    /** Pack voltage at or above which the full-charge alarm fires (0 = voltage check off). */
    var alarmHighVoltage: Float
        get() = prefs.getFloat("alarm_high_voltage", 83.8f)
        set(value) = prefs.edit().putFloat("alarm_high_voltage", value).apply()

    var alarmLowEnabled: Boolean
        get() = prefs.getBoolean("alarm_low_enabled", false)
        set(value) = prefs.edit().putBoolean("alarm_low_enabled", value).apply()

    /** SOC % at or below which the low-battery alarm fires (0 = SOC check off). */
    var alarmLowSoc: Int
        get() = prefs.getInt("alarm_low_soc", 25)
        set(value) = prefs.edit().putInt("alarm_low_soc", value).apply()

    /** Pack voltage at or below which the low-battery alarm fires (0 = voltage check off). */
    var alarmLowVoltage: Float
        get() = prefs.getFloat("alarm_low_voltage", 68.0f)
        set(value) = prefs.edit().putFloat("alarm_low_voltage", value).apply()

    /** Minutes before a dismissed alarm may ring again while the condition persists. */
    var alarmCooldownMinutes: Int
        get() = prefs.getInt("alarm_cooldown_min", 10)
        set(value) = prefs.edit().putInt("alarm_cooldown_min", value).apply()

    var alarmVibrate: Boolean
        get() = prefs.getBoolean("alarm_vibrate", true)
        set(value) = prefs.edit().putBoolean("alarm_vibrate", value).apply()

    var pollIntervalSeconds: Int
        get() = prefs.getInt("poll_interval", 3)
        set(value) = prefs.edit().putInt("poll_interval", value).apply()

    var autoReconnect: Boolean
        get() = prefs.getBoolean("auto_reconnect", true)
        set(value) = prefs.edit().putBoolean("auto_reconnect", value).apply()

    var temperatureUnit: String
        get() = prefs.getString("temp_unit", "C") ?: "C"
        set(value) = prefs.edit().putString("temp_unit", value).apply()

    // ── Victron Smart Orion ──────────────────────────────────────────────────
    var orionDeviceAddress: String?
        get() = prefs.getString("orion_address", null)
        set(value) = prefs.edit().putString("orion_address", value).apply()

    var orionDeviceName: String?
        get() = prefs.getString("orion_name", null)
        set(value) = prefs.edit().putString("orion_name", value).apply()

    /** 32-char hex advertisement key from VictronConnect → Product info */
    var orionAdvKey: String?
        get() = prefs.getString("orion_adv_key", null)
        set(value) = prefs.edit().putString("orion_adv_key", value).apply()

    // ── Victron SmartSolar MPPT ──────────────────────────────────────────────
    var smartSolarDeviceAddress: String?
        get() = prefs.getString("solar_address", null)
        set(value) = prefs.edit().putString("solar_address", value).apply()

    var smartSolarDeviceName: String?
        get() = prefs.getString("solar_name", null)
        set(value) = prefs.edit().putString("solar_name", value).apply()

    /** 32-char hex advertisement key from VictronConnect → Product info */
    var smartSolarAdvKey: String?
        get() = prefs.getString("solar_adv_key", null)
        set(value) = prefs.edit().putString("solar_adv_key", value).apply()

    // ── Ai-Tanker Switch Panel ────────────────────────────────────────────────
    var switchPanelDeviceAddress: String?
        get() = prefs.getString("switchpanel_address", null)
        set(value) = prefs.edit().putString("switchpanel_address", value).apply()

    var switchPanelDeviceName: String?
        get() = prefs.getString("switchpanel_name", null)
        set(value) = prefs.edit().putString("switchpanel_name", value).apply()

    fun getSwitchPanelChannelName(channel: Int): String {
        return prefs.getString("switchpanel_ch${channel}_name", null)
            ?: "Channel $channel"
    }

    fun setSwitchPanelChannelName(channel: Int, name: String) {
        prefs.edit().putString("switchpanel_ch${channel}_name", name.ifBlank { null }).apply()
    }
}
