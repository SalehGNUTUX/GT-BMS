// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ble

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vanaccess.bms.R
import com.vanaccess.bms.data.AppPreferences
import com.vanaccess.bms.data.EventLog
import com.vanaccess.bms.reports.ChargePlan
import com.vanaccess.bms.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Charge alarms by the clock, for when the owner leaves with the phone. Planned while connected,
 * refined with every reading, and rung only if the battery can't be heard at that moment; next to
 * the battery the live alarms do it, from real values. Survives the service and a reboot.
 */
object ChargeTimer {
    const val TARGET = "target"
    const val NEAR = "near"
    const val SNOOZE = "snooze"
    const val ACTION_RING = "com.gnutux.gtbms.CHARGE_TIMER"
    const val ACTION_DONE = "com.gnutux.gtbms.CHARGE_TIMER_DONE"
    private const val CHANNEL = "gt_bms_charge_plan"
    private const val NOTIF = 4012
    /** Data younger than this: connected. */
    private const val FRESH_MS = 90_000L

    private fun hm(ms: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

    private fun pending(ctx: Context, kind: String) = PendingIntent.getBroadcast(ctx, when (kind) { TARGET -> 41; NEAR -> 42; else -> 44 },
        Intent(ctx, ChargeTimerReceiver::class.java).setAction(ACTION_RING).putExtra("kind", kind),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** "I unplugged it": no more alarms for this charge. */
    fun donePending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(ctx, 43,
        Intent(ctx, ChargeTimerReceiver::class.java).setAction(ACTION_DONE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** A fresh estimate while charging (null = none). */
    @Synchronized
    fun update(ctx: Context, plan: ChargePlan?) {
        val prefs = AppPreferences(ctx)
        if (plan == null) return
        if (!prefs.alarmHighEnabled || !prefs.alarmAway) { cancel(ctx); return }
        val old = ChargePlan.fromJson(prefs.chargePlan)
        if (prefs.chargePlanDone || !plan.differs(old)) return
        prefs.chargePlan = plan.toJson()
        if (old == null) com.vanaccess.bms.data.NotifLog.add(ctx, ctx.getString(R.string.away_title), ctx.getString(R.string.away_full_at, hm(plan.fullAt)))
        if (old == null) EventLog.add(ctx, "charge timer: ${plan.targetSoc}% ${plan.targetAt?.let { hm(it) } ?: "-"}, near ${plan.nearAt?.let { hm(it) } ?: "-"}, full ${hm(plan.fullAt)}")
        arm(ctx, plan)
        show(ctx, plan)
    }

    private fun arm(ctx: Context, plan: ChargePlan) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        listOf(TARGET to plan.targetAt, NEAR to plan.nearAt).forEach { (kind, at) ->
            val pi = pending(ctx, kind)
            am.cancel(pi)
            if (at == null || at <= now) return@forEach
            setAt(am, at, pi)
        }
    }

    /** Quiet notification: the times, so the owner knows the alarms are set before leaving. */
    private fun show(ctx: Context, p: ChargePlan) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.away_channel), NotificationManager.IMPORTANCE_LOW))
        val times = com.vanaccess.bms.ui.Fmt.line(ctx,
            p.targetAt?.takeIf { p.targetSoc < 100 }?.let { ctx.getString(R.string.away_target_at, p.targetSoc, hm(it)) },
            ctx.getString(R.string.away_full_at, hm(p.fullAt)))
        val text = times + "\n" + ctx.getString(R.string.away_note)
        val open = PendingIntent.getActivity(ctx, 70, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(ctx.getString(R.string.away_title)).setContentText(times)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setOnlyAlertOnce(true)
            .addAction(0, ctx.getString(R.string.away_done), donePending(ctx)).build()
        try { nm.notify(NOTIF, n) } catch (_: SecurityException) {}
    }

