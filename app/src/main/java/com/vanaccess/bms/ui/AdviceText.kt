// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import com.vanaccess.bms.R
import com.vanaccess.bms.data.AppPreferences
import com.vanaccess.bms.reports.Advisor
import com.vanaccess.bms.reports.BmsLimits
import com.vanaccess.bms.reports.ChargeAdvice
import com.vanaccess.bms.reports.ReportStore
import com.vanaccess.bms.reports.Session

/** Turns [Advisor.Rec] codes into localised text, colours and notifications. */
object AdviceText {

    @SuppressLint("DiscouragedApi")
    fun text(ctx: Context, r: Advisor.Rec): String {
        val id = ctx.resources.getIdentifier("adv_${r.code}", "string", ctx.packageName)
        if (id == 0) return r.code
        val t = if (r.args.isEmpty()) ctx.getString(id) else ctx.getString(id, *r.args.toTypedArray())
        if (r.code == "all_ok") return t
        return ctx.getString(when (r.level) {
            Advisor.Level.CRITICAL -> R.string.degree_critical
            Advisor.Level.WARNING -> R.string.degree_warning
            Advisor.Level.INFO -> R.string.degree_info
            Advisor.Level.OK -> R.string.degree_ok
        }) + " " + t
    }

    fun color(level: Advisor.Level): Int = Color.parseColor(when (level) {
        Advisor.Level.CRITICAL -> "#F0544F"
        Advisor.Level.WARNING -> "#F5B83D"
        Advisor.Level.INFO -> "#4DA3FF"
        Advisor.Level.OK -> "#43D17A"
    })

    fun icon(level: Advisor.Level) = when (level) {
        Advisor.Level.CRITICAL -> "⛔"
        Advisor.Level.WARNING -> "⚠"
        Advisor.Level.INFO -> "💡"
        Advisor.Level.OK -> "✓"
    }

    fun limits(ctx: Context): BmsLimits = AppPreferences(ctx).limits

    fun chargerAdviceV(ctx: Context): Double? {
        val p = AppPreferences(ctx)
        return ChargeAdvice.from(p.bmsCellCount, p.effectiveOvMv)?.chargerPackV
    }

    /** Recommendations for a saved report (computed on demand; call off the main thread). */
    fun forSession(ctx: Context, s: Session): List<Advisor.Rec> {
        val lim = limits(ctx)
        val store = ReportStore.get(ctx)
        return if (s.type == Session.TYPE_CHARGE) {
            val lastFull = store.list(Session.TYPE_CHARGE, 100).firstOrNull {
                it.id != s.id && it.startMs < s.startMs && (it.b("reached_full") || it.b("ended_by_protection"))
            }
            val days = lastFull?.let { ((s.endMs - it.endMs) / 86_400_000L).toInt() }
            Advisor.afterCharge(s, lim, days, chargerAdviceV(ctx))
        } else if (s.type == Session.TYPE_DISCHARGE) {
            Advisor.afterDischarge(s, lim)
        } else {
            com.vanaccess.bms.reports.TripLearn.advice(s, lim, store.list(Session.TYPE_TRIP, 20, s.battery), store.list(Session.TYPE_TRIP, 31, s.battery))
        }
    }

    /** Charger analysis of one saved charge (background thread). */
    fun chargerFor(ctx: Context, s: Session): com.vanaccess.bms.reports.ChargerAnalyzer.Result? =
        com.vanaccess.bms.reports.ChargerAnalyzer.analyze(
            ReportStore.get(ctx).points(s.id), limits(ctx), s.b("ended_by_protection"), s.i("soc_end"))

    // ── Notifications ────────────────────────────────────────────────────────

    private const val CHANNEL = "gt_bms_summary"

