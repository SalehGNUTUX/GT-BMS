// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import com.vanaccess.bms.ble.BmsBasicInfo
import com.vanaccess.bms.ble.BmsPoller
import com.vanaccess.bms.ble.ReplayLink
import com.vanaccess.bms.ble.ReplayLog
import com.vanaccess.bms.ble.drivers.BwbmDriver
import com.vanaccess.bms.reports.ChargeLearn
import com.vanaccess.bms.reports.ChargeSim
import com.vanaccess.bms.reports.ChargeTracker
import com.vanaccess.bms.reports.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.zip.ZipInputStream

/** The battery as the screens see it, the charge being followed, the alarms and the saved reports. */
class Monitor(private val home: File = File(System.getProperty("user.home"))) {
    data class State(val source: String = "", val simulated: Boolean = false, val basic: BmsBasicInfo? = null,
                     val cells: List<Double> = emptyList(), val clock: Long = 0, val charge: ChargeTracker.Live? = null,
                     /** Watching through the phone; [problem] says why the values are not fresh. */
                     val viaPhone: Boolean = false, val problem: String? = null,
                     /** Reading the battery over this computer's Bluetooth. */
                     val viaBle: Boolean = false,
                     /** Only the last reading kept on this computer (no source running). */
                     val kept: Boolean = false)

    val settings = Settings(File(home, ".config/gt-bms/settings.json"))
    val store = Store(File(home, ".local/share/gt-bms/gt_bms_reports.db"))
    /** Charges of the simulator: apart, so they never mix with real ones nor teach anything. */
    private val simStore by lazy { Store(File(home, ".local/share/gt-bms/simulation.db")) }
    /** The reports to show: the simulator's while it runs. */
    fun reports(): Store = if (_state.value.simulated) simStore else store
    val log = DesktopLog(File(home, ".local/share/gt-bms/notifications.json"))
    val alarms = Alarms(settings, log)
    val phone by lazy { PhoneLink(this) }
    /** A short message for the owner, shown over the screens until closed. */
    val notice = MutableStateFlow<String?>(null)

    /** New battery values came from the phone: the analysis uses them from now on. */
    fun onPhoneValues() { tracker.cellOvV = settings.limits.cellOvMv?.let { it / 1000.0 } ?: tracker.cellOvV; tracker.targetSoc = settings.highSoc }
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    /** Bumped when reports change. */
    val reportsVersion = MutableStateFlow(0)
    private var poller: BmsPoller? = null
    private var clock: () -> Long = System::currentTimeMillis
    private var tracker = newTracker()

    /** [record] false: the phone records this charge itself (watching through it), so nothing is saved here. */
    private fun newTracker(simulated: Boolean = false, record: Boolean = true) = ChargeTracker(settings.limits.cellOvMv?.let { it / 1000.0 } ?: ChargeTracker.UNKNOWN_OV) { s, e, d, pts ->
        if (!record) return@ChargeTracker
        runCatching { (if (simulated) simStore else store).insert(Session.TYPE_CHARGE, s, e, settings.battery, d.put("source", if (simulated) "simulation" else "desktop"), pts) }
        reportsVersion.value++
    }.also { t ->
        t.targetSoc = settings.highSoc
        t.learn = runCatching { ChargeLearn.from(store.list().filter { it.type == Session.TYPE_CHARGE }, 0) { store.points(it.id) } }.getOrDefault(ChargeLearn())
    }

    /** Plays back a GT-BMS device report (the bundled one when [text] is null): a battery at rest. */
    fun startReplay(text: String? = null) {
        settings.source = "sim"
        val log = text ?: Monitor::class.java.getResource("/sample-bwbm-23s.txt")!!.readText()
        begin(Tr["sim"], false, ReplayLink(ReplayLog.parse(log)), System::currentTimeMillis)
    }

