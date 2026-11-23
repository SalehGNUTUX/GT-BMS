// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vanaccess.bms.R
import com.vanaccess.bms.update.UpdateService
import com.vanaccess.bms.update.Updater

/** Update actions shared by the in-app banner and the Settings card. */
object UpdateUi {

    /** "What's new": the release notes, formatted, in the app's dialog style. */
    fun showNotes(act: AppCompatActivity, info: Updater.Check) {
        val dp = act.resources.displayMetrics.density
        val body = TextView(act).apply {
            text = if (info.notes.isBlank()) act.getString(R.string.update_available_text) else ReleaseNotes.format(info.notes)
            textSize = 14.5f
            setTextColor(Color.parseColor("#C9CFDD"))
            setLineSpacing(3 * dp, 1f)
            setTextIsSelectable(false)
        }
        AppDialog.show(act, act.getString(R.string.update_available_title, info.version), body = body, tall = true,
            buttons = listOf(
                AppDialog.Btn(act.getString(R.string.update_download_install), AppDialog.GREEN) { download(act, info) },
                AppDialog.Btn(act.getString(R.string.update_later), AppDialog.GREY),
            ))
    }

    fun download(act: AppCompatActivity, info: Updater.Check) {
        val asset = info.asset
        if (asset == null) { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl))); return }
        (act as? MainActivity)?.ensureNotificationPermission()
        UpdateService.start(act, asset)
    }

    fun install(act: AppCompatActivity, file: java.io.File) {
        if (!Updater.canInstall(act)) {
            Toast.makeText(act, R.string.update_allow_install, Toast.LENGTH_LONG).show()
            act.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${act.packageName}")))
            return
        }
        Updater.install(act, file).onFailure { Toast.makeText(act, it.message, Toast.LENGTH_LONG).show() }
    }

    /** Auto-check intervals offered in Settings (hours). */
    val INTERVALS = intArrayOf(6, 12, 24, 168)
    fun intervalLabel(act: android.content.Context, h: Int): String = act.getString(when (h) {
        6 -> R.string.update_every_6h; 12 -> R.string.update_every_12h
        168 -> R.string.update_every_week; else -> R.string.update_every_day
    })
}
