// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.battery

import com.vanaccess.bms.map.OsmAndBridge.State as MapState
import com.vanaccess.bms.ui.AppDialog as AD
import com.vanaccess.bms.reports.Advisor.Level as L
import com.vanaccess.bms.reports.CapacityTest.Phase as P
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.chip.Chip
import com.vanaccess.bms.R
import com.vanaccess.bms.databinding.FragmentBatteryBinding
import com.vanaccess.bms.reports.ReportStore
import com.vanaccess.bms.reports.Session
import com.vanaccess.bms.reports.SessionRecorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Reports tab: charge and trip sessions, newest first, with a detail view and chart. */
class ReportsView(private val fragment: Fragment, private val b: FragmentBatteryBinding) {

    /** Fixed: background work and timers may outlive the fragment, which then has no context. */
    private val ctx: Context = fragment.requireContext()
    private val dp get() = ctx.resources.displayMetrics.density
    private val cGreen = Color.parseColor("#43D17A")
    private val cBlue = Color.parseColor("#4DA3FF")
    private val cOrange = Color.parseColor("#F5B83D")
    private val cGrey = Color.parseColor("#9BA3B8")
    private val cCard = Color.parseColor("#1E2130")

    private var filter: String? = null
    private val LEVELS = "levels"
    /** Discharge filter = stationary discharges and trips (a trip is a discharge while moving). */
    private val DISCHARGES = "discharges"
    private val DAILY = "daily"
    private val adapter = Adapter()
    private val dayAdapter = DayAdapter()

