// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The settings section left open, for the whole session. */
object SettingsMemory { var open: String? = null }

private data class Section(val key: String, val icon: String, val color: Color)

private val SECTIONS = listOf(
    Section("secSource", "🔌", Color(0xFF2ECC71)), Section("secAlarms", "⏰", Color(0xFFF5A623)),
    Section("secPhone", "📱", Color(0xFF7CB342)), Section("secData", "🗂", Color(0xFF4FC3F7)),
    Section("secPrefs", "🎛", Color(0xFF9C8CFF)), Section("secAbout", "ℹ", Color(0xFF90A4AE)),
)

/** The phone's settings layout: a grid of sections, each opening on its own page. */
@Composable
fun SettingsScreen(monitor: Monitor, onImport: () -> Unit, initial: String? = null) {
    // The open section is kept while visiting Live and back
    var open by remember { mutableStateOf(initial ?: SettingsMemory.open) }
    LaunchedEffect(open) { SettingsMemory.open = open }
    val sec = SECTIONS.firstOrNull { it.key == open }
    if (sec == null) {
        LazyVerticalGrid(GridCells.Adaptive(220.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(SECTIONS) { s ->
                Column(Modifier.background(Palette.card, RoundedCornerShape(18.dp)).clickable { open = s.key }.padding(16.dp).heightIn(min = 96.dp)) {
                    Box(Modifier.size(44.dp).background(s.color.copy(alpha = 0.18f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Text(s.icon, fontSize = 22.sp) }
                    Text(Tr[s.key], color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Text(Tr[s.key + "Sub"], color = Palette.grey, fontSize = 13.sp)
                }
            }
        }
        return
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { open = null }) { Text((if (Tr.rtl) "→ " else "← ") + Tr["back"]) }
            Text(Tr[sec.key], color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        when (sec.key) {
            "secSource" -> SourcePage(monitor)
            "secAlarms" -> AlarmsPage(monitor)
            "secPhone" -> PhonePage(monitor)
            "secData" -> DataPage(monitor, onImport)
            "secPrefs" -> PrefsPage(monitor)
            else -> AboutPage()
        }
    }
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().background(Palette.card, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.text, modifier = Modifier.weight(1f))
        Switch(checked = v, onCheckedChange = { v = it; onChange(it) })
    }
}

@Composable
private fun SourcePage(monitor: Monitor) {
    val st by monitor.state.collectAsState()
    val s = monitor.settings
    var found by remember { mutableStateOf<List<BlueZ.Found>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanMsg by remember { mutableStateOf("") }
    Page {
        Text(String.format(java.util.Locale.US, Tr["srcNow"], st.source.ifEmpty { "—" }), color = Palette.text, fontWeight = FontWeight.Bold)
        st.problem?.let { Text(it, color = Palette.orange) }
        // The battery itself, over this computer's Bluetooth
        Text(Tr["srcBle"], color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Text(Tr["bleHow"], color = Palette.grey, fontSize = 13.sp)
        if (s.battery.isNotEmpty()) Text(String.format(java.util.Locale.US, Tr["batteryFrom"], s.batteryName.ifEmpty { s.battery }) + (if (s.battery in s.batteryName) "" else "  " + iso(s.battery)), color = Palette.text)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { monitor.startBattery() }, enabled = s.battery.isNotEmpty() && !st.viaBle) { Text(Tr["srcBle"]) }
            OutlinedButton(onClick = {
                scanning = true; scanMsg = ""
                Thread {
                    monitor.scanBatteries(12) { found = it }.onSuccess { found = it }.onFailure { scanMsg = if (it is BlueZ.NoAdapter) Tr["bleNoAdapter"] else it.message ?: "" }
                    scanning = false
                }.apply { isDaemon = true; start() }
            }, enabled = !scanning) { Text(Tr["bleScan"]) }
            if (st.viaBle || st.viaPhone) OutlinedButton(onClick = { monitor.showLast() }) { Text(Tr["stop"]) }
        }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (scanMsg.isNotEmpty()) Text(scanMsg, color = Palette.orange)
        found.forEach { f ->
            Row(Modifier.fillMaxWidth().background(Palette.tile, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name.ifEmpty { "—" }, color = Palette.text, fontWeight = FontWeight.Bold)
                    Text(para(com.vanaccess.bms.text.Bidi.join(f.address, f.rssi?.let { "$it dBm" }, if (f.connected) "✓" else null)), color = Palette.grey, fontSize = 12.sp)
                }
                TextButton(onClick = {
                    s.battery = f.address; if (f.name.isNotBlank()) s.batteryName = f.name
                    monitor.startBattery(f.address)
                }) { Text(Tr["bleUse"]) }
            }
        }
        HorizontalDivider(color = Palette.tile, modifier = Modifier.padding(vertical = 6.dp))
        // Through the phone, or a recording to try the screens without a battery
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { monitor.phone.watchAgain() }, enabled = monitor.phone.last != null && !st.viaPhone) { Text(Tr["srcPhoneBtn"]) }
            OutlinedButton(onClick = { monitor.startReplay() }) { Text(Tr["srcRest"]) }
            OutlinedButton(onClick = { monitor.startChargeSim() }) { Text(Tr["srcCharge"]) }
        }
        if (monitor.phone.last == null) Text(Tr["srcPhoneNeed"], color = Palette.grey, fontSize = 13.sp)
        Text(s.limits.cellOvMv?.let { String.format(Locale.US, Tr["limitsKnown"], it / 1000.0) } ?: Tr["limitsUnknown"], color = Palette.grey, fontSize = 13.sp)
    }
}

