// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.vanaccess.bms.R
import com.vanaccess.bms.data.AppPreferences

/** "Run in the background without restriction", so trips and alarms work with the screen off. */
object BackgroundAccess {

    fun granted(ctx: Context) =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    @SuppressLint("BatteryLife")   // monitoring a vehicle battery over Bluetooth is the app's purpose
    fun ask(act: Activity) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${act.packageName}"))
        runCatching { act.startActivity(direct) }.onFailure {
            runCatching { act.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    /** Explained and asked once, for vehicles with automatic trips. */
    fun askOnce(act: Activity) {
        val p = AppPreferences(act)
        if (p.askedBackground || granted(act) || !p.vehicle.mobile || !p.tripsEnabled || p.selectedDeviceAddress.isNullOrEmpty()) return
        p.askedBackground = true
        AppDialog.show(act, act.getString(R.string.bg_title), message = act.getString(R.string.bg_text), buttons = listOf(
            AppDialog.Btn(act.getString(R.string.bg_allow), AppDialog.GREEN) { ask(act) },
            AppDialog.Btn(act.getString(R.string.update_later), AppDialog.GREY)))
    }
}
