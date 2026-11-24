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
 * GPS runs from that moment. Ends after 2 min without load and without movement. If Bluetooth
 * drops while riding, GPS keeps the trip going (see [TripGap]) and the energy of the gap is
 * taken from the BMS's own capacity counter when the battery answers again.
 *
 * Energy is integrated from the BMS current and pack voltage (dt capped at 10 s so a
 * Bluetooth gap is not counted as a long constant draw). All work runs on the main
 * looper, so no locking is needed.
 */
class SessionRecorder(private val context: Context, private val prefs: AppPreferences) {

    companion object {
        /** No protection known for this battery: nothing is judged against it. */
        const val UNKNOWN_OV = 99.0
        // Currents for a 30 Ah battery; scaled to the capacity of the connected one (see [scale])
        const val CHG_START_A = 0.3
        const val CHG_END_A = 0.1
        const val TRIP_START_A = 1.0
        const val TRIP_IDLE_A = 0.5
        /** A trip ends after this long with no load and no movement. */
        const val TRIP_IDLE_END_MS = 120_000L
        /** A saved trip may still get its missing energy from a reading this soon after its end. */
        private const val LATE_ENERGY_MS = 60 * 60_000L
        const val DIS_START_A = 0.5
        const val DIS_END_A = 0.2
        const val MOVING_KMH = 2.0
        private const val MAX_DT_S = 10.0
        private const val POINT_EVERY_MS = 2000L
        private const val HARD_ACCEL_MPS2 = 2.0

        /** Live state for the UI. */
        val liveSpeedKmh = MutableLiveData<Double?>(null)
        val activeTrip = MutableLiveData<TripLive?>(null)
        val activeDischarge = MutableLiveData<ChargeLive?>(null)
        val activeCharge = MutableLiveData<ChargeLive?>(null)
        /** The charge being followed (time left, next cut). */
        @Volatile var chargeWatch: ChargeWatch? = null
        @Volatile var chargeLearn = ChargeLearn()
        /** A charge that has been without current this long has stopped. */
        private const val CHG_STOP_MS = 30_000L
        /** A session younger than this is continued after a restart. */
        private const val RESUME_MS = 15 * 60_000L
        private const val REST_MS = 10 * 60_000L
        /** Bumped whenever a report is saved, so lists refresh. */
        val savedCount = MutableLiveData(0)
    }

    data class TripLive(val startMs: Long, val distanceKm: Double, val wh: Double, val maxSpeed: Double)
    data class ChargeLive(val startMs: Long, val socStart: Int, val ah: Double, val wh: Double, val stopped: Boolean = false)

    private val main = Handler(Looper.getMainLooper())
    private val store = ReportStore.get(context)
    private val gps = LocationTracker(context)

    private var lastSampleMs = 0L
    private var lastInfo: BmsBasicInfo? = null
    private var lastRemainAh = 0.0
    private var lastPackV = 0.0
    private var lastCurrentA = 0.0
    private var lastCells: List<Double> = emptyList()
    private var battery = ""
    /** Cell over-voltage protection limit (V) from the BMS parameters, if known. */
    @Volatile var cellOvLimitV: Double = UNKNOWN_OV

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
    private class Acc(val startMs: Long, val socStart: Int, val startMinCell: Double = 0.0) {
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
        // charge-only
        var watch: ChargeWatch? = null
        var stop: ChargeWatch.Result? = null
        var stopMs = 0L
        var remainStart = 0.0
        var etaFirstMin: Double? = null
        /** Riding time without battery data (Bluetooth lost). */
        var gapMs = 0L

