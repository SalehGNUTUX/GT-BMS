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
    }
}
