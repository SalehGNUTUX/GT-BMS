// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import com.vanaccess.bms.reports.BmsLimits
import org.json.JSONObject
import java.io.File

/** Desktop settings, in ~/.config/gt-bms/settings.json. Taken from the phone's backup when imported. */
class Settings(private val file: File) {
    private val o: JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    var highEnabled: Boolean get() = o.optBoolean("alarm_high_enabled", true); set(v) { o.put("alarm_high_enabled", v); save() }
    var highSoc: Int get() = o.optInt("alarm_high_soc", 100); set(v) { o.put("alarm_high_soc", v); save() }
    var lowEnabled: Boolean get() = o.optBoolean("alarm_low_enabled", true); set(v) { o.put("alarm_low_enabled", v); save() }
    var lowSoc: Int get() = o.optInt("alarm_low_soc", 25); set(v) { o.put("alarm_low_soc", v); save() }
    var cellAlarm: Boolean get() = o.optBoolean("alarm_cell_enabled", true); set(v) { o.put("alarm_cell_enabled", v); save() }
    var lowStep: Int get() = o.optInt("low_step", 0); set(v) { o.put("low_step", v); save() }
    /** This battery's limits, read from it or from the phone. */
    var limits: BmsLimits get() = BmsLimits.fromJson(o.optString("bms_limits", null)); set(v) { o.put("bms_limits", v.toJson()); save() }
    var battery: String get() = o.optString("battery", ""); set(v) { o.put("battery", v); save() }
    var batteryName: String get() = o.optString("battery_name", ""); set(v) { o.put("battery_name", v); save() }
    /** "" = the system's language; else ar, en, fr (applied at the next start). */
    var lang: String get() = o.optString("lang", ""); set(v) { o.put("lang", v); save() }
    var sound: Boolean get() = o.optBoolean("alarm_sound", true); set(v) { o.put("alarm_sound", v); save() }
    var systemNotify: Boolean get() = o.optBoolean("alarm_notify", true); set(v) { o.put("alarm_notify", v); save() }
    var phoneHost: String get() = o.optString("phone_host", ""); set(v) { o.put("phone_host", v); save() }
    var phonePort: Int get() = o.optInt("phone_port", 0); set(v) { o.put("phone_port", v); save() }
    /** What the Live screen reads at start: "ble" (the battery), "phone", "sim" or "" (the last reading kept). */
    var source: String get() = o.optString("source", ""); set(v) { o.put("source", v); save() }
    /** The protocol that answered last time, per battery address. */
    fun driverFor(address: String): String? = o.optJSONObject("drivers")?.optString(address)?.ifEmpty { null }
    fun setDriverFor(address: String, id: String) { o.put("drivers", (o.optJSONObject("drivers") ?: JSONObject()).put(address, id)); save() }
    /** The battery drawing on the Live screen (the phone's designs). */
    var design: String get() = o.optString("live_design", "segments"); set(v) { o.put("live_design", v); save() }
    /** Energy cost: price of a kWh (0 = not shown), currency, and the fuel comparison (0 = off). */
    var priceKwh: Double get() = o.optDouble("price_kwh", 0.0); set(v) { o.put("price_kwh", v); save() }
    var currency: String get() = o.optString("currency", "MAD"); set(v) { o.put("currency", v); save() }
    var fuelPrice: Double get() = o.optDouble("fuel_price", 0.0); set(v) { o.put("fuel_price", v); save() }
    var fuelL100: Double get() = o.optDouble("fuel_l100", 0.0); set(v) { o.put("fuel_l100", v); save() }
    /** Prices over time (core PriceBook): each report is costed at the prices of its day. */
    var priceBook: String? get() = o.optString("price_book", "").ifEmpty { null }; set(v) { o.put("price_book", v ?: ""); save() }
    fun pricesAt(ms: Long): com.vanaccess.bms.reports.Prices =
        com.vanaccess.bms.reports.PriceBook.at(com.vanaccess.bms.reports.PriceBook.parse(priceBook), ms)
            ?: com.vanaccess.bms.reports.Prices(0, priceKwh, currency, fuelPrice, fuelL100)
    /** New prices from now on; reports already recorded keep theirs. */
    fun setPrices(kwh: Double, cur: String, fuel: Double, l100: Double, now: Long = System.currentTimeMillis()) {
        val book = com.vanaccess.bms.reports.PriceBook.change(com.vanaccess.bms.reports.PriceBook.parse(priceBook), now,
            com.vanaccess.bms.reports.Prices(now, kwh, cur, fuel, l100), com.vanaccess.bms.reports.Prices(0, priceKwh, currency, fuelPrice, fuelL100))
        priceBook = com.vanaccess.bms.reports.PriceBook.toJson(book)
        priceKwh = kwh; currency = cur; fuelPrice = fuel; fuelL100 = l100
    }
    var lastPushMs: Long get() = o.optLong("last_push", 0); set(v) { o.put("last_push", v); save() }
    fun lastPullMs(host: String): Long = o.optJSONObject("last_pull")?.optLong(host, 0) ?: 0
    fun setLastPull(host: String, t: Long) { o.put("last_pull", (o.optJSONObject("last_pull") ?: JSONObject()).put(host, t)); save() }
    val lastSyncMs: Long get() = o.optJSONObject("last_pull")?.let { j -> j.keys().asSequence().maxOfOrNull { j.optLong(it) } } ?: 0

