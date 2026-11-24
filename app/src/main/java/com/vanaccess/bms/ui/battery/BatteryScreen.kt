// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.battery

import com.vanaccess.bms.reports.CellVerdict.Level as CellLevel
import com.vanaccess.bms.reports.CellVerdict.Why as CellWhy
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout
import com.vanaccess.bms.R
import com.vanaccess.bms.ble.BleService
import com.vanaccess.bms.ble.BmsBasicInfo
import com.vanaccess.bms.ble.drivers.BmsDrivers
import com.vanaccess.bms.ble.drivers.DetailKey
import com.vanaccess.bms.ble.drivers.Feature
import com.vanaccess.bms.ble.drivers.HistoryRecord
import com.vanaccess.bms.ble.drivers.ParamItem
import com.vanaccess.bms.data.AppPreferences
import com.vanaccess.bms.databinding.FragmentBatteryBinding
import com.vanaccess.bms.ui.L10n
import com.vanaccess.bms.ui.MainActivity
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Battery screen for every driver-based BMS: Live / Cells / Info / History tabs.
 * Tabs appear according to what the active protocol supports.
 */
class BatteryScreen(
    private val fragment: Fragment,
    private val b: FragmentBatteryBinding,
    private val prefs: AppPreferences
) {
    /** Fixed: background work and timers may outlive the fragment, which then has no context. */
    private val ctx: Context = fragment.requireContext()
    private val dp = ctx.resources.displayMetrics.density

    private val cGreen = Color.parseColor("#43D17A")
    private val cBlue = Color.parseColor("#4DA3FF")
    private val cOrange = Color.parseColor("#F5B83D")
    private val cRed = Color.parseColor("#F0544F")
    private val cGrey = Color.parseColor("#9BA3B8")
    private val cTile = Color.parseColor("#262A3B")

    private enum class Page { LIVE, CELLS, REPORTS, INFO, HISTORY }
    private var pages = listOf<Page>()
    private var lastInfo: BmsBasicInfo? = null
    private var lastCells: List<Double> = emptyList()
    private val historyAdapter = HistoryAdapter()
    private val reports = ReportsView(fragment, b)
    private var learned: Pair<Double, Double>? = null
    private var lastRange: com.vanaccess.bms.reports.RangeEstimator.Result? = null

    fun setup() {
        b.tvDeviceName.text = prefs.selectedDeviceName ?: ctx.getString(R.string.lbl_battery)
        setupBatterySwitcher()
        // Side swipe between Cells / Reports / Info / History (not on Live, which has no tabs)
        b.pageContainer.onSwipe = { next ->
            val to = b.tabs.selectedTabPosition + if (next) 1 else -1
            if (liveMode || to !in 0 until b.tabs.tabCount) false
            else { b.tabs.getTabAt(to)?.select(); true }
        }
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val p = tab.tag as Page
                if (liveMode) return
                prefs.lastBatteryTab = p.name
                MainActivity.batteryTabIcon.value = iconFor(p)
                showPage(p)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        rebuildTabs(BleService.activeFeatures.value ?: emptySet())
        setupChart()
        reports.setup()
        b.rvHistory.layoutManager = LinearLayoutManager(ctx)
        b.rvHistory.adapter = historyAdapter
        b.btnHistory.setOnClickListener {
            val svc = (fragment.activity as? MainActivity)?.getBleService()
            // While loading, the same button stops the download
            if (BleService.historyLoading.value == true) svc?.cancelHistory() else svc?.requestHistory()
        }
        applyDesign()
        observe()

        // Back on any other tab returns to Live before leaving the screen
        fragment.requireActivity().onBackPressedDispatcher.addCallback(fragment.viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!liveMode) {
                        MainActivity.batteryMode.value = MainActivity.MODE_LIVE
                    } else {
                        isEnabled = false
                        fragment.requireActivity().onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            })
    }

    // ── Tabs ─────────────────────────────────────────────────────────────────

    private fun rebuildTabs(features: Set<Feature>) {
        // Live is its own screen (central button); the Battery tabs start at Cells
        val wanted = buildList {
            add(Page.CELLS); add(Page.REPORTS); add(Page.INFO)
            if (Feature.HISTORY in features) add(Page.HISTORY)
        }
        if (wanted == pages) return
        val current = b.tabs.getTabAt(b.tabs.selectedTabPosition)?.tag as? Page ?: savedTab()
        pages = wanted
        b.tabs.removeAllTabs()
        wanted.forEach { p ->
            val title = when (p) {
                Page.LIVE -> R.string.tab_live; Page.CELLS -> R.string.tab_cells
                Page.REPORTS -> R.string.tab_reports
                Page.INFO -> R.string.tab_info; Page.HISTORY -> R.string.tab_history
            }
            b.tabs.addTab(b.tabs.newTab().setText(title).setTag(p), p == current)
        }
        if (current !in wanted) b.tabs.getTabAt(0)?.select()
        if (!liveMode) showPage(b.tabs.getTabAt(b.tabs.selectedTabPosition)?.tag as? Page ?: Page.CELLS)
    }

    // ── Saved batteries: switch from the header ──────────────────────────────

    /** With two or more saved batteries, tapping the name lists them; picking one connects to it. */
    private fun setupBatterySwitcher() {
        val saved = prefs.savedBatteries
        if (saved.size < 2) { b.tvDeviceName.setCompoundDrawables(null, null, null, null); return }
        b.tvDeviceName.text = (prefs.selectedDeviceName ?: ctx.getString(R.string.lbl_battery)) + "  ⇅"
        b.tvDeviceName.setOnClickListener {
            val current = saved.indexOfFirst { it.address == prefs.selectedDeviceAddress }
            com.vanaccess.bms.ui.AppDialog.show(ctx, ctx.getString(R.string.switch_battery),
                items = saved.map { sb ->
                    sb.name + "  •  " + (com.vanaccess.bms.ble.drivers.BmsDrivers.byId(sb.protocol)?.displayName ?: sb.protocol)
                },
                checked = current,
                onItem = { i ->
                    val sb = saved[i]
                    if (sb.address == prefs.selectedDeviceAddress) return@show
                    prefs.selectedDeviceAddress = sb.address
                    prefs.selectedDeviceName = sb.name
                    prefs.batteryType = sb.protocol
                    prefs.batteryTypeAddress = sb.address
                    (fragment.activity as? MainActivity)?.getBleService()?.connectToDevice(sb.address)
                    b.tvDeviceName.text = sb.name + "  ⇅"
                    reports.reload()
                },
                buttons = listOf(com.vanaccess.bms.ui.AppDialog.Btn(ctx.getString(R.string.btn_cancel), com.vanaccess.bms.ui.AppDialog.GREY)))
        }
    }

    // ── Live screen vs. Battery sub-tabs ─────────────────────────────────────

    private var liveMode = true

    private fun savedTab(): Page = runCatching { Page.valueOf(prefs.lastBatteryTab) }.getOrDefault(Page.CELLS)
        .takeIf { it != Page.LIVE } ?: Page.CELLS

    /** The central button: Live on its own, without the sub-tab row. */
    private fun enterLive() {
        liveMode = true
        b.pageContainer.enabledSwipe = false
        b.tabs.visibility = View.GONE
        showPage(Page.LIVE)
        b.pageLive.scrollTo(0, 0)
    }

    /** The Battery button: back to the last sub-tab used (Cells the first time). */
    private fun enterTabs() {
        liveMode = false
        b.pageContainer.enabledSwipe = true
        b.tabs.visibility = View.VISIBLE
        val want = savedTab()
        val tab = (0 until b.tabs.tabCount).map { b.tabs.getTabAt(it) }.firstOrNull { it?.tag == want } ?: b.tabs.getTabAt(0)
        if (tab != null && !tab.isSelected) tab.select() else showPage(tab?.tag as? Page ?: Page.CELLS)
        MainActivity.batteryTabIcon.value = iconFor(tab?.tag as? Page ?: Page.CELLS)
    }

    private fun iconFor(p: Page) = when (p) {
        Page.CELLS -> R.drawable.ic_tab_cells
        Page.REPORTS -> R.drawable.ic_tab_reports
        Page.INFO -> R.drawable.ic_tab_info
        Page.HISTORY -> R.drawable.ic_tab_history
        Page.LIVE -> R.drawable.ic_battery
    }

    /** Riding design: keep the screen on while the Live tab is visible. */
    private fun updateKeepScreenOn(p: Page) {
        val w = fragment.activity?.window ?: return
        val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (design == "ride" && p == Page.LIVE) w.addFlags(flag) else w.clearFlags(flag)
    }

    private fun showPage(p: Page) {
        updateKeepScreenOn(p)
        b.pageLive.visibility = if (p == Page.LIVE) View.VISIBLE else View.GONE
        b.pageCells.visibility = if (p == Page.CELLS) View.VISIBLE else View.GONE
        b.pageInfo.visibility = if (p == Page.INFO) View.VISIBLE else View.GONE
        b.pageHistory.visibility = if (p == Page.HISTORY) View.VISIBLE else View.GONE
        b.pageReports.visibility = if (p == Page.REPORTS) View.VISIBLE else View.GONE
        if (p == Page.REPORTS) reports.reload()
    }

    // ── Observers ────────────────────────────────────────────────────────────

    private fun observe() {
        val owner = fragment.viewLifecycleOwner
        BleService.connectionState.observe(owner) { updateHeader() }
        BleService.cellStats.observe(owner) { renderCellTrend(it) }
        // Central Live button in the bottom bar
        // Central button (Live) vs. Battery button (last sub-tab)
        MainActivity.batteryMode.observe(owner) { m ->
            if (m == MainActivity.MODE_TABS) enterTabs() else enterLive()
        }
        handler.post(ticker)
        owner.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(o: androidx.lifecycle.LifecycleOwner) {
                handler.removeCallbacks(ticker)
                fragment.activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        })
        BleService.activeDriverName.observe(owner) { updateHeader(); renderInfo() }
        BleService.activeFeatures.observe(owner) { rebuildTabs(it) }
        BleService.basicInfo.observe(owner) { info ->
            lastInfo = info
            b.tvWaiting.visibility = if (info == null) View.VISIBLE else View.GONE
            b.liveContent.visibility = if (info == null) View.GONE else View.VISIBLE
            if (info != null) renderLive(info)
        }
        BleService.cellVoltages.observe(owner) { cells ->
            if (cells != null && cells.isNotEmpty()) { lastCells = cells; renderCells(cells); renderLive(lastInfo ?: return@observe) }
        }
        BleService.deviceDetails.observe(owner) { d ->
            val clock = d[DetailKey.CLOCK]
            b.tvBmsClock.visibility = if (clock != null) View.VISIBLE else View.GONE
            b.tvBmsClock.text = clock?.replace(' ', '\n')
            d[DetailKey.MODEL]?.let { if (prefs.selectedDeviceName.isNullOrBlank()) b.tvDeviceName.text = it }
            renderInfo()
        }
        BleService.bmsParams.observe(owner) { renderInfo() }
        BleService.bmsHistory.observe(owner) { renderHistory() }
        BleService.historyLoading.observe(owner) { renderHistory() }
        BleService.historyCacheSaved.observe(owner) { loadHistoryCache() }
        com.vanaccess.bms.reports.SessionRecorder.liveSpeedKmh.observe(owner) { lastInfo?.let { renderVehicleTiles(it) } }
        com.vanaccess.bms.reports.SessionRecorder.activeTrip.observe(owner) { renderBanner() }
        com.vanaccess.bms.reports.SessionRecorder.activeCharge.observe(owner) { renderBanner() }
        com.vanaccess.bms.reports.SessionRecorder.activeDischarge.observe(owner) { renderBanner() }
        com.vanaccess.bms.reports.SessionRecorder.savedCount.observe(owner) { refreshLearned(); refreshAdviceContext(); refreshTripCell() }
        BleService.bmsParams.observe(owner) { refreshAdviceContext() }
    }

    private fun updateHeader() {
        val state = BleService.connectionState.value ?: BleService.ConnectionState.DISCONNECTED
        val (txt, col) = when (state) {
            BleService.ConnectionState.CONNECTED -> R.string.st_connected to cGreen
            BleService.ConnectionState.CONNECTING -> R.string.st_connecting to cOrange
            BleService.ConnectionState.RECONNECTING -> R.string.st_reconnecting to cOrange
            BleService.ConnectionState.SCANNING -> R.string.st_scanning to cBlue
            BleService.ConnectionState.DISCONNECTED -> R.string.st_disconnected to cRed
        }
        val connected = state == BleService.ConnectionState.CONNECTED
        val driver = BleService.activeDriverName.value?.takeIf { connected }
        // While the battery is out of reach, say how old the shown data is
        val age = BleService.lastDataMs.takeIf { !connected && it > 0 }?.let { lastUpdateText(it) }
        b.tvConnLine.text = "● " + ctx.getString(txt) + (driver?.let { "  •  $it" } ?: "") +
            (age?.let { "  •  $it" } ?: "")
        b.tvConnLine.setTextColor(col)
        // Stale data is dimmed until live readings resume
        b.liveContent.alpha = if (connected || BleService.lastDataMs == 0L) 1f else 0.55f
        b.tvBmsClock.alpha = if (connected) 1f else 0.4f
    }

    /** "Last update: 6 min ago" (minutes / hours / days). */
    private fun lastUpdateText(ms: Long): String {
        val min = ((System.currentTimeMillis() - ms) / 60_000L).coerceAtLeast(0)
        val ago = when {
            min < 1 -> ctx.getString(R.string.ago_now)
            min < 60 -> ctx.getString(R.string.ago_min, min.toInt())
            min < 48 * 60 -> ctx.getString(R.string.ago_hours, (min / 60).toInt())
            else -> ctx.getString(R.string.ago_days, (min / 1440).toInt())
        }
        return ctx.getString(R.string.last_update, ago)
    }

    // A handler of its own: the view is already detached when the fragment's view is destroyed, and
    // View.removeCallbacks then no longer reaches the main queue (this stopped the app 30 s after leaving it)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { updateHeader(); handler.postDelayed(this, 30_000L) }
    }

    // ── Live ─────────────────────────────────────────────────────────────────

    private fun fmt(pattern: String, vararg a: Any) = String.format(Locale.US, pattern, *a)

    private fun temp(c: Double): String =
        if (prefs.temperatureUnit == "F") fmt("%.0f°F", c * 9 / 5 + 32) else fmt("%.0f°C", c)

    private fun renderLive(info: BmsBasicInfo) {
        val charging = info.current > 0.1
        val discharging = info.current < -0.1
        b.gauge.level = info.soc
        b.gauge.charging = charging
        b.ring.level = info.soc
        b.ring.charging = charging
        b.segments.level = info.soc
        b.segments.current = info.current
        b.tvSoc.text = "${info.soc}%"
        b.tvSoc.setTextColor(b.gauge.colorFor(info.soc))

        val (stateTxt, stateCol) = when {
            charging -> R.string.st_charging to cGreen
            discharging -> R.string.st_discharging to cBlue
            else -> R.string.st_idle to cGrey
        }
        b.tvState.text = ctx.getString(stateTxt)
        b.tvState.background = pill(stateCol)

        // Time estimate from capacity and current
        val hours = when {
            charging && info.fullCapacity > info.remainCapacity -> (info.fullCapacity - info.remainCapacity) / info.current
            discharging && info.remainCapacity > 0 -> info.remainCapacity / abs(info.current)
            else -> null
        }
        fun dur(h: Double) = ctx.getString(R.string.dur_hm, h.toInt(), ((h - h.toInt()) * 60).toInt())
        val R1 = com.vanaccess.bms.reports.SessionRecorder
        val chargeEta = if (charging) com.vanaccess.bms.reports.ChargePredictor.predict(info.soc, info.fullCapacity, info.current,
            R1.chargeLearn, R1.chargeWatch, info.remainCapacity, if (prefs.knownOvMv > 0) prefs.knownOvMv / 1000.0 else 99.0) else null
        b.tvTimeLeft.text = when {
            // Last minutes before the protection cuts the charge
            chargeEta?.kind == com.vanaccess.bms.reports.ChargePredictor.Kind.CUT_SOON ->
                if (chargeEta.hours < 2 / 60.0) ctx.getString(R.string.eta_cut_now, chargeEta.cell)
                else ctx.getString(R.string.eta_cut_soon, Math.ceil(chargeEta.hours * 60).toInt(), chargeEta.cell)
            // Charging: time to 80 % and to the end
            chargeEta != null -> listOfNotNull(
                chargeEta.to80h?.let { ctx.getString(R.string.eta_80, dur(it)) },
                if (chargeEta.kind == com.vanaccess.bms.reports.ChargePredictor.Kind.CUT)
                    (if (chargeEta.hours < 3 / 60.0) ctx.getString(R.string.eta_end_soon)
                     else ctx.getString(R.string.eta_end, dur(chargeEta.hours)))
                else ctx.getString(R.string.eta_100, dur(chargeEta.hours))).joinToString("  •  ")
            else -> hours?.takeIf { it in 0.0..500.0 }?.let { h ->
                ctx.getString(if (charging) R.string.time_to_full else R.string.time_to_empty, dur(h))
            } ?: ""
        }

        // Metric tiles
        val delta = if (lastCells.size > 1) ((lastCells.max() - lastCells.min()) * 1000).toInt() else null
        val sign = if (charging) "+" else if (discharging) "−" else ""
        metric(b.gridMetrics, "v", R.string.m_voltage, fmt("%.2f V", info.packVoltage))
        metric(b.gridMetrics, "i", R.string.m_current, fmt("%s%.1f A", sign, abs(info.current)),
            if (charging) cGreen else if (discharging) cBlue else null)
        metric(b.gridMetrics, "p", R.string.m_power, fmt("%.0f W", abs(info.powerWatts)))
        metric(b.gridMetrics, "d", R.string.m_delta, delta?.let { "$it mV" } ?: "—",
            delta?.let { deltaColor(it) })
        renderVehicleTiles(info)

        // Capacity
        b.progressCapacity.setIndicatorColor(b.gauge.colorFor(info.soc))
        b.progressCapacity.progress = info.soc
        b.rowsCapacity.removeAllViews()
        if (info.fullCapacity > 0) {
            row(b.rowsCapacity, ctx.getString(R.string.cap_remaining), fmt("%.1f Ah", info.remainCapacity))
            row(b.rowsCapacity, ctx.getString(R.string.cap_full), fmt("%.1f Ah", info.fullCapacity))
        }
        row(b.rowsCapacity, ctx.getString(R.string.cap_cycles), "${info.cycleCount}")
        com.vanaccess.bms.reports.Advisor.cyclesLeft(info.cycleCount, prefs.ratedCycles, limits)?.let { (left, rated) ->
            row(b.rowsCapacity, ctx.getString(R.string.cycles_left), ctx.getString(R.string.cycles_left_val, left, rated))
        }
        info.sohReported?.let { row(b.rowsCapacity, ctx.getString(R.string.cap_soh), "$it%") }

        // Temperatures: cell probes then MOS probes
        b.gridTemps.removeAllViews()
        val mos = info.mosTemperatures
        info.temperatures.forEachIndexed { i, t ->
            addTile(b.gridTemps, ctx.getString(R.string.t_cell, i + 1), temp(t), tempColor(t), small = true)
        }
        mos.forEachIndexed { i, t ->
            addTile(b.gridTemps, ctx.getString(R.string.t_mos, i + 1), temp(t), tempColor(t), small = true)
        }

        // Status pills
        b.gridStatus.removeAllViews()
        statusTile(R.string.s_charge_mos, info.chargeFetOn)
        statusTile(R.string.s_discharge_mos, info.dischargeFetOn)
        val balancing = (0 until 32).filter { (info.balanceBits shr it) and 1L != 0L }
        statusTile(R.string.s_balancing, balancing.isNotEmpty())
        info.heating?.let { statusTile(R.string.s_heating, it) }

        renderRecs(info)

        // Alerts
        b.boxAlerts.removeAllViews()
        val alerts = info.protectionFlags.map { L10n.t(ctx, it.name) }
        if (alerts.isEmpty()) {
            b.boxAlerts.addView(label(ctx.getString(R.string.no_alerts), cGreen, 14f, bold = true))
        } else alerts.forEach { a ->
            b.boxAlerts.addView(label("⚠ $a", cRed, 14f, bold = true).apply { setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt()) })
        }
    }

    private var restSince = 0L
    private var limits = com.vanaccess.bms.reports.BmsLimits()
    private var lastChargeByProtection = false

    /** Live recommendations from the Advisor, using this battery's own BMS limits. */
    private fun renderRecs(info: BmsBasicInfo) {
        val now = System.currentTimeMillis()
        if (abs(info.current) < 1.0) { if (restSince == 0L) restSince = now } else restSince = 0L
        val x = com.vanaccess.bms.reports.Advisor.Live(
            soc = info.soc, current = info.current, cells = lastCells,
            cellTemps = info.temperatures, mosTemps = info.mosTemperatures,
            restSeconds = if (restSince == 0L) 0.0 else (now - restSince) / 1000.0,
            loadCurrentA = prefs.vehicle.loadCurrentA,
            restDeltaMv = prefs.alarmDeltaRestMv, loadDeltaMv = prefs.alarmDeltaLoadMv,
            lastChargeEndedByProtection = lastChargeByProtection,
            chargerAdviceV = com.vanaccess.bms.ui.AdviceText.chargerAdviceV(ctx),
        )
        val recs = com.vanaccess.bms.reports.Advisor.live(x, limits)
        b.boxRecs.removeAllViews()
        recs.forEach { r ->
            b.boxRecs.addView(label(com.vanaccess.bms.ui.AdviceText.icon(r.level) + " " +
                com.vanaccess.bms.ui.AdviceText.text(ctx, r), com.vanaccess.bms.ui.AdviceText.color(r.level), 14f,
                bold = r.level == com.vanaccess.bms.reports.Advisor.Level.CRITICAL).apply {
                setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
            })
        }
    }

    private fun refreshAdviceContext() {
        val ctx0 = ctx
        kotlin.concurrent.thread {
            val lim = com.vanaccess.bms.ui.AdviceText.limits(ctx0)
            val last = com.vanaccess.bms.reports.ReportStore.get(ctx0)
                .list(com.vanaccess.bms.reports.Session.TYPE_CHARGE, 1).firstOrNull()
            fragment.activity?.runOnUiThread {
                limits = lim
                lastChargeByProtection = last?.b("ended_by_protection") == true
            }
        }
    }

    /** Range and speed tiles for vehicle profiles. */
    private fun renderVehicleTiles(info: BmsBasicInfo) {
        val v = prefs.vehicle
        if (!v.mobile) return
        val cells = prefs.bmsCellCount.takeIf { it > 0 } ?: lastCells.size
        val remainAh = if (info.remainCapacity > 0) info.remainCapacity else info.soc / 100.0 * info.fullCapacity
        val r = com.vanaccess.bms.reports.RangeEstimator.estimate(
            remainAh, info.fullCapacity, info.packVoltage, cells, com.vanaccess.bms.reports.BatteryModel.nominalCell(prefs.chemistry),
            prefs.consumptionWhKm.toDouble(), learned, v.defaultWhPerKm, info.temperatures.minOrNull())
        lastRange = r
        metric(b.gridMetrics, "r", R.string.m_range_i, r?.let { fmt("≈ %.0f km", it.km) } ?: "—", cGreen)
        // Tap the range tile for how it is calculated, with the live numbers
        (metricViews["r"]?.parent?.parent as? View)?.setOnClickListener { showRangeInfo() }
        val sp = com.vanaccess.bms.reports.SessionRecorder.liveSpeedKmh.value
        metric(b.gridMetrics, "s", R.string.m_speed, sp?.let { fmt("%.0f km/h", it) } ?: "—")
    }

    private fun showRangeInfo() {
        val r = lastRange
        val msg = if (r == null) ctx.getString(R.string.range_info_none) else {
            val basis = when (r.basis) {
                com.vanaccess.bms.reports.RangeEstimator.Basis.USER -> ctx.getString(R.string.range_basis_user)
                com.vanaccess.bms.reports.RangeEstimator.Basis.LEARNED -> ctx.getString(R.string.range_basis_learned_km, r.learnedKm)
                com.vanaccess.bms.reports.RangeEstimator.Basis.DEFAULT -> ctx.getString(R.string.range_basis_type,
                    ctx.getString(prefs.vehicle.labelRes), com.vanaccess.bms.reports.RangeEstimator.MIN_LEARN_KM)
            }
            ctx.getString(R.string.range_info_body,
                r.remainAh, r.reserveAh, com.vanaccess.bms.reports.RangeEstimator.RESERVE_PCT, r.nominalV,
                r.usableWh, r.whPerKm, basis, r.km)
        }
        val cold = r?.takeIf { it.tempFactor < 0.999 && it.tempC != null }
            ?.let { ctx.getString(R.string.range_cold, it.tempC, (it.tempFactor * 100).toInt()) } ?: ""
        com.vanaccess.bms.ui.AppDialog.info(ctx, ctx.getString(R.string.range_info_title), msg + cold)
    }

    private fun refreshLearned() {
        val ctx0 = ctx
        kotlin.concurrent.thread {
            val l = com.vanaccess.bms.reports.ReportStore.get(ctx0).learnedConsumption(prefs.selectedDeviceAddress)
            fragment.activity?.runOnUiThread { learned = l }
        }
    }

    /** Cell difference against this battery's own alarm value. */
    private fun deltaColor(mv: Int): Int {
        val limit = prefs.alarmDeltaRestMv
        return if (mv >= limit * 1.6) cRed else if (mv >= limit * 0.8) cOrange else cGreen
    }

    private fun renderBanner() {
        val R0 = com.vanaccess.bms.reports.SessionRecorder
        val trip = R0.activeTrip.value
        val chg = R0.activeCharge.value
        val dis = R0.activeDischarge.value
        val time = java.text.SimpleDateFormat("HH:mm", Locale.US)
        val (txt, col) = when {
            trip != null -> ctx.getString(R.string.live_trip,
                "\u2066" + fmt("%.2f km", trip.distanceKm) + "\u2069", trip.wh) to cBlue
            chg != null -> ctx.getString(if (chg.stopped) R.string.live_charge_stopped else R.string.live_charge,
                time.format(java.util.Date(chg.startMs)), chg.ah) to cGreen
            dis != null -> ctx.getString(R.string.live_discharge,
                time.format(java.util.Date(dis.startMs)), dis.ah) to cOrange
            else -> null to cGrey
        }
        b.boxSessionBanner.visibility = if (txt == null) View.GONE else View.VISIBLE
        b.tvSessionBanner.text = txt
        b.btnEndTrip.visibility = if (trip != null) View.VISIBLE else View.GONE
        b.btnEndTrip.setOnClickListener {
            com.vanaccess.bms.ui.AppDialog.show(ctx, ctx.getString(R.string.end_trip_q), message = ctx.getString(R.string.end_trip_msg),
                buttons = listOf(
                    com.vanaccess.bms.ui.AppDialog.Btn(ctx.getString(R.string.btn_cancel), com.vanaccess.bms.ui.AppDialog.GREEN),
                    com.vanaccess.bms.ui.AppDialog.Btn(ctx.getString(R.string.end_trip), com.vanaccess.bms.ui.AppDialog.RED) {
                        (fragment.activity as? MainActivity)?.getBleService()?.endTripNow()
                    },
                ))
        }
        b.boxSessionBanner.background = GradientDrawable().apply {
            cornerRadius = 14 * dp
            setColor(Color.argb(0x33, Color.red(col), Color.green(col), Color.blue(col)))
            setStroke((1 * dp).toInt(), col)
        }
    }

    private fun tempColor(t: Double) = when {
        t < 0 -> cBlue; t <= 40 -> cGreen; t <= 55 -> cOrange; else -> cRed
    }

    private fun statusTile(labelRes: Int, on: Boolean) {
        addTile(b.gridStatus, ctx.getString(labelRes),
            ctx.getString(if (on) R.string.on else R.string.off), if (on) cGreen else cGrey, small = true)
    }

    // ── Cells ────────────────────────────────────────────────────────────────

    private fun renderCells(cells: List<Double>) {
        val maxV = cells.max(); val minV = cells.min()
        val iMax = cells.indexOf(maxV); val iMin = cells.indexOf(minV)
        val avg = cells.average()
        b.gridCellSummary.removeAllViews()
        addTile(b.gridCellSummary, ctx.getString(R.string.cell_max) + " #${iMax + 1}", fmt("%.3f V", maxV), cRed)
        addTile(b.gridCellSummary, ctx.getString(R.string.cell_min) + " #${iMin + 1}", fmt("%.3f V", minV), cBlue)
        addTile(b.gridCellSummary, ctx.getString(R.string.cell_avg), fmt("%.3f V", avg), null)
        val delta = ((maxV - minV) * 1000).toInt()
        addTile(b.gridCellSummary, ctx.getString(R.string.cell_delta), "$delta mV",
            deltaColor(delta))

        b.tvCellsTitle.text = ctx.getString(R.string.cells_count, cells.size)
        val bal = lastInfo?.balanceBits ?: 0L
        b.gridCells.removeAllViews()
        cells.forEachIndexed { i, v ->
            val border = when (i) { iMax -> cRed; iMin -> cBlue; else -> Color.TRANSPARENT }
            val balancing = (bal shr i) and 1L != 0L
            val tile = cellTile("#${i + 1}" + if (balancing) " ⚖" else "", fmt("%d", (v * 1000).toInt()), border)
            b.gridCells.addView(tile, gridParams())
        }
        updateChart(cells, avg)
    }

    /** What the recorded trips say about the lowest cell. */
    private var tripCell: com.vanaccess.bms.reports.CellTracker.TripCell? = null

    private fun refreshTripCell() {
        kotlin.concurrent.thread {
            val t = runCatching { com.vanaccess.bms.reports.CellTracker.fromTrips(com.vanaccess.bms.reports.ReportStore.get(ctx)
                .list(com.vanaccess.bms.reports.Session.TYPE_TRIP, 30, prefs.selectedDeviceAddress)) }.getOrNull()
            fragment.activity?.runOnUiThread { tripCell = t; if (fragment.view != null) BleService.cellStats.value?.let { renderCellTrend(it) } }
        }
    }

    /** Cells over time: under load and at rest apart (CellTracker via BleService), and the recorded trips. */
    private fun renderCellTrend(r: com.vanaccess.bms.reports.CellTracker.Report) {
        val box = b.boxCellTrend
        box.removeAllViews()
        fun line(t: String, color: Int, bold: Boolean = false, top: Int = 3) =
            box.addView(label(t, color, 13f, bold = bold).apply { setPadding(0, (top * dp).toInt(), 0, (3 * dp).toInt()) })
        fun rows(title: Int, l: List<com.vanaccess.bms.reports.CellTracker.Row>, marked: Int?) {
            line(ctx.getString(title), cGrey, true, 6)
            if (l.isEmpty()) line(ctx.getString(R.string.cell_trend_none), cGrey)
            l.forEach { w ->
                line(ctx.getString(R.string.cell_trend_mv, w.cell, w.mv) +
                    (w.lowestPct?.let { "  •  " + ctx.getString(R.string.cell_trend_pct, it) } ?: ""),
                    if (w.cell == marked) cOrange else Color.WHITE, w.cell == marked)
            }
        }
        rows(R.string.cell_trend_under_load, r.load, r.loadCell)
        rows(R.string.cell_trend_at_rest, r.rest, r.restCell)
        val trips = tripCell
        trips?.cell?.let { line(ctx.getString(R.string.cell_trend_trips, it, trips.times, trips.of), Color.WHITE, false, 8) }

        // The verdict: how serious, on what facts, and what to do
        val lim = com.vanaccess.bms.ui.AdviceText.limits(ctx)
        val v = com.vanaccess.bms.reports.CellVerdict.of(r, trips, prefs.alarmDeltaLoadMv, prefs.alarmDeltaRestMv, lim.cellUvMv)
        val (head, todo, color) = when (v.level) {
            CellLevel.UNKNOWN -> return
            CellLevel.NORMAL -> Triple(R.string.verdict_normal, R.string.verdict_normal_do, cGreen)
            CellLevel.ACCEPTABLE -> Triple(R.string.verdict_acceptable, R.string.verdict_acceptable_do, cBlue)
            CellLevel.CHECK -> Triple(R.string.verdict_check, R.string.verdict_check_do, cOrange)
            CellLevel.DANGER -> Triple(R.string.verdict_danger, R.string.verdict_danger_do, cRed)
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp; setColor(Color.argb(0x26, Color.red(color), Color.green(color), Color.blue(color)))
                setStroke((1 * dp).toInt(), color)
            }
        }
        card.addView(label(ctx.getString(head) + (v.cell?.let { " — " + ctx.getString(R.string.verdict_cell, it) } ?: ""), color, 15f, bold = true))
        v.why.forEach { w ->
            val t = when (w) {
                CellWhy.LOAD_SMALL -> ctx.getString(R.string.why_load_small)
                CellWhy.LOAD_LARGE -> ctx.getString(R.string.why_load_large)
                CellWhy.LOAD_HUGE -> ctx.getString(R.string.why_load_huge, prefs.alarmDeltaLoadMv)
                CellWhy.REST_FINE -> ctx.getString(R.string.why_rest_fine)
                CellWhy.REST_LOW -> ctx.getString(R.string.why_rest_low)
                CellWhy.REST_FAR -> ctx.getString(R.string.why_rest_far)
                CellWhy.PEAK_OVER_ALARM -> ctx.getString(R.string.why_peak, trips?.peakMv ?: 0, trips?.peakA ?: 0.0, prefs.alarmDeltaLoadMv)
                CellWhy.MARGIN_OK -> ctx.getString(R.string.why_margin_ok, trips?.minV ?: 0.0, (lim.cellUvMv ?: 0) / 1000.0)
                CellWhy.MARGIN_THIN -> ctx.getString(R.string.why_margin_thin, trips?.minV ?: 0.0, (lim.cellUvMv ?: 0) / 1000.0)
            }
            card.addView(label("• $t", Color.WHITE, 13f).apply { setPadding(0, (3 * dp).toInt(), 0, 0) })
        }
        card.addView(label(ctx.getString(todo), color, 13f, bold = true).apply { setPadding(0, (6 * dp).toInt(), 0, 0) })
        box.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (10 * dp).toInt() })
    }

    private fun cellTile(title: String, mv: String, border: Int): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(cTile)
                setStroke((2 * dp).toInt(), border)
            }
        }
        box.addView(label(title, cGrey, 11f, center = true))
        box.addView(label(mv, Color.WHITE, 16f, bold = true, center = true))
        box.addView(label("mV", cGrey, 10f, center = true))
        return box
    }

    private fun setupChart() {
        b.chartCells.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setTouchEnabled(false)
            setDrawValueAboveBar(true)
            axisRight.isEnabled = false
            axisLeft.textColor = cGrey
            axisLeft.gridColor = Color.parseColor("#22FFFFFF")
            axisLeft.setDrawZeroLine(true)
            axisLeft.zeroLineColor = Color.parseColor("#66FFFFFF")
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.textColor = cGrey
            xAxis.textSize = 9f
            xAxis.setDrawGridLines(false)
            xAxis.granularity = 1f
            setNoDataText(ctx.getString(R.string.chart_no_data))
            setNoDataTextColor(cGrey)
        }
    }

    private fun updateChart(cells: List<Double>, avg: Double) {
        val dev = cells.map { ((it - avg) * 1000).toFloat() }
        val entries = dev.mapIndexed { i, d -> BarEntry((i + 1).toFloat(), d) }
        val set = BarDataSet(entries, "").apply {
            colors = dev.map { if (it >= 0) cOrange else cBlue }
            setDrawValues(false)
        }
        val lim = max(5f, dev.maxOf { abs(it) } * 1.2f)
        b.chartCells.axisLeft.axisMinimum = -lim
        b.chartCells.axisLeft.axisMaximum = lim
        b.chartCells.xAxis.labelCount = minOf(cells.size, 12)
        b.chartCells.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float) = value.toInt().toString()
        }
        b.chartCells.data = BarData(set).apply { barWidth = 0.7f }
        b.chartCells.invalidate()
    }

    // ── Info + params ────────────────────────────────────────────────────────

    private fun renderInfo() {
        val box = b.boxInfo
        box.removeAllViews()
        val d = BleService.deviceDetails.value ?: emptyMap()
        val rows = mutableListOf<Pair<Int, String>>()
        d[DetailKey.MODEL]?.let { rows += R.string.info_model to it }
        d[DetailKey.HW]?.let { rows += R.string.info_hw to it }
        d[DetailKey.SW]?.let { rows += R.string.info_sw to it }
        d[DetailKey.SERIAL]?.let { rows += R.string.info_serial to it }
        d[DetailKey.PACK_NAME]?.takeIf { it.isNotBlank() }?.let { rows += R.string.info_pack_name to it }
        d[DetailKey.PACK_SERIAL]?.let { rows += R.string.info_pack_serial to it }
        d[DetailKey.CLOCK]?.let { rows += R.string.info_clock to it }
        if (d[DetailKey.MODEL] == null) BleService.hardwareInfo.value?.hardwareVersion?.takeIf { it.isNotBlank() }
            ?.let { rows += R.string.info_hw to it }
        if (lastCells.isNotEmpty()) rows += R.string.info_cells to "${lastCells.size}"
        prefs.selectedDeviceAddress?.let { rows += R.string.info_mac to it }
        val proto = prefs.batteryType?.let { BmsDrivers.byId(it)?.displayName } ?: BleService.activeDriverName.value
        proto?.let { rows += R.string.info_protocol to it }

        val dev = card(box, ctx.getString(R.string.info_device))
        rows.forEach { (k, v) -> row(dev, ctx.getString(k), v, selectable = true) }

        val features = BleService.activeFeatures.value ?: emptySet()
        if (Feature.PARAMS in features) {
            val params: List<ParamItem>? = BleService.bmsParams.value
            box.addView(label(ctx.getString(R.string.params_title), Color.WHITE, 15f, bold = true).apply {
                setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
            })
            if (params == null) {
                box.addView(label(ctx.getString(R.string.params_loading), cGrey, 13f))
            } else params.groupBy { it.group }.forEach { (g, items) ->
                val c = card(box, L10n.t(ctx, g))
                items.forEach { row(c, L10n.t(ctx, it.label), L10n.t(ctx, it.value)) }
            }
        }
    }

    // ── History ──────────────────────────────────────────────────────────────

    /** Last downloaded history kept on the phone (shown when nothing was downloaded this session). */
    private var cachedHistory: com.vanaccess.bms.reports.HistoryCache.Cached? = null

    private fun loadHistoryCache() {
        val ctx0 = ctx
        val addr = prefs.selectedDeviceAddress
        kotlin.concurrent.thread {
            val c = com.vanaccess.bms.reports.HistoryCache.load(ctx0, addr)
            fragment.activity?.runOnUiThread { cachedHistory = c; if (fragment.view != null) renderHistory() }
        }
    }

    private fun renderHistory() {
        val live = BleService.bmsHistory.value?.takeIf { it.isNotEmpty() || BleService.historyLoading.value == true }
        val loading = BleService.historyLoading.value == true
        val cached = cachedHistory
        val list = live ?: cached?.records
        b.progressHistory.visibility = if (loading) View.VISIBLE else View.GONE
        b.btnHistory.isEnabled = true
        b.btnHistory.text = ctx.getString(when {
            loading -> R.string.history_stop
            cached != null || live != null -> R.string.history_reload
            else -> R.string.history_load
        })
        b.btnHistory.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (loading) com.vanaccess.bms.ui.AppDialog.RED else Color.parseColor("#3A3F55"))
        b.btnHistory.setTextColor(Color.WHITE)
        val savedAt = cached?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date(it.savedMs))
        }
        b.tvHistoryStatus.text = when {
            loading -> ctx.getString(R.string.history_loading, live?.size ?: 0)
            list == null -> ctx.getString(R.string.history_hint)
            list.isEmpty() -> ctx.getString(R.string.history_empty)
            live == null && savedAt != null -> ctx.getString(R.string.history_cached, list.size, "\u2066$savedAt\u2069")
            else -> ctx.getString(R.string.history_done, list.size)
        }
        // Newest first by the record's own timestamp (the BMS order is not guaranteed);
        // "yyyy-MM-dd HH:mm:ss" sorts correctly as text, and 2000-01-01 records sink to the bottom.
        historyAdapter.submit(list?.sortedByDescending { it.time } ?: emptyList())
    }

    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.VH>() {
        private var items = listOf<HistoryRecord>()
        inner class VH(val box: LinearLayout) : RecyclerView.ViewHolder(box)
        fun submit(l: List<HistoryRecord>) { items = l; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val box = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
                background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(Color.parseColor("#1E2130")) }
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = (8 * dp).toInt() }
            }
            return VH(box)
        }
        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = items[pos]
            h.box.removeAllViews()
            val clockUnset = r.time.startsWith("2000-")
            h.box.addView(label(r.time + if (clockUnset) "  " + ctx.getString(R.string.history_clock_unset) else "", cGrey, 12f))
            // Charging/Discharging are the state at the moment of the event, not events themselves
            val states = r.events.filter { it == "Charging" || it == "Discharging" }
            val events = r.events - states.toSet()
            if (events.isEmpty()) {
                // The BMS logs an empty record when every protection flag has cleared again
                h.box.addView(label(ctx.getString(R.string.history_cleared), cGreen, 14f, bold = true))
            }
            events.forEach { e ->
                val col = if (e == "Cell overvoltage protection") cOrange else cRed
                h.box.addView(label("⚠ " + L10n.t(ctx, e), col, 14f, bold = true))
            }
            if (states.isNotEmpty()) h.box.addView(label(
                ctx.getString(R.string.history_state, states.joinToString(" • ") { L10n.t(ctx, it) }), cBlue, 12f))
            // Isolate the LTR "82.45 V • 100%" so RTL layout does not reorder it
            if (r.extra.isNotBlank()) h.box.addView(label("\u2066" + r.extra + "\u2069", cGrey, 12f))
        }
    }

    // ── View helpers ─────────────────────────────────────────────────────────

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = 50 * dp
        setColor(Color.argb(0x40, Color.red(color), Color.green(color), Color.blue(color)))
        setStroke((1 * dp).toInt(), color)
    }

    private fun label(t: String, color: Int, sizeSp: Float, bold: Boolean = false, center: Boolean = false) =
        TextView(ctx).apply {
            text = t; setTextColor(color); textSize = sizeSp
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (center) gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LOCALE
        }

    private fun gridParams() = GridLayout.LayoutParams(
        GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)
    ).apply {
        width = 0
        val m = (4 * dp).toInt(); setMargins(m, m, m, m)
    }

    private val metricViews = mutableMapOf<String, TextView>()

    // ── Live designs ─────────────────────────────────────────────────────────

    private val design: String get() = prefs.liveDesign
    private fun metricSize() = when (design) { "compact" -> 17f; "ride" -> 34f; else -> 22f }

    /**
     * Switch the Live tab look. classic = battery drawing (default); ring = circular gauge;
     * segments = battery of coloured bars whose top bar pulses with the current;
     * compact = dense, three columns, no drawing; ride = very large numbers, detail cards hidden.
     */
    private fun applyDesign() {
        val d = design
        b.gauge.visibility = if (d == "classic") View.VISIBLE else View.GONE
        b.ring.visibility = if (d == "ring") View.VISIBLE else View.GONE
        b.segments.visibility = if (d == "segments") View.VISIBLE else View.GONE
        b.tvSoc.visibility = if (d == "ring") View.GONE else View.VISIBLE
        b.tvSocLabel.visibility = if (d == "ring") View.GONE else View.VISIBLE
        b.tvSoc.textSize = when (d) { "compact" -> 38f; "ride" -> 84f; else -> 52f }
        val landscape = ctx.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        b.gridMetrics.columnCount = when {
            d == "compact" -> 3
            d == "ride" && landscape -> 4      // handlebar mount: one row of big numbers
            landscape -> 3
            else -> 2
        }
        if (d == "ride" && landscape) b.tvSoc.textSize = 64f
        val details = d != "ride"
        listOf(b.boxCapacity.parent, b.gridTemps.parent?.parent, b.gridStatus.parent?.parent).forEach {
            (it as? View)?.visibility = if (details) View.VISIBLE else View.GONE
        }
        metricViews.values.forEach { it.textSize = metricSize() }
    }

    /** Large metric tile kept across updates (no re-inflation every second). */
    private fun metric(grid: GridLayout, key: String, labelRes: Int, value: String, color: Int? = null) {
        val tv = metricViews[key] ?: run {
            val card = MaterialCardView(ctx).apply {
                setCardBackgroundColor(Color.parseColor("#1E2130"))
                radius = 18 * dp
                cardElevation = 0f
                strokeWidth = 0
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            }
            col.addView(label(ctx.getString(labelRes), cGrey, 12f))
            val v = label(value, Color.WHITE, metricSize(), bold = true)
            col.addView(v)
            card.addView(col)
            grid.addView(card, gridParams())
            metricViews[key] = v
            v
        }
        tv.text = value
        tv.setTextColor(color ?: Color.WHITE)
    }

    private fun addTile(grid: GridLayout, title: String, value: String, color: Int?, small: Boolean = false) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(if (small) cTile else Color.parseColor("#1E2130")) }
        }
        box.addView(label(title, cGrey, 12f))
        box.addView(label(value, color ?: Color.WHITE, if (small) 17f else 20f, bold = true))
        grid.addView(box, gridParams())
    }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val card = MaterialCardView(ctx).apply {
            setCardBackgroundColor(Color.parseColor("#1E2130"))
            radius = 18 * dp
            cardElevation = 0f
            strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = (10 * dp).toInt() }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
        }
        col.addView(label(title, Color.WHITE, 15f, bold = true).apply { setPadding(0, 0, 0, (6 * dp).toInt()) })
        card.addView(col)
        parent.addView(card)
        return col
    }

    /** Label on the start side, value on the end side (works in RTL and LTR). */
    private fun row(parent: LinearLayout, k: String, v: String, selectable: Boolean = false) {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        }
        r.addView(label(k, cGrey, 13f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(label(v, Color.WHITE, 14f, bold = true).apply {
            // numbers/units/serials read left-to-right; Arabic phrases keep their own direction
            textDirection = if (v.any { it in '\u0600'..'\u06FF' }) View.TEXT_DIRECTION_LOCALE else View.TEXT_DIRECTION_LTR
            gravity = Gravity.END
            setTextIsSelectable(selectable)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
        parent.addView(r)
    }
}
