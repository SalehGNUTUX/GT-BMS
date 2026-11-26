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
class Monitor(home: File = File(System.getProperty("user.home"))) {
    data class State(val source: String = "", val simulated: Boolean = false, val basic: BmsBasicInfo? = null,
                     val cells: List<Double> = emptyList(), val clock: Long = 0, val charge: ChargeTracker.Live? = null,
                     /** Watching through the phone; [problem] says why the values are not fresh. */
                     val viaPhone: Boolean = false, val problem: String? = null)

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

    private fun reading(basic: BmsBasicInfo?, newCells: List<Double>?, t: Long) {
        val s = _state.value
        val b = basic ?: s.basic; val cells = newCells ?: s.cells
        if (basic != null && b != null) {
            tracker.onReading(t, b, cells)
            alarms.evaluate(t, b, cells, tracker)
        }
        _state.value = s.copy(basic = b, cells = cells, clock = t, charge = tracker.live)
    }

    @Volatile private var phoneWatch: Thread? = null

    /**
     * Watches the battery through the phone (its reading every few seconds over the home network),
     * while the phone keeps the Bluetooth link. Charges are recorded by the phone, not here.
     */
    fun startPhoneLive(host: String, port: Int, code: String) {
        stop()
        tracker = newTracker(record = false)
        clock = System::currentTimeMillis
        _state.value = State(source = Tr["srcPhone"], viaPhone = true, problem = Tr["syncWorking"])
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
                    if (e is com.vanaccess.bms.sync.SyncClient.Refused) {
                        val msg = when { e.status == 401 -> Tr["syncBadCode"]; e.status == 403 -> Tr["syncClosed"]; e.message == "unknown" -> Tr["phoneOld"]; else -> e.message ?: "" }
                        _state.value = _state.value.copy(problem = msg); phoneWatch = null; return@Thread
                    }
                    if (fails >= 5) { _state.value = _state.value.copy(problem = Tr["phoneGone"]); phoneWatch = null; return@Thread }
                    Tr["phoneRetry"]
                }
                if (_state.value.problem != problem) _state.value = _state.value.copy(problem = problem)
                try { Thread.sleep(com.vanaccess.bms.sync.SyncProtocol.LIVE_EVERY_MS) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; name = "gtbms-phone-live" }
        phoneWatch = th
        th.start()
    }

    fun predict() = _state.value.basic?.let { tracker.predict(it) }

    fun stop() { poller?.stop(); poller = null; phoneWatch?.let { phoneWatch = null; it.interrupt() }; tracker.close() }

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
