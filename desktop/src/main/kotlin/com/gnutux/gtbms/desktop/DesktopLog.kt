// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Everything the desktop told the owner (alarms, syncs, imports), newest first, kept on this computer. */
class DesktopLog(private val file: File) {
    data class Entry(val tMs: Long, val title: String, val text: String)
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
    fun all(): List<Entry> = read().let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> Entry(o.optLong("t"), o.optString("ti"), o.optString("tx")) } } }

    @Synchronized
    fun clear() { file.delete(); version.value++ }

    private fun read() = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
}
