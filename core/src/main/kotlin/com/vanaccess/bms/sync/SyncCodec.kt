// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.sync

import com.vanaccess.bms.reports.Point
import com.vanaccess.bms.reports.Session
import org.json.JSONArray
import org.json.JSONObject

/** A report with its track, as exchanged between the phone and the computer. */
data class SyncedSession(val session: Session, val points: List<Point>)

object SyncCodec {
    fun toJson(s: SyncedSession): JSONObject = JSONObject()
        .put("type", s.session.type).put("start", s.session.startMs).put("end", s.session.endMs)
        .put("battery", s.session.battery).put("data", s.session.data)
        .put("points", JSONArray().also { a -> s.points.forEach { p ->
            a.put(JSONArray().put(p.tMs).put(p.v).put(p.i).put(p.soc)
                .put(p.speedKmh ?: JSONObject.NULL).put(p.lat ?: JSONObject.NULL).put(p.lon ?: JSONObject.NULL)) } })

    fun fromJson(o: JSONObject): SyncedSession {
        val pts = o.optJSONArray("points") ?: JSONArray()
        return SyncedSession(
            Session(0, o.getString("type"), o.getLong("start"), o.getLong("end"), o.optString("battery"), o.getJSONObject("data")),
            (0 until pts.length()).map { k -> pts.getJSONArray(k).let { p ->
                fun d(n: Int) = if (p.isNull(n)) null else p.getDouble(n)
                Point(p.getLong(0), p.getDouble(1), p.getDouble(2), p.getInt(3), d(4), d(5), d(6)) } })
    }

    fun list(l: List<SyncedSession>) = JSONObject().put("sessions", JSONArray().also { a -> l.forEach { a.put(toJson(it)) } })
    fun parseList(o: JSONObject) = o.optJSONArray("sessions")?.let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } } ?: emptyList()

    /** One report is the same as another: same kind, same start, same battery. */
    fun key(s: Session) = "${s.type}|${s.startMs}|${s.battery}"
}
