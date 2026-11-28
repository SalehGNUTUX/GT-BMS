// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanaccess.bms.reports.DayReport
import com.vanaccess.bms.reports.EnergyCost
import com.vanaccess.bms.reports.Point
import com.vanaccess.bms.reports.RegenMeter
import com.vanaccess.bms.reports.Session
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The report lists, as on the phone: all, charges, trips, daily; and the consumption over time. */
private enum class Filter(val label: () -> String) {
    ALL({ Res.s("rep_all") }), CHARGES({ Res.s("rep_charges") }), TRIPS({ Res.s("rep_trips") }),
    DAILY({ Res.s("rep_daily") }), USE({ Tr["consTitle"] })
}

@Composable
internal fun ReportsScreen(monitor: Monitor) {
    val version by monitor.reportsVersion.collectAsState()
    val simulated = monitor.state.collectAsState().value.simulated
    // Each battery its own record (the one this computer knows first); others can be browsed
    val known = remember(version, simulated) { runCatching {
        monitor.settings.battery.takeIf { it.isNotEmpty() }?.let { monitor.reports().claimUnowned(it) }
        monitor.reports().batteries() }.getOrDefault(emptyMap()) }
    var viewed by remember { mutableStateOf(ReportsMemory.battery ?: monitor.settings.battery.ifEmpty { null }) }
    LaunchedEffect(viewed) { ReportsMemory.battery = viewed }
    val all = remember(version, simulated, viewed) { runCatching { monitor.reports().list(5000).filter { viewed == null || it.battery == viewed } }.getOrDefault(emptyList()) }
    var filter by remember { mutableStateOf(runCatching { Filter.valueOf(ReportsMemory.filter) }.getOrDefault(Filter.ALL)) }
    LaunchedEffect(filter) { ReportsMemory.filter = filter.name }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            Filter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label()) }) }
        }
        if (known.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            known.forEach { (addr, n) ->
                val name = if (addr == monitor.settings.battery) monitor.settings.batteryName.ifEmpty { addr } else addr
                FilterChip(selected = viewed == addr, onClick = { viewed = addr }, label = { Text("🔋 " + para(line(name, "$n"))) })
            }
        }
        if (all.isEmpty()) { Text(Res.s("rep_empty"), color = Palette.grey); return@Column }
        when (filter) {
            Filter.DAILY -> DailyList(all, monitor)
            Filter.USE -> ConsumptionPage(all, monitor)
            else -> SessionList(all.filter {
                when (filter) { Filter.CHARGES -> it.type == Session.TYPE_CHARGE; Filter.TRIPS -> it.type != Session.TYPE_CHARGE; else -> true }
            }, monitor)
        }
    }
}

/** The list shown last, kept while visiting other screens (and set by --filter for checks). */
object ReportsMemory { var filter = "ALL"; var battery: String? = null }

private val DATE = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US)

@Composable
private fun SessionList(list: List<Session>, monitor: Monitor) {
    var picked by remember(list) { mutableStateOf(list.firstOrNull()) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list) { s ->
                val (icon, label, color) = kind(s)
                Column(Modifier.fillMaxWidth().background(if (s == picked) Palette.tile else Palette.card, RoundedCornerShape(14.dp))
                    .clickable { picked = s }.padding(12.dp)) {
                    Row { Text("$icon " + Res.s(label), color = color, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(iso(DATE.format(Date(s.startMs))), color = Palette.grey, fontSize = 12.sp) }
                    Text(para(summary(s)), color = Palette.text, fontSize = 13.sp)
                    badges(s).takeIf { it.isNotEmpty() }?.let { Text(para(com.vanaccess.bms.text.Bidi.joinAll(it)), color = Palette.orange, fontSize = 12.sp) }
                }
            }
        }
        DetailCard(Modifier.weight(1.3f)) {
            val s = picked
            if (s == null) { Text(Tr["noReport"], color = Palette.grey); return@DetailCard }
            val (icon, label, color) = kind(s)
            Text("$icon " + Res.s(label) + "  " + iso(DATE.format(Date(s.startMs))), color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(when (s.data.optString("source")) { "desktop" -> Tr["fromPc"]; "simulation" -> Tr["simCharge"]; else -> Tr["fromPhone"] }, color = Palette.grey, fontSize = 12.sp)
            badges(s).forEach { Text(para(it), color = Palette.orange, fontSize = 13.sp) }
            Spacer(Modifier.height(8.dp))
            Rows(rows(s) + costRows(s, monitor.settings))
            Spacer(Modifier.height(10.dp))
            advice(s, monitor).forEach { r -> Text(Res.advice(r), color = levelColor(r.level), fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp)) }
            val pts = remember(s) { runCatching { monitor.reports().points(s.id) }.getOrDefault(emptyList()) }
            if (pts.size >= 3) {
                Text(Res.s("rep_chart"), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                PointsChart(pts, charge = s.type == Session.TYPE_CHARGE)
            }
        }
    }
}