    private fun channel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.sum_notify), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun notify(ctx: Context, id: Int, title: String, recs: List<Advisor.Rec>) {
        if (recs.isEmpty()) return
        channel(ctx)
        val body = recs.joinToString("\n") { icon(it.level) + " " + text(ctx, it) }
        val open = PendingIntent.getActivity(ctx, 20 + id, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(title)
            .setContentText(text(ctx, recs.first()))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(4000 + id, n) } catch (_: SecurityException) {}
        com.vanaccess.bms.data.NotifLog.add(ctx, title, body)
    }

    /** Plain one-line notification on the summary channel. */
    fun notifyPlain(ctx: Context, id: Int, title: String, text: String) {
        channel(ctx)
        val open = PendingIntent.getActivity(ctx, 40 + id, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_battery).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(4000 + id, n) } catch (_: SecurityException) {}
        com.vanaccess.bms.data.NotifLog.add(ctx, title, text)
    }

    private const val CHANNEL_QUIET = "gt_bms_quiet"

    /** "Why did the charge stop": silent, whatever the alarms are set to. */
    fun notifyChargeStop(ctx: Context, r: com.vanaccess.bms.reports.ChargeWatch.Result) {
        if (!AppPreferences(ctx).notifySessionSummary) return
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_QUIET, ctx.getString(R.string.chg_stop_channel), NotificationManager.IMPORTANCE_LOW))
        val text = stopText(ctx, r.reason.name.lowercase(), r.alarm, r.maxIdx, r.maxCell) + "\n" +
            socText(ctx, r.socBefore, r.socShown, r.socCounted) +
            if (r.maxIdx > 0) "\n" + ctx.getString(R.string.chg_stop_cells, r.packV, r.maxIdx, r.maxCell, r.minIdx, r.minCell, r.deltaMv) else ""
        val open = PendingIntent.getActivity(ctx, 50, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL_QUIET).setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(ctx.getString(R.string.chg_stop_title)).setContentText(text.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(4010, n) } catch (_: SecurityException) {}
        com.vanaccess.bms.data.NotifLog.add(ctx, ctx.getString(R.string.chg_stop_title), text)
    }

    fun stopText(ctx: Context, reason: String, alarm: String?, cell: Int, cellV: Double): String = when (reason) {
        "protection" -> if (cell > 0 && (alarm == null || alarm.contains("overvoltage", true)))
            ctx.getString(R.string.stop_protection_cell, cell, cellV)
            else ctx.getString(R.string.stop_protection) + (alarm?.let { " (" + L10n.t(ctx, it) + ")" } ?: "")
        "charger" -> ctx.getString(R.string.stop_charger)
        "lost" -> ctx.getString(R.string.stop_lost)
        else -> ctx.getString(R.string.stop_interrupted)
    }

    /** Never the jumped figure alone. */
    fun socText(ctx: Context, before: Int, shown: Int, counted: Int?): String {
        val jump = shown - before >= 2
        val s = if (jump) ctx.getString(R.string.soc_jump, before, shown) else ctx.getString(R.string.soc_plain, shown)
        return s + if (jump && counted != null) " " + ctx.getString(R.string.soc_counted, counted) else ""
    }

    /** Summary after a charge or trip (background thread). */
    fun notifySession(ctx: Context, s: Session) {
        if (s.type == Session.TYPE_STANDBY) return            // part of the day report
        if (!AppPreferences(ctx).notifySessionSummary) return
        Thread {
            val recs = forSession(ctx, s)
            val (title, id) = when (s.type) {
                Session.TYPE_CHARGE -> R.string.adv_charge_title to 1
                Session.TYPE_DISCHARGE -> R.string.adv_discharge_title to 4
                else -> R.string.adv_trip_title to 2
            }
            notify(ctx, id, ctx.getString(title), recs)
        }.start()
    }

    /**
     * Balance reminder: no full charge for 7 days → one notification, repeated at most every
     * 3 days. Cells only balance near full, so an occasional 100 % charge keeps them even.
     */
    fun maybeBalanceReminder(ctx: Context) {
        val prefs = AppPreferences(ctx)
        val now = System.currentTimeMillis()
        if (!prefs.balanceReminder || now - prefs.lastBalanceReminderMs < 3 * 86_400_000L) return
        Thread {
            val charges = ReportStore.get(ctx).list(Session.TYPE_CHARGE, 200)
            if (charges.isEmpty()) return@Thread             // nothing recorded yet: no basis to judge
            val lastFull = charges.firstOrNull { it.b("reached_full") || it.b("ended_by_protection") }
            val since = lastFull?.endMs ?: charges.last().startMs
            val days = ((now - since) / 86_400_000L).toInt()
            if (days < 7) return@Thread
            prefs.lastBalanceReminderMs = now
            val lim = limits(ctx)
            notify(ctx, 4, ctx.getString(R.string.balance_reminder_title),
                listOf(Advisor.Rec(Advisor.Level.WARNING, "balance_needed", listOf(days, lim.balanceStartV))))
        }.start()
    }

    private const val CHANNEL_DAILY = "gt_bms_daily"

    /** One line for a day: trips, distance, energy, charge. */
    fun dayLine(ctx: Context, d: com.vanaccess.bms.reports.DayReport): String {
        fun f(p: String, v: Double) = String.format(java.util.Locale.US, p, v)
        return com.vanaccess.bms.ui.Fmt.line(ctx,
            if (d.tripCount > 0) ctx.resources.getQuantityString(R.plurals.day_trips, d.tripCount, d.tripCount) else null,
            if (d.km > 0) f("%.2f km", d.km) else null,
            if (d.wh > 0) f("%.0f Wh", d.wh) else null,
            if (d.chargeCount > 0) ctx.resources.getQuantityString(R.plurals.day_charges, d.chargeCount, d.chargeCount) else null,
        )
    }

    /** After midnight: the finished day, silently. Waits for a trip that crosses midnight. */
    fun maybeDaily(ctx: Context, tripActive: Boolean) {
        val prefs = AppPreferences(ctx)
        val today = com.vanaccess.bms.reports.DayReport.dayStart(System.currentTimeMillis())
        val last = prefs.lastDailyMs
        if (last == today || tripActive) return
        prefs.lastDailyMs = today
        if (last == 0L || !prefs.notifyDaily) return            // first run: today is still open
        Thread {
            runCatching {
                val yesterday = com.vanaccess.bms.reports.DayReport.dayStart(today - 1)
                val d = com.vanaccess.bms.reports.DayReport.of(ReportStore.get(ctx).list(null, 300, prefs.selectedDeviceAddress), yesterday) ?: return@Thread
                ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL_DAILY, ctx.getString(R.string.day_title), NotificationManager.IMPORTANCE_LOW))
                val text = dayLine(ctx, d) + if (d.socStart != null && d.socEnd != null) "\n" + ctx.getString(R.string.day_soc, d.socStart, d.socEnd) else ""
                val open = PendingIntent.getActivity(ctx, 60, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val n = NotificationCompat.Builder(ctx, CHANNEL_DAILY).setSmallIcon(R.drawable.ic_battery)
                    .setContentTitle(ctx.getString(R.string.day_notify, java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(yesterday))))
                    .setContentText(text.substringBefore('\n')).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).build()
                ctx.getSystemService(NotificationManager::class.java).notify(4011, n)
                com.vanaccess.bms.data.NotifLog.add(ctx, ctx.getString(R.string.day_notify, java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(yesterday))), text)
            }
        }.start()
    }

    /** Weekly summary, at most once every 7 days (background thread). */
    fun maybeWeekly(ctx: Context, fullAh: Double?, cycles: Int?) {
        val prefs = AppPreferences(ctx)
        val now = System.currentTimeMillis()
        if (!prefs.notifyWeekly) return
        if (prefs.lastWeeklyMs == 0L) { prefs.lastWeeklyMs = now; return }   // first week starts now
        if (now - prefs.lastWeeklyMs < 7 * 86_400_000L) return
        prefs.lastWeeklyMs = now
        Thread {
            val store = ReportStore.get(ctx)
            val since = now - 7 * 86_400_000L
            val week = store.list(null, 500).filter { it.startMs >= since }
            val trips = week.filter { it.type == Session.TYPE_TRIP }
            val lim = limits(ctx)
            val h = Advisor.health(fullAh, lim, store.list(Session.TYPE_CHARGE, 200))
            val soh = h.measuredSohPct ?: h.bmsSohPct?.takeIf { !h.bmsReportsDesign }
            val recs = Advisor.weekly(Advisor.Week(week.count { it.type == Session.TYPE_CHARGE }, trips.size,
                trips.sumOf { it.d("distance_km") }, trips.sumOf { it.d("wh") }, cycles, soh), lim)
            notify(ctx, 3, ctx.getString(R.string.adv_weekly_title), recs)
        }.start()
    }
}
