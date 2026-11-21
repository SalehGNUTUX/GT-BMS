// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import com.vanaccess.bms.ble.BmsBasicInfo
import com.vanaccess.bms.data.AppPreferences
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the live BMS stream into charge and trip reports, automatically.
 *
 * Charge: starts after the current stays above +[CHG_START_A] for 20 s; ends after it
 * stays below [CHG_END_A] for 2 min (or the battery is gone for 10 min).
 *
 * Trip (vehicle profiles only): starts after discharging below −[TRIP_START_A] for 10 s;
 * GPS runs from that moment. Ends after 3 min without load and without movement.
 *
 * Energy is integrated from the BMS current and pack voltage (dt capped at 10 s so a
 * Bluetooth gap is not counted as a long constant draw). All work runs on the main
 * looper, so no locking is needed.
 */
class SessionRecorder(private val context: Context, private val prefs: AppPreferences) {

    companion object {
        const val CHG_START_A = 0.3
        const val CHG_END_A = 0.1
        const val TRIP_START_A = 1.0
        const val TRIP_IDLE_A = 0.5
        const val MOVING_KMH = 2.0
        private const val MAX_DT_S = 10.0
        private const val POINT_EVERY_MS = 2000L
        private const val HARD_ACCEL_MPS2 = 2.0

        /** Live state for the UI. */
        val liveSpeedKmh = MutableLiveData<Double?>(null)
        val activeTrip = MutableLiveData<TripLive?>(null)
        val activeCharge = MutableLiveData<ChargeLive?>(null)
        /** Bumped whenever a report is saved, so lists refresh. */
        val savedCount = MutableLiveData(0)
    }

    data class TripLive(val startMs: Long, val distanceKm: Double, val wh: Double, val maxSpeed: Double)
    data class ChargeLive(val startMs: Long, val socStart: Int, val ah: Double, val wh: Double)

    private val main = Handler(Looper.getMainLooper())
    private val store = ReportStore.get(context)
    private val gps = LocationTracker(context)

    private var lastSampleMs = 0L
    private var lastInfo: BmsBasicInfo? = null
    private var lastCells: List<Double> = emptyList()
    private var battery = ""
    /** Cell over-voltage protection limit (V) from the BMS parameters, if known. */
    @Volatile var cellOvLimitV: Double = 3.65

    // ── Charge accumulator ──
    private var chgCandidateSince = 0L
    private var chg: Acc? = null
    private var chgQuietSince = 0L

    // ── Trip accumulator ──
    private var tripCandidateSince = 0L
    private var trip: Acc? = null
    private var tripIdleSince = 0L
    private var gpsStopAt = 0L

    /** Running totals shared by both kinds of session. */
    private class Acc(val startMs: Long, val socStart: Int) {
        var endMs = startMs
        var socEnd = socStart
        var ah = 0.0; var wh = 0.0
        var activeS = 0.0
        var maxV = 0.0; var minV = Double.MAX_VALUE
        var maxCellV = 0.0; var maxCellIdx = 0
        var minCellV = Double.MAX_VALUE; var minCellIdx = 0
        var maxDeltaMv = 0; var currentAtMaxDelta = 0.0
        var maxTemp = -100.0
        var maxCurrent = 0.0
        var balancingSeen = false
        var endedByProtection = false
        // trip-only
        var distanceKm = 0.0; var maxSpeed = 0.0; var movingS = 0.0; var hardAccel = 0
        var lastFix: LocationTracker.Fix? = null; var lastAccelMs = 0L
        val points = mutableListOf<Point>()
        var lastPointMs = 0L
    }

    fun onBattery(address: String?) { battery = address ?: "" }

    fun onCells(cells: List<Double>) { main.post { lastCells = cells } }

    fun onReading(info: BmsBasicInfo) { main.post { process(info) } }

    /** Call every few seconds so sessions end even if the battery disappears. */
    fun onTick() {
        main.post {
            val now = System.currentTimeMillis()
            val silent = lastSampleMs > 0 && now - lastSampleMs > 10 * 60_000L
            if (silent) {
                chg?.let { finishCharge(it) }; chg = null
                trip?.let { finishTrip(it) }; trip = null
            }
            if (trip == null && tripCandidateSince == 0L && gps.running && now > gpsStopAt) stopGps()
        }
    }