@Composable
private fun DetailCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxHeight().background(Palette.card, RoundedCornerShape(18.dp)).padding(16.dp).verticalScroll(rememberScrollState()), content = content)

@Composable
private fun Rows(rows: List<Pair<String, String>>) = rows.forEach { (k, v) ->
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(k, color = Palette.grey, modifier = Modifier.weight(1f))
        Text(para(v), color = Palette.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.3f))
    }
}

/** Energy cost of one report, and the same trip with fuel (prices from the phone or Settings › Prefs). */
internal fun costRows(s: Session, set: Settings): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    val p = set.pricesAt(s.startMs)   // the prices of its day: a later change does not rewrite it
    if (!p.hasEnergy || s.d("wh") <= 0) return out
    out += Res.s("cost_row") to f("%.2f %s", EnergyCost.cost(s.d("wh"), p.kwh), p.currency)
    if (s.type != Session.TYPE_CHARGE && !s.data.isNull("wh_per_km") && s.d("distance_km") > 0) {
        out += Res.s("cost_per_km") to f("%.3f %s", EnergyCost.perKm(s.d("wh_per_km"), p.kwh), p.currency)
        if (p.hasFuel) out += Res.s("cost_fuel") to f("%.2f %s", EnergyCost.fuel(s.d("distance_km"), p.l100, p.fuel), p.currency)
    }
    return out
}

// ── Daily ─────────────────────────────────────────────────────────────────