        fun toJson() = JSONObject().apply {
            put("start", startMs); put("soc0", socStart); put("cell0", startMinCell); put("end", endMs); put("soc1", socEnd)
            put("ah", ah); put("wh", wh); put("act", activeS); put("maxV", maxV); if (minV < 1e6) put("minV", minV)
            put("mxC", maxCellV); put("mxI", maxCellIdx); if (minCellV < 1e6) put("mnC", minCellV); put("mnI", minCellIdx)
            put("dmv", maxDeltaMv); put("di", currentAtMaxDelta); put("temp", maxTemp); put("maxI", maxCurrent)
            put("bal", balancingSeen); put("prot", endedByProtection); put("km", distanceKm); put("vmax", maxSpeed)
            put("mov", movingS); put("acc", hardAccel); put("gap", gapMs); etaFirstMin?.let { put("eta", it) }
            put("pts", org.json.JSONArray().also { a -> points.forEach { p ->
                a.put(org.json.JSONArray().put(p.tMs).put(p.v).put(p.i).put(p.soc).put(p.speedKmh ?: JSONObject.NULL)
                    .put(p.lat ?: JSONObject.NULL).put(p.lon ?: JSONObject.NULL)) } })
        }

        companion object {
            fun fromJson(o: JSONObject) = Acc(o.getLong("start"), o.getInt("soc0"), o.optDouble("cell0", 0.0)).apply {
                endMs = o.getLong("end"); socEnd = o.getInt("soc1"); ah = o.getDouble("ah"); wh = o.getDouble("wh"); activeS = o.getDouble("act")
                maxV = o.getDouble("maxV"); minV = o.optDouble("minV", Double.MAX_VALUE)
                maxCellV = o.getDouble("mxC"); maxCellIdx = o.getInt("mxI"); minCellV = o.optDouble("mnC", Double.MAX_VALUE); minCellIdx = o.getInt("mnI")
                maxDeltaMv = o.getInt("dmv"); currentAtMaxDelta = o.getDouble("di"); maxTemp = o.getDouble("temp"); maxCurrent = o.getDouble("maxI")
                balancingSeen = o.getBoolean("bal"); endedByProtection = o.getBoolean("prot"); distanceKm = o.getDouble("km")
                maxSpeed = o.getDouble("vmax"); movingS = o.getDouble("mov"); hardAccel = o.getInt("acc"); gapMs = o.getLong("gap")
                if (o.has("eta")) etaFirstMin = o.getDouble("eta")
                val a = o.getJSONArray("pts")
                for (k in 0 until a.length()) a.getJSONArray(k).let { p ->
                    fun d(n: Int) = if (p.isNull(n)) null else p.getDouble(n)
                    points += Point(p.getLong(0), p.getDouble(1), p.getDouble(2), p.getInt(3), d(4), d(5), d(6)) }
            }
        }
    }

    // ── A running trip or charge survives a crash or a kill ──

    private val stateFile = java.io.File(context.filesDir, "active_session.json")
    private val disk = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var lastCheckpoint = 0L

    private fun checkpoint(now: Long) {
        val t = trip; val c = chg
        if (t == null && c == null) {
            if (lastCheckpoint != 0L) { lastCheckpoint = 0L; disk.execute { stateFile.delete() } }
            return
        }
        if (now - lastCheckpoint < 20_000) return
        lastCheckpoint = now
        val json = JSONObject().put("battery", battery).put("saved", now).put("sample", lastSampleMs).put("remain", lastRemainAh)
            .put("pack", lastPackV).put("cur", lastCurrentA).put("active", lastTripActiveMs).put("riding", lastRidingMs).put("loss", ridingAtLossMs)
        t?.let { json.put("trip", it.toJson()) }
        c?.let { json.put("charge", it.toJson()).put("charge_remain0", it.remainStart) }
        val text = json.toString()
        disk.execute { runCatching {
            val tmp = java.io.File(stateFile.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(stateFile)) { stateFile.delete(); tmp.renameTo(stateFile) }
        } }
    }

