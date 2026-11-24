// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.vanaccess.bms.data.AppPreferences
import com.vanaccess.bms.data.EventLog

/**
 * Wakes the phone when something may be starting, without keeping it awake all day. While the
 * phone sleeps the battery is neither polled nor reconnected, so a trip could go unseen.
 *  - the "significant motion" sensor (made for this, almost free) fires when the owner moves off;
 *  - an inexact alarm every 15 min covers phones without it, and charges started meanwhile.
 * Each wake-up lasts a few minutes; a trip or charge then keeps the phone awake by itself.
 */
class KeepAlive(private val ctx: Context, private val connected: () -> Boolean, private val onWake: (String) -> Unit) {

    companion object {
        private const val MOTION_AWAKE_MS = 3 * 60_000L
        private const val AWAY_AWAKE_MS = 5_000L
        /** Not armed again sooner than this: connected / away. */
        private const val REARM_MS = 20_000L
        private const val REARM_AWAY_MS = 10 * 60_000L
        private const val ALARM_AWAKE_MS = 45_000L
        private const val ALARM_EVERY_MS = 15 * 60_000L

        private fun pending(ctx: Context) = PendingIntent.getBroadcast(ctx, 30,
            Intent(ctx, KeepAliveReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            runCatching { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + ALARM_EVERY_MS, pending(ctx)) }
        }

        fun cancel(ctx: Context) { ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(ctx)) }
    }

    private val lock = ctx.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gtbms:wake").apply { setReferenceCounted(false) }
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val motion: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private var armed = false
    @Volatile private var lastMotion = 0L
    private var stopped = false
    @Volatile private var heldUntil = 0L

    private val listener = object : TriggerEventListener() {
        override fun onTrigger(e: TriggerEvent?) {
            armed = false                                   // one-shot: re-armed by [tick]
            if (stopped) return
            lastMotion = SystemClock.elapsedRealtime()
            // Near the battery: read it, a trip may be starting. Away from it, walking around must
            // not keep the phone awake; Android connects by itself when the battery is in range.
            awake("motion", if (connected()) MOTION_AWAKE_MS else AWAY_AWAKE_MS)
        }
    }

    fun start() { tick(); schedule(ctx) }

    /** Arm the motion sensor again (vehicle profiles with automatic trips only). */
    fun tick() {
        val want = !stopped && AppPreferences(ctx).let { it.vehicle.mobile && it.tripsEnabled && !it.selectedDeviceAddress.isNullOrEmpty() }
        val rested = lastMotion == 0L || SystemClock.elapsedRealtime() - lastMotion >= if (connected()) REARM_MS else REARM_AWAY_MS
        if (want && !armed && rested && motion != null) armed = runCatching { sm!!.requestTriggerSensor(listener, motion) }.getOrDefault(false)
        if (!want && armed) { runCatching { sm?.cancelTriggerSensor(listener, motion) }; armed = false }
    }

    fun onAlarm() { awake("alarm", ALARM_AWAKE_MS); schedule(ctx) }

    /** Keep the phone awake for a moment (a longer hold in progress is kept). */
    fun hold(ms: Long = 2 * 60_000L) {
        val until = SystemClock.elapsedRealtime() + ms
        if (stopped || until <= heldUntil) return
        heldUntil = until
        runCatching { lock.acquire(ms) }
    }

    private fun awake(why: String, ms: Long) {
        hold(ms)
        onWake(why)
    }

    fun stop() {
        stopped = true
        if (armed) runCatching { sm?.cancelTriggerSensor(listener, motion) }
        armed = false
        runCatching { if (lock.isHeld) lock.release() }
        cancel(ctx)
    }
}

class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val service = BleService.instance
        if (service != null) { service.onKeepAliveAlarm(); return }
        // The system killed the service (it never stopped by itself): bring monitoring back
        KeepAlive.schedule(ctx)
        val p = AppPreferences(ctx)
        if (!p.monitoring || p.selectedDeviceAddress.isNullOrEmpty()) return
        val err = runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, BleService::class.java)) }.exceptionOrNull()
        EventLog.add(ctx, "alarm: service was gone, restart " + (err?.javaClass?.simpleName ?: "ok"))
    }
}
