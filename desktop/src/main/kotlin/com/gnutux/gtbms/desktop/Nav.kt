// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where the window is: the phone's layout (Live in the middle, Battery with its tabs, Settings). */
enum class Place { LIVE, BATTERY, SETTINGS }

@Composable
fun App(monitor: Monitor, initialPlace: Place = Place.LIVE, initialTab: Int = 0, overlay: Boolean = false, initialSection: String? = null, onImport: () -> Unit = {}) {
    val st by monitor.state.collectAsState()
    val ring by monitor.alarms.ring.collectAsState()
    var place by remember { mutableStateOf(initialPlace) }
    var tab by remember { mutableStateOf(initialTab) }
    MaterialTheme(colorScheme = darkColorScheme(primary = Palette.blue, background = Palette.bg, surface = Palette.card)) {
        CompositionLocalProvider(LocalLayoutDirection provides if (Tr.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Box(Modifier.fillMaxSize().background(Palette.bg)) {
                Column(Modifier.fillMaxSize()) {
                    Header(st, monitor)
                    if (place == Place.BATTERY) TabRow(selectedTabIndex = tab, containerColor = Palette.bg, contentColor = Palette.blue,
                        modifier = Modifier.padding(horizontal = 16.dp)) {
                        listOf("cells", "reports", "notifs").forEachIndexed { i, k -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(Tr[k], fontSize = 16.sp) }) }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                        val b = st.basic
                        when {
                            place == Place.SETTINGS -> SettingsScreen(monitor, onImport, initialSection)
                            place == Place.BATTERY && tab == 1 -> ReportsScreen(monitor)
                            place == Place.BATTERY && tab == 2 -> NotifsScreen(monitor)
                            b == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(Tr["waiting"], color = Palette.grey) }
                            place == Place.BATTERY -> CellsScreen(st.cells, b.balanceBits)
                            else -> LiveScreen(st, monitor)
                        }
                    }
                    BottomBar(place, charging = (st.basic?.current ?: 0.0) > 0.3) { place = it }
                }
                val notice by monitor.notice.collectAsState()
                notice?.let { n ->
                    Row(Modifier.align(Alignment.TopCenter).padding(top = 70.dp).background(Color(0xFF2E9E5B), RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(n, color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp))
                        Text("✕", color = Color.White, modifier = Modifier.clickable { monitor.notice.value = null })
                    }
                }
                if (overlay && place == Place.LIVE) ring?.let { r ->
                    Box(Modifier.fillMaxSize().background(Color(0xE60F1118)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
                        AlarmCard(r, monitor.alarms)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(st: Monitor.State, monitor: Monitor) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(monitor.settings.batteryName.ifEmpty { Tr["app"] }, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(Tr["readonly"], color = Palette.grey, fontSize = 12.sp)
        }
        st.problem?.let { Text(it, color = Palette.orange, fontSize = 13.sp, modifier = Modifier.padding(end = 10.dp)) }
        if (st.source.isNotEmpty()) Text(st.source, color = if (st.viaPhone) Palette.green else Palette.orange, fontSize = 13.sp,
            modifier = Modifier.border(1.dp, if (st.viaPhone) Palette.green else Palette.orange, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
        if (st.clock > 0) Text(iso(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(st.clock))), color = Palette.grey, fontSize = 13.sp,
            modifier = Modifier.padding(start = 12.dp))
    }
}

/** The phone's bottom bar: Battery and Settings, and the round Live button in the middle (pulsing while charging). */
@Composable
private fun BottomBar(place: Place, charging: Boolean, go: (Place) -> Unit) {
    val pulse = rememberInfiniteTransition()
    val p by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    Box(Modifier.fillMaxWidth().height(84.dp).background(Color(0xFF181B26))) {
        Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            NavItem("🔋", Tr["battery"], place == Place.BATTERY, Modifier.weight(1f)) { go(Place.BATTERY) }
            Spacer(Modifier.width(96.dp))
            NavItem("⚙", Tr["settings"], place == Place.SETTINGS, Modifier.weight(1f)) { go(Place.SETTINGS) }
        }
        val ring = if (charging) lerp(Palette.blue, Palette.green, p) else Palette.blue
        Box(Modifier.align(Alignment.Center).size(68.dp).clip(CircleShape).background(Color(0xFF12141C))
            .border(3.dp, ring, CircleShape).clickable { go(Place.LIVE) }, contentAlignment = Alignment.Center) {
            Text("⚡", color = if (place == Place.LIVE) ring else Palette.grey, fontSize = 30.sp)
        }
    }
}

@Composable
private fun NavItem(icon: String, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = if (selected) Palette.blue else Palette.grey
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) Color(0x264DA3FF) else Color.Transparent).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, color = c, fontSize = 22.sp)
        Text(label, color = c, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/** Alarms, syncs and imports, as they were said, newest first; cleared by hand. */
@Composable
fun NotifsScreen(monitor: Monitor) {
    val v by monitor.log.version.collectAsState()
    val items = remember(v) { monitor.log.all() }
    val fmt = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(String.format(Locale.US, "%d", items.size), color = Palette.grey, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) OutlinedButton(onClick = { monitor.log.clear() }) { Text(Tr["clear"]) }
        }
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) Text(Tr["noNotifs"], color = Palette.grey)
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items.size) { i ->
                val e = items[i]
                Column(Modifier.fillMaxWidth().background(Palette.tile, RoundedCornerShape(14.dp)).padding(12.dp)) {
                    Text(iso(fmt.format(Date(e.tMs))) + if (e.fromPhone) "  •  📱 " + Tr["fromPhone"] else "", color = Palette.grey, fontSize = 12.sp)
                    Text(e.title, color = Palette.text, fontWeight = FontWeight.Bold)
                    Text(e.text, color = Color(0xFFC9CFDD), fontSize = 13.sp)
                }
            }
        }
    }
}