@Composable
private fun AlarmsPage(monitor: Monitor) {
    val s = monitor.settings
    Page {
        Toggle(Tr["alHigh"], s.highEnabled) { s.highEnabled = it }
        var high by remember { mutableStateOf(s.highSoc) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(80, 90, 100).forEach { pct -> FilterChip(selected = high == pct, onClick = { high = pct; s.highSoc = pct; monitor.onPhoneValues() }, label = { Text(Res.s("limit_preset", pct)) }) }
        }
        Toggle(Tr["alLow"], s.lowEnabled) { s.lowEnabled = it }
        var low by remember { mutableStateOf(s.lowSoc) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(20, 25, 30, 40).forEach { pct -> FilterChip(selected = low == pct, onClick = { low = pct; s.lowSoc = pct }, label = { Text(Res.s("limit_preset", pct)) }) }
        }
        Toggle(Tr["alCell"], s.cellAlarm) { s.cellAlarm = it }
        HorizontalDivider(color = Palette.tile)
        Toggle(Tr["alSound"], s.sound) { s.sound = it }
        Toggle(Tr["alNotify"], s.systemNotify) { s.systemNotify = it }
        Button(onClick = { monitor.alarms.test(Alarms.Kind.HIGH) }) { Text(Tr["alTest"]) }
    }
}

@Composable
private fun PhonePage(monitor: Monitor) {
    val link = monitor.phone
    val ps by link.state.collectAsState()
    val s = monitor.settings
    var addr by remember { mutableStateOf(if (s.phoneHost.isNotEmpty()) "${s.phoneHost}:${s.phonePort}" else "") }
    var code by remember { mutableStateOf("") }
    fun host() = addr.substringBefore(':').trim()
    fun port() = addr.substringAfter(':', "").trim().toIntOrNull() ?: 0
    val ready = host().isNotEmpty() && port() > 0 && code.count { it.isDigit() } == 6 && !ps.busy
    Page {
        Text(Tr["syncTitle"], color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(Tr["syncHow"], color = Palette.grey, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { link.discover() }, enabled = !ps.busy) { Text(Tr["syncSearch"]) }
            ps.found.forEach { f -> FilterChip(selected = addr == "${f.host}:${f.port}", onClick = { addr = "${f.host}:${f.port}" }, label = { Text(f.name + "  " + iso(f.host)) }) }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(addr, { addr = it.trim() }, label = { Text(Tr["syncAddr"]) }, singleLine = true, modifier = Modifier.weight(2f))
                OutlinedTextField(code, { v -> code = v.filter { it.isDigit() }.take(6) }, label = { Text(Tr["syncCode"]) }, singleLine = true, modifier = Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { link.sync(host(), port(), code) }, enabled = ready) { Text(Tr["syncNow"]) }
            OutlinedButton(onClick = { link.askYield(host(), port(), code, 60) }, enabled = ready) { Text(Tr["yieldAsk"]) }
            OutlinedButton(onClick = { link.giveBack(host(), port(), code) }, enabled = ready) { Text(Tr["yieldBack"]) }
        }
        if (ps.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (ps.message.isNotEmpty()) Text(ps.message, color = if (ps.ok) Palette.green else Palette.orange, fontWeight = FontWeight.Bold)
        if (s.lastSyncMs > 0) Text(String.format(java.util.Locale.US, Tr["syncLast"], iso(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(s.lastSyncMs)))), color = Palette.grey, fontSize = 13.sp)
    }
}