    private fun process(info: BmsBasicInfo) {
        val now = System.currentTimeMillis()
        val dtS = if (lastSampleMs == 0L) 0.0 else min((now - lastSampleMs) / 1000.0, MAX_DT_S)
        lastSampleMs = now
        lastInfo = info
        val i = info.current
        val fix = gps.current
        liveSpeedKmh.value = fix?.speedKmh

        // ── Charge ──
        if (chg == null) {
            if (i > CHG_START_A) {
                if (chgCandidateSince == 0L) chgCandidateSince = now
                if (now - chgCandidateSince >= 20_000) { chg = Acc(chgCandidateSince, info.soc); chgQuietSince = 0L }
            } else chgCandidateSince = 0L
        }
        chg?.let { c ->
            if (i > CHG_END_A) { accumulate(c, info, dtS, now, charging = true); chgQuietSince = 0L }
            else {
                if (chgQuietSince == 0L) chgQuietSince = now
                if (!info.chargeFetOn && c.maxCellV >= cellOvLimitV - 0.01) c.endedByProtection = true
                if (now - chgQuietSince >= 120_000) { finishCharge(c); chg = null; chgCandidateSince = 0L }
            }
            if (chg != null) activeCharge.value = ChargeLive(c.startMs, c.socStart, c.ah, c.wh)
        }

        // ── Trip ──
        val tripsOn = prefs.vehicle.mobile && prefs.tripsEnabled
        if (!tripsOn) {
            if (trip != null) { finishTrip(trip!!); trip = null }
            if (gps.running) stopGps()
            return
        }
        if (trip == null) {
            if (i < -TRIP_START_A) {
                if (tripCandidateSince == 0L) { tripCandidateSince = now; gpsStopAt = now + 5 * 60_000L; startGps() }
                if (now - tripCandidateSince >= 10_000) { trip = Acc(tripCandidateSince, info.soc); tripIdleSince = 0L }
            } else if (tripCandidateSince != 0L && now - tripCandidateSince > 60_000) {
                tripCandidateSince = 0L
                gpsStopAt = now + 60_000
            }
        }
        trip?.let { t ->
            accumulate(t, info, dtS, now, charging = false)
            val moving = (fix?.speedKmh ?: 0.0) >= MOVING_KMH
            val loaded = i < -TRIP_IDLE_A
            if (moving) t.movingS += dtS
            if (moving || loaded) tripIdleSince = 0L else {
                if (tripIdleSince == 0L) tripIdleSince = now
                if (now - tripIdleSince >= 180_000) {
                    finishTrip(t); trip = null; tripCandidateSince = 0L
                    gpsStopAt = now + 60_000
                }
            }
            if (trip != null) activeTrip.value = TripLive(t.startMs, t.distanceKm, t.wh, t.maxSpeed)
        }
    }

    private fun accumulate(a: Acc, info: BmsBasicInfo, dtS: Double, now: Long, charging: Boolean) {
        val iAbs = abs(info.current)
        // Net energy in the session's direction: for trips, regenerative braking (positive
        // current) reduces the energy used instead of adding to it.
        val iDir = if (charging) info.current else -info.current
        a.endMs = now
        a.socEnd = info.soc
        a.ah += iDir * dtS / 3600.0
        a.wh += iDir * info.packVoltage * dtS / 3600.0
        a.activeS += dtS
        a.maxV = max(a.maxV, info.packVoltage)
        a.minV = min(a.minV, info.packVoltage)
        a.maxCurrent = max(a.maxCurrent, iDir)
        (info.temperatures + info.mosTemperatures).maxOrNull()?.let { a.maxTemp = max(a.maxTemp, it) }
        if (info.balanceBits != 0L) a.balancingSeen = true
        val cells = lastCells
        if (cells.size > 1) {
            val mx = cells.max(); val mn = cells.min()
            if (mx > a.maxCellV) { a.maxCellV = mx; a.maxCellIdx = cells.indexOf(mx) + 1 }
            if (mn < a.minCellV) { a.minCellV = mn; a.minCellIdx = cells.indexOf(mn) + 1 }
            val delta = ((mx - mn) * 1000).toInt()
            // For trips we care about the difference under load; for charges, overall.
            if (delta > a.maxDeltaMv && (charging || iAbs >= prefs.vehicle.loadCurrentA)) {
                a.maxDeltaMv = delta; a.currentAtMaxDelta = iAbs
            }
        }
        if (now - a.lastPointMs >= POINT_EVERY_MS) {
            a.lastPointMs = now
            val f = gps.current
            a.points += Point(now, info.packVoltage, info.current, info.soc, f?.speedKmh, f?.lat, f?.lon)
        }
    }

