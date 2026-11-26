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
    /** Bumped on every change, for the screen that shows them. */
    val changed = androidx.lifecycle.MutableLiveData(0)
    private const val MAX = 300

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "notif_log.json")

    @Synchronized
    fun add(ctx: Context, title: String, text: String) = runCatching {
        val a = read(ctx)
        val now = System.currentTimeMillis()
        val keep = keepFrom(ctx, now)
        // The same notice posted again within a minute is one entry
        if (a.length() > 0 && a.getJSONObject(0).let { it.optString("ti") == title && it.optString("tx") == text && now - it.optLong("t") < 60_000 }) return@runCatching
        val b = JSONArray().put(JSONObject().put("t", now).put("ti", title).put("tx", text))
        for (i in 0 until minOf(a.length(), MAX - 1)) a.getJSONObject(i).let { if (it.optLong("t") >= keep) b.put(it) }
        file(ctx).writeText(b.toString())
        changed.postValue((changed.value ?: 0) + 1)
    }

    @Synchronized
    fun all(ctx: Context): List<Entry> = read(ctx).let { a ->
        val keep = keepFrom(ctx, System.currentTimeMillis())
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Entry(o.optLong("t"), o.optString("ti"), o.optString("tx")) } }
            .filter { it.tMs >= keep }
    }

    /** Older entries go by themselves when the owner chose so (off by default). */
    private fun keepFrom(ctx: Context, now: Long): Long =
        AppPreferences(ctx).notifAutoClearDays.let { if (it > 0) now - it * 86_400_000L else 0L }

    val AUTO_DAYS = intArrayOf(0, 1, 7, 30, 90)

    @Synchronized
    fun clear(ctx: Context) { file(ctx).delete(); changed.postValue((changed.value ?: 0) + 1) }

    private fun read(ctx: Context): JSONArray = runCatching { JSONArray(file(ctx).readText()) }.getOrDefault(JSONArray())
}
