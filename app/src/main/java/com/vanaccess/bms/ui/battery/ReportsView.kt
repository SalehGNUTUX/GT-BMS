// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.battery

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

    private val ctx: Context get() = fragment.requireContext()
    private val dp get() = fragment.resources.displayMetrics.density
    private val cGreen = Color.parseColor("#43D17A")
    private val cBlue = Color.parseColor("#4DA3FF")
    private val cOrange = Color.parseColor("#F5B83D")
    private val cGrey = Color.parseColor("#9BA3B8")
    private val cCard = Color.parseColor("#1E2130")

    private var filter: String? = null
    private val adapter = Adapter()

    fun setup() {
        b.rvReports.layoutManager = LinearLayoutManager(ctx)
        b.rvReports.adapter = adapter
        listOf(null to R.string.rep_all, Session.TYPE_CHARGE to R.string.rep_charges, Session.TYPE_TRIP to R.string.rep_trips)
            .forEach { (type, label) ->
                b.chipsReports.addView(Chip(ctx).apply {
                    text = fragment.getString(label)
                    isCheckable = true
                    isChecked = type == filter
                    setOnClickListener { filter = type; reload() }
                })
            }
        SessionRecorder.savedCount.observe(fragment.viewLifecycleOwner) { reload() }
    }

    fun reload() {
        val store = ReportStore.get(ctx)
        val f = filter
        thread {
            val list = store.list(f)
            fragment.activity?.runOnUiThread {
                if (fragment.view == null) return@runOnUiThread
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
        return fragment.getString(R.string.dur_hm, m / 60, m % 60)
    }

    private fun summary(s: Session): List<Pair<String, Int>> = when (s.type) {
        Session.TYPE_CHARGE -> listOf(
            "${s.i("soc_start")}% → ${s.i("soc_end")}%  •  ${dur((s.endMs - s.startMs) / 1000.0)}" to Color.WHITE,
            f("+%.2f Ah  •  %.0f Wh  •  ⌀ %.1f A", s.d("ah"), s.d("wh"), s.d("avg_current")) to cGrey,
        )
        else -> listOf(
            (if (s.d("distance_km") > 0) f("%.2f km  •  ", s.d("distance_km")) else "") +
                dur((s.endMs - s.startMs) / 1000.0) +
                (if (s.d("max_speed") > 0) f("  •  ⤒ %.0f km/h", s.d("max_speed")) else "") to Color.WHITE,
            f("%.0f Wh  •  %.2f Ah", s.d("wh"), s.d("ah")) +
                (if (!s.data.isNull("wh_per_km")) f("  •  %.1f Wh/km", s.d("wh_per_km")) else "") to cGrey,
        )
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
            val charge = s.type == Session.TYPE_CHARGE
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(text((if (charge) "🔌 " else "🛵 ") + fragment.getString(if (charge) R.string.rep_charge else R.string.rep_trip),
                if (charge) cGreen else cBlue, 15f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(ltr(date(s.startMs), cGrey, 12f))
            h.box.addView(head)
            summary(s).forEach { (t, c) -> h.box.addView(ltr(t, c, 13f).apply { setPadding(0, (3 * dp).toInt(), 0, 0) }) }
            badges(s).takeIf { it.isNotEmpty() }?.let { h.box.addView(text(it.joinToString("  •  "), cOrange, 12f, false)) }
            h.box.setOnClickListener { showDetail(s) }
            h.box.setOnLongClickListener { confirmDelete(s); true }
        }
    }

    private fun badges(s: Session) = buildList {
        if (s.b("ended_by_protection")) add("⚠ " + fragment.getString(R.string.rep_by_protection))
        if (s.type == Session.TYPE_CHARGE && s.b("reached_full")) add("✓ " + fragment.getString(R.string.rep_full))
    }

    private fun confirmDelete(s: Session) {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.rep_delete_q)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                thread { ReportStore.get(ctx).delete(s.id); fragment.activity?.runOnUiThread { reload() } }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
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
        rows += R.string.rep_soc to "${s.i("soc_start")}% → ${s.i("soc_end")}%"
        rows += R.string.rep_energy to f("%.2f Ah  •  %.0f Wh", s.d("ah"), s.d("wh"))
        if (charge) {
            rows += R.string.rep_avg_current to f("%.1f A", s.d("avg_current"))
            rows += R.string.rep_charging_cur to f("%.1f A", s.d("max_current"))
            rows += R.string.rep_max_v to f("%.2f V", s.d("max_v"))
            if (s.d("max_cell_v") > 0) rows += R.string.rep_max_cell to fragment.getString(R.string.cell_at, s.d("max_cell_v"), s.i("max_cell_idx"))
            rows += R.string.rep_max_delta to "${s.i("max_delta_mv")} mV"
        } else {
            if (s.d("distance_km") > 0) {
                rows += R.string.rep_distance to f("%.2f km", s.d("distance_km"))
                rows += R.string.rep_moving to dur(s.d("moving_s"))
                rows += R.string.rep_max_speed to f("%.0f km/h", s.d("max_speed"))
                rows += R.string.rep_avg_speed to f("%.1f km/h", s.d("avg_speed"))
                if (!s.data.isNull("wh_per_km")) rows += R.string.rep_consumption to
                    f("%.1f Wh/km  •  %.3f Ah/km", s.d("wh_per_km"), s.d("ah_per_km"))
                rows += R.string.rep_hard_accel to "${s.i("hard_accel")}"
            }
            rows += R.string.rep_max_current to f("%.1f A", s.d("max_current"))
            rows += R.string.rep_min_v to f("%.2f V", s.d("min_v"))
            if (s.d("min_cell_v") > 0) rows += R.string.rep_min_cell to fragment.getString(R.string.cell_at, s.d("min_cell_v"), s.i("min_cell_idx"))
            if (s.i("max_delta_mv") > 0) rows += R.string.rep_max_delta_load to
                fragment.getString(R.string.delta_at, s.i("max_delta_mv"), s.d("current_at_max_delta"))
        }
        if (!s.data.isNull("max_temp")) rows += R.string.rep_max_temp to f("%.0f °C", s.d("max_temp"))
        rows.forEach { (k, v) -> col.addView(row(fragment.getString(k), v)) }
        badges(s).forEach { col.addView(text(it, cOrange, 13f, true).apply { setPadding(0, (4 * dp).toInt(), 0, 0) }) }
        if (charge && s.b("ended_by_protection")) {
            val prefs = com.vanaccess.bms.data.AppPreferences(ctx)
            val cells = prefs.bmsCellCount.takeIf { it > 0 } ?: 0
            com.vanaccess.bms.reports.ChargeAdvice.from(cells, prefs.bmsCellOvMv)?.let { a ->
                col.addView(text("💡 " + fragment.getString(R.string.advice_report, a.chargerPackV), cBlue, 13f, false)
                    .apply { setPadding(0, (6 * dp).toInt(), 0, 0) })
            }
        }
        if (!charge && s.d("distance_km") <= 0) col.addView(text(fragment.getString(R.string.rep_no_gps), cGrey, 12f, false))

        col.addView(text(fragment.getString(R.string.rep_chart), Color.WHITE, 14f, true).apply {
            setPadding(0, (14 * dp).toInt(), 0, (6 * dp).toInt())
        })
        val chart = LineChart(ctx)
        col.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (220 * dp).toInt()))
        loadChart(chart, s)

        val scroll = androidx.core.widget.NestedScrollView(ctx).apply { addView(col) }
        AlertDialog.Builder(ctx)
            .setTitle((if (charge) "🔌 " else "🛵 ") + fragment.getString(if (charge) R.string.rep_charge else R.string.rep_trip) +
                "  " + date(s.startMs))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.btn_delete) { _, _ -> confirmDelete(s) }
            .show()
    }

    private fun loadChart(chart: LineChart, s: Session) {
        chart.description.isEnabled = false
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
                    LineDataSet(e, fragment.getString(label)).apply {
                        this.color = color; setDrawCircles(false); setDrawValues(false); lineWidth = 1.6f
                        axisDependency = dep
                    }
                val sets = mutableListOf(
                    set(R.string.chart_v, cGreen, YAxis.AxisDependency.LEFT, pts.map { Entry(x(it.tMs), it.v.toFloat()) }),
                    set(R.string.chart_i, cBlue, YAxis.AxisDependency.RIGHT, pts.map { Entry(x(it.tMs), it.i.toFloat()) }),
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
        addView(text("⁦$v⁩", Color.WHITE, 13f, true).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
    }
}
