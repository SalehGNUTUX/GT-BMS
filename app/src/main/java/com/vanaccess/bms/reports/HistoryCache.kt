// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import android.content.Context
import com.vanaccess.bms.ble.drivers.HistoryRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last BMS event history downloaded for each battery, kept on the phone with the
 * download time, so the History tab shows it without reading the BMS again.
 */
object HistoryCache {

    data class Cached(val savedMs: Long, val records: List<HistoryRecord>)

    private fun file(ctx: Context, address: String) =
        File(ctx.filesDir, "history_" + address.replace(Regex("[^A-Za-z0-9]"), "") + ".json")

    fun save(ctx: Context, address: String?, records: List<HistoryRecord>) {
        if (address.isNullOrBlank() || records.isEmpty()) return
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(JSONObject().put("t", r.time).put("e", JSONArray(r.events)).put("x", r.extra))
        }
        val json = JSONObject().put("saved", System.currentTimeMillis()).put("records", arr)
        try { file(ctx, address).writeText(json.toString()) } catch (_: Exception) {}
    }

    fun load(ctx: Context, address: String?): Cached? {
        if (address.isNullOrBlank()) return null
        return try {
            val f = file(ctx, address)
            if (!f.exists()) return null
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("records")
            val list = (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                val ev = r.optJSONArray("e") ?: JSONArray()
                HistoryRecord(r.getString("t"), (0 until ev.length()).map { ev.getString(it) }, r.optString("x"))
            }
            Cached(o.getLong("saved"), list)
        } catch (_: Exception) { null }
    }
}