    /** A pretend charge of the bundled battery, [stepS] seconds per reading, for the charge screen and alarms. */
    fun startChargeSim(stepS: Int = 10, fromSoc: Int = 69) {
        val lim = settings.limits
        val ov = (lim.cellOvMv ?: SAMPLE_OV_MV) / 1000.0
        val sim = ChargeSim(SAMPLE_CELLS, SAMPLE_FULL_AH, fromSoc, SAMPLE_AMPS, ov)
        val link = SimChargeLink(sim, SAMPLE_FULL_AH, stepS)
        if (lim.cellOvMv == null) settings.limits = lim.copy(cellOvMv = SAMPLE_OV_MV, cells = SAMPLE_CELLS.size, designAh = SAMPLE_FULL_AH)
        begin(Tr["simCharge"], true, link, link::now)
    }

    private fun begin(source: String, simulated: Boolean, link: com.vanaccess.bms.ble.BmsLink, clk: () -> Long) {
        stop()
        tracker = newTracker(simulated)
        clock = clk
        _state.value = State(source = source, simulated = simulated)
        reportsVersion.value++
        poller = BmsPoller(BwbmDriver(), link) { out -> reading(out.basic, out.cells, clock()) }.also { it.start() }
    }

    @Volatile private var lastReadingMs = 0L
    private var lastSavedMs = 0L
    private val lastFile = File(home, ".local/share/gt-bms/last-reading.json")

    private fun reading(basic: BmsBasicInfo?, newCells: List<Double>?, t: Long) {
        val s = _state.value
        val b = basic ?: s.basic; val cells = newCells ?: s.cells
        if (basic != null && b != null) {
            lastReadingMs = System.currentTimeMillis()
            tracker.onReading(t, b, cells)
            alarms.evaluate(t, b, cells, tracker)
        }
        _state.value = s.copy(basic = b, cells = cells, clock = t, charge = tracker.live, kept = false)
        // The last real reading stays on this computer: shown again after a lost link or a restart
        if (b != null && !s.simulated && s.source != Tr["sim"] && System.currentTimeMillis() - lastSavedMs > 15_000) saveLast()
    }

    private fun saveLast() = runCatching {
        val s = _state.value; val b = s.basic ?: return@runCatching
        lastSavedMs = System.currentTimeMillis()
        lastFile.parentFile?.mkdirs()
        lastFile.writeText(com.vanaccess.bms.sync.SyncCodec.liveJson(com.vanaccess.bms.sync.SyncCodec.Live(s.clock, settings.batteryName, settings.battery, true, b, s.cells))
            .put("source", s.source).toString())
    }

    /** The last reading kept here, with its time, while nothing is being read. */
    fun showLast() {
        stop()
        val o = runCatching { org.json.JSONObject(lastFile.readText()) }.getOrNull()
        val l = o?.let { runCatching { com.vanaccess.bms.sync.SyncCodec.parseLive(it) }.getOrNull() }
        _state.value = if (l?.basic != null) State(source = Tr["srcKept"], basic = l.basic, cells = l.cells, clock = l.tMs, kept = true)
            else State(source = "", problem = Tr["srcNone"], kept = true)
    }

    @Volatile private var phoneWatch: Thread? = null

