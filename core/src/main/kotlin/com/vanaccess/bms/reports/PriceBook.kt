// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import org.json.JSONArray
import org.json.JSONObject

/** Electricity and fuel prices from [fromMs] on (0 = not set). */
data class Prices(val fromMs: Long, val kwh: Double, val currency: String, val fuel: Double, val l100: Double) {
    val hasEnergy get() = kwh > 0
    val hasFuel get() = fuel > 0 && l100 > 0
    fun sameValues(o: Prices) = kwh == o.kwh && currency == o.currency && fuel == o.fuel && l100 == o.l100
}

/**
 * The prices over time: a report is costed at the prices in force when it was recorded, so a
 * new price changes only what comes after it. Kept as a small JSON list (phone and computer).
 */
object PriceBook {
    /** Changes closer than this replace each other (the owner is still typing). */
    const val EDIT_MS = 10 * 60_000L

    fun parse(json: String?): List<Prices> = runCatching {
        val a = JSONArray(json ?: return emptyList())
        (0 until a.length()).map { a.getJSONObject(it).let { o ->
            Prices(o.optLong("from"), o.optDouble("kwh", 0.0), o.optString("cur", ""), o.optDouble("fuel", 0.0), o.optDouble("l100", 0.0)) } }
            .sortedBy { it.fromMs }
    }.getOrDefault(emptyList())

    fun toJson(list: List<Prices>): String = JSONArray().also { a -> list.forEach {
        a.put(JSONObject().put("from", it.fromMs).put("kwh", it.kwh).put("cur", it.currency).put("fuel", it.fuel).put("l100", it.l100)) } }.toString()

    /**
     * The book after the owner set [p] at [now]. [before] = the prices in use until now when the
     * book is still empty (they then stay for everything already recorded).
     */
    fun change(list: List<Prices>, now: Long, p: Prices, before: Prices? = null): List<Prices> {
        val book = list.ifEmpty { listOfNotNull(before?.copy(fromMs = 0)) }
        val last = book.lastOrNull()
        val entry = p.copy(fromMs = now)
        return when {
            last != null && last.sameValues(entry) -> book
            last != null && last.fromMs > 0 && now - last.fromMs < EDIT_MS -> book.dropLast(1).let { rest ->
                // edited back to the previous values: the change is undone
                if (rest.lastOrNull()?.sameValues(entry) == true) rest else rest + entry
            }
            else -> book + entry
        }
    }

    /** The prices in force at [ms]; the oldest ones for anything recorded before the first. */
    fun at(list: List<Prices>, ms: Long): Prices? = list.lastOrNull { it.fromMs <= ms } ?: list.firstOrNull()
}
