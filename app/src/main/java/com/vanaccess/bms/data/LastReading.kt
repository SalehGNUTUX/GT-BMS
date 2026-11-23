// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.data

import android.content.Context
import com.vanaccess.bms.ble.BmsBasicInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local copy of the last battery reading, so the screens are not empty after a crash, a
 * force-stop or a reboot: shown dimmed with its age ("last update: 2 h ago") until the
 * battery connects again. Written atomically (temp file + rename) every few seconds.
 */
object LastReading {
    private const val FILE = "last_reading.json"

    data class Snapshot(val address: String, val timeMs: Long, val basic: BmsBasicInfo, val cells: List<Double>?)

    fun toJson(s: Snapshot): JSONObject {
        val b = s.basic
        return JSONObject().put("v", 1).put("address", s.address).put("time", s.timeMs)
            .put("pack_v", b.packVoltage).put("current", b.current).put("remain", b.remainCapacity)
            .put("full", b.fullCapacity).put("cycles", b.cycleCount).put("soc", b.soc).put("cell_count", b.cellCount)
            .put("temps", JSONArray(b.temperatures)).put("chg_fet", b.chargeFetOn).put("dsg_fet", b.dischargeFetOn)
            .put("protect", b.protectFlags).put("balance", b.balanceBits).put("sw", b.softwareVersion)
            .put("mfg", b.manufactureDate).put("source", b.source)
            .put("alarms", b.alarmTexts?.let { JSONArray(it) } ?: JSONObject.NULL)
            .put("mos", b.mosTemperature ?: JSONObject.NULL).put("soh", b.sohReported ?: JSONObject.NULL)
            .put("mos_all", JSONArray(b.mosTemperatures)).put("heating", b.heating ?: JSONObject.NULL)
            .put("cells", s.cells?.let { JSONArray(it) } ?: JSONObject.NULL)
    }

    fun fromJson(o: JSONObject): Snapshot? = runCatching {
        fun dl(a: JSONArray?) = a?.let { arr -> (0 until arr.length()).map { arr.getDouble(it) } }
        val basic = BmsBasicInfo(
            packVoltage = o.getDouble("pack_v"), current = o.getDouble("current"),
            remainCapacity = o.getDouble("remain"), fullCapacity = o.getDouble("full"),
            cycleCount = o.getInt("cycles"), soc = o.getInt("soc"), cellCount = o.getInt("cell_count"),
            temperatures = dl(o.optJSONArray("temps")) ?: emptyList(),
            chargeFetOn = o.getBoolean("chg_fet"), dischargeFetOn = o.getBoolean("dsg_fet"),
            protectFlags = o.optInt("protect"), balanceBits = o.optLong("balance"),
            softwareVersion = o.optString("sw"), manufactureDate = o.optString("mfg"), rawData = ByteArray(0),
            alarmTexts = o.optJSONArray("alarms")?.let { a -> (0 until a.length()).map { a.getString(it) } },
            mosTemperature = if (o.isNull("mos")) null else o.getDouble("mos"),
            sohReported = if (o.isNull("soh")) null else o.getInt("soh"),
            source = o.optString("source"),
            mosTemperatures = dl(o.optJSONArray("mos_all")) ?: emptyList(),
            heating = if (o.isNull("heating")) null else o.getBoolean("heating"),
        )
        Snapshot(o.getString("address"), o.getLong("time"), basic, dl(o.optJSONArray("cells")))
    }.getOrNull()

    fun save(ctx: Context, s: Snapshot) {
        runCatching {
            val f = File(ctx.filesDir, FILE); val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(toJson(s).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /** The saved reading, only if it belongs to the battery selected now. */
    fun load(ctx: Context, address: String?): Snapshot? {
        if (address.isNullOrBlank()) return null
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return null
        return runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()?.takeIf { it.address == address }
    }
}
