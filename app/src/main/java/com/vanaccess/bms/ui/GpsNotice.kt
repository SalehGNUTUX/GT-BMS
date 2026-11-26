// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vanaccess.bms.R
import com.vanaccess.bms.data.AppPreferences

/** Tells the rider that a trip has no GPS: no distance, speed or route without it. */
object GpsNotice {
    enum class Problem { OFF, PERMISSION }
    private const val CHANNEL = "gt_bms_gps"
    private const val NOTIF = 4013

    /** Only for vehicles with automatic trips; null = GPS is ready. */
    fun problem(ctx: Context): Problem? {
        val p = AppPreferences(ctx)
        if (!p.vehicle.mobile || !p.tripsEnabled) return null
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return Problem.PERMISSION
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return if (runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(true)) null else Problem.OFF
    }

    fun fixIntent(ctx: Context, p: Problem): Intent = when (p) {
        Problem.OFF -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        Problem.PERMISSION -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}"))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** A trip is starting or running: with sound, the phone is probably in a pocket. */
    fun show(ctx: Context, p: Problem) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.gps_channel), NotificationManager.IMPORTANCE_HIGH))
        val text = ctx.getString(if (p == Problem.OFF) R.string.gps_off_text else R.string.gps_perm_text)
        val fix = PendingIntent.getActivity(ctx, 80, fixIntent(ctx, p), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(ctx.getString(R.string.gps_title)).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(fix).addAction(0, ctx.getString(R.string.gps_enable), fix).setOnlyAlertOnce(true).setAutoCancel(true).build()
        try { nm.notify(NOTIF, n) } catch (_: SecurityException) {}
        com.vanaccess.bms.data.NotifLog.add(ctx, ctx.getString(R.string.gps_title), text)
    }

    fun clear(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF)
}
