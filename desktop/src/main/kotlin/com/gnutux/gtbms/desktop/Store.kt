// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import com.vanaccess.bms.reports.Point
import com.vanaccess.bms.reports.Session
import org.json.JSONObject
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Reports on this computer, in a SQLite file with the phone's own tables: a phone backup's
 * database can be merged in as it is. Nothing leaves the computer.
 */
class Store(file: File) {
    private val db: Connection

    init {
        file.parentFile?.mkdirs()
        db = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        db.createStatement().use { st ->
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL,
                start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, battery TEXT, data TEXT NOT NULL)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS points(session_id INTEGER NOT NULL, t_ms INTEGER NOT NULL, v REAL, i REAL,
                soc INTEGER, speed REAL, lat REAL, lon REAL)""")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_points_session ON points(session_id)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_start ON sessions(start_ms)")
        }
    }

    @Synchronized
    fun insert(type: String, start: Long, end: Long, battery: String, data: JSONObject, points: List<Point>): Long {
        db.autoCommit = false
        try {
            val id = db.prepareStatement("INSERT INTO sessions(type,start_ms,end_ms,battery,data) VALUES(?,?,?,?,?)").use { p ->
                p.setString(1, type); p.setLong(2, start); p.setLong(3, end); p.setString(4, battery); p.setString(5, data.toString())
                p.executeUpdate()
                db.createStatement().use { it.executeQuery("SELECT last_insert_rowid()").let { r -> r.next(); r.getLong(1) } }
            }
            db.prepareStatement("INSERT INTO points VALUES(?,?,?,?,?,?,?,?)").use { p ->
                points.forEach { pt ->
                    p.setLong(1, id); p.setLong(2, pt.tMs); p.setDouble(3, pt.v); p.setDouble(4, pt.i); p.setInt(5, pt.soc)
                    pt.speedKmh?.let { p.setDouble(6, it) } ?: p.setNull(6, java.sql.Types.REAL)
                    pt.lat?.let { p.setDouble(7, it) } ?: p.setNull(7, java.sql.Types.REAL)
                    pt.lon?.let { p.setDouble(8, it) } ?: p.setNull(8, java.sql.Types.REAL)
                    p.addBatch()
                }
                p.executeBatch()
            }
            db.commit()
            return id
        } catch (e: Exception) { db.rollback(); throw e } finally { db.autoCommit = true }
    }

    @Synchronized
    fun list(limit: Int = 500): List<Session> = db.prepareStatement(
        "SELECT id,type,start_ms,end_ms,battery,data FROM sessions ORDER BY start_ms DESC LIMIT ?").use { p ->
        p.setInt(1, limit)
        val r = p.executeQuery(); val out = mutableListOf<Session>()
        while (r.next()) out += Session(r.getLong(1), r.getString(2), r.getLong(3), r.getLong(4), r.getString(5) ?: "",
            runCatching { JSONObject(r.getString(6)) }.getOrDefault(JSONObject()))
        out
    }

    @Synchronized
    fun points(id: Long): List<Point> = db.prepareStatement("SELECT t_ms,v,i,soc,speed,lat,lon FROM points WHERE session_id=? ORDER BY t_ms").use { p ->
        p.setLong(1, id)
        val r = p.executeQuery(); val out = mutableListOf<Point>()
        fun d(c: Int) = r.getDouble(c).let { v -> if (r.wasNull()) null else v }
        while (r.next()) out += Point(r.getLong(1), r.getDouble(2), r.getDouble(3), r.getInt(4), d(5), d(6), d(7))
        out
    }

    /** A report from the phone, unless one of that kind started then for that battery is here already. */
    @Synchronized
    fun addIfNew(x: com.vanaccess.bms.sync.SyncedSession): Boolean {
        val s = x.session
        val exists = db.prepareStatement("SELECT 1 FROM sessions WHERE type=? AND start_ms=? AND IFNULL(battery,'')=?").use { p ->
            p.setString(1, s.type); p.setLong(2, s.startMs); p.setString(3, s.battery); p.executeQuery().next()
        }
        if (exists) return false
        insert(s.type, s.startMs, s.endMs, s.battery, s.data, x.points); return true
    }

    fun synced(s: Session) = com.vanaccess.bms.sync.SyncedSession(s, points(s.id))

    /** Adds the reports of another GT-BMS database (a phone backup) that are not here yet. */
    @Synchronized
    fun merge(other: File): Int {
        var added = 0
        DriverManager.getConnection("jdbc:sqlite:${other.absolutePath}").use { o ->
            val rows = o.createStatement().use { st ->
                val r = st.executeQuery("SELECT id,type,start_ms,end_ms,battery,data FROM sessions")
                val l = mutableListOf<Session>()
                while (r.next()) l += Session(r.getLong(1), r.getString(2), r.getLong(3), r.getLong(4), r.getString(5) ?: "",
                    runCatching { JSONObject(r.getString(6)) }.getOrDefault(JSONObject()))
                l
            }
            rows.forEach { s ->
                val exists = db.prepareStatement("SELECT 1 FROM sessions WHERE type=? AND start_ms=? AND IFNULL(battery,'')=?").use { p ->
                    p.setString(1, s.type); p.setLong(2, s.startMs); p.setString(3, s.battery); p.executeQuery().next()
                }
                if (exists) return@forEach
                val pts = o.prepareStatement("SELECT t_ms,v,i,soc,speed,lat,lon FROM points WHERE session_id=? ORDER BY t_ms").use { p ->
                    p.setLong(1, s.id); val r = p.executeQuery(); val l = mutableListOf<Point>()
                    fun d(c: Int) = r.getDouble(c).let { v -> if (r.wasNull()) null else v }
                    while (r.next()) l += Point(r.getLong(1), r.getDouble(2), r.getDouble(3), r.getInt(4), d(5), d(6), d(7))
                    l
                }
                insert(s.type, s.startMs, s.endMs, s.battery, s.data, pts); added++
            }
        }
        return added
    }
}
