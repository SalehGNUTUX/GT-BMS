// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.sync

import com.vanaccess.bms.ble.BmsBasicInfo
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

    /** The phone's latest reading, so the computer can watch the battery live through the phone. */
    data class Live(val tMs: Long, val name: String, val address: String, val connected: Boolean, val basic: BmsBasicInfo?, val cells: List<Double>)

    private fun nums(l: List<Double>) = JSONArray().also { a -> l.forEach { a.put(it) } }
    private fun nums(a: JSONArray?) = a?.let { (0 until it.length()).map { k -> it.getDouble(k) } } ?: emptyList()

    fun liveJson(l: Live): JSONObject = JSONObject().put("t", l.tMs).put("name", l.name).put("address", l.address)
        .put("connected", l.connected).put("cells", nums(l.cells)).also { o ->
            l.basic?.let { b -> o.put("basic", JSONObject().put("v", b.packVoltage).put("a", b.current).put("remain", b.remainCapacity)
                .put("full", b.fullCapacity).put("cycles", b.cycleCount).put("soc", b.soc).put("n", b.cellCount).put("temps", nums(b.temperatures))
                .put("chg", b.chargeFetOn).put("dsg", b.dischargeFetOn).put("protect", b.protectFlags).put("balance", b.balanceBits)
                .put("sw", b.softwareVersion).put("made", b.manufactureDate).put("src", b.source).put("mos_temps", nums(b.mosTemperatures))
                .also { j ->
                    b.mosTemperature?.let { j.put("mos", it) }; b.sohReported?.let { j.put("soh", it) }; b.heating?.let { j.put("heat", it) }
                    b.alarmTexts?.let { j.put("alarms", JSONArray(it)) }
                }) }
        }

    fun parseLive(o: JSONObject): Live = Live(o.optLong("t"), o.optString("name"), o.optString("address"), o.optBoolean("connected"),
        o.optJSONObject("basic")?.let { j -> BmsBasicInfo(j.getDouble("v"), j.getDouble("a"), j.optDouble("remain"), j.optDouble("full"),
            j.optInt("cycles"), j.getInt("soc"), j.optInt("n"), nums(j.optJSONArray("temps")), j.optBoolean("chg"), j.optBoolean("dsg"),
            j.optInt("protect"), j.optLong("balance"), j.optString("sw"), j.optString("made"), ByteArray(0),
            j.optJSONArray("alarms")?.let { a -> (0 until a.length()).map { a.getString(it) } },
            if (j.has("mos")) j.getDouble("mos") else null, if (j.has("soh")) j.getInt("soh") else null, j.optString("src"),
            nums(j.optJSONArray("mos_temps")), if (j.has("heat")) j.getBoolean("heat") else null) },
        nums(o.optJSONArray("cells")))

    /** A notification the phone posted: (time, title, text). */
    data class Notif(val tMs: Long, val title: String, val text: String)

    fun notifsJson(l: List<Notif>) = JSONObject().put("notifs", JSONArray().also { a -> l.forEach { a.put(JSONObject().put("t", it.tMs).put("ti", it.title).put("tx", it.text)) } })
    fun parseNotifs(o: JSONObject) = o.optJSONArray("notifs")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { n -> Notif(n.optLong("t"), n.optString("ti"), n.optString("tx")) } } } ?: emptyList()
}
