// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Everything the desktop told the owner (alarms, syncs, imports), newest first, kept on this computer. */
class DesktopLog(private val file: File) {
    data class Entry(val tMs: Long, val title: String, val text: String, val fromPhone: Boolean = false)
    val version = MutableStateFlow(0)

    @Synchronized
    fun add(title: String, text: String) = runCatching {
        val a = read()
        val b = JSONArray().put(JSONObject().put("t", System.currentTimeMillis()).put("ti", title).put("tx", text))
        for (i in 0 until minOf(a.length(), 299)) b.put(a.get(i))
        file.parentFile?.mkdirs(); file.writeText(b.toString())
        version.value++
    }

    @Synchronized
    fun all(): List<Entry> = read().let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> Entry(o.optLong("t"), o.optString("ti"), o.optString("tx"), o.optBoolean("ph")) } } }

    /** The phone's own notifications, at their own times; those already here are skipped. Returns how many were new. */
    @Synchronized
    fun addFromPhone(list: List<com.vanaccess.bms.sync.SyncCodec.Notif>): Int {
        val have = all()
        val seen = have.filter { it.fromPhone }.map { it.tMs to it.title }.toHashSet()
        val fresh = list.filter { (it.tMs to it.title) !in seen }
        if (fresh.isEmpty()) return 0
        val merged = (have + fresh.map { Entry(it.tMs, it.title, it.text, true) }).sortedByDescending { it.tMs }.take(300)
        val b = JSONArray()
        merged.forEach { e -> b.put(JSONObject().put("t", e.tMs).put("ti", e.title).put("tx", e.text).also { if (e.fromPhone) it.put("ph", true) }) }
        file.parentFile?.mkdirs(); file.writeText(b.toString())
        runCatching { mark.writeText(fresh.maxOf { it.tMs }.coerceAtLeast(lastFromPhone()).toString()) }
        version.value++
        return fresh.size
    }

    /** Time of the newest notification taken from the phone (0: none yet); kept when the list is cleared. */
    fun lastFromPhone(): Long = maxOf(runCatching { mark.readText().trim().toLong() }.getOrDefault(0L), all().filter { it.fromPhone }.maxOfOrNull { it.tMs } ?: 0L)

    private val mark get() = File(file.parentFile, "notifications-phone-since.txt")

    @Synchronized
    fun clear() { file.delete(); version.value++ }

    private fun read() = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
}
