// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Choosing a file with the desktop's own dialog: KDE's (kdialog), then GNOME's (zenity), then
 * Swing's. (AWT's FileDialog draws black on some desktops, Wayland especially.) Blocking: call it
 * off the interface thread.
 */
object FilePicker {
    fun open(title: String, ext: String): File? {
        val home = System.getProperty("user.home")
        val kde = (System.getenv("XDG_CURRENT_DESKTOP") ?: "").contains("KDE", true)
        val tries = buildList {
            val k = listOf("kdialog", "--title", title, "--getopenfilename", home, "*.$ext")
            val z = listOf("zenity", "--file-selection", "--title=$title", "--file-filter=*.$ext")
            if (kde) { add(k); add(z) } else { add(z); add(k) }
        }
        for (cmd in tries) {
            val r = runCatching {
                val p = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor() to out
            }.getOrNull() ?: continue                      // that dialog is not installed
            val (code, out) = r
            return if (code == 0 && out.isNotEmpty()) File(out) else null   // chosen, or cancelled
        }
        val ch = JFileChooser(home).apply { dialogTitle = title; fileFilter = FileNameExtensionFilter("*.$ext", ext) }
        return if (ch.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) ch.selectedFile else null
    }
}
