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
        return if (r.args.isEmpty()) ctx.getString(id) else ctx.getString(id, *r.args.toTypedArray())
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

    fun limits(ctx: Context) = BmsLimits.fromJson(AppPreferences(ctx).bmsLimitsJson)

    fun chargerAdviceV(ctx: Context): Double? {
        val p = AppPreferences(ctx)
        return ChargeAdvice.from(p.bmsCellCount, p.bmsCellOvMv)?.chargerPackV
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
            val learned = store.learnedConsumption()?.first
            val whKm = if (!s.data.isNull("wh_per_km")) s.d("wh_per_km") else null
            val design = lim.designAh
            val range = if (whKm != null && whKm > 0 && design != null)
                s.i("soc_end") / 100.0 * design * ((s.d("max_v") + s.d("min_v")) / 2) / whKm else null
            Advisor.afterTrip(s, lim, learned, range) + com.vanaccess.bms.reports.DrivingAnalyzer.recs(s)
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
    }

    /** Summary after a charge or trip (background thread). */
    fun notifySession(ctx: Context, s: Session) {
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
                listOf(Advisor.Rec(Advisor.Level.WARNING, "balance_needed", listOf(days, (lim.balanceStartMv ?: 3400) / 1000.0))))
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
