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

    enum class Kind { HIGH, LOW, DELTA_LOAD, CELL }

    /** How serious it is, said plainly. */
    enum class Level(val label: Int, val color: String) {
        NORMAL(R.string.lvl_normal, "#43D17A"), ACCEPTABLE(R.string.lvl_acceptable, "#4DA3FF"),
        ATTENTION(R.string.lvl_attention, "#F5B83D"), DANGER(R.string.lvl_danger, "#F0544F")
    }

    /** What is ringing now (full screen and notification). */
    class Ring(val kind: Kind, val title: String, val big: String, val text: String,
               val level: Level, val note: String? = null, val away: Boolean = false)

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
        /** Charging is considered "recent" for this long after the last reading of a confirmed charge. */
        private const val CHARGE_MEMORY_MS = 90_000L
        private const val CHARGE_THRESHOLD_A = 0.1
        const val SHORT_ALARM_MS = 5_000L
        /** Null = nothing rings. */
        val ring = androidx.lifecycle.MutableLiveData<Ring?>(null)
    }

    private val main = Handler(Looper.getMainLooper())
    private val states = mapOf(Kind.HIGH to State(), Kind.LOW to State(), Kind.DELTA_LOAD to State(), Kind.CELL to State())
    private var ringing: Kind? = null
    private var player: MediaPlayer? = null
    private var ringtone: Ringtone? = null

    init { createChannel() }

    /** Feed every fresh reading (any thread). */
    fun onReading(soc: Int, voltage: Double, current: Double) {
        main.post { evaluate(soc, voltage, current) }
    }

    private fun evaluate(soc: Int, voltage: Double, current: Double) {
        val now = System.currentTimeMillis()
        // A confirmed charge, never a moment of regenerative braking
        val charging = now - com.vanaccess.bms.reports.SessionRecorder.lastChargingMs < CHARGE_MEMORY_MS
        val validV = voltage > 1.0

        // ── High charge ──
        val hSoc = prefs.alarmHighSoc
        // A value the protection would never let the battery reach is replaced by the advised one
        val hV = AlarmCheck.effectiveHighVoltage(prefs.alarmHighVoltage.toDouble(), prefs.bmsCellCount, prefs.effectiveOvMv)
        val highHit = (hSoc > 0 && soc >= hSoc) || (hV > 0 && validV && voltage >= hV)
        val highClear = (hSoc <= 0 || soc < hSoc - SOC_HYSTERESIS) &&
                (hV <= 0 || !validV || voltage < hV - VOLT_HYSTERESIS)
        check(Kind.HIGH, prefs.alarmHighEnabled && charging && highHit, highClear, now) {
            Ring(Kind.HIGH, context.getString(R.string.alarm_high_title), "$soc%",
                context.getString(R.string.alarm_high_text, soc, voltage), Level.NORMAL)
        }

        // ── Low battery ──
        val lSoc = prefs.alarmLowSoc
        val lV = prefs.alarmLowVoltage.toDouble()
        lastSoc = soc
        val notCharging = current <= CHARGE_THRESHOLD_A
        // Percentage: once at the owner's value, then at each lower step; the last one repeats
        val announced = LowSteps.settle(prefs.lowStep, soc, lSoc)
        if (announced != prefs.lowStep) prefs.lowStep = announced
        val atLast = LowSteps.at(soc, lSoc).let { it > 0 && LowSteps.next(soc, lSoc) == null }
        val last = states.getValue(Kind.LOW).lastStoppedAt
        val again = atLast && last != 0L && now - last >= prefs.alarmCooldownMinutes.coerceAtLeast(1) * 60_000L
        if (prefs.alarmLowEnabled && notCharging && ringing == null && (LowSteps.due(announced, soc, lSoc) || again)) {
            prefs.lowStep = LowSteps.at(soc, lSoc)
            states.getValue(Kind.LOW).armed = false
            startAlarm(lowRing(soc, voltage, lSoc))
        }
        // Voltage: as before
        check(Kind.LOW, prefs.alarmLowEnabled && notCharging && lV > 0 && validV && voltage <= lV,
            lV <= 0 || !validV || voltage > lV + VOLT_HYSTERESIS, now) { lowRing(soc, voltage, lSoc) }
    }

    private var lastSoc = 0

    private fun lowRing(soc: Int, voltage: Double, limit: Int) = Ring(Kind.LOW,
        context.getString(R.string.alarm_low_title), "$soc%",
        context.getString(when {
            soc <= 10 -> R.string.alarm_low_text_danger
            soc <= 20 -> R.string.alarm_low_text_soon
            else -> R.string.alarm_low_text
        }, soc, voltage),
        when { soc <= 10 -> Level.DANGER; soc <= 20 -> Level.ATTENTION; else -> Level.ACCEPTABLE },
        LowSteps.next(soc, limit)?.takeIf { LowSteps.at(soc, limit) > 0 }?.let { context.getString(R.string.alarm_low_next, it) })

    private inline fun check(kind: Kind, hit: Boolean, clear: Boolean, now: Long, make: () -> Ring) {
        val st = states.getValue(kind)
        if (clear) st.armed = true
        if (!hit || ringing != null) return
        val cooldownMs = prefs.alarmCooldownMinutes.coerceAtLeast(1) * 60_000L
        val cooledDown = st.lastStoppedAt != 0L && now - st.lastStoppedAt >= cooldownMs
        if (st.armed || cooledDown) {
            st.armed = false
            startAlarm(make())
        }
    }

    /**
     * Early warning while charging: the highest cell gets close to the protection. With the
     * automatic value it only rings for chargers known (or not yet known) to be cut by the BMS.
     */
    private fun checkCell(cells: List<Double>, current: Double) {
        val ov = prefs.effectiveOvMv / 1000.0
        val manual = prefs.alarmCellVoltage.toDouble()
        val learn = com.vanaccess.bms.reports.SessionRecorder.chargeLearn
        if (!prefs.alarmCellEnabled || (manual <= 0 && (ov <= 0 || learn.cutByProtection == false))) return
        val limit = if (manual > 0) manual else learn.alarmCellV(ov)
        val mx = cells.max()
        val now = System.currentTimeMillis()
        val charging = now - com.vanaccess.bms.reports.SessionRecorder.lastChargingMs < 15_000L
        check(Kind.CELL, charging && current > CHARGE_THRESHOLD_A && mx >= limit, mx < limit - 0.05, now) {
            Ring(Kind.CELL, context.getString(R.string.alarm_cell_title), String.format(java.util.Locale.US, "%.2f V", mx),
                context.getString(R.string.alarm_cell_text, cells.indexOf(mx) + 1, mx), Level.ACCEPTABLE)
        }
    }

    private var restSince = 0L
    private var lastRestNoticeAt = 0L

    /**
     * Cell difference. Under load (≥ the profile's load current) a large difference rings
     * the alarm. At rest it only posts a notification, and only between 20 % and 90 % SOC:
     * near full or empty LiFePO4 cells naturally spread apart, so a difference there means
     * little. Rest = |I| < 1 A for at least 2 minutes.
     */
    fun onCells(cells: List<Double>, current: Double, soc: Int) {
        if (cells.size < 2) return
        main.post {
            checkCell(cells, current)
            if (!prefs.alarmDeltaEnabled) return@post
            val now = System.currentTimeMillis()
            val mx = cells.max(); val mn = cells.min()
            val delta = ((mx - mn) * 1000).toInt()
            val lowest = cells.indexOf(mn) + 1
            val iAbs = kotlin.math.abs(current)
            val loadA = prefs.vehicle.loadCurrentA
            val limit = prefs.alarmDeltaLoadMv
            check(Kind.DELTA_LOAD, iAbs >= loadA && delta >= limit, delta < limit - 20, now) {
                Ring(Kind.DELTA_LOAD, context.getString(R.string.alarm_delta_load_title), "$delta mV",
                    context.getString(R.string.alarm_delta_load_text, delta, iAbs, lowest), Level.ATTENTION)
            }
            if (iAbs < 1.0) { if (restSince == 0L) restSince = now } else restSince = 0L
            val atRest = restSince != 0L && now - restSince >= 120_000
            if (atRest && soc in 20..90 && delta >= prefs.alarmDeltaRestMv &&
                now - lastRestNoticeAt >= 6 * 3600_000L) {
                lastRestNoticeAt = now
                notifyRest(context.getString(R.string.alarm_delta_rest_text, delta, lowest))
            }
        }
    }

    /** Quiet notification (no alarm sound) for a cell difference at rest. */
    private fun notifyRest(text: String) {
        val openPi = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(context.getString(R.string.alarm_delta_rest_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .build()
        try { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1, n) }
        catch (_: SecurityException) {}
    }

    /** Ring a short demo alarm from the settings screen. */
    fun testAlarm(kind: Kind = Kind.HIGH) {
        main.post {
            if (ringing != null) stopSound()
            ringing = null
            startAlarm(Ring(kind, context.getString(title(kind)), "🔔", context.getString(R.string.alarm_test_text), Level.NORMAL))
        }
    }

    fun stopAlarm() {
        main.post {
            val kind = ringing
            if (kind != null) states.getValue(kind).lastStoppedAt = System.currentTimeMillis()
            // It went lower while ringing: stopping it covers that step too
            if (kind == Kind.LOW && prefs.lowStep > 0) LowSteps.at(lastSoc, prefs.alarmLowSoc).let { if (it in 1 until prefs.lowStep) prefs.lowStep = it }
            ringing = null
            ring.value = null
            stopSound()
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    fun release() {
        ringing = null
        ring.value = null
        stopSound()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    // ── Sound / vibration ─────────────────────────────────────────────────────

    /** By the clock, the battery being out of reach (see ble.ChargeTimer): tone of the full-charge alarm. */
    fun ringAway(text: String) { main.post { if (ringing == null) startAlarm(
        Ring(Kind.HIGH, context.getString(R.string.away_ring_title), "⏰", text, Level.NORMAL, away = true)) } }

    private fun startAlarm(r: Ring) {
        val kind = r.kind
        Log.i(TAG, "Alarm $kind: ${r.text}")
        com.vanaccess.bms.data.EventLog.add(context, "alarm $kind")
        ringing = kind
        val short = prefs.alarmShort(Tones.toneKey(kind))
        ring.value = r
        showNotification(r, ongoing = !short)
        showFullScreen()
        playSound(kind)
        if (prefs.alarmVibrate) vibrate()
        if (short) {
            // Short mode: ring for 5 s, then stop by itself (counts as stopped for the
            // cooldown) and leave a normal notification so the alert is not lost.
            main.postDelayed({
                if (ringing == kind && ring.value === r) {
                    states.getValue(kind).lastStoppedAt = System.currentTimeMillis()
                    ringing = null
                    ring.value = null
                    stopSound()
                }
            }, SHORT_ALARM_MS)
        }
    }

    /**
     * Tone for each alarm kind: the user's choice, else a different system sound per kind
     * so the three alarms are told apart even before choosing (full = notification tone,
     * low = alarm tone, cell difference = ringtone).
     */
    private fun alarmUri(kind: Kind): Uri? {
        prefs.alarmTone(Tones.toneKey(kind)).takeIf { it.isNotBlank() }?.let { return Uri.parse(it) }
        return Tones.defaultTone(context, kind)
    }

    object Tones {
        fun toneKey(kind: Kind) = when (kind) { Kind.HIGH -> "high"; Kind.LOW -> "low"; Kind.DELTA_LOAD -> "delta"; Kind.CELL -> "cell" }

        fun defaultTone(context: Context, kind: Kind): Uri? {
            val type = when (kind) {
                Kind.HIGH, Kind.CELL -> RingtoneManager.TYPE_NOTIFICATION
                Kind.LOW -> RingtoneManager.TYPE_ALARM
                Kind.DELTA_LOAD -> RingtoneManager.TYPE_RINGTONE
            }
            return RingtoneManager.getActualDefaultRingtoneUri(context, type)
                ?: RingtoneManager.getDefaultUri(type)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        }
    }

    private fun playSound(kind: Kind) {
        stopSound()
        val uri = alarmUri(kind) ?: return
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

    /**
     * Screen off or locked: the notification's full-screen intent does it. In use: Android only
     * lets the app open it when it is on the screen itself or may show over other apps.
     */
    private fun showFullScreen() {
        val st = android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }
        val visible = st.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE &&
            st.importance != android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        if (visible || com.vanaccess.bms.ui.FullScreenAccess.overApps(context))
            runCatching { context.startActivity(AlarmActivity.intent(context)) }.onFailure { Log.w(TAG, "full screen: ${it.message}") }
    }

    private fun title(kind: Kind) = when (kind) {
        Kind.HIGH -> R.string.alarm_high_title
        Kind.LOW -> R.string.alarm_low_title
        Kind.DELTA_LOAD -> R.string.alarm_delta_load_title
        Kind.CELL -> R.string.alarm_cell_title
    }

    private fun showNotification(r: Ring, ongoing: Boolean) {
        val stopIntent = Intent(context, BleService::class.java).setAction(BleService.ACTION_STOP_ALARM)
        val stopPi = PendingIntent.getService(
            context, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = r.text + (r.note?.let { "\n" + it } ?: "")
        val fullPi = PendingIntent.getActivity(context, 4, AlarmActivity.intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(r.title)
            .setContentText(r.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(if (ongoing) fullPi else openPi)
            .setFullScreenIntent(fullPi, true)
            .setDeleteIntent(stopPi)
            .addAction(R.drawable.ic_battery, context.getString(R.string.alarm_stop), stopPi)
            .apply { if (r.away) addAction(0, context.getString(R.string.away_done), com.vanaccess.bms.ble.ChargeTimer.donePending(context)) }
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
