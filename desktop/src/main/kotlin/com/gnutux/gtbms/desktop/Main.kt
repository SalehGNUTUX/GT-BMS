// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

fun main(args: Array<String>) {
    val monitor = Monitor()
    monitor.startReplay(args.indexOf("--replay").takeIf { it >= 0 }?.let { File(args[it + 1]).readText() })
    // Headless picture of the screens, for checks without a display: --snapshot out.png [--tab cells]
    args.indexOf("--snapshot").takeIf { it >= 0 }?.let { i ->
        Thread.sleep(3_000)
        snapshot(monitor, File(args[i + 1]), if ("--cells" in args) 1 else 0)
        monitor.stop(); return
    }
    application {
        Window(onCloseRequest = { monitor.stop(); exitApplication() }, title = "GT-BMS",
            icon = painterResource("icon.png"), state = rememberWindowState(width = 1100.dp, height = 760.dp)) {
            App(monitor)
        }
    }
}

private fun snapshot(monitor: Monitor, out: File, tab: Int) {
    val scene = ImageComposeScene(1100, 760, Density(1f)) { App(monitor, tab) }
    scene.render(0); val img = scene.render(1_000_000_000L)
    out.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    scene.close()
}
