// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import java.util.Locale
import kotlin.math.abs

object Palette {
    val bg = Color(0xFF12141C); val card = Color(0xFF1E2130); val tile = Color(0xFF262A3B)
    val text = Color(0xFFE6E9F2); val grey = Color(0xFF9BA3B8)
    val green = Color(0xFF43D17A); val blue = Color(0xFF4DA3FF); val orange = Color(0xFFF5B83D); val red = Color(0xFFF0544F)
}

private fun f(p: String, vararg a: Any) = String.format(Locale.US, p, *a)

private fun socColor(soc: Int) = when { soc >= 50 -> Palette.green; soc >= 20 -> Palette.orange; else -> Palette.red }

@Composable
fun App(monitor: Monitor, initialTab: Int = 0) {
    val st by monitor.state.collectAsState()
    var tab by remember { mutableStateOf(initialTab) }
    MaterialTheme(colorScheme = darkColorScheme(primary = Palette.blue, background = Palette.bg, surface = Palette.card)) {
        CompositionLocalProvider(LocalLayoutDirection provides if (Tr.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Column(Modifier.fillMaxSize().background(Palette.bg).padding(16.dp)) {
                Header(st)
                Spacer(Modifier.height(12.dp))
                TabRow(selectedTabIndex = tab, containerColor = Palette.bg, contentColor = Palette.blue) {
                    listOf("live", "cells").forEachIndexed { i, k ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(Tr[k], fontSize = 16.sp) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                val b = st.basic
                if (b == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(Tr["waiting"], color = Palette.grey) }
                else if (tab == 0) LiveScreen(st) else CellsScreen(st.cells, b.balanceBits)
            }
        }
    }
}

@Composable
private fun Header(st: Monitor.State) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(Tr["app"], color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(Tr["readonly"], color = Palette.grey, fontSize = 12.sp)
        }
        if (st.source.isNotEmpty()) Text(st.source, color = Palette.orange, fontSize = 13.sp,
            modifier = Modifier.border(1.dp, Palette.orange, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.background(Palette.card, RoundedCornerShape(18.dp)).padding(16.dp), content = content)

@Composable
private fun Metric(label: String, value: String, color: Color = Palette.text, modifier: Modifier = Modifier) =
    Column(modifier.background(Palette.tile, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Text(label, color = Palette.grey, fontSize = 13.sp)
        // numbers keep their own left-to-right order
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }

@Composable
private fun LiveScreen(st: Monitor.State) {
    val b = st.basic ?: return
    val state = when { b.current > 0.1 -> "charging" to Palette.green; b.current < -0.1 -> "discharging" to Palette.blue; else -> "idle" to Palette.grey }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.weight(1f)) {
            Text(Tr["soc"], color = Palette.grey, fontSize = 14.sp)
            Text("${b.soc}%", color = socColor(b.soc), fontSize = 64.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { b.soc / 100f }, color = socColor(b.soc), trackColor = Palette.tile,
                modifier = Modifier.fillMaxWidth().height(10.dp))
            Spacer(Modifier.height(10.dp))
            Text(Tr[state.first], color = state.second, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                Metric(Tr["mos"], (b.mosTemperatures.ifEmpty { listOfNotNull(b.mosTemperature) }).joinToString("  ") { f("%.0f°C", it) }.ifEmpty { "—" }, modifier = Modifier.weight(1f))
                Metric(Tr["delta"], if (st.cells.size > 1) f("%.0f mV", (st.cells.max() - st.cells.min()) * 1000) else "—", modifier = Modifier.weight(1f))
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