    /**
     * Watches the battery through the phone (its reading every few seconds over the home network),
     * while the phone keeps the Bluetooth link. Charges are recorded by the phone, not here.
     */
    fun startPhoneLive(host: String, port: Int, code: String) {
        stop()
        settings.source = "phone"
        tracker = newTracker(record = false)
        clock = System::currentTimeMillis
        val prev = _state.value
        _state.value = State(source = Tr["srcPhone"], viaPhone = true, problem = Tr["syncWorking"], basic = prev.basic.takeIf { !prev.simulated }, cells = prev.cells, clock = prev.clock)
        val client = com.vanaccess.bms.sync.SyncClient(host, port, code, PhoneLink.computerName())
        val th = Thread {
            var lastT = 0L; var lastNotifs = 0L; var fails = 0
            while (phoneWatch === Thread.currentThread()) {
                val problem = runCatching {
                    val now = System.currentTimeMillis()
                    if (now - lastNotifs > 30_000) { lastNotifs = now; log.addFromPhone(client.notifs(log.lastFromPhone())) }
                    val l = client.live()
                    fails = 0
                    when {
                        l == null || l.basic == null -> Tr["phoneNoReading"]
                        else -> {
                            if (l.tMs != lastT) { lastT = l.tMs; reading(l.basic, l.cells.ifEmpty { null }, l.tMs) }
                            if (!l.connected || now - l.tMs > 60_000) Tr["phoneStale"] else null
                        }
                    }
                }.getOrElse { e ->
                    fails++
                    // A new code is needed only when the phone refuses ours; anything else is retried
                    if (e is com.vanaccess.bms.sync.SyncClient.Refused && e.status in listOf(401, 403, 404)) {
                        val msg = when { e.status == 401 -> Tr["syncBadCode"]; e.status == 403 -> Tr["syncClosed"]; e.message == "unknown" -> Tr["phoneOld"]; else -> e.message ?: "" }
                        _state.value = _state.value.copy(problem = msg); phoneWatch = null; saveLast(); return@Thread
                    }
                    val since = _state.value.clock.takeIf { it > 0 }?.let { java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(it)) } ?: "—"
                    String.format(java.util.Locale.US, Tr["phoneRetry"], since)
                }
                if (_state.value.problem != problem) _state.value = _state.value.copy(problem = problem)
                // slower while the phone does not answer (up to 30 s)
                val wait = if (fails == 0) com.vanaccess.bms.sync.SyncProtocol.LIVE_EVERY_MS else minOf(30_000L, 3_000L * fails)
                try { Thread.sleep(wait) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; name = "gtbms-phone-live" }
        phoneWatch = th
        th.start()
    }

    // ── The battery over this computer's Bluetooth (BlueZ) ────────────────────

    @Volatile private var bleThread: Thread? = null
    private var bluez: BlueZ? = null
    /** The phone was asked to leave the battery: given back when we stop. */
    private var yieldedBy: Triple<String, Int, String>? = null

    private class Problem(val key: String) : Exception(key)

    /**
     * Reads the battery directly: finds it (asking the phone to step aside when it holds the
     * link), picks the protocol that answers (remembered for next time), and reconnects by itself.
     * Charges are recorded here. Only the driver's read commands are sent.
     */
    fun startBattery(address: String? = null) {
        stop()
        settings.source = "ble"
        tracker = newTracker()
        clock = System::currentTimeMillis
        val prev = _state.value
        _state.value = State(source = Tr["srcBle"], viaBle = true, problem = Tr["bleSearching"], basic = prev.basic, cells = prev.cells, clock = prev.clock)
        val th = Thread {
            val me = Thread.currentThread()
            while (bleThread === me) {
                val e = runCatching { bleSession(address ?: settings.battery.ifEmpty { null }, me) }.exceptionOrNull() ?: continue
                if (bleThread !== me) break
                val msg = when (e) {
                    is Problem -> Tr[e.key]
                    is BlueZ.NoAdapter -> Tr["bleNoAdapter"]
                    else -> String.format(java.util.Locale.US, Tr["bleError"], e.message ?: e.javaClass.simpleName)
                }
                _state.value = _state.value.copy(problem = msg)
                try { Thread.sleep(if (e is Problem && e.key == "bleNoBattery") 60_000 else 8_000) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "gtbms-bluez" }
        bleThread = th
        th.start()
    }

    private fun bleSession(address: String?, me: Thread) {
        if (address == null) throw Problem("bleNoBattery")
        val bz = bluez ?: BlueZ.open().also { bluez = it }
        fun find() = bz.devices().firstOrNull { it.address.equals(address, true) && (it.connected || it.rssi != null) }
        var dev = find()
        if (dev == null) {
            // Most batteries take one link: if the phone holds it, ask it to step aside for an hour
            phone.last?.let { (h, p, c) ->
                if (yieldedBy == null && runCatching { com.vanaccess.bms.sync.SyncClient(h, p, c, PhoneLink.computerName()).yieldLink(60) }.isSuccess) yieldedBy = Triple(h, p, c)
            }
            _state.value = _state.value.copy(problem = Tr["bleSearching"])
            val until = System.currentTimeMillis() + 30_000
            while (dev == null && System.currentTimeMillis() < until && bleThread === me) { bz.scan(5); dev = find() }
            if (dev == null) throw Problem(if (yieldedBy == null && phone.last == null) "bleNotSeenPhone" else "bleNotSeen")
        }
        if (bleThread !== me) return
        _state.value = _state.value.copy(problem = Tr["bleConnecting"])
        val drivers = com.vanaccess.bms.ble.drivers.BmsDrivers.candidates(com.vanaccess.bms.ble.drivers.BmsDrivers.AUTO,
            settings.driverFor(address), dev.name, dev.services).ifEmpty { com.vanaccess.bms.ble.drivers.BmsDrivers.all }
        for (drv in drivers) for (profile in drv.profiles) {
            if (bleThread !== me) return
            val link = runCatching { bz.link(address, profile) }.getOrNull() ?: continue
            val first = java.util.concurrent.CountDownLatch(1)
            val p = BmsPoller(drv, link) { out -> if (out.basic != null) first.countDown(); reading(out.basic, out.cells, clock()) }
            p.start()
            if (!first.await(12, java.util.concurrent.TimeUnit.SECONDS)) { p.stop(); continue }
            settings.setDriverFor(address, drv.id)
            if (dev.name.isNotBlank() && settings.batteryName.isEmpty()) settings.batteryName = dev.name
            poller = p
            _state.value = _state.value.copy(problem = null, source = Tr["srcBle"] + " • " + drv.displayName)
            // Connected: watch the link; silence or a drop starts over
            while (bleThread === me && !link.lost && System.currentTimeMillis() - lastReadingMs < 30_000) Thread.sleep(1000)
            if (poller === p) poller = null
            p.stop()
            if (bleThread !== me) return
            throw Problem("bleLost")
        }
        throw Problem("bleNoAnswer")
    }

    /** A scan for batteries nearby ([seconds]), nearest first; [onUpdate] as they appear. */
    fun scanBatteries(seconds: Int = 12, onUpdate: (List<BlueZ.Found>) -> Unit): Result<List<BlueZ.Found>> = runCatching {
        val bz = bluez ?: BlueZ.open().also { bluez = it }
        bz.scan(seconds, onUpdate)
    }

    fun predict() = _state.value.basic?.let { tracker.predict(it) }

    fun stop() {
        bleThread?.let { bleThread = null; it.interrupt() }
        poller?.stop(); poller = null
        phoneWatch?.let { phoneWatch = null; it.interrupt() }
        if (_state.value.basic != null && !_state.value.simulated && !_state.value.kept && _state.value.source != Tr["sim"]) saveLast()
        tracker.close()
        // the phone gets its battery back as soon as this computer stops reading it
        yieldedBy?.let { (h, p, c) -> yieldedBy = null; Thread { runCatching { com.vanaccess.bms.sync.SyncClient(h, p, c, PhoneLink.computerName()).resumeLink() } }.start() }
    }

    /** A phone backup ZIP: its reports join those of this computer, its battery settings are taken. */
    fun importPhoneBackup(zip: File): Int {
        var db: ByteArray? = null; var prefs: String? = null; var ok = false
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                when (e.name) {
                    "manifest.json" -> ok = org.json.JSONObject(z.readBytes().decodeToString()).optString("app") == "GT-BMS"
                    "gt_bms_reports.db" -> db = z.readBytes()
                    "gt_bms_prefs.xml" -> prefs = z.readBytes().decodeToString()
                }
            }
        }
        require(ok) { "not a GT-BMS backup" }
        prefs?.let { settings.takeFromPhone(it) }
        val added = db?.let { bytes ->
            val tmp = File.createTempFile("gtbms-phone", ".db").apply { deleteOnExit() }
            tmp.writeBytes(bytes)
            store.merge(tmp).also { tmp.delete() }
        } ?: 0
        reportsVersion.value++
        return added
    }

    companion object {
        // The bundled recording's battery (the owner's 23S LiFePO4): its own values, for the simulator only
        private val SAMPLE_CELLS = listOf(3.321, 3.323, 3.322, 3.321, 3.319, 3.320, 3.320, 3.321, 3.316, 3.319, 3.322, 3.320,
            3.317, 3.326, 3.328, 3.328, 3.327, 3.327, 3.329, 3.331, 3.330, 3.327, 3.328)
        private const val SAMPLE_FULL_AH = 29.9
        private const val SAMPLE_AMPS = 5.1
        private const val SAMPLE_OV_MV = 3630
    }
}
