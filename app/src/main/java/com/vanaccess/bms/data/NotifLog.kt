// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every notification the app posted, kept on the phone so none is lost when the system shade is
 * cleared. Newest first, the last [MAX] only; emptied by hand.
 */
object NotifLog {
    data class Entry(val tMs: Long, val title: String, val text: String)
    private const val MAX = 300

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "notif_log.json")

    @Synchronized
    fun add(ctx: Context, title: String, text: String) = runCatching {
        val a = read(ctx)
        val now = System.currentTimeMillis()
        // The same notice posted again within a minute is one entry
        if (a.length() > 0 && a.getJSONObject(0).let { it.optString("ti") == title && it.optString("tx") == text && now - it.optLong("t") < 60_000 }) return@runCatching
        val b = JSONArray().put(JSONObject().put("t", now).put("ti", title).put("tx", text))
        for (i in 0 until minOf(a.length(), MAX - 1)) b.put(a.get(i))
        file(ctx).writeText(b.toString())
    }

    @Synchronized
    fun all(ctx: Context): List<Entry> = read(ctx).let { a ->
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Entry(o.optLong("t"), o.optString("ti"), o.optString("tx")) } }
    }

    @Synchronized
    fun clear(ctx: Context) { file(ctx).delete() }

    private fun read(ctx: Context): JSONArray = runCatching { JSONArray(file(ctx).readText()) }.getOrDefault(JSONArray())
}