    /** After the process ended unexpectedly: go on with the session, or save it if it is too old. */
    fun restore() {
        if (!stateFile.exists()) return
        runCatching {
            val o = JSONObject(stateFile.readText())
            val age = System.currentTimeMillis() - o.getLong("saved")
            val same = o.optString("battery") == (prefs.selectedDeviceAddress ?: "")
            battery = o.optString("battery")
            lastSampleMs = o.getLong("sample"); lastRemainAh = o.getDouble("remain"); lastPackV = o.getDouble("pack"); lastCurrentA = o.optDouble("cur", 0.0)
            lastTripActiveMs = o.getLong("active"); lastRidingMs = o.getLong("riding"); ridingAtLossMs = o.getLong("loss")
            lastCheckpoint = 1L
            val go = same && age in 0..RESUME_MS
            o.optJSONObject("trip")?.let { Acc.fromJson(it) }?.let { t ->
                log("trip restored after ${age / 1000}s, ${if (go) "continues" else "saved"}")
                if (go) {
                    trip = t; gpsStopAt = System.currentTimeMillis() + 5 * 60_000L
                    lastRidingMs = System.currentTimeMillis()       // time for GPS to find itself before judging
                    if (!startGps()) log("gps refused")
                } else { t.endMs = maxOf(t.endMs, minOf(lastTripActiveMs, o.getLong("saved"))); finishTrip(t) }
            }
            o.optJSONObject("charge")?.let { Acc.fromJson(it) }?.let { c ->
                c.remainStart = o.optDouble("charge_remain0", 0.0)
                c.watch = ChargeWatch(c.startMs, c.socStart, c.remainStart)
                log("charge restored after ${age / 1000}s, ${if (go) "continues" else "saved"}")
                if (go) { chg = c; chgCandidateSince = c.startMs; chargeWatch = c.watch } else finishCharge(c)
            }
            updateWakeLock()
        }.onFailure { log("restore failed: $it") }
        if (trip == null && chg == null) stateFile.delete()
    }

    /** A 10 Ah scooter and a 300 Ah store don't idle or start at the same current. */
    private fun scale(fullAh: Double) = if (fullAh > 0) (fullAh / 30.0).coerceIn(0.5, 5.0) else 1.0

    fun onBattery(address: String?) { main.post { battery = address ?: "" } }

    /** Another battery is about to be connected: close what belongs to the previous one. */
    fun switchBattery() {
        main.post {
            chg?.let { finishCharge(it) }; chg = null; chgCandidateSince = 0L
            dis?.let { finishDischarge(it) }; dis = null; disCandidateSince = 0L
            trip?.let { endTrip(it) }
            lastRemainAh = 0.0; lastPackV = 0.0
            rest = null; late = null; lastInfo = null; lastCells = emptyList(); lastSampleMs = 0L
            chargeLearn = ChargeLearn()
            cellOvLimitV = UNKNOWN_OV
        }
    }

    fun onCells(cells: List<Double>) { main.post { lastCells = cells } }

    fun onReading(info: BmsBasicInfo) { main.post { process(info) } }

    /** Call every few seconds so sessions end even if the battery disappears. */
    fun onTick() {
        main.post {
            val now = System.currentTimeMillis()
            val silent = lastSampleMs > 0 && now - lastSampleMs > 10 * 60_000L
            if (silent) {
                // Forget when they began too, or the next one would be dated back to them
                if (now - lastSampleMs > TripGap.CHARGE_SILENCE_MS) {
                    chg?.let { finishCharge(it) }; chg = null; chgCandidateSince = 0L
                }
                dis?.let { finishDischarge(it) }; dis = null; disCandidateSince = 0L
            }
            // Battery silent during a trip: parked → the rider left, end it at the last moment it
            // was moving or under load; riding → GPS decides.
            if (trip != null && lastSampleMs > 0 &&
                TripGap.shouldEnd(now, lastSampleMs, ridingAtLossMs, lastRidingMs)) endTrip(trip!!)
            updateWakeLock()
            checkpoint(now)
            if (trip == null && tripCandidateSince == 0L && gps.running && now > gpsStopAt) stopGps()
        }
    }

    // Riding evidence (load or faster than walking): overall, and up to the last battery reading
    private var lastRidingMs = 0L
    private var ridingAtLossMs = 0L

