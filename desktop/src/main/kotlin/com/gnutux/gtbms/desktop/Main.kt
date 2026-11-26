// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.skia.EncodedImageFormat
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JOptionPane

fun main(args: Array<String>) {
    // Headless checks: --home DIR (own settings and reports), --sim-charge, --wait SECONDS, --snapshot out.png [--tab N]
    val home = args.indexOf("--home").takeIf { it >= 0 }?.let { File(args[it + 1]) } ?: File(System.getProperty("user.home"))
    val monitor = Monitor(home)
    if ("--snapshot" in args) monitor.alarms.quiet = true
    args.indexOf("--import").takeIf { it >= 0 }?.let { println("imported " + monitor.importPhoneBackup(File(args[it + 1]))) }
    if ("--sim-charge" in args) monitor.startChargeSim(args.indexOf("--speed").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 10,
        args.indexOf("--sim-soc").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 69)
    else monitor.startReplay(args.indexOf("--replay").takeIf { it >= 0 }?.let { File(args[it + 1]).readText() })
    args.indexOf("--snapshot").takeIf { it >= 0 }?.let { i ->
        Thread.sleep((args.indexOf("--wait").takeIf { it >= 0 }?.let { args[it + 1].toLong() } ?: 3L) * 1000)
        snapshot(monitor, File(args[i + 1]), args.indexOf("--tab").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 0)
        monitor.stop(); System.exit(0)
    }
    application {
        val windowState = rememberWindowState(width = 1180.dp, height = 800.dp)
        Window(onCloseRequest = { monitor.stop(); exitApplication() }, title = "GT-BMS", icon = painterResource("icon.png"), state = windowState) {
            App(monitor, onImport = {
                val d = FileDialog(null as Frame?, Tr["import"], FileDialog.LOAD).apply { file = "*.zip"; isVisible = true }
                val f = d.file?.let { File(d.directory, it) } ?: return@App
                val msg = runCatching { String.format(Tr["imported"], monitor.importPhoneBackup(f)) }.getOrElse { Tr["importFail"] }
                JOptionPane.showMessageDialog(null, msg, "GT-BMS", JOptionPane.INFORMATION_MESSAGE)
            })
        }
        // The ringing alarm: its own window in the middle of the screen, over the others, even with
        // GT-BMS minimised; closing it is not offered, only its buttons stop the alarm
        val ring by monitor.alarms.ring.collectAsState()
        ring?.let { r ->
            Window(onCloseRequest = {}, title = r.title, icon = painterResource("icon.png"), undecorated = true, transparent = true,
                alwaysOnTop = true, resizable = false, focusable = true,
                state = WindowState(position = WindowPosition(Alignment.Center), size = DpSize(660.dp, 560.dp))) {
                LaunchedEffect(r) { window.toFront(); window.requestFocus() }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AlarmCard(r, monitor.alarms) }
            }
        }
    }
}

private fun snapshot(monitor: Monitor, out: File, tab: Int) {
    val scene = ImageComposeScene(1180, 800, Density(1f)) { App(monitor, tab, overlay = true) }
    scene.render(0); val img = scene.render(1_000_000_000L)
    out.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    scene.close()
}