    // ── GPS ──

    private fun startGps() {
        gps.start { fix -> main.post { onFix(fix) } }
    }

    private fun stopGps() {
        gps.stop()
        liveSpeedKmh.value = null
    }

    private fun onFix(fix: LocationTracker.Fix) {
        liveSpeedKmh.value = fix.speedKmh
        val t = trip ?: return
        val prev = t.lastFix
        t.lastFix = fix
        t.maxSpeed = max(t.maxSpeed, fix.speedKmh)
        if (prev == null) return
        val dtS = (fix.elapsedMs - prev.elapsedMs) / 1000.0
        if (dtS <= 0 || dtS > 10) return
        // Distance only while really moving, to ignore GPS drift when parked
        if (fix.speedKmh >= MOVING_KMH || prev.speedKmh >= MOVING_KMH) {
            val res = FloatArray(1)
            android.location.Location.distanceBetween(prev.lat, prev.lon, fix.lat, fix.lon, res)
            if (res[0] < 200f) t.distanceKm += res[0] / 1000.0
        }
        val accel = (fix.speedKmh - prev.speedKmh) / 3.6 / dtS
        if (dtS <= 2.5 && accel >= HARD_ACCEL_MPS2 && fix.elapsedMs - t.lastAccelMs > 5000) {
            t.hardAccel++; t.lastAccelMs = fix.elapsedMs
        }
    }

    // ── Saving ──

    private fun common(a: Acc) = JSONObject().apply {
        put("soc_start", a.socStart); put("soc_end", a.socEnd)
        put("ah", a.ah); put("wh", a.wh); put("active_s", a.activeS)
        put("max_v", a.maxV); put("min_v", if (a.minV == Double.MAX_VALUE) 0.0 else a.minV)
        put("max_cell_v", a.maxCellV); put("max_cell_idx", a.maxCellIdx)
        put("min_cell_v", if (a.minCellV == Double.MAX_VALUE) 0.0 else a.minCellV); put("min_cell_idx", a.minCellIdx)
        put("max_delta_mv", a.maxDeltaMv); put("current_at_max_delta", a.currentAtMaxDelta)
        put("max_temp", if (a.maxTemp < -50) JSONObject.NULL else a.maxTemp)
        put("max_current", a.maxCurrent)
    }

    private fun finishCharge(c: Acc) {
        activeCharge.value = null
        if (c.activeS < 180 || c.ah < 0.05) return
        val data = common(c).apply {
            put("avg_current", if (c.activeS > 0) c.ah * 3600 / c.activeS else 0.0)
            put("balancing_seen", c.balancingSeen)
            put("ended_by_protection", c.endedByProtection || c.maxCellV >= cellOvLimitV - 0.005)
            put("cell_ov_limit", cellOvLimitV)
            put("reached_full", c.socEnd >= 99)
        }
        save(Session.TYPE_CHARGE, c, data)
    }

    private fun finishTrip(t: Acc) {
        activeTrip.value = null
        if (t.activeS < 60 || (t.distanceKm < 0.1 && t.ah < 0.05)) return
        val data = common(t).apply {
            put("distance_km", t.distanceKm)
            put("max_speed", t.maxSpeed)
            put("moving_s", t.movingS)
            put("avg_speed", if (t.movingS > 0) t.distanceKm / (t.movingS / 3600.0) else 0.0)
            put("wh_per_km", if (t.distanceKm >= 0.1) t.wh / t.distanceKm else JSONObject.NULL)
            put("ah_per_km", if (t.distanceKm >= 0.1) t.ah / t.distanceKm else JSONObject.NULL)
            put("hard_accel", t.hardAccel)
            put("vehicle", prefs.vehicleType)
            put("gps", t.distanceKm > 0 || t.maxSpeed > 0)
        }
        save(Session.TYPE_TRIP, t, data)
    }

    private fun save(type: String, a: Acc, data: JSONObject) {
        val pts = a.points.toList()
        val bat = battery
        Thread {
            try { store.insert(type, a.startMs, a.endMs, bat, data, pts) } catch (_: Exception) {}
            main.post { savedCount.value = (savedCount.value ?: 0) + 1 }
        }.start()
    }

    fun release() {
        chg?.let { finishCharge(it) }; chg = null
        trip?.let { finishTrip(it) }; trip = null
        stopGps()
    }
}
