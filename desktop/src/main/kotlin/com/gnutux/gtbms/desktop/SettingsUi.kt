// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import androidx.compose.foundation.background
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

private data class Section(val key: String, val icon: String, val color: Color)

private val SECTIONS = listOf(
    Section("secSource", "🔌", Color(0xFF2ECC71)), Section("secAlarms", "⏰", Color(0xFFF5A623)),
    Section("secPhone", "📱", Color(0xFF7CB342)), Section("secData", "🗂", Color(0xFF4FC3F7)),
    Section("secPrefs", "🎛", Color(0xFF9C8CFF)), Section("secAbout", "ℹ", Color(0xFF90A4AE)),
)

/** The phone's settings layout: a grid of sections, each opening on its own page. */
@Composable
fun SettingsScreen(monitor: Monitor, onImport: () -> Unit, initial: String? = null) {
    var open by remember { mutableStateOf(initial) }
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
    Page {
        Text(String.format(Tr["srcNow"], st.source.ifEmpty { "—" }), color = Palette.text)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { monitor.startReplay() }) { Text(Tr["srcRest"]) }
            OutlinedButton(onClick = { monitor.startChargeSim() }) { Text(Tr["srcCharge"]) }
            OutlinedButton(onClick = {}, enabled = false) { Text(Tr["srcBle"]) }
        }
        Text(Tr["srcBleSoon"], color = Palette.grey, fontSize = 13.sp)
        val s = monitor.settings
        if (s.battery.isNotEmpty()) Text(String.format(Tr["batteryFrom"], (s.batteryName.ifEmpty { s.battery })), color = Palette.text)
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
        if (s.lastSyncMs > 0) Text(String.format(Tr["syncLast"], iso(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(s.lastSyncMs)))), color = Palette.grey, fontSize = 13.sp)
    }
}

@Composable
private fun DataPage(monitor: Monitor, onImport: () -> Unit) {
    var msg by remember { mutableStateOf("") }
    Page {
        Button(onClick = onImport) { Text(Tr["import"]) }
        OutlinedButton(onClick = {
            val f = File(System.getProperty("user.home"), "GT-BMS-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".csv")
            msg = runCatching { f.writeText(com.vanaccess.bms.reports.Exporter.csv(monitor.store.list(5000))); String.format(Tr["exported"], f.path) }.getOrElse { it.message ?: "" }
        }) { Text(Tr["exportCsv"]) }
        if (msg.isNotEmpty()) Text(msg, color = Palette.green)
    }
}

@Composable
private fun PrefsPage(monitor: Monitor) {
    val s = monitor.settings
    var lang by remember { mutableStateOf(s.lang) }
    Page {
        listOf("" to Tr["langSystem"], "ar" to "العربية", "en" to "English", "fr" to "Français").forEach { (tag, label) ->
            Row(Modifier.clickable { lang = tag; s.lang = tag }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = lang == tag, onClick = { lang = tag; s.lang = tag }); Text(label, color = Palette.text)
            }
        }
        Text(Tr["langRestart"], color = Palette.grey, fontSize = 13.sp)
    }
}

@Composable
private fun AboutPage() {
    val v = remember { runCatching { Monitor::class.java.getResource("/gtbms-desktop-version.txt")!!.readText().trim() }.getOrDefault("?") }
    Page {
        Text("GT-BMS", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(String.format(Tr["version"], v), color = Palette.grey)
        Text(Tr["about"], color = Palette.text)
        Text(iso("https://salehgnutux.github.io/GT-BMS/"), color = Palette.blue)
    }
}
