// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.reports

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat

/**
 * GPS speed/distance for trips. Same approach as GT-SPEEDOMETER's LocationEngine:
 * raw [LocationManager] GPS provider (no Google Play services), so it works on
 * de-Googled phones and gives the chip's real fix rate.
 *
 * Runs only while a trip may be in progress (started/stopped by [SessionRecorder]).
 */
class LocationTracker(private val context: Context) {

    data class Fix(val speedKmh: Double, val lat: Double, val lon: Double, val accuracyM: Float, val elapsedMs: Long)

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    @Volatile var running = false; private set
    @Volatile private var last: Fix? = null
    private var listener: ((Fix) -> Unit)? = null

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Latest fix if it is fresh (≤ 5 s old). */
    val current: Fix?
        get() = last?.takeIf { SystemClock.elapsedRealtime() - it.elapsedMs <= 5000 }

    private val gps = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onProviderDisabled(provider: String) { last = null; onProvider(false) }
        override fun onProviderEnabled(provider: String) { onProvider(true) }
    }

    /** GPS switched on or off while in use. */
    var onProvider: (Boolean) -> Unit = {}

    private var prevLoc: Location? = null

    private fun onFix(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > 50f) return   // too vague to trust
        val prev = prevLoc
        val speedMps = when {
            loc.hasSpeed() -> loc.speed.toDouble()
            prev != null -> {
                val dt = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
                if (dt > 0) prev.distanceTo(loc) / dt else 0.0
            }
            else -> 0.0
        }
        prevLoc = loc
        val fix = Fix(speedMps * 3.6, loc.latitude, loc.longitude,
            if (loc.hasAccuracy()) loc.accuracy else 30f, loc.elapsedRealtimeNanos / 1_000_000)
        last = fix
        listener?.invoke(fix)
    }

    @SuppressLint("MissingPermission")
    fun start(onFix: (Fix) -> Unit): Boolean {
        if (running) return true
        if (!hasPermission) return false
        listener = onFix
        return try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gps, Looper.getMainLooper())
            running = true
            true
        } catch (_: Exception) { false }
    }

    fun stop() {
        if (!running) return
        try { lm.removeUpdates(gps) } catch (_: Exception) {}
        running = false
        listener = null
        last = null
        prevLoc = null
    }
}