@Composable
private fun DataPage(monitor: Monitor, onImport: () -> Unit) {
    var msg by remember { mutableStateOf("") }
    Page {
        Button(onClick = onImport) { Text(Tr["import"]) }
        OutlinedButton(onClick = {
            val f = File(System.getProperty("user.home"), "GT-BMS-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".csv")
            msg = runCatching { f.writeText(com.vanaccess.bms.reports.Exporter.csv(monitor.store.list(5000))); String.format(java.util.Locale.US, Tr["exported"], f.path) }.getOrElse { it.message ?: "" }
        }) { Text(Tr["exportCsv"]) }
        if (msg.isNotEmpty()) Text(msg, color = Palette.green)
    }
}

@Composable
private fun PrefsPage(monitor: Monitor) {
    val s = monitor.settings
    var lang by remember { mutableStateOf(s.lang) }
    var design by remember { mutableStateOf(s.design) }
    Page {
        Text(Tr["langTitle"], color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to Tr["langSystem"], "ar" to "العربية", "en" to "English", "fr" to "Français").forEach { (tag, label) ->
                FilterChip(selected = lang == tag, onClick = { lang = tag; s.lang = tag }, label = { Text(label) })
            }
        }
        Text(Tr["langRestart"], color = Palette.grey, fontSize = 13.sp)
    }
    // The battery drawing on the Live screen: the phone's designs, previewed charging
    Page {
        Text(Tr["design"], color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        LazyVerticalGrid(GridCells.Adaptive(170.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.heightIn(max = 760.dp)) {
            items(Gauges.DESIGNS) { (id, label) ->
                Column(Modifier.background(if (design == id) Palette.tile else Palette.bg, RoundedCornerShape(14.dp))
                    .border(2.dp, if (design == id) Palette.blue else Color.Transparent, RoundedCornerShape(14.dp))
                    .clickable { design = id; s.design = id }.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.height(130.dp), contentAlignment = Alignment.Center) { BatteryGauge(id, 64, 6.0, height = 110.dp) }
                    Text(Res.s(label).substringBefore(" — "), color = if (design == id) Palette.blue else Palette.text, fontWeight = FontWeight.Bold)
                    Text(Res.s(label).substringAfter(" — ", ""), color = Palette.grey, fontSize = 11.sp, maxLines = 3)
                }
            }
        }
    }
    // Energy cost and the fuel comparison (also taken from the phone)
    Page {
        Text(Res.s("cost_title"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(Res.s("cost_desc"), color = Palette.grey, fontSize = 13.sp)
        fun num(v: Double) = if (v > 0) v.toString().removeSuffix(".0") else ""
        var price by remember { mutableStateOf(num(s.priceKwh)) }
        var cur by remember { mutableStateOf(s.currency) }
        var fuel by remember { mutableStateOf(num(s.fuelPrice)) }
        var l100 by remember { mutableStateOf(num(s.fuelL100)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(price, { price = it; s.priceKwh = it.replace(',', '.').toDoubleOrNull() ?: 0.0 }, label = { Text(Res.s("cost_price_hint")) }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(cur, { cur = it.take(8); s.currency = cur }, label = { Text(Res.s("cost_currency_hint")) }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(fuel, { fuel = it; s.fuelPrice = it.replace(',', '.').toDoubleOrNull() ?: 0.0 }, label = { Text(Res.s("cost_fuel_price_title")) }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(l100, { l100 = it; s.fuelL100 = it.replace(',', '.').toDoubleOrNull() ?: 0.0 }, label = { Text(Res.s("cost_fuel_l100_title")) }, singleLine = true, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun AboutPage() {
    val v = remember { runCatching { Monitor::class.java.getResource("/gtbms-desktop-version.txt")!!.readText().trim() }.getOrDefault("?") }
    Page {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource("icon.png"), "GT-BMS",
                Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)))
            Column {
                Text("GT-BMS", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(String.format(java.util.Locale.US, Tr["version"], v), color = Palette.grey)
            }
        }
        Text(Tr["about"], color = Palette.text)
        Text(iso("https://salehgnutux.github.io/GT-BMS/"), color = Palette.blue)
        Text(iso("https://github.com/SalehGNUTUX/GT-BMS"), color = Palette.blue)
    }
}
