// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** CSV of all reports and GPX of GPS trips. Pure text builders (unit-tested). */
object Exporter {

    private val CSV_FIELDS = listOf(
        "soc_start", "soc_end", "ah", "wh", "avg_current", "max_current", "max_v", "min_v",
        "max_cell_v", "max_cell_idx", "min_cell_v", "min_cell_idx", "max_delta_mv", "max_temp",
        "distance_km", "max_speed", "avg_speed", "wh_per_km", "ah_per_km", "hard_accel",
        "ended_by_protection", "reached_full",
    )

    private fun local(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    fun csv(sessions: List<Session>): String = buildString {
        append("id,type,start,end,duration_min,").append(CSV_FIELDS.joinToString(",")).append('\n')
        sessions.forEach { s ->
            append(s.id).append(',').append(s.type).append(',').append(local(s.startMs)).append(',')
            append(local(s.endMs)).append(',').append(String.format(Locale.US, "%.1f", (s.endMs - s.startMs) / 60000.0))
            CSV_FIELDS.forEach { k ->
                append(',')
                if (s.data.has(k) && !s.data.isNull(k)) {
                    when (val v = s.data.get(k)) {
                        is Double -> append(String.format(Locale.US, "%.3f", v))
                        is Float -> append(String.format(Locale.US, "%.3f", v))
                        else -> append(v.toString())
                    }
                }
            }
            append('\n')
        }
    }

    /** One GPX file, one track per trip; only points that have a GPS position. */
    fun gpx(trips: List<Pair<Session, List<Point>>>): String = buildString {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="GT-BMS" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        trips.forEach { (s, pts) ->
            val gps = pts.filter { it.lat != null && it.lon != null }
            if (gps.isEmpty()) return@forEach
            append("  <trk><name>GT-BMS ").append(local(s.startMs)).append("</name>\n")
            append("    <desc>").append(String.format(Locale.US, "%.2f km, %.0f Wh", s.d("distance_km"), s.d("wh"))).append("</desc>\n")
            append("    <trkseg>\n")
            gps.forEach { p ->
                append(String.format(Locale.US, "      <trkpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time>", p.lat, p.lon, iso.format(Date(p.tMs))))
                p.speedKmh?.let { append(String.format(Locale.US, "<extensions><speed>%.2f</speed></extensions>", it / 3.6)) }
                append("</trkpt>\n")
            }
            append("    </trkseg>\n  </trk>\n")
        }
        append("</gpx>\n")
    }
}