    /** The battery values the phone sent during a sync (its own limits and alarms). */
    fun takeFromPhoneBattery(b: JSONObject) {
        b.optString("address").takeIf { it.isNotEmpty() }?.let { battery = it }
        b.optString("name").takeIf { it.isNotEmpty() }?.let { batteryName = it }
        runCatching { BmsLimits.fromJson(b.optString("bms_limits", null)) }.getOrNull()?.let { l ->
            val ov = l.cellOvMv ?: b.optInt("bms_cell_ov_mv").takeIf { it > 0 }
            limits = l.copy(cellOvMv = ov, cells = l.cells ?: b.optInt("cells").takeIf { it > 0 })
        }
        if (b.has("alarm_high_enabled")) highEnabled = b.optBoolean("alarm_high_enabled")
        b.optInt("alarm_high_soc").takeIf { it > 0 }?.let { highSoc = it }
        if (b.has("alarm_low_enabled")) lowEnabled = b.optBoolean("alarm_low_enabled")
        b.optInt("alarm_low_soc").takeIf { it > 0 }?.let { lowSoc = it }
        takeShared { k -> if (b.has(k)) b.get(k).toString() else null }
    }

    /** Prices and the design, the same keys in the phone's sync answer and in its backup. */
    private fun takeShared(v: (String) -> String?) {
        v("price_kwh")?.toDoubleOrNull()?.let { priceKwh = it }
        v("currency")?.takeIf { it.isNotBlank() }?.let { currency = it }
        v("fuel_price")?.toDoubleOrNull()?.let { fuelPrice = it }
        v("fuel_l100")?.toDoubleOrNull()?.let { fuelL100 = it }
        v("live_design")?.takeIf { it.isNotBlank() }?.let { design = it }
        // the phone's price history wins: it is where prices are usually changed
        v("price_book")?.replace("&quot;", "\"")?.replace("&amp;", "&")?.takeIf { it.startsWith("[") }?.let { priceBook = it }
    }

    private fun save() = runCatching { file.parentFile?.mkdirs(); file.writeText(o.toString(2)) }

    /** Values of the selected battery from the phone's shared_prefs XML (per-battery keys are "key@address"). */
    fun takeFromPhone(prefsXml: String) {
        val map = HashMap<String, String>()
        Regex("""<(string|int|boolean|float|long) name="([^"]+)"(?: value="([^"]*)")?\s*(?:/>|>([^<]*)</string>)""").findAll(prefsXml).forEach { m ->
            map[m.groupValues[2]] = m.groupValues[3].ifEmpty { m.groupValues[4] }
        }
        val addr = map["selected_device_address"].orEmpty()
        fun v(k: String) = map["$k@$addr"] ?: map[k]
        if (addr.isNotEmpty()) battery = addr
        v("alarm_high_enabled")?.let { highEnabled = it.toBoolean() }
        v("alarm_high_soc")?.toIntOrNull()?.let { highSoc = it }
        v("alarm_low_enabled")?.let { lowEnabled = it.toBoolean() }
        v("alarm_low_soc")?.toIntOrNull()?.let { lowSoc = it }
        v("bms_limits")?.let { s -> runCatching { BmsLimits.fromJson(s.replace("&quot;", "\"").replace("&amp;", "&")) }.getOrNull()?.let { if (it.cellOvMv != null) limits = it } }
        // a limit learned from a real cut, when the BMS doesn't report it
        v("bms_cell_ov_mv")?.toIntOrNull()?.takeIf { it > 0 && limits.cellOvMv == null }?.let { limits = limits.copy(cellOvMv = it) }
        takeShared { map[it] }
    }
}
