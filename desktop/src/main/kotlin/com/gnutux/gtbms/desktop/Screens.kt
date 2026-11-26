// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanaccess.bms.reports.Advisor
import com.vanaccess.bms.reports.ChargeAdvice
import com.vanaccess.bms.reports.ChargePredictor
import com.vanaccess.bms.reports.Session
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object Palette {
    val bg = Color(0xFF12141C); val card = Color(0xFF1E2130); val tile = Color(0xFF262A3B)
    val text = Color(0xFFE6E9F2); val grey = Color(0xFF9BA3B8)
    val green = Color(0xFF43D17A); val blue = Color(0xFF4DA3FF); val orange = Color(0xFFF5B83D); val red = Color(0xFFF0544F)
}

private fun f(p: String, vararg a: Any) = String.format(Locale.US, p, *a)
private fun socColor(soc: Int) = when { soc >= 50 -> Palette.green; soc >= 20 -> Palette.orange; else -> Palette.red }
/** Numbers and units keep their left-to-right order inside Arabic text. */
private fun iso(s: String) = "⁦$s⁩"
/** A range: each end whole, the order following the interface (start on the right in Arabic). */
private fun range(a: String, b: String) = iso(a) + " – " + iso(b)

@Composable
fun App(monitor: Monitor, initialTab: Int = 0, onImport: () -> Unit = {}, overlay: Boolean = false) {
    val st by monitor.state.collectAsState()
    val ring by monitor.alarms.ring.collectAsState()
    var tab by remember { mutableStateOf(initialTab) }
    MaterialTheme(colorScheme = darkColorScheme(primary = Palette.blue, background = Palette.bg, surface = Palette.card)) {
        CompositionLocalProvider(LocalLayoutDirection provides if (Tr.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Box(Modifier.fillMaxSize().background(Palette.bg)) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Header(st, monitor, onImport)
                    Spacer(Modifier.height(12.dp))
                    TabRow(selectedTabIndex = tab, containerColor = Palette.bg, contentColor = Palette.blue) {
                        listOf("live", "cells", "reports").forEachIndexed { i, k ->
                            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(Tr[k], fontSize = 16.sp) })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    when {
                        tab == 2 -> ReportsScreen(monitor)
                        st.basic == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(Tr["waiting"], color = Palette.grey) }
                        tab == 0 -> LiveScreen(st, monitor)
                        else -> CellsScreen(st.cells, st.basic!!.balanceBits)
                    }
                }
                // In the window, the alarm is its own window over everything (see Main); a picture shows it here
                if (overlay && tab != 2) ring?.let { r ->
                    Box(Modifier.fillMaxSize().background(Color(0xE60F1118)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
                        AlarmCard(r, monitor.alarms)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(st: Monitor.State, monitor: Monitor, onImport: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(Tr["app"], color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(Tr["readonly"], color = Palette.grey, fontSize = 12.sp)
        }
        if (st.source.isNotEmpty()) Text(st.source, color = Palette.orange, fontSize = 13.sp,
            modifier = Modifier.border(1.dp, Palette.orange, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
        OutlinedButton(onClick = { monitor.startReplay() }) { Text(Tr["srcRest"]) }
        OutlinedButton(onClick = { monitor.startChargeSim() }) { Text(Tr["srcCharge"]) }
        Button(onClick = onImport) { Text(Tr["import"]) }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.background(Palette.card, RoundedCornerShape(18.dp)).padding(16.dp), content = content)

@Composable
private fun Metric(label: String, value: String, color: Color = Palette.text, modifier: Modifier = Modifier) =
    Column(modifier.background(Palette.tile, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Text(label, color = Palette.grey, fontSize = 13.sp)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }

@Composable
private fun LiveScreen(st: Monitor.State, monitor: Monitor) {
    val b = st.basic ?: return
    val state = when { b.current > 0.1 -> "st_charging" to Palette.green; b.current < -0.1 -> "st_discharging" to Palette.blue; else -> "st_idle" to Palette.grey }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.weight(1f)) {
                Text(Tr["soc"], color = Palette.grey, fontSize = 14.sp)
                Text("${b.soc}%", color = socColor(b.soc), fontSize = 64.sp, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(progress = { b.soc / 100f }, color = socColor(b.soc), trackColor = Palette.tile, modifier = Modifier.fillMaxWidth().height(10.dp))
                Spacer(Modifier.height(10.dp))
                Text(Res.s(state.first), color = state.second, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric(Tr["voltage"], f("%.2f V", b.packVoltage), modifier = Modifier.weight(1f))
                    Metric(Tr["current"], f("%+.1f A", b.current), state.second, Modifier.weight(1f))
                    Metric(Tr["power"], f("%.0f W", abs(b.powerWatts)), modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric(Tr["remain"], f("%.1f Ah", b.remainCapacity), modifier = Modifier.weight(1f))
                    Metric(Tr["full"], f("%.1f Ah", b.fullCapacity), modifier = Modifier.weight(1f))
                    Metric(Tr["cycles"], "${b.cycleCount}", modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric(Tr["temp"], b.temperatures.joinToString("  ") { f("%.0f°C", it) }.ifEmpty { "—" }, modifier = Modifier.weight(1f))
                    Metric(Tr["mos"], b.mosTemperatures.ifEmpty { listOfNotNull(b.mosTemperature) }.joinToString("  ") { f("%.0f°C", it) }.ifEmpty { "—" }, modifier = Modifier.weight(1f))
                    Metric(Tr["delta"], if (st.cells.size > 1) f("%.0f mV", (st.cells.max() - st.cells.min()) * 1000) else "—", modifier = Modifier.weight(1f))
                }
            }
        }
        ChargeCard(st, monitor)
    }
}

/** While charging: since when, how much, what comes next (the phone's estimate), and the alarm's target. */
@Composable
private fun ChargeCard(st: Monitor.State, monitor: Monitor) {
    val b = st.basic ?: return
    val live = st.charge
    if (live == null && b.current <= 0.1) return
    Card(Modifier.fillMaxWidth()) {
        Text(Tr["charge"], color = Palette.green, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        live?.let {
            val hm = SimpleDateFormat("HH:mm", Locale.US).format(Date(it.startMs))
            Text(Res.s(if (it.stopped) "live_charge_stopped" else "live_charge", hm, it.ah), color = Palette.text, fontSize = 15.sp)
        }
        val p = monitor.predict()
        val eta = when {
            p == null -> null
            p.kind == ChargePredictor.Kind.CUT_SOON -> if (p.hours < 2 / 60.0) Res.s("eta_cut_now", p.cell) else Res.s("eta_cut_soon", Math.ceil(p.hours * 60).toInt(), p.cell)
            else -> listOfNotNull(p.to80h?.let { Res.s("eta_80", Res.dur(it * 3600)) },
                if (p.kind == ChargePredictor.Kind.CUT) Res.s("eta_end", Res.dur(p.hours * 3600)) else Res.s("eta_100", Res.dur(p.hours * 3600))).joinToString("  •  ")
        }
        eta?.let { Text(it, color = if (p?.kind == ChargePredictor.Kind.CUT_SOON) Palette.orange else Palette.blue, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(10.dp))
        var target by remember { mutableStateOf(if (monitor.settings.highEnabled) monitor.settings.highSoc else 0) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Tr["target"], color = Palette.grey)
            listOf(80, 90, 100, 0).forEach { pct ->
                FilterChip(selected = target == pct, onClick = {
                    target = pct
                    if (pct == 0) monitor.settings.highEnabled = false else { monitor.settings.highEnabled = true; monitor.settings.highSoc = pct }
                }, label = { Text(if (pct == 0) Tr["off"] else Res.s("limit_preset", pct)) })
            }
        }
    }
}

@Composable
private fun CellsScreen(cells: List<Double>, balance: Long) {
    if (cells.isEmpty()) return
    val mx = cells.max(); val mn = cells.min()
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric(Tr["highest"] + " #${cells.indexOf(mx) + 1}", f("%.3f V", mx), Palette.red, Modifier.weight(1f))
            Metric(Tr["lowest"] + " #${cells.indexOf(mn) + 1}", f("%.3f V", mn), Palette.blue, Modifier.weight(1f))
            Metric(Tr["delta"], f("%.0f mV", (mx - mn) * 1000), if ((mx - mn) * 1000 < 30) Palette.green else Palette.orange, Modifier.weight(1f))
            Metric(Tr["average"], f("%.3f V", cells.average()), modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Text(String.format(Locale.US, Tr["count"], cells.size), color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(GridCells.Adaptive(110.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(cells) { i, v ->
                val border = when (v) { mx -> Palette.red; mn -> Palette.blue; else -> Color.Transparent }
                Column(Modifier.background(Palette.tile, RoundedCornerShape(14.dp)).border(2.dp, border, RoundedCornerShape(14.dp)).padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("#${i + 1}" + if (balance and (1L shl i) != 0L) " ⚖" else "", color = Palette.grey, fontSize = 13.sp)
                    Text("${Math.round(v * 1000)}", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("mV", color = Palette.grey, fontSize = 12.sp)
                }
            }
        }
    }
}

// ── Reports ────────────────────────────────────────────────────────────────

private fun kind(s: Session) = when (s.type) {
    Session.TYPE_CHARGE -> Triple("🔌", "rep_charge", Palette.green)
    Session.TYPE_DISCHARGE -> Triple("🔋", "rep_discharge", Palette.orange)
    else -> Triple("🛵", "rep_trip", Palette.blue)
}

private fun summary(s: Session): String = when (s.type) {
    Session.TYPE_CHARGE -> listOf(range("${s.i("soc_start")}%", "${s.i("soc_end")}%"), Res.dur((s.endMs - s.startMs) / 1000.0),
        iso(f("+%.2f Ah", s.d("ah"))), iso(f("⌀ %.1f A", s.avgA))).joinToString("  •  ")
    Session.TYPE_DISCHARGE -> listOf(range("${s.i("soc_start")}%", "${s.i("soc_end")}%"), iso(f("−%.2f Ah", s.d("ah"))), iso(f("%.0f Wh", s.d("wh")))).joinToString("  •  ")
    else -> listOfNotNull(if (s.d("distance_km") > 0) iso(f("%.2f km", s.d("distance_km"))) else null, Res.dur((s.endMs - s.startMs) / 1000.0),
        iso(f("%.0f Wh", s.d("wh"))), if (!s.data.isNull("wh_per_km") && s.data.has("wh_per_km")) iso(f("%.1f Wh/km", s.d("wh_per_km"))) else null).joinToString("  •  ")
}

private fun rows(s: Session): List<Pair<String, String>> {
    val r = mutableListOf<Pair<String, String>>()
    fun row(k: String, v: String) { r += Res.s(k) to v }
    row("rep_duration", Res.dur((s.endMs - s.startMs) / 1000.0))
    row("rep_soc", range("${s.i("soc_start")}%", "${s.i("soc_end")}%"))
    row("rep_energy", iso(f("%.2f Ah  •  %.0f Wh", s.d("ah"), s.d("wh"))))
    if (s.type == Session.TYPE_CHARGE) {
        row("rep_avg_current", iso(f("%.1f A", s.avgA)))
        row("rep_max_v", iso(f("%.2f V", s.d("max_v"))))
        if (s.d("max_cell_v") > 0) row("rep_max_cell", Res.s("cell_at", s.d("max_cell_v"), s.i("max_cell_idx")))
        row("rep_max_delta", iso("${s.i("max_delta_mv")} mV"))
        if (s.data.has("stop_reason")) {
            val reason = s.data.optString("stop_reason")
            row("rep_stop", when (reason) {
                "protection" -> if (s.i("stop_max_idx") > 0) Res.s("stop_protection_cell", s.i("stop_max_idx"), s.d("stop_max_cell")) else Res.s("stop_protection")
                "charger" -> Res.s("stop_charger"); "lost" -> Res.s("stop_lost"); else -> Res.s("stop_interrupted")
            })
            val before = s.i("soc_before_stop"); val shown = s.i("soc_after_stop")
            row("rep_stop_soc", (if (shown - before >= 2) Res.s("soc_jump", before, shown) else Res.s("soc_plain", shown)) +
                (if (shown - before >= 2 && s.data.has("soc_counted")) " " + Res.s("soc_counted", s.i("soc_counted")) else ""))
            if (s.i("stop_max_idx") > 0) {
                row("rep_stop_max", Res.s("cell_at", s.d("stop_max_cell"), s.i("stop_max_idx")))
                row("rep_stop_min", Res.s("cell_at", s.d("stop_min_cell"), s.i("stop_min_idx")))
                row("rep_stop_delta", iso("${s.i("stop_delta_mv")} mV"))
            }
        }
    } else {
        if (s.d("distance_km") > 0) {
            row("rep_distance", iso(f("%.2f km", s.d("distance_km"))))
            row("rep_max_speed", iso(f("%.0f km/h", s.d("max_speed"))))
            row("rep_avg_speed", iso(f("%.1f km/h", s.d("avg_speed"))))
            if (!s.data.isNull("wh_per_km") && s.data.has("wh_per_km")) row("rep_consumption", iso(f("%.1f Wh/km", s.d("wh_per_km"))))
        }
        row("rep_max_current", iso(f("%.1f A", s.d("max_current"))))
        if (s.d("min_cell_v") > 0) row("rep_min_cell", Res.s("cell_at", s.d("min_cell_v"), s.i("min_cell_idx")))
        if (s.d("regen_wh") >= 0.05) row("regen_energy", iso(f("%.1f Wh", s.d("regen_wh"))))
    }
    if (!s.data.isNull("max_temp") && s.data.has("max_temp")) row("rep_max_temp", iso(f("%.0f °C", s.d("max_temp"))))
    return r
}

private fun advice(s: Session, monitor: Monitor): List<Advisor.Rec> {
    val lim = monitor.settings.limits
    return runCatching {
        when (s.type) {
            Session.TYPE_CHARGE -> Advisor.afterCharge(s, lim, null,
                lim.cellOvMv?.let { ChargeAdvice.from(lim.cells ?: 0, it)?.chargerPackV })
            Session.TYPE_DISCHARGE -> Advisor.afterDischarge(s, lim)
            else -> Advisor.afterTrip(s, lim, null, null)
        }
    }.getOrDefault(emptyList())
}

private fun levelColor(l: Advisor.Level) = when (l) {
    Advisor.Level.CRITICAL -> Palette.red; Advisor.Level.WARNING -> Palette.orange; Advisor.Level.INFO -> Palette.blue; Advisor.Level.OK -> Palette.green
}

@Composable
private fun ReportsScreen(monitor: Monitor) {
    val version by monitor.reportsVersion.collectAsState()
    val simulated = monitor.state.collectAsState().value.simulated
    val list = remember(version, simulated) { runCatching { monitor.reports().list() }.getOrDefault(emptyList()) }
    var picked by remember(version) { mutableStateOf(list.firstOrNull()) }
    if (list.isEmpty()) { Text(Res.s("rep_empty"), color = Palette.grey); return }
    val date = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list) { s ->
                val (icon, label, color) = kind(s)
                Column(Modifier.fillMaxWidth().background(if (s == picked) Palette.tile else Palette.card, RoundedCornerShape(14.dp))
                    .clickable { picked = s }.padding(12.dp)) {
                    Row { Text("$icon " + Res.s(label), color = color, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(iso(date.format(Date(s.startMs))), color = Palette.grey, fontSize = 12.sp) }
                    Text(summary(s), color = Palette.text, fontSize = 13.sp)
                }
            }
        }
        Column(Modifier.weight(1.3f).fillMaxHeight().background(Palette.card, RoundedCornerShape(18.dp)).padding(16.dp).verticalScroll(rememberScrollState())) {
            val s = picked
            if (s == null) { Text(Tr["noReport"], color = Palette.grey); return@Column }
            val (icon, label, color) = kind(s)
            Text("$icon " + Res.s(label) + "  " + iso(date.format(Date(s.startMs))), color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(when (s.data.optString("source")) { "desktop" -> Tr["fromPc"]; "simulation" -> Tr["simCharge"]; else -> Tr["fromPhone"] }, color = Palette.grey, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            rows(s).forEach { (k, v) ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(k, color = Palette.grey, modifier = Modifier.weight(1f))
                    Text(v, color = Palette.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.3f))
                }
            }
            Spacer(Modifier.height(10.dp))
            advice(s, monitor).forEach { r -> Text(Res.advice(r), color = levelColor(r.level), fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp)) }
        }
    }
}

// ── The ringing alarm ─────────────────────────────────────────────────────

/** The alarm in the app's design: what, how serious, and only its buttons stop it. */
@Composable
fun AlarmCard(r: Alarms.Ring, alarms: Alarms, modifier: Modifier = Modifier) {
    val accent = Color(r.level.color)
    MaterialTheme(colorScheme = darkColorScheme(primary = Palette.blue, background = Palette.bg, surface = Palette.card)) {
    CompositionLocalProvider(LocalLayoutDirection provides if (Tr.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(modifier.widthIn(max = 640.dp).background(Palette.card, RoundedCornerShape(24.dp)).border(2.dp, accent, RoundedCornerShape(24.dp)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(r.title, color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(iso(r.big), color = accent, fontSize = 72.sp, fontWeight = FontWeight.Bold)
            Text(Res.s(r.level.key), color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.border(2.dp, accent, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 6.dp))
            Spacer(Modifier.height(16.dp))
            Text(r.text, color = Palette.text, fontSize = 18.sp, textAlign = TextAlign.Center)
            r.note?.let { Text(it, color = Palette.grey, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp)) }
            Spacer(Modifier.height(22.dp))
            val snooze = r.snoozeMin
            if (snooze != null) {
                Button(onClick = { alarms.unplugged() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E9E5B), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(72.dp)) { Text(Res.s("away_done"), fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(10.dp))
                Button(onClick = { alarms.snooze() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3F55), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(Res.s("alarm_snooze", snooze), fontSize = 18.sp) }
            } else Button(onClick = { alarms.dismiss() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD9443F), contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(80.dp)) { Text(Res.s("alarm_stop"), fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        }
    }
    }
}
