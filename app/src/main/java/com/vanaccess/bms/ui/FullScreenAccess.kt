// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.vanaccess.bms.R
import com.vanaccess.bms.data.AppPreferences

/** What Android wants before an alarm may take the whole screen. */
object FullScreenAccess {
    /** Over the lock screen and with the screen off (always granted before Android 14). */
    private fun locked(ctx: Context) =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** While another app is being used. */
    fun overApps(ctx: Context) = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(ctx)

    fun granted(ctx: Context) = locked(ctx) && overApps(ctx)

    /** Opens the first page still missing. */
    fun ask(act: Activity) {
        val pkg = Uri.parse("package:${act.packageName}")
        val i = if (!locked(act) && Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
        else Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
        runCatching { act.startActivity(i) }.onFailure {
            runCatching { act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }
        }
    }

    /** Explained and asked once, when some alarm is on (cell alarms are by default). */
    fun askOnce(act: Activity) {
        val p = AppPreferences(act)
        if (p.askedFullScreen || granted(act) || p.selectedDeviceAddress.isNullOrEmpty()) return
        if (!p.alarmHighEnabled && !p.alarmLowEnabled && !p.alarmDeltaEnabled && !p.alarmCellEnabled) return
        p.askedFullScreen = true
        AppDialog.show(act, act.getString(R.string.fs_title), message = act.getString(R.string.fs_text), buttons = listOf(
            AppDialog.Btn(act.getString(R.string.bg_allow), AppDialog.GREEN) { ask(act) },
            AppDialog.Btn(act.getString(R.string.update_later), AppDialog.GREY)))
    }
}
