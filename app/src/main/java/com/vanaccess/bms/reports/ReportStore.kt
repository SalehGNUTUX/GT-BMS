// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** One saved charge or trip; [data] holds the report fields (see [SessionRecorder]). */
data class Session(
    val id: Long,
    val type: String,          // TYPE_CHARGE | TYPE_TRIP
    val startMs: Long,
    val endMs: Long,
    val battery: String,
    val data: JSONObject,
) {
    fun d(key: String, def: Double = 0.0) = data.optDouble(key, def)
    fun i(key: String, def: Int = 0) = data.optInt(key, def)
    fun b(key: String) = data.optBoolean(key, false)

    /**
     * Current at the largest cell difference; null when it can't be trusted: before 1.7 (no
     * "gap_s") the cells of one reading were paired with the current of another.
     */
    val deltaA: Double? get() = if (data.has("gap_s") || b("delta_paired")) d("current_at_max_delta") else null

    companion object {
        const val TYPE_CHARGE = "charge"
        const val TYPE_TRIP = "trip"
        const val TYPE_DISCHARGE = "discharge"
    }
}

/** One chart sample of a session (every ~2 s). */
data class Point(
    val tMs: Long, val v: Double, val i: Double, val soc: Int,
    val speedKmh: Double?, val lat: Double?, val lon: Double?,
)

/**
 * Local SQLite store for charge and trip reports. Nothing leaves the phone.
 * Report fields are kept as JSON so new fields don't need schema migrations.
 */
class ReportStore private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "gt_bms_reports.db", null, 2) {