    fun setup() {
        b.rvReports.layoutManager = LinearLayoutManager(ctx)
        b.rvReports.adapter = adapter
        // One filter for energy taken out of the battery: called "Trips" for vehicles and
        // "Discharge" for home/storage or stationary use — it lists both kinds of session.
        val outLabel = if (com.vanaccess.bms.data.AppPreferences(ctx).vehicle.mobile) R.string.rep_trips else R.string.rep_discharges
        listOf(null to R.string.rep_all, Session.TYPE_CHARGE to R.string.rep_charges, DISCHARGES to outLabel,
            DAILY to R.string.rep_daily, LEVELS to R.string.levels_title)
            .forEach { (type, label) ->
                b.chipsReports.addView(Chip(ctx).apply {
                    text = ctx.getString(label)
                    isCheckable = true
                    isChecked = type == filter
                    setOnClickListener { filter = type; reload() }
                })
            }
        // Export as an icon-only chip on the same row as the filters
        b.chipsReports.addView(Chip(ctx).apply {
            text = ""
            contentDescription = ctx.getString(R.string.export_title)
            isCheckable = false
            chipIcon = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_export)
            chipIconTint = android.content.res.ColorStateList.valueOf(Color.parseColor("#4DA3FF"))
            isChipIconVisible = true
            chipIconSize = 22 * dp
            textStartPadding = 0f; textEndPadding = 0f
            chipStartPadding = 10 * dp; chipEndPadding = 2 * dp
            iconStartPadding = 0f; iconEndPadding = 0f
            setOnClickListener { chooseExport() }
        })
        SessionRecorder.savedCount.observe(fragment.viewLifecycleOwner) { reload() }
        com.vanaccess.bms.ble.BleService.capacityTest.observe(fragment.viewLifecycleOwner) { if (filter == LEVELS) reload() }
    }

    fun reload() {
        val store = ReportStore.get(ctx)
        val f = filter
        val levels = f == LEVELS
        b.scrollLevels.visibility = if (levels) View.VISIBLE else View.GONE
        b.rvReports.visibility = if (levels) View.GONE else View.VISIBLE
        if (levels) { b.tvReportsEmpty.visibility = View.GONE; loadLevels(); return }
        if (f == DAILY) {
            thread {
                val days = com.vanaccess.bms.reports.DayReport.all(
                    store.list(null, 1000, com.vanaccess.bms.data.AppPreferences(ctx).selectedDeviceAddress))
                fragment.activity?.runOnUiThread {
                    if (fragment.view == null || filter != DAILY) return@runOnUiThread
                    b.rvReports.adapter = dayAdapter
                    dayAdapter.submit(days)
                    b.tvReportsEmpty.visibility = if (days.isEmpty()) View.VISIBLE else View.GONE
                }
            }
            return
        }
        thread {
            val bat = com.vanaccess.bms.data.AppPreferences(ctx).selectedDeviceAddress
            val list = if (f == DISCHARGES) store.listTypes(listOf(Session.TYPE_DISCHARGE, Session.TYPE_TRIP), battery = bat)
                else store.list(f, battery = bat)
            fragment.activity?.runOnUiThread {
                if (fragment.view == null || filter != f) return@runOnUiThread
                if (b.rvReports.adapter !== adapter) b.rvReports.adapter = adapter
                adapter.submit(list)
                b.tvReportsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    private fun f(p: String, vararg a: Any) = String.format(Locale.US, p, *a)
    private fun date(ms: Long) = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US).format(Date(ms))
    private fun dur(s: Double): String {
        val m = (s / 60).toInt()
        return ctx.getString(R.string.dur_hm, m / 60, m % 60)
    }

    /** Wraps numbers/units so they keep left-to-right order inside Arabic text. */
    private fun iso(t: String) = "\u2066$t\u2069"
    private fun line(vararg parts: String?) = parts.filterNotNull().filter { it.isNotBlank() }.joinToString("  •  ")

    private fun kind(s: Session): Triple<String, Int, Int> = when (s.type) {
        Session.TYPE_CHARGE -> Triple("🔌", R.string.rep_charge, cGreen)
        Session.TYPE_DISCHARGE -> Triple("🔋", R.string.rep_discharge, cOrange)
        else -> Triple("🛵", R.string.rep_trip, cBlue)
    }

    private fun summary(s: Session): List<Pair<String, Int>> {
        val socs = iso("${s.i("soc_start")}% – ${s.i("soc_end")}%")
        val d = dur((s.endMs - s.startMs) / 1000.0)
        return when (s.type) {
            Session.TYPE_CHARGE -> listOf(
                line(socs, d) to Color.WHITE,
                line(iso(f("+%.2f Ah", s.d("ah"))), iso(f("%.0f Wh", s.d("wh"))), iso(f("⌀ %.1f A", s.avgA))) to cGrey,
            )
            Session.TYPE_DISCHARGE -> listOf(
                line(socs, d) to Color.WHITE,
                line(iso(f("−%.2f Ah", s.d("ah"))), iso(f("%.0f Wh", s.d("wh"))), iso(f("⌀ %.0f W", s.d("avg_power")))) to cGrey,
            )
            else -> listOf(
                line(if (s.d("distance_km") > 0) iso(f("%.2f km", s.d("distance_km"))) else null, d,
                    if (s.d("max_speed") > 0) iso(f("⤒ %.0f km/h", s.d("max_speed"))) else null) to Color.WHITE,
                line(iso(f("%.0f Wh", s.d("wh"))), iso(f("%.2f Ah", s.d("ah"))),
                    if (!s.data.isNull("wh_per_km")) iso(f("%.1f Wh/km", s.d("wh_per_km"))) else null) to cGrey,
            ) + (if (s.d("regen_wh") >= 1) listOf(line("♻ " + ctx.getString(R.string.regen_energy),
                iso(f("%.0f Wh", s.d("regen_wh"))),
                com.vanaccess.bms.reports.RegenMeter.sharePct(s.d("regen_wh"), s.d("wh"))?.let { iso(f("%.0f%%", it)) }) to cGreen) else emptyList())
        }
    }

    // ── List ─────────────────────────────────────────────────────────────────

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private var items = listOf<Session>()
        inner class VH(val box: LinearLayout) : RecyclerView.ViewHolder(box)
        fun submit(l: List<Session>) { items = l; notifyDataSetChanged() }
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(cCard) }
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = (8 * dp).toInt() }
            isClickable = true
        })

        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = items[pos]
            h.box.removeAllViews()
            val (icon, label, color) = kind(s)
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(text("$icon " + ctx.getString(label), color, 15f, true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(ltr(date(s.startMs), cGrey, 12f))
            h.box.addView(head)
            summary(s).forEach { (t, c) -> h.box.addView(text(t, c, 13f, false).apply { setPadding(0, (3 * dp).toInt(), 0, 0) }) }
            badges(s).takeIf { it.isNotEmpty() }?.let { h.box.addView(text(it.joinToString("  •  "), cOrange, 12f, false)) }
            h.box.setOnClickListener { showDetail(s) }
            h.box.setOnLongClickListener { confirmDelete(s); true }
        }
    }

    private fun badges(s: Session) = buildList {
        if (s.b("energy_partial")) add("⚠ " + (if (s.d("energy_km") > 0) ctx.getString(R.string.rep_energy_part_km, s.d("energy_km"), s.d("distance_km"))
            else ctx.getString(R.string.rep_energy_partial)))
        else if (s.d("gap_s") >= 30) add("📶 " + ctx.getString(R.string.rep_gap, s.i("gap_s") / 60.0))
        if (s.b("ended_by_protection")) add("⚠ " + ctx.getString(R.string.rep_by_protection))
        val jumped = s.data.has("soc_before_stop") && s.i("soc_after_stop") - s.i("soc_before_stop") >= 2
        if (s.type == Session.TYPE_CHARGE && s.b("reached_full")) add(
            if (jumped) "🔋 " + com.vanaccess.bms.ui.AdviceText.socText(ctx, s.i("soc_before_stop"), s.i("soc_after_stop"), null)
            else "✓ " + ctx.getString(R.string.rep_full))
    }

    private fun confirmDelete(s: Session) {
        // Safe action first (right in Arabic) in green, destructive one in red
        com.vanaccess.bms.ui.AppDialog.show(ctx, ctx.getString(R.string.rep_delete_q),
            message = ctx.getString(R.string.rep_delete_msg),
            buttons = listOf(
                com.vanaccess.bms.ui.AppDialog.Btn(ctx.getString(R.string.btn_cancel), com.vanaccess.bms.ui.AppDialog.GREEN),
                com.vanaccess.bms.ui.AppDialog.Btn(ctx.getString(R.string.btn_delete), com.vanaccess.bms.ui.AppDialog.RED) {
                    thread { ReportStore.get(ctx).delete(s.id); fragment.activity?.runOnUiThread { reload() } }
                },
            ))
    }

    // ── Daily report (midnight to midnight) ──────────────────────────────────

    private fun dayName(ms: Long): String {
        val loc = ctx.resources.configuration.locales[0]
        return SimpleDateFormat("EEEE", loc).format(Date(ms)) + "  " + iso(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms)))
    }

    private fun isToday(d: com.vanaccess.bms.reports.DayReport) = System.currentTimeMillis() < d.endMs

    private inner class DayAdapter : RecyclerView.Adapter<DayAdapter.VH>() {
        private var items = listOf<com.vanaccess.bms.reports.DayReport>()
        inner class VH(val box: LinearLayout) : RecyclerView.ViewHolder(box)
        fun submit(l: List<com.vanaccess.bms.reports.DayReport>) { items = l; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(cCard) }
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = (8 * dp).toInt() }
            isClickable = true
        })
        override fun onBindViewHolder(h: VH, pos: Int) {
            val d = items[pos]
            h.box.removeAllViews()
            h.box.addView(text(dayName(d.startMs), cBlue, 15f, true))
            if (isToday(d)) h.box.addView(text(ctx.getString(R.string.day_so_far), cOrange, 12f, false))
            val r = ctx.resources
            h.box.addView(text(line(
                if (d.tripCount > 0) r.getQuantityString(R.plurals.day_trips, d.tripCount, d.tripCount) else null,
                if (d.km > 0) iso(f("%.2f km", d.km)) else null, if (d.totalS > 0) dur(d.totalS) else null,
                if (d.chargeCount > 0) r.getQuantityString(R.plurals.day_charges, d.chargeCount, d.chargeCount) else null), Color.WHITE, 13f, false)
                .apply { setPadding(0, (3 * dp).toInt(), 0, 0) })
            h.box.addView(text(line(if (d.wh > 0) iso(f("%.0f Wh", d.wh)) else null, d.whPerKm?.let { iso(f("%.1f Wh/km", it)) },
                if (d.socUsed >= 0.5) iso(f("−%.0f%%", d.socUsed)) else null, if (d.socCharged > 0) iso("+${d.socCharged}%") else null), cGrey, 13f, false)
                .apply { setPadding(0, (3 * dp).toInt(), 0, 0) })
            h.box.setOnClickListener { showDay(d) }
        }
    }

    private fun dayRows(d: com.vanaccess.bms.reports.DayReport): List<Pair<Int, String>> {
        val rows = mutableListOf<Pair<Int, String>>()
        if (d.tripCount > 0 || d.km > 0) {
            rows += R.string.rep_trips to "${d.tripCount}"
            if (d.km > 0) rows += R.string.rep_distance to f("%.2f km", d.km)
            rows += R.string.rep_duration to dur(d.totalS)
            if (d.km > 0) {
                rows += R.string.rep_moving to dur(d.ridingS)
                rows += R.string.rep_max_speed to f("%.0f km/h", d.maxSpeed)
                rows += R.string.rep_avg_speed to f("%.1f km/h", d.avgSpeed)
            }
        }
        if (d.wh > 0) rows += R.string.rep_energy to f("%.2f Ah  •  %.0f Wh", d.ah, d.wh)
        d.whPerKm?.let { rows += R.string.rep_consumption to f("%.1f Wh/km", it) }
        if (d.regenWh >= 0.5) rows += R.string.regen_energy to f("%.0f Wh", d.regenWh) +
            (com.vanaccess.bms.reports.RegenMeter.sharePct(d.regenWh, d.wh)?.let { f("  •  %.1f%%", it) } ?: "") +
            (d.whPerKm?.takeIf { it > 0 }?.let { f("  •  ≈ %.2f km", d.regenWh / it) } ?: "")
        if (d.socStart != null && d.socEnd != null) rows += R.string.day_soc_row to "${d.socStart}% – ${d.socEnd}%"
        if (d.socUsed >= 0.5) rows += R.string.day_used to f("%.0f%%", d.socUsed)
        if (d.unrecorded > 0) rows += R.string.day_unrecorded to "${d.unrecorded}%"
        if (d.socCharged > 0) rows += R.string.day_soc_charged to "${d.socCharged}%"
        if (d.chargeCount > 0 || d.chargedAh > 0) rows += R.string.day_charged to "${d.chargeCount}  •  " + f("%.2f Ah  •  %.0f Wh", d.chargedAh, d.chargedWh)
        if (d.trips.isNotEmpty()) {
            rows += R.string.rep_hard_accel to "${d.hardAccel}"
            rows += R.string.day_shocks to "${d.shocks}"
        }
        if (d.maxCurrent > 0) rows += R.string.rep_max_current to f("%.1f A", d.maxCurrent)
        if (d.minCellV > 0) rows += R.string.rep_min_cell to ctx.getString(R.string.cell_at, d.minCellV, d.minCellIdx)
        d.maxTemp?.let { rows += R.string.rep_max_temp to f("%.0f °C", it) }
        val cp = com.vanaccess.bms.data.AppPreferences(ctx)
        if (cp.pricePerKwh > 0 && d.wh > 0) {
            rows += R.string.cost_row to f("%.2f %s", com.vanaccess.bms.reports.EnergyCost.cost(d.wh, cp.pricePerKwh.toDouble()), cp.currency)
            if (d.km > 0 && cp.fuelPrice > 0 && cp.fuelL100 > 0) rows += R.string.cost_fuel to f("%.2f %s",
                com.vanaccess.bms.reports.EnergyCost.fuel(d.km, cp.fuelL100.toDouble(), cp.fuelPrice.toDouble()), cp.currency)
        }
        return rows
    }

    private fun showDay(d: com.vanaccess.bms.reports.DayReport) {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        }
        col.addView(text(ctx.getString(if (isToday(d)) R.string.day_so_far else R.string.day_24h), cGrey, 12f, false))
        val rows = dayRows(d)
        rows.forEach { (k, v) -> col.addView(row(ctx.getString(k), v)) }
        if (d.partial) col.addView(text("⚠ " + ctx.getString(R.string.day_partial), cOrange, 12f, false))
        if (d.km > 0) {
            col.addView(text(ctx.getString(R.string.day_routes), Color.WHITE, 14f, true).apply { setPadding(0, (14 * dp).toInt(), 0, (2 * dp).toInt()) })
            col.addView(text(ctx.getString(R.string.map_legend), cGrey, 12f, false))
            addRouteMap(col, d.trips.filter { it.d("distance_km") > 0 })
        }
        val sessions = (d.trips + d.charges).sortedBy { it.startMs }
        if (sessions.isNotEmpty()) col.addView(text(ctx.getString(R.string.day_list), Color.WHITE, 14f, true)
            .apply { setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt()) })
        val hm = SimpleDateFormat("HH:mm", Locale.US)
        sessions.forEach { s ->
            val (icon, label, color) = kind(s)
            col.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * dp).toInt(), (9 * dp).toInt(), (12 * dp).toInt(), (9 * dp).toInt())
                background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(Color.parseColor("#262A3B")) }
                addView(text("$icon " + ctx.getString(label) + "  " + iso(hm.format(Date(s.startMs)) + " – " + hm.format(Date(s.endMs))), color, 13f, true))
                summary(s).take(1).forEach { (t, c) -> addView(text(t, c, 12f, false)) }
                setOnClickListener { showDetail(s) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (6 * dp).toInt() })
        }
        com.vanaccess.bms.ui.AppDialog.show(ctx, dayName(d.startMs), body = col, tall = true, buttons = listOf(
            com.vanaccess.bms.ui.AppDialog.Btn(ctx.getString(android.R.string.ok), com.vanaccess.bms.ui.AppDialog.GREEN),
            com.vanaccess.bms.ui.AppDialog.Btn(ctx.getString(R.string.day_share), Color.parseColor("#2A6FB8")) {
                val txt = "GT-BMS — " + dayName(d.startMs) + "\n" + rows.joinToString("\n") { (k, v) -> ctx.getString(k) + ": " + v }
                runCatching { ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, txt) }, ctx.getString(R.string.day_share))) }
            }))
    }

    /** Energy recuperation of one trip. */
    private fun regenRows(s: Session): List<Pair<Int, String>> {
        val wh = s.d("regen_wh")
        val rows = mutableListOf<Pair<Int, String>>()
        if (s.d("coast_km") >= 0.05) rows += R.string.coast_row to f("%.2f km  •  ", s.d("coast_km")) + dur(s.d("coast_s"))
        if (wh < 0.05) return rows
        rows += R.string.regen_energy to f("%.1f Wh  •  %.3f Ah", wh, s.d("regen_ah"))
        com.vanaccess.bms.reports.RegenMeter.sharePct(wh, s.d("wh"))?.let { rows += R.string.regen_share to f("%.1f%%", it) }
        rows += R.string.regen_times to "${s.i("regen_n")}  •  " + (if (s.d("regen_s") < 60) f("%.0f s", s.d("regen_s")) else dur(s.d("regen_s")))
        rows += R.string.regen_peak to f("%.1f A  •  %.0f W", s.d("regen_max_a"), s.d("regen_max_w"))
        if (s.d("regen_brake_wh") >= 0.05) rows += R.string.regen_brake to f("≈ %.1f Wh", s.d("regen_brake_wh"))
        if (s.d("regen_hill_wh") >= 0.05) rows += R.string.regen_hill to f("≈ %.1f Wh", s.d("regen_hill_wh"))
        if (!s.data.isNull("wh_per_km") && s.d("wh_per_km") > 0 && wh / s.d("wh_per_km") >= 0.01) rows += R.string.regen_km to f("≈ %.2f km", wh / s.d("wh_per_km"))
        return rows
    }

    // ── Detail ───────────────────────────────────────────────────────────────

    private fun showDetail(s: Session) {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        }
        val charge = s.type == Session.TYPE_CHARGE
        val rows = mutableListOf<Pair<Int, String>>()
        rows += R.string.rep_duration to dur((s.endMs - s.startMs) / 1000.0)
        rows += R.string.rep_soc to "${s.i("soc_start")}% – ${s.i("soc_end")}%"
        rows += R.string.rep_energy to f("%.2f Ah  •  %.0f Wh", s.d("ah"), s.d("wh"))
        if (charge) {
            rows += R.string.rep_avg_current to f("%.1f A", s.avgA)
            rows += R.string.rep_charging_cur to f("%.1f A", s.d("max_current"))
            rows += R.string.rep_max_v to f("%.2f V", s.d("max_v"))
            if (s.d("max_cell_v") > 0) rows += R.string.rep_max_cell to ctx.getString(R.string.cell_at, s.d("max_cell_v"), s.i("max_cell_idx"))
            rows += R.string.rep_max_delta to "${s.i("max_delta_mv")} mV"
            if (s.data.has("stop_reason")) {
                val AT = com.vanaccess.bms.ui.AdviceText
                rows += R.string.rep_stop to AT.stopText(ctx, s.data.optString("stop_reason"),
                    s.data.optString("stop_alarm").ifBlank { null }, s.i("stop_max_idx"), s.d("stop_max_cell"))
                rows += R.string.rep_stop_soc to AT.socText(ctx, s.i("soc_before_stop"), s.i("soc_after_stop"),
                    if (s.data.has("soc_counted")) s.i("soc_counted") else null)
                rows += R.string.rep_stop_v to f("%.2f V", s.d("stop_v"))
                if (s.i("stop_max_idx") > 0) {
                    rows += R.string.rep_stop_max to ctx.getString(R.string.cell_at, s.d("stop_max_cell"), s.i("stop_max_idx"))
                    rows += R.string.rep_stop_min to ctx.getString(R.string.cell_at, s.d("stop_min_cell"), s.i("stop_min_idx"))
                    rows += R.string.rep_stop_delta to "${s.i("stop_delta_mv")} mV"
                }
                if (s.i("top_main") > 0) rows += R.string.rep_top to ctx.getString(R.string.rep_top_val,
                    s.i("top_start"), s.i("top_main"), s.i("top_main_pct"), s.i("stop_max_idx"))
                if (s.d("gap_s") >= 30) rows += R.string.rep_gap_row to ctx.getString(R.string.stats_min, Math.round(s.d("gap_s") / 60).toInt().coerceAtLeast(1))
                if (s.data.has("rest_v")) rows += R.string.rep_rest to f("%.2f V  •  %d mV", s.d("rest_v"), s.i("rest_delta_mv"))
                if (s.data.has("target_v") || s.data.has("target_soc")) {
                    val ok = (s.d("target_v") > 0 && s.d("max_v") >= s.d("target_v")) ||
                        (s.i("target_soc") > 0 && s.i("soc_before_stop") >= s.i("target_soc"))
                    rows += R.string.rep_target to line(
                        s.d("target_v").takeIf { it > 0 }?.let { f("%.1f V", it) }, s.i("target_soc").takeIf { it > 0 }?.let { "$it%" },
                        ctx.getString(if (ok) R.string.rep_target_yes else R.string.rep_target_no))
                }
                if (s.data.has("eta_first_min")) {
                    val real = (s.endMs - s.startMs) / 60000.0
                    rows += R.string.rep_eta to ctx.getString(R.string.rep_eta_val, dur(s.d("eta_first_min") * 60), dur(real * 60),
                        Math.round(s.d("eta_first_min") - real).toInt())
                }
            }
        } else {
            if (s.type == Session.TYPE_DISCHARGE) {
                rows += R.string.rep_avg_current to f("%.1f A", s.d("avg_current"))
                rows += R.string.rep_avg_power to f("%.0f W", s.d("avg_power"))
            }
            if (s.d("distance_km") > 0) {
                rows += R.string.rep_distance to f("%.2f km", s.d("distance_km"))
                rows += R.string.rep_moving to dur(s.d("moving_s"))
                rows += R.string.rep_max_speed to f("%.0f km/h", s.d("max_speed"))
                rows += R.string.rep_avg_speed to f("%.1f km/h", s.d("avg_speed"))
                if (!s.data.isNull("wh_per_km")) rows += R.string.rep_consumption to
                    f("%.1f Wh/km  •  %.3f Ah/km", s.d("wh_per_km"), s.d("ah_per_km"))
                rows += R.string.rep_hard_accel to "${s.i("hard_accel")}"
            }
            if (s.type == Session.TYPE_TRIP) rows += regenRows(s)
            rows += R.string.rep_max_current to f("%.1f A", s.d("max_current"))
            rows += R.string.rep_min_v to f("%.2f V", s.d("min_v"))
            if (s.d("min_cell_v") > 0) rows += R.string.rep_min_cell to ctx.getString(R.string.cell_at, s.d("min_cell_v"), s.i("min_cell_idx"))
            if (s.i("max_delta_mv") > 0) rows += R.string.rep_max_delta_load to
                (s.deltaA?.let { ctx.getString(R.string.delta_at, s.i("max_delta_mv"), it) } ?: "${s.i("max_delta_mv")} mV")
        }
        if (!s.data.isNull("max_temp")) rows += R.string.rep_max_temp to f("%.0f °C", s.d("max_temp"))
        // Energy cost (if a price is set in Settings → Vehicle & trips)
        val cp = com.vanaccess.bms.data.AppPreferences(ctx)
        if (cp.pricePerKwh > 0 && s.d("wh") > 0) {
            val cur = cp.currency
            rows += R.string.cost_row to f("%.2f %s", com.vanaccess.bms.reports.EnergyCost.cost(s.d("wh"), cp.pricePerKwh.toDouble()), cur)
            if (!charge && !s.data.isNull("wh_per_km") && s.d("distance_km") > 0) {
                rows += R.string.cost_per_km to f("%.3f %s", com.vanaccess.bms.reports.EnergyCost.perKm(s.d("wh_per_km"), cp.pricePerKwh.toDouble()), cur)
                if (cp.fuelPrice > 0 && cp.fuelL100 > 0)
                    rows += R.string.cost_fuel to f("%.2f %s", com.vanaccess.bms.reports.EnergyCost.fuel(s.d("distance_km"),
                        cp.fuelL100.toDouble(), cp.fuelPrice.toDouble()), cur)
            }
        }
        rows.forEach { (k, v) -> col.addView(row(ctx.getString(k), v)) }
        badges(s).forEach { col.addView(text(it, cOrange, 13f, true).apply { setPadding(0, (4 * dp).toInt(), 0, 0) }) }
        if (s.type == Session.TYPE_TRIP && s.d("distance_km") <= 0) col.addView(text(ctx.getString(R.string.rep_no_gps), cGrey, 12f, false))

        val recBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (8 * dp).toInt(), 0, 0) }
        col.addView(recBox)
        thread {
            val recs = com.vanaccess.bms.ui.AdviceText.forSession(ctx, s) +
                (if (charge) com.vanaccess.bms.ui.AdviceText.chargerFor(ctx, s)?.recs.orEmpty() else emptyList())
            fragment.activity?.runOnUiThread {
                recs.forEach { r ->
                    recBox.addView(text(com.vanaccess.bms.ui.AdviceText.icon(r.level) + " " +
                        com.vanaccess.bms.ui.AdviceText.text(ctx, r), com.vanaccess.bms.ui.AdviceText.color(r.level), 13f, false)
                        .apply { setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt()) })
                }
            }
        }

        // Trip map (offline sketch coloured by current) + open the track in OsmAnd
        if (s.type == Session.TYPE_TRIP && s.d("distance_km") > 0) {
            col.addView(text(ctx.getString(R.string.map_title), Color.WHITE, 14f, true).apply {
                setPadding(0, (14 * dp).toInt(), 0, (2 * dp).toInt())
            })
            col.addView(text(ctx.getString(R.string.map_legend), cGrey, 12f, false))
            addRouteMap(col, listOf(s))
        }

        col.addView(text(ctx.getString(R.string.rep_chart), Color.WHITE, 14f, true).apply {
            setPadding(0, (14 * dp).toInt(), 0, (6 * dp).toInt())
        })
        val chart = LineChart(ctx)
        col.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (220 * dp).toInt()))
        loadChart(chart, s)

        com.vanaccess.bms.ui.AppDialog.show(ctx,
            kind(s).first + " " + ctx.getString(kind(s).second) + "  " + date(s.startMs),
            body = col, tall = true,
            buttons = listOf(
                com.vanaccess.bms.ui.AppDialog.Btn(ctx.getString(android.R.string.ok), com.vanaccess.bms.ui.AppDialog.GREEN),
                com.vanaccess.bms.ui.AppDialog.Btn(ctx.getString(R.string.btn_delete), com.vanaccess.bms.ui.AppDialog.RED) { confirmDelete(s) },
            ))
    }


    private fun loadChart(chart: LineChart, s: Session) {
        chart.description.isEnabled = false
        chart.setNoDataText(ctx.getString(R.string.chart_no_data))
        chart.setNoDataTextColor(cGrey)
        chart.legend.textColor = cGrey
        chart.xAxis.textColor = cGrey
        chart.axisLeft.textColor = cGrey
        chart.axisRight.textColor = cGrey
        chart.xAxis.setDrawGridLines(false)
        chart.axisLeft.gridColor = Color.parseColor("#22FFFFFF")
        chart.axisRight.setDrawGridLines(false)
        thread {
            val pts = ReportStore.get(ctx).points(s.id)
            fragment.activity?.runOnUiThread {
                if (pts.size < 2) { chart.invalidate(); return@runOnUiThread }
                val t0 = pts.first().tMs
                fun x(t: Long) = (t - t0) / 60000f
                fun set(label: Int, color: Int, dep: YAxis.AxisDependency, e: List<Entry>) =
                    LineDataSet(e, ctx.getString(label)).apply {
                        this.color = color; setDrawCircles(false); setDrawValues(false); lineWidth = 1.6f
                        axisDependency = dep
                    }
                val sets = mutableListOf(
                    set(R.string.chart_v, cGreen, YAxis.AxisDependency.LEFT, pts.filter { it.v > 0 }.map { Entry(x(it.tMs), it.v.toFloat()) }),
                    set(R.string.chart_i, cBlue, YAxis.AxisDependency.RIGHT, pts.filter { it.v > 0 }.map { Entry(x(it.tMs), it.i.toFloat()) }),
                )
                val sp = pts.filter { it.speedKmh != null }
                if (sp.size > 1) sets += set(R.string.chart_speed, cOrange, YAxis.AxisDependency.RIGHT,
                    sp.map { Entry(x(it.tMs), it.speedKmh!!.toFloat()) })
                chart.xAxis.valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = "${value.toInt()}′"
                }
                chart.data = LineData(sets.toList())
                chart.invalidate()
            }
        }
    }

    // ── Maps (OsmAnd) ────────────────────────────────────────────────────────

    /** OsmAnd (Play), OsmAnd+ / OsmAnd~ (F-Droid build, same package), OsmAnd nightly. */

    /** Open one trip's GPX in OsmAnd if installed, else any GPX viewer, else suggest OsmAnd. */
    /** The route on OsmAnd's map (sketch until it answers), zoom buttons, and what to do with the track. */
    private fun addRouteMap(col: LinearLayout, trips: List<Session>) {
        val route = RouteView(ctx)
        val frame = android.widget.FrameLayout(ctx)
        frame.addView(route, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val tools = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun tool(icon: Int, desc: Int, click: () -> Unit) = tools.addView(android.widget.ImageButton(ctx).apply {
            setImageResource(icon); contentDescription = ctx.getString(desc)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#E61E2130")); setStroke(dp.toInt(), Color.parseColor("#3A4260")) }
            setOnClickListener { click() }
        }, LinearLayout.LayoutParams((42 * dp).toInt(), (42 * dp).toInt()).apply { topMargin = (6 * dp).toInt() })
        tool(R.drawable.ic_map_plus, R.string.map_zoom_in) { route.zoomIn() }
        tool(R.drawable.ic_map_minus, R.string.map_zoom_out) { route.zoomOut() }
        tool(R.drawable.ic_map_fit, R.string.map_fit) { route.fit() }
        frame.addView(tools, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START).apply { setMargins((8 * dp).toInt(), 0, (8 * dp).toInt(), (8 * dp).toInt()) })
        col.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (300 * dp).toInt()).apply { topMargin = (6 * dp).toInt() })

        val note = text("", cGrey, 12f, false).apply { setPadding(0, (6 * dp).toInt(), 0, 0); visibility = android.view.View.GONE }
        col.addView(note)
        fun say(t: Int?, click: (() -> Unit)? = null) {
            note.visibility = if (t == null) android.view.View.GONE else android.view.View.VISIBLE
            t?.let { note.text = ctx.getString(it) }
            note.setTextColor(if (click != null) Color.parseColor("#4DA3FF") else cGrey)
            note.setOnClickListener(click?.let { c -> android.view.View.OnClickListener { c() } })
        }
        fun load() {
            val B = com.vanaccess.bms.map.OsmAndBridge
            if (B.installed(ctx) == null) { say(R.string.map_osm_missing) { suggestOsmand() }; return }
            say(R.string.map_osm_drawing)
            B.render(ctx, route.pieces(), route.width, route.height, Color.parseColor("#1E88E5")) { a ->
                fragment.activity?.runOnUiThread {
                    if (!route.isAttachedToWindow) return@runOnUiThread
                    when (a.state) {
                        MapState.READY -> { route.setMap(a.shot); say(null) }
                        // Asked once, OsmAnd lists GT-BMS among its connected apps, switched off
                        MapState.DENIED -> say(R.string.map_osm_denied) { askOsmand { load() } }
                        MapState.NO_MAP -> say(R.string.map_osm_nomap) { load() }
                        MapState.UNREACHABLE -> say(R.string.map_osm_unreachable) { load() }
                        MapState.MISSING -> say(R.string.map_osm_missing) { suggestOsmand() }
                    }
                }
            }
        }
        thread {
            val tracks = trips.sortedBy { it.startMs }.map { ReportStore.get(ctx).points(it.id) }
            fragment.activity?.runOnUiThread {
                route.setTracks(tracks)
                if (route.track().size >= 2) route.post { load() } else say(R.string.rep_no_gps)
            }
        }

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(t: Int, color: String, click: () -> Unit) = row.addView(TextView(ctx).apply {
            text = ctx.getString(t)
            setTextColor(Color.WHITE); textSize = 14f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding((8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(Color.parseColor(color)) }
            setOnClickListener { click() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = (6 * dp).toInt() })
        action(R.string.map_open_osmand, "#2A6FB8") { openInMaps(trips) }
        action(R.string.map_share, "#3A3F55") { shareTrack(trips) }
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (8 * dp).toInt() })
    }

    /** How to allow GT-BMS inside OsmAnd, then try again. */
    private fun askOsmand(retry: () -> Unit) {
        AD.show(ctx, ctx.getString(R.string.map_osm_allow_title), message = ctx.getString(R.string.map_osm_allow),
            buttons = listOf(
                AD.Btn(ctx.getString(R.string.map_osm_open), AD.GREEN) {
                    com.vanaccess.bms.map.OsmAndBridge.installed(ctx)?.let { pkg ->
                        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { ctx.startActivity(it) } } }
                },
                AD.Btn(ctx.getString(R.string.update_retry), Color.parseColor("#2A6FB8")) { retry() },
                AD.Btn(ctx.getString(R.string.btn_cancel), AD.GREY)))
    }

    /** Writes the trip's GPX and hands its address over on the main thread. */
    private fun withGpx(trips: List<Session>, use: (android.net.Uri) -> Unit) {
        val app = ctx.applicationContext
        thread {
            val gpx = com.vanaccess.bms.reports.Exporter.gpx(trips.map { it to ReportStore.get(app).points(it.id) })
            val stamp = SimpleDateFormat(if (trips.size > 1) "yyyyMMdd" else "yyyyMMdd-HHmm", Locale.US).format(Date(trips.minOf { it.startMs }))
            val f = java.io.File(java.io.File(app.cacheDir, "exports").apply { mkdirs() }, "GT-BMS-trip-$stamp.gpx")
            f.writeText(gpx)
            fragment.activity?.runOnUiThread {
                use(androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f))
            }
        }
    }

    private fun shareTrack(trips: List<Session>) = withGpx(trips) { uri ->
        runCatching {
            ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, ctx.getString(R.string.map_share)))
        }
    }

    private fun openInMaps(trips: List<Session>) = withGpx(trips) { uri ->
        fun view(pkg: String?) = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/gpx+xml")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            pkg?.let { setPackage(it) }
        }
        try {
            val osmand = com.vanaccess.bms.map.OsmAndBridge.installed(ctx)
            if (osmand != null) ctx.startActivity(view(osmand))
            else ctx.startActivity(android.content.Intent.createChooser(view(null), ctx.getString(R.string.map_open_osmand)))
        } catch (_: Exception) {
            suggestOsmand()
        }
    }

    /** Recommend OsmAnd from its official site, in the app's language. */
    private fun suggestOsmand() {
        AD.show(ctx, ctx.getString(R.string.map_no_app_title), message = ctx.getString(R.string.map_no_app),
            buttons = listOf(
                AD.Btn(ctx.getString(R.string.map_osm_site), AD.GREEN) {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(ctx.getString(R.string.map_osm_url)))) }
                },
                AD.Btn(ctx.getString(R.string.btn_cancel), AD.GREY),
            ))
    }

    // ── Export ───────────────────────────────────────────────────────────────

    private fun chooseExport() {
        val blue = Color.parseColor("#2A6FB8")
        com.vanaccess.bms.ui.AppDialog.show(ctx, ctx.getString(R.string.export_title),
            message = ctx.getString(R.string.export_csv) + "\n" + ctx.getString(R.string.export_gpx),
            buttons = listOf(
                com.vanaccess.bms.ui.AppDialog.Btn("CSV", blue) { export(gpx = false) },
                com.vanaccess.bms.ui.AppDialog.Btn("GPX", blue) { export(gpx = true) },
                com.vanaccess.bms.ui.AppDialog.Btn(ctx.getString(R.string.btn_cancel), com.vanaccess.bms.ui.AppDialog.GREY),
            ))
    }

    /** Write CSV (all reports) or GPX (GPS trips) to cache/exports and open the share sheet. */
    private fun export(gpx: Boolean) {
        val app = ctx.applicationContext
        thread {
            val store = ReportStore.get(app)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            val text: String? = if (gpx) {
                val trips = store.list(Session.TYPE_TRIP, 500).map { it to store.points(it.id) }
                    .filter { (_, p) -> p.any { it.lat != null } }
                if (trips.isEmpty()) null else com.vanaccess.bms.reports.Exporter.gpx(trips)
            } else {
                val all = store.list(null, 5000)
                if (all.isEmpty()) null else com.vanaccess.bms.reports.Exporter.csv(all)
            }
            fragment.activity?.runOnUiThread {
                if (text == null) {
                    android.widget.Toast.makeText(app, R.string.export_empty, android.widget.Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                try {
                    val dir = java.io.File(app.cacheDir, "exports").apply { mkdirs() }
                    val f = java.io.File(dir, "GT-BMS-$stamp." + if (gpx) "gpx" else "csv")
                    f.writeText(text)
                    val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = if (gpx) "application/gpx+xml" else "text/csv"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(android.content.Intent.createChooser(send, ctx.getString(R.string.export_title)))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(app, e.message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ── Levels ───────────────────────────────────────────────────────────────

    private fun loadLevels() {
        val box = b.boxLevels
        thread {
            val store = ReportStore.get(ctx)
            val now = System.currentTimeMillis()
            val levels = store.levels(now - 14 * 86_400_000L, com.vanaccess.bms.data.AppPreferences(ctx).selectedDeviceAddress)
            val charges = store.list(Session.TYPE_CHARGE, 300, com.vanaccess.bms.data.AppPreferences(ctx).selectedDeviceAddress)
            val lim = com.vanaccess.bms.ui.AdviceText.limits(ctx)
            val info = com.vanaccess.bms.ble.BleService.basicInfo.value
            val fullAh = info?.fullCapacity ?: levels.lastOrNull()?.fullAh
            val health = com.vanaccess.bms.reports.Advisor.health(fullAh, lim, charges)
            val lastFull = charges.firstOrNull { it.b("reached_full") || it.b("ended_by_protection") }
            val lowCal = (lim.socLowCalMv ?: if (lim.lion) 3300 else if (lim.lfp) 3050 else lim.cellUvMv ?: 2500) / 1000.0
            val allLevels = store.levels(0, com.vanaccess.bms.data.AppPreferences(ctx).selectedDeviceAddress)
            val lastDeep = allLevels.lastOrNull { it.soc <= 10 || (it.minCell > 0 && it.minCell <= lowCal) }
            val cycles = info?.cycleCount ?: levels.lastOrNull()?.cycles
            val prefs0 = com.vanaccess.bms.data.AppPreferences(ctx)
            val trips = store.list(Session.TYPE_TRIP, 60, prefs0.selectedDeviceAddress)
            val repeats = com.vanaccess.bms.reports.HistoryCache.load(ctx, prefs0.selectedDeviceAddress)
                ?.let { com.vanaccess.bms.reports.DrivingAnalyzer.protectionRepeats(it.records) } ?: 0
            val capTest = com.vanaccess.bms.reports.CapacityTest.State.fromJson(
                com.vanaccess.bms.data.AppPreferences(ctx).capacityTestJson)
            fragment.activity?.runOnUiThread {
                if (fragment.view == null) return@runOnUiThread
                box.removeAllViews()
                // Health
                val hCard = card(box, "🩺 " + ctx.getString(R.string.health_title))
                health.bmsSohPct?.let { hCard.addView(text(ctx.getString(R.string.health_bms, it), Color.WHITE, 14f, true)) }
                if (health.bmsReportsDesign && lim.designAh != null)
                    hCard.addView(text(ctx.getString(R.string.health_bms_design, lim.designAh), cGrey, 12f, false))
                // A finished capacity test is the most reliable measurement; else a qualifying charge
                val testAh = capTest.resultAh?.takeIf { capTest.phase == com.vanaccess.bms.reports.CapacityTest.Phase.DONE }
                val measuredAh = testAh ?: health.measuredAh
                val designAh = lim.designAh
                if (measuredAh != null && designAh != null) {
                    hCard.addView(text(ctx.getString(R.string.health_measured, measuredAh,
                        (measuredAh / designAh * 100).toInt(), designAh), cGreen, 14f, true))
                }
                renderCapacityTest(hCard, capTest, lowCal)
                // Facts
                val fCard = card(box, "📊 " + ctx.getString(R.string.levels_title))
                fCard.addView(row(ctx.getString(R.string.levels_cycles), cycles?.toString() ?: "—"))
                if (cycles != null) com.vanaccess.bms.reports.Advisor.cyclesLeft(cycles,
                    com.vanaccess.bms.data.AppPreferences(ctx).ratedCycles, lim)?.let { (left, rated) ->
                    fCard.addView(row(ctx.getString(R.string.cycles_left), ctx.getString(R.string.cycles_left_val, left, rated)))
                }
                fCard.addView(row(ctx.getString(R.string.levels_last_full), lastFull?.let { date(it.endMs) }
                    ?: ctx.getString(R.string.levels_never)))
                fCard.addView(row(ctx.getString(R.string.levels_last_deep), lastDeep?.let { date(it.tMs) }
                    ?: ctx.getString(R.string.levels_never)))
                com.vanaccess.bms.reports.ChargeStats.from(charges.take(50), trips)?.let { st ->
                    val sCard = card(box, "🔌 " + ctx.getString(R.string.stats_title))
                    sCard.addView(row(ctx.getString(R.string.stats_total), "${st.total}"))
                    sCard.addView(row(ctx.getString(R.string.stats_cut), "${st.byProtection} (${st.byProtection * 100 / st.total}%)"))
                    sCard.addView(row(ctx.getString(R.string.stats_charger), "${st.byCharger}"))
                    sCard.addView(row(ctx.getString(R.string.stats_early), "${st.early}"))
                    st.avgDeltaMv?.let { sCard.addView(row(ctx.getString(R.string.stats_delta), "$it mV")) }
                    st.cutCell?.let { sCard.addView(row(ctx.getString(R.string.stats_cut_cell),
                        ctx.getString(R.string.stats_cell_times, it, st.cutCellTimes, st.byProtection))) }
                    st.weakCell?.let { sCard.addView(row(ctx.getString(R.string.stats_weak_cell),
                        ctx.getString(R.string.stats_cell_times, it, st.weakCellTimes, st.trips))) }
                    st.avgEtaErrorMin?.let { sCard.addView(row(ctx.getString(R.string.stats_eta), ctx.getString(R.string.stats_min, it))) }
                }
                renderUsageCard(box, trips, repeats)
                // SOC chart
                val cCard = card(box, ctx.getString(R.string.levels_days))
                val chart = LineChart(ctx)
                cCard.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (200 * dp).toInt()))
                styleChart(chart)
                if (levels.size >= 2) {
                    val e = levels.map { Entry((it.tMs - now) / 86_400_000f, it.soc.toFloat()) }
                    chart.data = LineData(LineDataSet(e, "SOC %").apply {
                        color = cGreen; setDrawCircles(false); setDrawValues(false); lineWidth = 1.8f
                        setDrawFilled(true); fillColor = cGreen; fillAlpha = 40
                    })
                    chart.axisLeft.axisMinimum = 0f; chart.axisLeft.axisMaximum = 100f
                    chart.axisRight.isEnabled = false
                    chart.xAxis.valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float) = "${value.toInt()}d"
                    }
                }
                chart.invalidate()
            }
        }
    }

    /**
     * "Driving & usage": style over the last trips, most economical speed band from your own
     * trips, repeated protection cuts while charging, and lithium driving tips.
     */
    private fun renderUsageCard(box: LinearLayout, trips: List<Session>, protectionRepeats: Int) {
        val AT = com.vanaccess.bms.ui.AdviceText
        val c = card(box, "🛵 " + ctx.getString(R.string.usage_title))
        fun rec(r: com.vanaccess.bms.reports.Advisor.Rec) =
            c.addView(text(AT.icon(r.level) + " " + AT.text(ctx, r), AT.color(r.level), 13f, false)
                .apply { setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt()) })
        val recent = trips.filter { it.data.has("throttle_shocks") }.take(10)
        if (recent.isNotEmpty()) {
            val km = recent.sumOf { it.d("distance_km") }.coerceAtLeast(0.1)
            val shocks = recent.sumOf { it.i("throttle_shocks") }
            rec(com.vanaccess.bms.reports.Advisor.Rec(if (shocks / km >= 0.5) L.WARNING else L.OK,
                "usage_shocks", listOf(recent.size, shocks, km)))
        }
        com.vanaccess.bms.reports.DrivingAnalyzer.bestSpeed(trips)?.let { b ->
            rec(com.vanaccess.bms.reports.Advisor.Rec(L.INFO, "usage_best_speed",
                listOf(b.fromKmh, b.fromKmh + 10, b.bestWhKm, b.worstFromKmh, b.worstFromKmh + 10, b.worstWhKm)))
        }
        if (protectionRepeats >= 2) rec(com.vanaccess.bms.reports.Advisor.Rec(L.WARNING, "usage_protection_repeats",
            listOf(protectionRepeats, AT.chargerAdviceV(ctx) ?: 0.0)))
        c.addView(text(ctx.getString(R.string.driving_tips_title), Color.WHITE, 13f, true)
            .apply { setPadding(0, (10 * dp).toInt(), 0, (2 * dp).toInt()) })
        ctx.resources.getStringArray(R.array.driving_tips).forEach { c.addView(text("• $it", Color.parseColor("#C9CFDD"), 13f, false)
            .apply { setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt()) }) }
    }

    /** Guided capacity test: explanation, live step and start / cancel buttons. */
    private fun renderCapacityTest(card: LinearLayout, st: com.vanaccess.bms.reports.CapacityTest.State, lowV: Double) {
        card.addView(text("🧪 " + ctx.getString(R.string.captest_title), Color.WHITE, 14f, true)
            .apply { setPadding(0, (12 * dp).toInt(), 0, (2 * dp).toInt()) })
        val minCell = com.vanaccess.bms.ble.BleService.cellVoltages.value?.minOrNull()
        val stepText = when (st.phase) {
            P.DISCHARGE -> ctx.getString(R.string.captest_discharge, lowV, minCell?.let { f("%.3f V", it) } ?: "—")
            P.READY -> ctx.getString(R.string.captest_ready)
            P.CHARGING -> ctx.getString(R.string.captest_charging, st.ah)
            P.DONE -> ctx.getString(R.string.captest_done, st.resultAh ?: 0.0, date(st.finishedMs))
            P.FAILED -> ctx.getString(R.string.captest_failed)
            P.IDLE -> ctx.getString(R.string.captest_desc, lowV)
        }
        card.addView(text(stepText, if (st.phase == P.FAILED) Color.parseColor("#F0544F") else cBlue, 13f, false))
        val running = st.phase in setOf(P.DISCHARGE, P.READY, P.CHARGING)
        val btn = TextView(ctx).apply {
            text = ctx.getString(if (running) R.string.captest_cancel else R.string.captest_start)
            setTextColor(Color.WHITE); textSize = 14f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (9 * dp).toInt(), (16 * dp).toInt(), (9 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(if (running) com.vanaccess.bms.ui.AppDialog.RED else com.vanaccess.bms.ui.AppDialog.GREEN)
            }
            setOnClickListener {
                val svc = (fragment.activity as? com.vanaccess.bms.ui.MainActivity)?.getBleService() ?: return@setOnClickListener
                if (running) { svc.cancelCapacityTest(); reload() }
                else com.vanaccess.bms.ui.AppDialog.show(ctx, ctx.getString(R.string.captest_start_q),
                    message = ctx.getString(R.string.captest_desc, lowV),
                    buttons = listOf(
                        com.vanaccess.bms.ui.AppDialog.Btn(ctx.getString(R.string.captest_start), com.vanaccess.bms.ui.AppDialog.GREEN) {
                            svc.startCapacityTest(); reload()
                        },
                        com.vanaccess.bms.ui.AppDialog.Btn(ctx.getString(R.string.btn_cancel), com.vanaccess.bms.ui.AppDialog.GREY),
                    ))
            }
        }
        card.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (8 * dp).toInt() })
    }

    private fun styleChart(chart: LineChart) {
        chart.description.isEnabled = false
        chart.setNoDataText(ctx.getString(R.string.chart_no_data))
        chart.setNoDataTextColor(cGrey)
        chart.legend.textColor = cGrey
        chart.xAxis.textColor = cGrey
        chart.axisLeft.textColor = cGrey
        chart.axisRight.textColor = cGrey
        chart.xAxis.setDrawGridLines(false)
        chart.axisLeft.gridColor = Color.parseColor("#22FFFFFF")
        chart.axisRight.setDrawGridLines(false)
    }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(cCard) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = (10 * dp).toInt() }
        }
        c.addView(text(title, Color.WHITE, 15f, true).apply { setPadding(0, 0, 0, (6 * dp).toInt()) })
        parent.addView(c)
        return c
    }

    // ── Views ────────────────────────────────────────────────────────────────

    private fun text(t: String, color: Int, size: Float, bold: Boolean) = TextView(ctx).apply {
        text = t; setTextColor(color); textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    /** Numbers/units read left-to-right even in the Arabic UI. */
    private fun ltr(t: String, color: Int, size: Float) = text("⁦$t⁩", color, size, false)

    private fun row(k: String, v: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, (5 * dp).toInt(), 0, (5 * dp).toInt())
        addView(text(k, cGrey, 13f, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Force left-to-right only for pure numbers/units; Arabic phrases keep their own direction
        addView(text(if (v.any { it in '\u0600'..'\u06FF' }) v else "\u2066$v\u2069", Color.WHITE, 13f, true).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
    }
}