@Composable
private fun DailyList(all: List<Session>, monitor: Monitor) {
    val days = remember(all) { runCatching { DayReport.all(all) }.getOrDefault(emptyList()) }
    var picked by remember(days) { mutableStateOf(days.firstOrNull()) }
    val dayFmt = SimpleDateFormat("EEEE", Tr.locale)
    val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(days) { d ->
                Column(Modifier.fillMaxWidth().background(if (d == picked) Palette.tile else Palette.card, RoundedCornerShape(14.dp))
                    .clickable { picked = d }.padding(12.dp)) {
                    Text("📅 " + dayFmt.format(Date(d.startMs)) + "  " + iso(dateFmt.format(Date(d.startMs))), color = Palette.blue, fontWeight = FontWeight.Bold)
                    Text(para(line(Res.plural("day_trips", d.tripCount), if (d.km > 0) f("%.2f km", d.km) else null, if (d.wh > 0) f("%.0f Wh", d.wh) else null,
                        if (d.chargeCount > 0) Res.plural("day_charges", d.chargeCount) else null)), color = Palette.text, fontSize = 13.sp)
                }
            }
        }
        DetailCard(Modifier.weight(1.3f)) {
            val d = picked ?: return@DetailCard
            Text("📅 " + Res.s("day_title") + "  " + iso(dateFmt.format(Date(d.startMs))), color = Palette.blue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(Res.s("day_24h"), color = Palette.grey, fontSize = 12.sp)
            if (d.partial) Text("⚠ " + Res.s("day_partial"), color = Palette.orange, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Rows(dayRows(d, monitor.settings))
            if (d.trips.isNotEmpty() || d.charges.isNotEmpty()) {
                Text(Res.s("day_list"), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                val hm = SimpleDateFormat("HH:mm", Locale.US)
                (d.trips + d.charges).sortedBy { it.startMs }.forEach { s ->
                    val (icon, label, color) = kind(s)
                    Text("$icon " + Res.s(label) + "  " + range(hm.format(Date(s.startMs)), hm.format(Date(s.endMs))) + "   " + para(summary(s)),
                        color = color, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

private fun dayRows(d: DayReport, set: Settings): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    fun row(k: String, v: String) { rows += Res.s(k) to v }
    if (d.tripCount > 0 || d.km > 0) {
        row("rep_trips", "${d.tripCount}")
        if (d.km > 0) row("rep_distance", f("%.2f km", d.km))
        row("rep_duration", Res.dur(d.totalS))
        if (d.km > 0) { row("rep_moving", Res.dur(d.ridingS)); row("rep_max_speed", f("%.0f km/h", d.maxSpeed)); row("rep_avg_speed", f("%.1f km/h", d.avgSpeed)) }
    }
    if (d.wh > 0) row("rep_energy", line(f("%.2f Ah", d.ah), f("%.0f Wh", d.wh)))
    d.whPerKm?.let { row("rep_consumption", f("%.1f Wh/km", it)) }
    if (d.regenWh >= 0.5) row("regen_energy", line(f("%.0f Wh", d.regenWh), RegenMeter.sharePct(d.regenWh, d.wh)?.let { f("%.1f%%", it) },
        d.whPerKm?.takeIf { it > 0 }?.let { f("≈ %.2f km", d.regenWh / it) }))
    if (d.socStart != null && d.socEnd != null) row("day_soc_row", range("${d.socStart}%", "${d.socEnd}%"))
    if (d.standbyS >= 60) row("standby_day", line(Res.dur(d.standbyS), f("%.1f Wh", d.standbyWh)))
    if (d.socUsed >= 0.5) row("day_used", f("%.0f%%", d.socUsed))
    if (d.unrecorded > 0) row("day_unrecorded", "${d.unrecorded}%")
    if (d.socCharged > 0) row("day_soc_charged", "${d.socCharged}%")
    if (d.chargeCount > 0 || d.chargedAh > 0) row("day_charged", line("${d.chargeCount}", f("%.2f Ah", d.chargedAh), f("%.0f Wh", d.chargedWh)))
    if (d.trips.isNotEmpty()) { row("rep_hard_accel", "${d.hardAccel}"); row("day_shocks", "${d.shocks}") }
    if (d.maxCurrent > 0) row("rep_max_current", f("%.1f A", d.maxCurrent))
    if (d.minCellV > 0) row("rep_min_cell", Res.s("cell_at", d.minCellV, d.minCellIdx))
    d.maxTemp?.let { row("rep_max_temp", f("%.0f °C", it)) }
    val p = set.pricesAt(d.startMs)
    if (p.hasEnergy && d.wh > 0) {
        row("cost_row", f("%.2f %s", EnergyCost.cost(d.wh, p.kwh), p.currency))
        if (d.km > 0 && p.hasFuel) row("cost_fuel", f("%.2f %s", EnergyCost.fuel(d.km, p.l100, p.fuel), p.currency))
    }
    return rows
}

// ── Consumption over time, against fuel ──────────────────────────────────

/** Trips grouped by period: distance, energy, cost, and what the same distance costs in fuel. */
@Composable
private fun ConsumptionPage(all: List<Session>, monitor: Monitor) {
    val set = monitor.settings
    var days by remember { mutableStateOf(30) }
    val now = System.currentTimeMillis()
    val trips = all.filter { it.type == Session.TYPE_TRIP && (days == 0 || it.startMs >= now - days * 86_400_000L) }
    val charges = all.filter { it.type == Session.TYPE_CHARGE && (days == 0 || it.startMs >= now - days * 86_400_000L) }
    DetailCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30, 365, 0).forEach { n ->
                FilterChip(selected = days == n, onClick = { days = n }, label = { Text(if (n == 0) Res.s("rep_all") else String.format(java.util.Locale.US, Tr["consDays"], n)) })
            }
        }
        Spacer(Modifier.height(10.dp))
        val km = trips.sumOf { it.d("distance_km") }
        val wh = trips.sumOf { it.d("wh") }
        val withKm = trips.filter { it.d("distance_km") > 0 && !it.b("energy_partial") }
        val whKm = withKm.sumOf { it.d("distance_km") }.takeIf { it > 0 }?.let { withKm.sumOf { t -> t.d("wh") } / it }
        val regen = trips.sumOf { it.d("regen_wh") }
        val rows = mutableListOf<Pair<String, String>>()
        rows += Res.s("rep_trips") to "${trips.size}"
        rows += Res.s("rep_distance") to f("%.1f km", km)
        rows += Res.s("rep_energy") to line(f("%.0f Wh", wh), f("%.2f kWh", wh / 1000))
        whKm?.let { rows += Res.s("rep_consumption") to f("%.1f Wh/km", it) }
        if (regen >= 0.5) rows += Res.s("regen_energy") to line(f("%.0f Wh", regen), RegenMeter.sharePct(regen, wh)?.let { f("%.1f%%", it) })
        rows += Res.s("rep_charges") to line("${charges.size}", f("%.1f Ah", charges.sumOf { it.d("ah") }), f("%.0f Wh", charges.sumOf { it.d("wh") }))
        // each trip at the prices of its day
        val priced = trips.map { it to set.pricesAt(it.startMs) }
        val cur = set.pricesAt(now).currency
        if (priced.any { it.second.hasEnergy }) {
            val elec = priced.sumOf { (t, p) -> if (p.hasEnergy) EnergyCost.cost(t.d("wh"), p.kwh) else 0.0 }
            rows += Res.s("cost_row") to f("%.2f %s", elec, cur)
            if (km > 0) rows += Res.s("cost_per_km") to f("%.3f %s", elec / km, cur)
            if (priced.any { it.second.hasFuel } && km > 0) {
                val fuel = priced.sumOf { (t, p) -> if (p.hasFuel) EnergyCost.fuel(t.d("distance_km"), p.l100, p.fuel) else 0.0 }
                val litres = priced.sumOf { (t, p) -> t.d("distance_km") * p.l100 / 100 }
                rows += Tr["consFuel"] to line(f("%.2f %s", fuel, cur), f("%.1f L", litres))
                rows += Tr["consSaved"] to f("%.2f %s", fuel - elec, cur)
            }
        }
        Rows(rows)
        Text(Tr["consAtTheirPrices"], color = Palette.grey, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        if (set.priceKwh <= 0 || set.fuelPrice <= 0) Text(Tr["consSetPrices"], color = Palette.grey, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        // Month by month
        val months = trips.groupBy { SimpleDateFormat("yyyy-MM", Locale.US).format(Date(it.startMs)) }.toSortedMap(compareByDescending { it })
        if (months.size > 1) {
            Text(Tr["consMonths"], color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
            months.forEach { (m, l) ->
                val mkm = l.sumOf { it.d("distance_km") }; val mwh = l.sumOf { it.d("wh") }
                val pr = l.map { it to set.pricesAt(it.startMs) }
                val me = pr.sumOf { (t, p) -> if (p.hasEnergy) EnergyCost.cost(t.d("wh"), p.kwh) else 0.0 }
                val mf = pr.sumOf { (t, p) -> if (p.hasFuel) EnergyCost.fuel(t.d("distance_km"), p.l100, p.fuel) else 0.0 }
                Rows(listOf(iso(m) to line(f("%.1f km", mkm), f("%.0f Wh", mwh),
                    if (me > 0) f("%.2f %s", me, cur) else null, if (mf > 0) "⛽ " + f("%.2f %s", mf, cur) else null)))
            }
        }
    }
}

// ── Chart ─────────────────────────────────────────────────────────────────

/** Charge (green) and current (blue: drawn, orange: charged or recovered) over the report. */
@Composable
private fun PointsChart(pts: List<Point>, charge: Boolean) {
    Column {
        Canvas(Modifier.fillMaxWidth().height(180.dp).background(Palette.tile, RoundedCornerShape(12.dp)).padding(10.dp)) {
            val t0 = pts.first().tMs; val t1 = pts.last().tMs.coerceAtLeast(t0 + 1)
            fun x(t: Long) = (t - t0).toFloat() / (t1 - t0) * size.width
            // grid
            for (k in 0..4) drawLine(Color(0x22FFFFFF), Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4))
            val maxA = pts.maxOf { kotlin.math.abs(it.i) }.coerceAtLeast(1.0)
            val zero = if (charge) size.height else size.height * 0.5f
            val bar = Path()
            pts.forEach { p ->
                val y = if (charge) size.height - (p.i / maxA * size.height).toFloat() else zero - (p.i / maxA * size.height / 2).toFloat()
                drawLine(if (p.i >= 0) Color(0xFFF28B3C) else Color(0x994DA3FF), Offset(x(p.tMs), zero), Offset(x(p.tMs), y), strokeWidth = 1.5f)
            }
            val soc = Path()
            pts.forEachIndexed { k, p ->
                val y = size.height - p.soc / 100f * size.height
                if (k == 0) soc.moveTo(x(p.tMs), y) else soc.lineTo(x(p.tMs), y)
            }
            drawPath(soc, Color(0xFF43D17A), style = Stroke(width = 3f))
            bar.close()
        }
        Text(para(line("— " + Tr["chartSoc"], "▮ " + Tr["chartCurrent"])), color = Palette.grey, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