    companion object {
        @Volatile private var instance: ReportStore? = null
        fun get(ctx: Context): ReportStore =
            instance ?: synchronized(this) { instance ?: ReportStore(ctx).also { instance = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE sessions(
            id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, start_ms INTEGER NOT NULL,
            end_ms INTEGER NOT NULL, battery TEXT, data TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE points(
            session_id INTEGER NOT NULL, t_ms INTEGER NOT NULL, v REAL, i REAL, soc INTEGER,
            speed REAL, lat REAL, lon REAL)""")
        db.execSQL("CREATE INDEX idx_points_session ON points(session_id)")
        db.execSQL("CREATE INDEX idx_sessions_start ON sessions(start_ms)")
        createLevels(db)
    }

    /** v2: battery level samples for the levels report (one row every ~10 min). */
    private fun createLevels(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS levels(
            t_ms INTEGER NOT NULL, battery TEXT, soc INTEGER, v REAL, remain_ah REAL, full_ah REAL,
            cycles INTEGER, min_cell REAL, max_cell REAL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_levels_t ON levels(t_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createLevels(db)
    }

    data class Level(val tMs: Long, val soc: Int, val v: Double, val remainAh: Double, val fullAh: Double,
                     val cycles: Int, val minCell: Double, val maxCell: Double)

    @Synchronized
    fun addLevel(battery: String, l: Level) {
        writableDatabase.insert("levels", null, ContentValues().apply {
            put("t_ms", l.tMs); put("battery", battery); put("soc", l.soc); put("v", l.v)
            put("remain_ah", l.remainAh); put("full_ah", l.fullAh); put("cycles", l.cycles)
            put("min_cell", l.minCell); put("max_cell", l.maxCell)
        })
    }

    @Synchronized
    fun levels(sinceMs: Long, battery: String? = null): List<Level> {
        readableDatabase.rawQuery(
            "SELECT t_ms, soc, v, remain_ah, full_ah, cycles, min_cell, max_cell FROM levels WHERE t_ms >= ? AND (? = '' OR battery = ? OR battery = '') ORDER BY t_ms",
            arrayOf(sinceMs.toString(), battery ?: "", battery ?: "")
        ).use { c ->
            val out = mutableListOf<Level>()
            while (c.moveToNext()) out += Level(c.getLong(0), c.getInt(1), c.getDouble(2), c.getDouble(3),
                c.getDouble(4), c.getInt(5), c.getDouble(6), c.getDouble(7))
            return out
        }
    }

    @Synchronized
    fun insert(type: String, startMs: Long, endMs: Long, battery: String, data: JSONObject, points: List<Point>): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insert("sessions", null, ContentValues().apply {
                put("type", type); put("start_ms", startMs); put("end_ms", endMs)
                put("battery", battery); put("data", data.toString())
            })
            points.forEach { p ->
                db.insert("points", null, ContentValues().apply {
                    put("session_id", id); put("t_ms", p.tMs); put("v", p.v); put("i", p.i); put("soc", p.soc)
                    p.speedKmh?.let { put("speed", it) }; p.lat?.let { put("lat", it) }; p.lon?.let { put("lon", it) }
                })
            }
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun updateData(id: Long, data: JSONObject) {
        writableDatabase.update("sessions", ContentValues().apply { put("data", data.toString()) }, "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    /**
     * @param battery only this battery's reports (plus old ones saved before the address was
     *        recorded); null = every battery.
     */
    fun list(type: String? = null, limit: Int = 200, battery: String? = null): List<Session> {
        val conds = mutableListOf<String>(); val a = mutableListOf<String>()
        if (type != null) { conds += "type = ?"; a += type }
        if (!battery.isNullOrBlank()) { conds += "(battery = ? OR battery = '' OR battery IS NULL)"; a += battery }
        val where = if (conds.isEmpty()) "" else "WHERE " + conds.joinToString(" AND ")
        val args = if (a.isEmpty()) null else a.toTypedArray()
        readableDatabase.rawQuery(
            "SELECT id, type, start_ms, end_ms, battery, data FROM sessions $where ORDER BY start_ms DESC LIMIT $limit", args
        ).use { c ->
            val out = mutableListOf<Session>()
            while (c.moveToNext()) {
                out += Session(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getString(4) ?: "",
                    try { JSONObject(c.getString(5)) } catch (_: Exception) { JSONObject() })
            }
            return out
        }
    }

    /** Sessions of several types, newest first. */
    @Synchronized
    fun listTypes(types: List<String>, limit: Int = 200, battery: String? = null): List<Session> =
        types.flatMap { list(it, limit, battery) }.sortedByDescending { it.startMs }.take(limit)

    @Synchronized
    fun points(sessionId: Long): List<Point> {
        readableDatabase.rawQuery(
            "SELECT t_ms, v, i, soc, speed, lat, lon FROM points WHERE session_id = ? ORDER BY t_ms",
            arrayOf(sessionId.toString())
        ).use { c ->
            val out = mutableListOf<Point>()
            while (c.moveToNext()) {
                out += Point(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getInt(3),
                    if (c.isNull(4)) null else c.getDouble(4),
                    if (c.isNull(5)) null else c.getDouble(5),
                    if (c.isNull(6)) null else c.getDouble(6))
            }
            return out
        }
    }

    @Synchronized
    fun delete(id: Long) {
        writableDatabase.apply {
            delete("points", "session_id = ?", arrayOf(id.toString()))
            delete("sessions", "id = ?", arrayOf(id.toString()))
        }
    }

    /**
     * Trips whose distance covers more than their energy would teach a consumption that is too
     * low. Before 1.6.1 such trips were not marked; an average speed above the top speed gives them away.
     */
    private fun energyComplete(s: Session) = !s.b("energy_partial") &&
        (s.data.has("gap_s") || s.d("max_speed") <= 0 || s.d("avg_speed") <= s.d("max_speed"))

    /**
     * Consumption learned from recent trips with GPS distance: (Wh/km, km covered).
     * Null until at least 2 km have been recorded.
     */
    fun learnedConsumption(battery: String?, maxTrips: Int = 20): Pair<Double, Double>? {
        val trips = list(Session.TYPE_TRIP, maxTrips, battery).filter { it.d("distance_km") >= 0.3 && it.d("wh") > 0 && energyComplete(it) }
        val km = trips.sumOf { it.d("distance_km") }
        if (km < 2.0) return null
        return trips.sumOf { it.d("wh") } / km to km
    }
}