    /** A trip saved while the battery was silent, waiting for the energy of its last stretch. */
    private class Late(val session: Session, val acc: Acc, val remainAh: Double, val packV: Double)
    private var late: Late? = null

    // The phone must not sleep during a trip or a charge: polling, GPS and reconnection stop with it.
    private val wakeLock by lazy {
        context.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "gtbms:session").apply { setReferenceCounted(false) }
    }

    /** Renewed every tick; the timeout frees it by itself if the app is killed or stuck. */
    private fun updateWakeLock() {
        tripActive = trip != null || (gps.running && tripCandidateSince != 0L)
        val need = trip != null || gps.running ||
            (chg != null && System.currentTimeMillis() - lastSampleMs < 3 * 60_000L)
        runCatching { if (need) wakeLock.acquire(60_000L) else if (wakeLock.isHeld) wakeLock.release() }
    }

    private var lastLevelMs = 0L
    private var lastTripActiveMs = 0L
    /** After a manual end, don't start a new trip until the vehicle has been idle a moment. */
    private var manualHold = false
    private var holdIdleSince = 0L

    // ── Stationary discharge accumulator (storage profile, or trips switched off) ──
    private var disCandidateSince = 0L
    private var dis: Acc? = null
    private var disQuietSince = 0L

    /** Manual "End trip" from the UI. */
    fun endTripNow() {
        main.post {
            trip?.let { endTrip(it) }
            manualHold = true; holdIdleSince = 0L
            tripCandidateSince = 0L
        }
    }

    private fun endTrip(t: Acc) {
        if (lastTripActiveMs > t.startMs) t.endMs = lastTripActiveMs
        log("trip ended, battery silent ${(System.currentTimeMillis() - lastSampleMs) / 1000}s, ${"%.2f".format(java.util.Locale.US, t.distanceKm)} km")
        finishTrip(t); trip = null; tripCandidateSince = 0L
        gpsStopAt = System.currentTimeMillis() + 60_000
    }

    /** A charge stopped (or resumed and stopped again): why, and at which SOC. */
    var onChargeStop: ((ChargeWatch.Result) -> Unit)? = null
    var log: (String) -> Unit = {}

    /** Riding, or just started: the phone is kept awake. */
    @Volatile var tripActive = false
        private set

    /** Saved charge waiting for its resting voltage. */
    private class Rest(val session: Session, val stopMs: Long)
    private var rest: Rest? = null

    private fun stepRest(info: BmsBasicInfo, now: Long) {
        val r = rest ?: return
        val age = now - r.stopMs
        if (abs(info.current) > CHG_START_A || age > 3 * REST_MS || r.session.battery != battery) { rest = null; return }
        if (age < REST_MS) return
        rest = null
        val cells = lastCells
        r.session.data.put("rest_v", info.packVoltage).put("rest_min", age / 60_000)
        if (cells.size > 1) r.session.data.put("rest_delta_mv", Math.round((cells.max() - cells.min()) * 1000))
        Thread {
            try { store.updateData(r.session.id, r.session.data) } catch (_: Exception) {}
            main.post { savedCount.value = (savedCount.value ?: 0) + 1 }
        }.start()
    }

    /** Called with every saved report (on the main thread). */
    var onSaved: ((Session) -> Unit)? = null

    private fun process(info: BmsBasicInfo) {
        val now = System.currentTimeMillis()
        if (now - lastLevelMs >= 10 * 60_000L) {
            lastLevelMs = now
            val cells = lastCells
            val lvl = ReportStore.Level(now, info.soc, info.packVoltage, info.remainCapacity, info.fullCapacity,
                info.cycleCount, cells.minOrNull() ?: 0.0, cells.maxOrNull() ?: 0.0)
            val bat = battery
            Thread { try { store.addLevel(bat, lvl) } catch (_: Exception) {} }.start()
        }
        val silentMs = if (lastSampleMs == 0L) 0L else now - lastSampleMs
        val dtS = min(silentMs / 1000.0, MAX_DT_S)
        val remainBefore = lastRemainAh; val packBefore = lastPackV; val currentBefore = lastCurrentA
        lastSampleMs = now
        lastInfo = info
        lastRemainAh = info.remainCapacity; lastPackV = info.packVoltage; lastCurrentA = info.current
        late?.let { completeLate(it, info, now) }
        if (silentMs > MAX_DT_S * 1000) trip?.let { t ->
            // Back after a gap: add what the BMS counted meanwhile, minus the part accumulate() adds
            val ah = TripGap.missingAh(remainBefore, info.remainCapacity, info.fullCapacity) -
                max(0.0, -info.current) * dtS / 3600.0
            if (ah > 0) { t.ah += ah; t.wh += ah * (packBefore + info.packVoltage) / 2 }
            t.gapMs += silentMs
        }
        val i = info.current
        val k = scale(info.fullCapacity)
        val fix = gps.current
        liveSpeedKmh.value = fix?.speedKmh.takeIf { trip != null }      // not the rider on foot

        stepRest(info, now)
        // ── Charge ──
        if (silentMs > MAX_DT_S * 1000) chg?.let { c ->
            if (info.remainCapacity < remainBefore - 0.15) {          // used meanwhile: that charge was over
                finishCharge(c); chg = null; chgCandidateSince = 0L
            } else {
                val ah = TripGap.chargedAh(remainBefore, info.remainCapacity, currentBefore, silentMs / 1000.0) -
                    max(0.0, i) * dtS / 3600.0
                if (ah > 0) { c.ah += ah; c.wh += ah * (packBefore + info.packVoltage) / 2 }
                c.endMs = now; c.gapMs += silentMs; chgQuietSince = 0L
                log("charge continues after ${silentMs / 1000}s of silence")
            }
        }
        if (chg == null) {
            if (i > CHG_START_A * k) {
                if (chgCandidateSince == 0L) chgCandidateSince = now
                if (now - chgCandidateSince >= 20_000) {
                    chg = Acc(chgCandidateSince, info.soc, lastCells.minOrNull() ?: 0.0).apply {
                        watch = ChargeWatch(startMs, info.soc, info.remainCapacity); remainStart = info.remainCapacity
                    }
                    chargeWatch = chg?.watch; chgQuietSince = 0L; rest = null
                    updateWakeLock()
                    log("charge started ${info.soc}%")
                }
            } else chgCandidateSince = 0L
        }
        chg?.let { c ->
            if (i > CHG_END_A * k) {
                accumulate(c, info, dtS, now, charging = true); chgQuietSince = 0L; c.stop = null
                c.watch?.charging(now, info.soc, info.remainCapacity, info.fullCapacity, i, info.packVoltage,
                    lastCells, info.chargeFetOn, c.ah)
                if (c.etaFirstMin == null && now - c.startMs >= 120_000) c.etaFirstMin = predict(info)?.let {
                    (now - c.startMs) / 60_000.0 + it.hours * 60 }
            } else {
                if (chgQuietSince == 0L) chgQuietSince = now
                if (c.stop == null && now - chgQuietSince >= CHG_STOP_MS) {
                    c.stopMs = chgQuietSince
                    c.stop = c.watch?.stopped(now, info.soc, info.remainCapacity, info.fullCapacity,
                        info.chargeFetOn, info.protectionFlags.map { it.name }, cellOvLimitV)
                    c.stop?.let { r -> if (c.activeS >= 180) { log("charge stopped ${r.reason} ${r.socBefore}/${r.socShown}%"); onChargeStop?.invoke(r) } }
                    if (c.stop?.reason == ChargeWatch.Stop.PROTECTION) c.endedByProtection = true
                }
                if (!info.chargeFetOn && c.maxCellV >= cellOvLimitV - 0.01) c.endedByProtection = true
                if (now - chgQuietSince >= 120_000) { c.socEnd = info.soc; finishCharge(c); chg = null; chgCandidateSince = 0L }
            }
            if (chg != null) activeCharge.value = ChargeLive(c.startMs, c.socStart, c.ah, c.wh, c.stop != null)
        }

        // ── Trip ──
        val tripsOn = prefs.vehicle.mobile && prefs.tripsEnabled
        if (!tripsOn) {
            if (trip != null) endTrip(trip!!)
            if (gps.running) stopGps()
            processDischarge(info, i / k, dtS, now)
            return
        }
        dis?.let { finishDischarge(it); dis = null }
        if (manualHold) {
            val idleNow = i >= -TRIP_IDLE_A * k && (fix?.speedKmh ?: 0.0) < MOVING_KMH
            if (idleNow) { if (holdIdleSince == 0L) holdIdleSince = now } else holdIdleSince = 0L
            if (holdIdleSince != 0L && now - holdIdleSince >= 30_000) manualHold = false
        }
        if (trip == null && !manualHold) {
            if (i < -TRIP_START_A * k) {
                if (tripCandidateSince == 0L) {
                    tripCandidateSince = now; gpsStopAt = now + 5 * 60_000L
                    if (!startGps()) log("gps refused")
                    updateWakeLock()
                }
                if (now - tripCandidateSince >= 10_000) {
                    trip = Acc(tripCandidateSince, info.soc); tripIdleSince = 0L; lastTripActiveMs = now
                    lastRidingMs = now; ridingAtLossMs = now
                    log("trip started ${info.soc}%")
                }
            } else if (tripCandidateSince != 0L && now - tripCandidateSince > 60_000) {
                tripCandidateSince = 0L
                gpsStopAt = now + 60_000
            }
        }
        trip?.let { t ->
            accumulate(t, info, dtS, now, charging = false)
            val moving = (fix?.speedKmh ?: 0.0) >= MOVING_KMH
            val loaded = i < -TRIP_IDLE_A * k
            if (loaded || (fix?.speedKmh ?: 0.0) >= TripGap.RIDING_KMH) lastRidingMs = now
            ridingAtLossMs = lastRidingMs
            if (moving || loaded) { tripIdleSince = 0L; lastTripActiveMs = now } else {
                if (tripIdleSince == 0L) tripIdleSince = now
                if (now - tripIdleSince >= TRIP_IDLE_END_MS) endTrip(t)
            }
            if (trip != null) activeTrip.value = TripLive(t.startMs, t.distanceKm, t.wh, t.maxSpeed)
        }
    }

    /** Time left of the running charge, for the screen and the report. */
    private fun predict(info: BmsBasicInfo) = ChargePredictor.predict(info.soc, info.fullCapacity, info.current,
        chargeLearn, chargeWatch, info.remainCapacity, cellOvLimitV)

    /** Discharge without trip tracking: starts after 60 s below −0.5 A, ends 2 min after the load stops. */
    private fun processDischarge(info: BmsBasicInfo, i: Double, dtS: Double, now: Long) {
        if (dis == null) {
            if (i < -DIS_START_A) {
                if (disCandidateSince == 0L) disCandidateSince = now
                if (now - disCandidateSince >= 60_000) { dis = Acc(disCandidateSince, info.soc); disQuietSince = 0L }
            } else disCandidateSince = 0L
        }
        dis?.let { d ->
            if (i < -DIS_END_A) { accumulate(d, info, dtS, now, charging = false); disQuietSince = 0L }
            else {
                if (disQuietSince == 0L) disQuietSince = now
                if (now - disQuietSince >= 120_000 || i > CHG_START_A) {
                    finishDischarge(d); dis = null; disCandidateSince = 0L
                }
            }
            if (dis != null) activeDischarge.value = ChargeLive(d.startMs, d.socStart, d.ah, d.wh)
        }
    }

    private fun finishDischarge(d: Acc) {
        activeDischarge.value = null
        if (d.activeS < 120 || d.ah < 0.05) return
        val data = common(d).apply {
            put("avg_current", if (d.activeS > 0) d.ah * 3600 / d.activeS else 0.0)
            put("avg_power", if (d.activeS > 0) d.wh * 3600 / d.activeS else 0.0)
        }
        save(Session.TYPE_DISCHARGE, d, data)
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

    private fun startGps() = gps.start { fix -> main.post { onFix(fix) } }

    private fun stopGps() {
        gps.stop()
        liveSpeedKmh.value = null
    }

    private fun onFix(fix: LocationTracker.Fix) {
        val t = trip ?: return
        liveSpeedKmh.value = fix.speedKmh
        val now = System.currentTimeMillis()
        val silent = lastSampleMs > 0 && now - lastSampleMs > MAX_DT_S * 1000
        // Without battery data only riding counts, so walking away adds nothing to the trip
        val minKmh = if (silent) TripGap.RIDING_KMH else MOVING_KMH
        if (silent && fix.speedKmh >= TripGap.RIDING_KMH) {
            lastRidingMs = now; lastTripActiveMs = now
            activeTrip.value = TripLive(t.startMs, t.distanceKm, t.wh, t.maxSpeed)
            // Keep drawing the route; v = 0 marks "no battery data" (left out of charts and analysis)
            if (now - t.lastPointMs >= POINT_EVERY_MS) {
                t.lastPointMs = now
                t.points += Point(now, 0.0, 0.0, t.socEnd, fix.speedKmh, fix.lat, fix.lon)
            }
        }
        val prev = t.lastFix
        t.lastFix = fix
        if (fix.speedKmh >= minKmh) t.maxSpeed = max(t.maxSpeed, fix.speedKmh)
        if (prev == null) return
        val dtS = (fix.elapsedMs - prev.elapsedMs) / 1000.0
        if (dtS <= 0) return
        // Distance only while really moving, to ignore GPS drift when parked
        if (fix.speedKmh >= minKmh || prev.speedKmh >= minKmh) {
            val res = FloatArray(1)
            android.location.Location.distanceBetween(prev.lat, prev.lon, fix.lat, fix.lon, res)
            if (TripGap.plausible(res[0].toDouble(), dtS, prev.speedKmh, fix.speedKmh)) {
                t.distanceKm += res[0] / 1000.0
                t.movingS += dtS          // same clock as the distance, so the average is real
            }
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
        put("start_min_cell", a.startMinCell)
    }

    private fun finishCharge(c: Acc) {
        activeCharge.value = null
        chargeWatch = null
        if (c.activeS < 180 || c.ah < 0.05) return
        val stop = c.stop ?: c.watch?.lost()
        val data = common(c).apply {
            put("avg_current", if (c.activeS > 0) c.ah * 3600 / c.activeS else 0.0)
            put("balancing_seen", c.balancingSeen)
            put("ended_by_protection", c.endedByProtection || c.maxCellV >= cellOvLimitV - 0.005)
            put("cell_ov_limit", cellOvLimitV)
            put("reached_full", c.socEnd >= 99)
            put("gap_s", c.gapMs / 1000)
            stop?.let { r ->
                put("stop_reason", r.reason.name.lowercase())
                r.alarm?.let { put("stop_alarm", it) }
                put("soc_before_stop", r.socBefore); put("soc_after_stop", r.socShown)
                r.socCounted?.let { put("soc_counted", it) }
                put("stop_v", r.packV); put("stop_max_cell", r.maxCell); put("stop_max_idx", r.maxIdx)
                put("stop_min_cell", r.minCell); put("stop_min_idx", r.minIdx); put("stop_delta_mv", r.deltaMv)
                r.earlyCell?.let { put("stop_early_cell", it) }
            }
            c.etaFirstMin?.let { put("eta_first_min", it) }
            val hv = com.vanaccess.bms.alarm.AlarmCheck.effectiveHighVoltage(prefs.alarmHighVoltage.toDouble(),
                prefs.bmsCellCount, prefs.effectiveOvMv)
            if (hv > 0) put("target_v", hv)
            if (prefs.alarmHighSoc > 0) put("target_soc", prefs.alarmHighSoc)
        }
        save(Session.TYPE_CHARGE, c, data) { s -> if (c.stopMs > 0) rest = Rest(s, c.stopMs) }
    }

    private fun finishTrip(t: Acc) {
        activeTrip.value = null
        if (t.endMs - t.startMs < 60_000 || (t.distanceKm < 0.1 && t.ah < 0.05)) return
        // Ridden on without battery data and not heard from it since: energy is incomplete
        val partial = lastTripActiveMs - lastSampleMs > MAX_DT_S * 1000
        if (partial) t.gapMs += lastTripActiveMs - lastSampleMs
        val data = common(t).apply {
            put("distance_km", t.distanceKm)
            put("max_speed", t.maxSpeed)
            put("moving_s", t.movingS)
            put("avg_speed", if (t.movingS > 0) t.distanceKm / (t.movingS / 3600.0) else 0.0)
            put("gap_s", t.gapMs / 1000)
            put("energy_partial", partial)
            put("wh_per_km", if (t.distanceKm >= 0.1 && !partial) t.wh / t.distanceKm else JSONObject.NULL)
            put("ah_per_km", if (t.distanceKm >= 0.1 && !partial) t.ah / t.distanceKm else JSONObject.NULL)
            put("hard_accel", t.hardAccel)
            put("vehicle", prefs.vehicleType)
            put("gps", t.distanceKm > 0 || t.maxSpeed > 0)
            // Driving style from the current curve (throttle shocks, press-and-release, time at high current)
            val style = DrivingAnalyzer.analyze(t.points.filter { it.v > 0 }, prefs.limits.dischargeOcA)
            put("throttle_shocks", style.shocks)
            put("onoff_cycles", style.onOff)
            put("high_current_pct", style.highPct)
            put("high_current_a", style.highA)
        }
        val remain = lastRemainAh; val pack = lastPackV
        save(Session.TYPE_TRIP, t, data) { s ->
            if (partial && remain > 0) late = Late(s, t, remain, pack)
        }
    }

    /** The battery answered again after a trip that ended in silence: complete its energy. */
    private fun completeLate(l: Late, info: BmsBasicInfo, now: Long) {
        late = null
        if (l.session.battery != battery || now - l.session.endMs > LATE_ENERGY_MS) return
        // Nothing drawn meanwhile (walked off with the phone): the report was complete after all
        if (!TripGap.known(l.remainAh, info.remainCapacity, info.fullCapacity)) return
        val ah = TripGap.missingAh(l.remainAh, info.remainCapacity, info.fullCapacity)
        val d = l.session.data; val t = l.acc
        val totalAh = t.ah + ah; val totalWh = t.wh + ah * (l.packV + info.packVoltage) / 2
        d.put("ah", totalAh).put("wh", totalWh).put("soc_end", info.soc).put("energy_partial", false)
        if (t.distanceKm >= 0.1) d.put("wh_per_km", totalWh / t.distanceKm).put("ah_per_km", totalAh / t.distanceKm)
        Thread {
            try { store.updateData(l.session.id, d) } catch (_: Exception) {}
            main.post { savedCount.value = (savedCount.value ?: 0) + 1 }
        }.start()
    }

    private fun save(type: String, a: Acc, data: JSONObject, then: ((Session) -> Unit)? = null) {
        val pts = a.points.toList()
        val bat = battery
        Thread {
            val id = try { store.insert(type, a.startMs, a.endMs, bat, data, pts) } catch (_: Exception) { -1L }
            main.post {
                savedCount.value = (savedCount.value ?: 0) + 1
                if (id > 0) Session(id, type, a.startMs, a.endMs, bat, data).let { then?.invoke(it); onSaved?.invoke(it) }
            }
        }.start()
    }

    fun release() {
        chg?.let { finishCharge(it) }; chg = null
        dis?.let { finishDischarge(it) }; dis = null
        trip?.let { finishTrip(it) }; trip = null
        stopGps()
        stateFile.delete()
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
    }
}
