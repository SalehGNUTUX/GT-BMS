// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.battery

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
    private val ctx: Context get() = fragment.requireContext()
    private val dp = fragment.resources.displayMetrics.density

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
    private var learnedWhKm: Double? = null

    fun setup() {
        b.tvDeviceName.text = prefs.selectedDeviceName ?: fragment.getString(R.string.lbl_battery)
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPage(tab.tag as Page)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        rebuildTabs(BleService.activeFeatures.value ?: emptySet())
        setupChart()
        reports.setup()
        b.rvHistory.layoutManager = LinearLayoutManager(ctx)
        b.rvHistory.adapter = historyAdapter
        b.btnHistory.setOnClickListener {
            (fragment.activity as? MainActivity)?.getBleService()?.requestHistory()
        }
        observe()

        // Back on any other tab returns to Live before leaving the screen
        fragment.requireActivity().onBackPressedDispatcher.addCallback(fragment.viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val current = b.tabs.getTabAt(b.tabs.selectedTabPosition)?.tag as? Page
                    if (current != null && current != Page.LIVE) {
                        b.tabs.getTabAt(0)?.select()
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
        val wanted = buildList {
            add(Page.LIVE); add(Page.CELLS); add(Page.REPORTS); add(Page.INFO)
            if (Feature.HISTORY in features) add(Page.HISTORY)
        }
        if (wanted == pages) return
        val current = b.tabs.getTabAt(b.tabs.selectedTabPosition)?.tag as? Page ?: Page.LIVE
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
        showPage(b.tabs.getTabAt(b.tabs.selectedTabPosition)?.tag as? Page ?: Page.LIVE)
    }

    private fun showPage(p: Page) {
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
        com.vanaccess.bms.reports.SessionRecorder.liveSpeedKmh.observe(owner) { lastInfo?.let { renderVehicleTiles(it) } }
        com.vanaccess.bms.reports.SessionRecorder.activeTrip.observe(owner) { renderBanner() }
        com.vanaccess.bms.reports.SessionRecorder.activeCharge.observe(owner) { renderBanner() }
        com.vanaccess.bms.reports.SessionRecorder.savedCount.observe(owner) { refreshLearned(); refreshAdviceContext() }
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
        val driver = BleService.activeDriverName.value?.takeIf { state == BleService.ConnectionState.CONNECTED }
        b.tvConnLine.text = "● " + fragment.getString(txt) + (driver?.let { "  •  $it" } ?: "")
        b.tvConnLine.setTextColor(col)
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
        b.tvSoc.text = "${info.soc}%"
        b.tvSoc.setTextColor(b.gauge.colorFor(info.soc))

        val (stateTxt, stateCol) = when {
            charging -> R.string.st_charging to cGreen
            discharging -> R.string.st_discharging to cBlue
            else -> R.string.st_idle to cGrey
        }
        b.tvState.text = fragment.getString(stateTxt)
        b.tvState.background = pill(stateCol)

        // Time estimate from capacity and current
        val hours = when {
            charging && info.fullCapacity > info.remainCapacity -> (info.fullCapacity - info.remainCapacity) / info.current
            discharging && info.remainCapacity > 0 -> info.remainCapacity / abs(info.current)
            else -> null
        }
        b.tvTimeLeft.text = hours?.takeIf { it in 0.0..500.0 }?.let { h ->
            val dur = fragment.getString(R.string.dur_hm, h.toInt(), ((h - h.toInt()) * 60).toInt())
            fragment.getString(if (charging) R.string.time_to_full else R.string.time_to_empty, dur)
        } ?: ""

        // Metric tiles
        val delta = if (lastCells.size > 1) ((lastCells.max() - lastCells.min()) * 1000).toInt() else null
        val sign = if (charging) "+" else if (discharging) "−" else ""
        metric(b.gridMetrics, "v", R.string.m_voltage, fmt("%.2f V", info.packVoltage))
        metric(b.gridMetrics, "i", R.string.m_current, fmt("%s%.1f A", sign, abs(info.current)),
            if (charging) cGreen else if (discharging) cBlue else null)
        metric(b.gridMetrics, "p", R.string.m_power, fmt("%.0f W", abs(info.powerWatts)))
        metric(b.gridMetrics, "d", R.string.m_delta, delta?.let { "$it mV" } ?: "—",
            delta?.let { if (it >= 50) cRed else if (it >= 25) cOrange else cGreen })
        renderVehicleTiles(info)

        // Capacity
        b.progressCapacity.setIndicatorColor(b.gauge.colorFor(info.soc))
        b.progressCapacity.progress = info.soc
        b.rowsCapacity.removeAllViews()
        if (info.fullCapacity > 0) {
            row(b.rowsCapacity, fragment.getString(R.string.cap_remaining), fmt("%.1f Ah", info.remainCapacity))
            row(b.rowsCapacity, fragment.getString(R.string.cap_full), fmt("%.1f Ah", info.fullCapacity))
        }
        row(b.rowsCapacity, fragment.getString(R.string.cap_cycles), "${info.cycleCount}")
        com.vanaccess.bms.reports.Advisor.cyclesLeft(info.cycleCount, prefs.ratedCycles, limits)?.let { (left, rated) ->
            row(b.rowsCapacity, fragment.getString(R.string.cycles_left), fragment.getString(R.string.cycles_left_val, left, rated))
        }
        info.sohReported?.let { row(b.rowsCapacity, fragment.getString(R.string.cap_soh), "$it%") }

        // Temperatures: cell probes then MOS probes
        b.gridTemps.removeAllViews()
        val mos = info.mosTemperatures
        info.temperatures.forEachIndexed { i, t ->
            addTile(b.gridTemps, fragment.getString(R.string.t_cell, i + 1), temp(t), tempColor(t), small = true)
        }
        mos.forEachIndexed { i, t ->
            addTile(b.gridTemps, fragment.getString(R.string.t_mos, i + 1), temp(t), tempColor(t), small = true)
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
            b.boxAlerts.addView(label(fragment.getString(R.string.no_alerts), cGreen, 14f, bold = true))
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
        val whKm = learnedWhKm ?: prefs.consumptionWhKm.toDouble().takeIf { it > 0 } ?: v.defaultWhPerKm
        val remWh = if (info.remainCapacity > 0) info.remainCapacity * info.packVoltage
            else info.soc / 100.0 * info.fullCapacity * info.packVoltage
        val basis = fragment.getString(if (learnedWhKm != null) R.string.range_basis_learned else R.string.range_basis_default)
        metric(b.gridMetrics, "r", R.string.m_range,
            if (whKm > 0 && remWh > 0) fmt("≈ %.0f km", remWh / whKm) else "—", cGreen)
        metricViews["r"]?.let { it.contentDescription = basis }
        val sp = com.vanaccess.bms.reports.SessionRecorder.liveSpeedKmh.value
        metric(b.gridMetrics, "s", R.string.m_speed, sp?.let { fmt("%.0f km/h", it) } ?: "—")
    }

    private fun refreshLearned() {
        val ctx0 = ctx
        kotlin.concurrent.thread {
            val l = com.vanaccess.bms.reports.ReportStore.get(ctx0).learnedConsumption()?.first
            fragment.activity?.runOnUiThread { learnedWhKm = l }
        }
    }

    private fun renderBanner() {
        val trip = com.vanaccess.bms.reports.SessionRecorder.activeTrip.value
        val chg = com.vanaccess.bms.reports.SessionRecorder.activeCharge.value
        val time = java.text.SimpleDateFormat("HH:mm", Locale.US)
        val (txt, col) = when {
            trip != null -> fragment.getString(R.string.live_trip,
                "\u2066" + fmt("%.2f km", trip.distanceKm) + "\u2069", trip.wh) to cBlue
            chg != null -> fragment.getString(R.string.live_charge,
                time.format(java.util.Date(chg.startMs)), chg.ah) to cGreen
            else -> null to cGrey
        }
        b.tvSessionBanner.visibility = if (txt == null) View.GONE else View.VISIBLE
        b.tvSessionBanner.text = txt
        b.tvSessionBanner.background = GradientDrawable().apply {
            cornerRadius = 14 * dp
            setColor(Color.argb(0x33, Color.red(col), Color.green(col), Color.blue(col)))
            setStroke((1 * dp).toInt(), col)
        }
    }

    private fun tempColor(t: Double) = when {
        t < 0 -> cBlue; t <= 40 -> cGreen; t <= 55 -> cOrange; else -> cRed
    }

    private fun statusTile(labelRes: Int, on: Boolean) {
        addTile(b.gridStatus, fragment.getString(labelRes),
            fragment.getString(if (on) R.string.on else R.string.off), if (on) cGreen else cGrey, small = true)
    }

    // ── Cells ────────────────────────────────────────────────────────────────

    private fun renderCells(cells: List<Double>) {
        val maxV = cells.max(); val minV = cells.min()
        val iMax = cells.indexOf(maxV); val iMin = cells.indexOf(minV)
        val avg = cells.average()
        b.gridCellSummary.removeAllViews()
        addTile(b.gridCellSummary, fragment.getString(R.string.cell_max) + " #${iMax + 1}", fmt("%.3f V", maxV), cRed)
        addTile(b.gridCellSummary, fragment.getString(R.string.cell_min) + " #${iMin + 1}", fmt("%.3f V", minV), cBlue)
        addTile(b.gridCellSummary, fragment.getString(R.string.cell_avg), fmt("%.3f V", avg), null)
        val delta = ((maxV - minV) * 1000).toInt()
        addTile(b.gridCellSummary, fragment.getString(R.string.cell_delta), "$delta mV",
            if (delta >= 50) cRed else if (delta >= 25) cOrange else cGreen)

        b.tvCellsTitle.text = fragment.getString(R.string.cells_count, cells.size)
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

        val dev = card(box, fragment.getString(R.string.info_device))
        rows.forEach { (k, v) -> row(dev, fragment.getString(k), v, selectable = true) }

        val features = BleService.activeFeatures.value ?: emptySet()
        if (Feature.PARAMS in features) {
            val params: List<ParamItem>? = BleService.bmsParams.value
            box.addView(label(fragment.getString(R.string.params_title), Color.WHITE, 15f, bold = true).apply {
                setPadding((4 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
            })
            if (params == null) {
                box.addView(label(fragment.getString(R.string.params_loading), cGrey, 13f))
            } else params.groupBy { it.group }.forEach { (g, items) ->
                val c = card(box, L10n.t(ctx, g))
                items.forEach { row(c, L10n.t(ctx, it.label), L10n.t(ctx, it.value)) }
            }
        }
    }

    // ── History ──────────────────────────────────────────────────────────────

    private fun renderHistory() {
        val list = BleService.bmsHistory.value
        val loading = BleService.historyLoading.value == true
        b.progressHistory.visibility = if (loading) View.VISIBLE else View.GONE
        b.btnHistory.isEnabled = !loading
        b.tvHistoryStatus.text = when {
            loading -> fragment.getString(R.string.history_loading, list?.size ?: 0)
            list == null -> fragment.getString(R.string.history_hint)
            list.isEmpty() -> fragment.getString(R.string.history_empty)
            else -> fragment.getString(R.string.history_done, list.size)
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
            h.box.addView(label(r.time + if (clockUnset) "  " + fragment.getString(R.string.history_clock_unset) else "", cGrey, 12f))
            // Charging/Discharging are the state at the moment of the event, not events themselves
            val states = r.events.filter { it == "Charging" || it == "Discharging" }
            val events = r.events - states.toSet()
            if (events.isEmpty()) {
                // The BMS logs an empty record when every protection flag has cleared again
                h.box.addView(label(fragment.getString(R.string.history_cleared), cGreen, 14f, bold = true))
            }
            events.forEach { e ->
                val col = if (e == "Cell overvoltage protection") cOrange else cRed
                h.box.addView(label("⚠ " + L10n.t(ctx, e), col, 14f, bold = true))
            }
            if (states.isNotEmpty()) h.box.addView(label(
                fragment.getString(R.string.history_state, states.joinToString(" • ") { L10n.t(ctx, it) }), cBlue, 12f))
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
            col.addView(label(fragment.getString(labelRes), cGrey, 12f))
            val v = label(value, Color.WHITE, 22f, bold = true)
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
            textDirection = View.TEXT_DIRECTION_LTR   // numbers/units/serials read left-to-right
            gravity = Gravity.END
            setTextIsSelectable(selectable)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
        parent.addView(r)
    }
}