    /** The charge is over (or another battery, or the user said so). */
    @Synchronized
    fun cancel(ctx: Context, byUser: Boolean = false) {
        val prefs = AppPreferences(ctx)
        val had = prefs.chargePlan != null
        prefs.chargePlan = null
        prefs.chargePlanDone = byUser
        if (!had && !byUser) return
        val am = ctx.getSystemService(AlarmManager::class.java)
        listOf(TARGET, NEAR, SNOOZE).forEach { am?.cancel(pending(ctx, it)) }
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF)
    }

    /** After a reboot the alarms are gone; the plan is not. */
    fun rearm(ctx: Context) {
        ChargePlan.fromJson(AppPreferences(ctx).chargePlan)?.let {
            if (it.fullAt > System.currentTimeMillis()) { arm(ctx, it); show(ctx, it) } else cancel(ctx)
        }
    }

    /** Away alarm put off by the owner: estimated from the plan, sooner near the end. */
    fun snoozeMinutes(ctx: Context): Int {
        val plan = ChargePlan.fromJson(AppPreferences(ctx).chargePlan) ?: return 5
        return ((plan.fullAt - System.currentTimeMillis()) / 60_000 / 2).toInt().coerceIn(3, 15)
    }

    fun snooze(ctx: Context, minutes: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        setAt(am, System.currentTimeMillis() + minutes * 60_000L, pending(ctx, SNOOZE))
    }

    /** Heard from the battery a moment ago: perhaps just leaving, look again shortly (30 min at most). */
    fun recheck(ctx: Context, kind: String) {
        val plan = ChargePlan.fromJson(AppPreferences(ctx).chargePlan) ?: return
        val at = (if (kind == TARGET) plan.targetAt else plan.nearAt) ?: return
        val now = System.currentTimeMillis()
        if (now - at > 25 * 60_000) return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        setAt(am, now + 2 * 60_000, pending(ctx, kind))
    }

    /** Exact when Android allows it (background access granted), else with a few minutes of slack. */
    @android.annotation.SuppressLint("MissingPermission", "ScheduleExactAlarm")
    private fun setAt(am: AlarmManager, at: Long, pi: PendingIntent) {
        val exact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching { if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
            .onFailure { runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) } }
    }

    /** What to say now, or null when it must stay silent (connected, changed, or no plan). */
    fun textFor(ctx: Context, kind: String): String? {
        val prefs = AppPreferences(ctx)
        val plan = ChargePlan.fromJson(prefs.chargePlan) ?: return null
        val now = System.currentTimeMillis()
        val at = when (kind) { TARGET -> plan.targetAt; NEAR -> plan.nearAt; else -> now } ?: return null
        val seen = maxOf(prefs.lastDataMs, BleService.lastDataMs)
        // The computer watches this charge: no data here is expected, not a sign of being away
        if (prefs.linkYieldUntil > now) return null
        if (!prefs.alarmHighEnabled || !prefs.alarmAway || now - seen < FRESH_MS || now < at - 60_000 || now - at > 30 * 60_000) return null
        if (kind == SNOOZE) return ctx.getString(R.string.away_ring_snooze)
        return if (kind == TARGET) ctx.getString(R.string.away_ring_target, minOf(plan.targetSoc, 100))
        else ctx.getString(R.string.away_ring_near, hm(plan.fullAt))
    }
}

class ChargeTimerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx0: Context, intent: Intent) {
        val ctx = com.vanaccess.bms.data.AppLanguage.wrap(ctx0)
        if (intent.action == ChargeTimer.ACTION_DONE) {
            EventLog.add(ctx, "charge timer: unplugged by user")
            ChargeTimer.cancel(ctx, byUser = true)
            BleService.instance?.alarmUnplugged()
            return
        }
        val kind = intent.getStringExtra("kind") ?: return
        val text = ChargeTimer.textFor(ctx, kind)
        EventLog.add(ctx, "charge timer $kind: " + if (text == null) "silent (connected)" else "rings")
        if (text == null) { ChargeTimer.recheck(ctx, kind); return }
        val i = Intent(ctx, BleService::class.java).setAction(ChargeTimer.ACTION_RING).putExtra("text", text)
        runCatching { ContextCompat.startForegroundService(ctx, i) }.onFailure {
            // Not allowed to start the service: at least a notification with sound
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("gt_bms_away", ctx.getString(R.string.away_channel), NotificationManager.IMPORTANCE_HIGH))
            com.vanaccess.bms.data.NotifLog.add(ctx, ctx.getString(R.string.away_ring_title), text)
            runCatching { nm.notify(4014, NotificationCompat.Builder(ctx, "gt_bms_away").setSmallIcon(R.drawable.ic_battery)
                .setContentTitle(ctx.getString(R.string.away_ring_title)).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
                .addAction(0, ctx.getString(R.string.away_done), ChargeTimer.donePending(ctx)).build()) }
        }
    }
}
