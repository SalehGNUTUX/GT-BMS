// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vanaccess.bms.R
import com.vanaccess.bms.ble.BleService
import com.vanaccess.bms.data.AppPreferences
import com.vanaccess.bms.ui.MainActivity

/**
 * Watches live battery readings and rings an audible alarm (alarm audio stream, looping)
 * with a high-priority notification carrying a "Stop alarm" button.
 *
 * Anti-spam rules for each alarm kind:
 *  - it never re-triggers while already ringing;
 *  - after it fired it is "disarmed" until the condition clears with hysteresis
 *    (SOC ±2 %, voltage ±0.5 V);
 *  - if the condition never clears, it may ring again only after the cooldown
 *    (default 10 min) counted from the moment the user stopped it.
 */
class BatteryAlarmMonitor(private val context: Context, private val prefs: AppPreferences) {

    enum class Kind { HIGH, LOW }

    private class State {
        var armed = true
        var lastStoppedAt = 0L
    }

    companion object {
        private const val TAG = "BatteryAlarm"
        const val CHANNEL_ID = "gt_bms_alarm"
        const val NOTIFICATION_ID = 2001
        private const val SOC_HYSTERESIS = 2
        private const val VOLT_HYSTERESIS = 0.5
        /** Charging is considered "recent" for this long after current was last > 0. */
        private const val CHARGE_MEMORY_MS = 90_000L
        private const val CHARGE_THRESHOLD_A = 0.1
    }

    private val main = Handler(Looper.getMainLooper())
    private val states = mapOf(Kind.HIGH to State(), Kind.LOW to State())
    private var ringing: Kind? = null
    private var player: MediaPlayer? = null
    private var ringtone: Ringtone? = null
    private var lastChargingAt = 0L

    init { createChannel() }

    /** Feed every fresh reading (any thread). */
    fun onReading(soc: Int, voltage: Double, current: Double) {
        main.post { evaluate(soc, voltage, current) }
    }

    private fun evaluate(soc: Int, voltage: Double, current: Double) {
        val now = System.currentTimeMillis()
        if (current > CHARGE_THRESHOLD_A) lastChargingAt = now
        val charging = now - lastChargingAt < CHARGE_MEMORY_MS
        val validV = voltage > 1.0

        // ── High charge ──
        val hSoc = prefs.alarmHighSoc
        val hV = prefs.alarmHighVoltage.toDouble()
        val highHit = (hSoc > 0 && soc >= hSoc) || (hV > 0 && validV && voltage >= hV)
        val highClear = (hSoc <= 0 || soc < hSoc - SOC_HYSTERESIS) &&
                (hV <= 0 || !validV || voltage < hV - VOLT_HYSTERESIS)
        check(Kind.HIGH, prefs.alarmHighEnabled && charging && highHit, highClear, now) {
            context.getString(R.string.alarm_high_text, soc, voltage)
        }

        // ── Low battery ──
        val lSoc = prefs.alarmLowSoc
        val lV = prefs.alarmLowVoltage.toDouble()
        val lowHit = (lSoc > 0 && soc <= lSoc) || (lV > 0 && validV && voltage <= lV)
        val lowClear = (lSoc <= 0 || soc > lSoc + SOC_HYSTERESIS) &&
                (lV <= 0 || !validV || voltage > lV + VOLT_HYSTERESIS)
        val notCharging = current <= CHARGE_THRESHOLD_A
        check(Kind.LOW, prefs.alarmLowEnabled && notCharging && lowHit, lowClear, now) {
            context.getString(R.string.alarm_low_text, soc, voltage)
        }
    }

    private inline fun check(kind: Kind, hit: Boolean, clear: Boolean, now: Long, text: () -> String) {
        val st = states.getValue(kind)
        if (clear) st.armed = true
        if (!hit || ringing != null) return
        val cooldownMs = prefs.alarmCooldownMinutes.coerceAtLeast(1) * 60_000L
        val cooledDown = st.lastStoppedAt != 0L && now - st.lastStoppedAt >= cooldownMs
        if (st.armed || cooledDown) {
            st.armed = false
            startAlarm(kind, text())
        }
    }

    /** Ring a short demo alarm from the settings screen. */
    fun testAlarm() {
        main.post {
            if (ringing == null) startAlarm(Kind.HIGH, context.getString(R.string.alarm_test_text))
        }
    }

    fun stopAlarm() {
        main.post {
            val kind = ringing
            if (kind != null) states.getValue(kind).lastStoppedAt = System.currentTimeMillis()
            ringing = null
            stopSound()
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    fun release() {
        ringing = null
        stopSound()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    // ── Sound / vibration ─────────────────────────────────────────────────────

    private fun startAlarm(kind: Kind, text: String) {
        Log.i(TAG, "Alarm $kind: $text")
        ringing = kind
        showNotification(kind, text)
        playSound()
        if (prefs.alarmVibrate) vibrate()
    }

    private fun alarmUri(): Uri? =
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private fun playSound() {
        stopSound()
        val uri = alarmUri() ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(context, uri)
                isLooping = true
                setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer failed (${e.message}), falling back to Ringtone")
            player?.release(); player = null
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = attrs
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }
    }

    private fun stopSound() {
        try { player?.stop() } catch (_: Exception) {}
        player?.release(); player = null
        ringtone?.stop(); ringtone = null
        vibrator()?.cancel()
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private fun vibrate() {
        val v = vibrator() ?: return
        val pattern = longArrayOf(0, 800, 600)
        v.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.alarm_channel_name), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.alarm_channel_desc)
            setSound(null, null)          // sound is played by MediaPlayer on the alarm stream
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun showNotification(kind: Kind, text: String) {
        val stopIntent = Intent(context, BleService::class.java).setAction(BleService.ACTION_STOP_ALARM)
        val stopPi = PendingIntent.getService(
            context, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = context.getString(
            if (kind == Kind.HIGH) R.string.alarm_high_title else R.string.alarm_low_title
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openPi)
            .setDeleteIntent(stopPi)
            .addAction(R.drawable.ic_battery, context.getString(R.string.alarm_stop), stopPi)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission missing: ${e.message}")
        }
    }

    /** True while an alarm is sounding (used by the UI). */
    val isRinging: Boolean get() = ringing != null
}
