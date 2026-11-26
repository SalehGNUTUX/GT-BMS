// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.alarm

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.vanaccess.bms.R
import com.vanaccess.bms.ble.BleService
import com.vanaccess.bms.ble.ChargeTimer

/**
 * The ringing alarm, on the whole screen: over the lock screen too. Closes by itself when the
 * alarm stops, wherever it was stopped from. Only its buttons stop it: not Back, not the keys.
 */
class AlarmActivity : AppCompatActivity() {

    private var shown: BatteryAlarmMonitor.Ring? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        BatteryAlarmMonitor.ring.observe(this) { r ->
            if (r == null) finish() else if (r !== shown) { shown = r; render(r) }
        }
    }

    private fun stop() {
        BleService.instance?.stopRinging()
        finish()
    }

    private fun render(r: BatteryAlarmMonitor.Ring) {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun tv(s: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
            text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        fun button(s: String, color: Int, size: Float, height: Int, onClick: () -> Unit) = tv(s, size, Color.WHITE, true).apply {
            minHeight = px(height)
            setPadding(px(12), px(12), px(12), px(12))
            background = GradientDrawable().apply { cornerRadius = 28 * dp; setColor(color) }
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        val accent = Color.parseColor(r.level.color)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(tv(r.title, 24f, Color.WHITE, true))
            addView(tv("⁦" + r.big + "⁩", 72f, accent, true).apply { setPadding(0, px(16), 0, px(8)) })
            addView(tv(getString(r.level.label), 18f, accent, true).apply {
                setPadding(px(20), px(6), px(20), px(6))
                background = GradientDrawable().apply { cornerRadius = 20 * dp; setStroke(px(2), accent) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(tv(r.text, 19f, Color.parseColor("#E6E9F2"), false).apply { setPadding(0, px(20), 0, 0); setLineSpacing(4 * dp, 1f) })
            r.note?.let { addView(tv(it, 15f, Color.parseColor("#9BA3B8"), false).apply { setPadding(0, px(14), 0, 0) }) }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1118"))
            setPadding(px(24), px(40), px(24), px(32))
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            // Large: to be hit at a glance, with gloves, on a handlebar
            val snooze = r.snoozeMin
            if (snooze != null) {
                addView(button(getString(R.string.away_done), Color.parseColor("#2E9E5B"), 24f, 104) {
                    BleService.instance?.alarmUnplugged() ?: ChargeTimer.cancel(this@AlarmActivity, byUser = true); finish()
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(14) })
                addView(button(getString(R.string.alarm_snooze, snooze), Color.parseColor("#3A3F55"), 20f, 80) {
                    BleService.instance?.alarmSnooze(); finish()
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            } else addView(button(getString(R.string.alarm_stop), Color.parseColor("#D9443F"), 26f, 112) { stop() },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        window.decorView.layoutDirection = resources.configuration.layoutDirection
        window.statusBarColor = Color.parseColor("#0F1118")
        window.navigationBarColor = Color.parseColor("#0F1118")
        window.decorView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    companion object {
        fun intent(ctx: Context): Intent = Intent(ctx, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
