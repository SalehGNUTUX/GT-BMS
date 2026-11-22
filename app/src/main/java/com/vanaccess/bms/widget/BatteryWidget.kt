// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import com.vanaccess.bms.R
import com.vanaccess.bms.data.AppPreferences
import com.vanaccess.bms.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home-screen widget: state of charge, voltage and current, range for vehicles, and the time
 * of the reading. Fed by BleService through [AppPreferences.liveSummary] (no polling of its own).
 */
class BatteryWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    companion object {
        /** Push the latest summary to every placed widget. */
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context)
            val ids = m.getAppWidgetIds(ComponentName(context, BatteryWidget::class.java))
            if (ids.isNotEmpty()) ids.forEach { m.updateAppWidget(it, views(context)) }
        }

        /** Draw the Live design's gauge (static) into a bitmap for the widget. */
        fun gaugeBitmap(context: Context, design: String, soc: Int, current: Double): android.graphics.Bitmap {
            val dm = context.resources.displayMetrics
            val view: android.view.View
            val (wDp, hDp) = when (design) {
                "ring" -> {
                    view = com.vanaccess.bms.ui.battery.RingGaugeView(context).apply { level = soc; charging = current > 0.1 }
                    76 to 76
                }
                "segments" -> {
                    view = com.vanaccess.bms.ui.battery.SegmentGaugeView(context).apply { animate = false; level = soc; this.current = current }
                    42 to 72
                }
                else -> {
                    view = com.vanaccess.bms.ui.battery.BatteryGaugeView(context).apply { level = soc; charging = current > 0.1 }
                    40 to 68
                }
            }
            val w = (wDp * dm.density).toInt(); val h = (hDp * dm.density).toInt()
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bmp))
            return bmp
        }

        private fun views(context: Context): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_battery)
            val s = AppPreferences(context).liveSummary
            if (s == null) {
                v.setTextViewText(R.id.w_soc, "—")
                v.setTextViewText(R.id.w_line, context.getString(R.string.waiting_data))
                v.setViewVisibility(R.id.w_range, View.GONE)
                v.setTextViewText(R.id.w_time, "")
            } else {
                val design = AppPreferences(context).liveDesign
                v.setImageViewBitmap(R.id.w_gauge, gaugeBitmap(context, design, s.soc, s.current))
                // The ring already shows the percentage inside it
                v.setViewVisibility(R.id.w_soc, if (design == "ring") View.GONE else View.VISIBLE)
                v.setTextViewText(R.id.w_soc, "${s.soc}%")
                v.setTextColor(R.id.w_soc, Color.parseColor(when {
                    s.soc >= 50 -> "#43D17A"; s.soc >= 25 -> "#F5B83D"; else -> "#F0544F"
                }))
                v.setTextViewText(R.id.w_line, String.format(Locale.US, "%.2f V • %+.1f A", s.voltage, s.current))
                if (s.rangeKm != null) {
                    v.setViewVisibility(R.id.w_range, View.VISIBLE)
                    v.setTextViewText(R.id.w_range, String.format(Locale.US, "≈ %.0f km", s.rangeKm))
                } else v.setViewVisibility(R.id.w_range, View.GONE)
                v.setTextViewText(R.id.w_time, SimpleDateFormat("HH:mm", Locale.US).format(Date(s.timeMs)))
            }
            val open = PendingIntent.getActivity(context, 30, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.w_root, open)
            return v
        }
    }
}
