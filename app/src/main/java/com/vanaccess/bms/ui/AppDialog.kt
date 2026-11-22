// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The app's own dialog: dark rounded card like the rest of GT-BMS, with filled colour buttons
 * and white text. (System AlertDialog buttons are Material buttons that ignore background
 * colours, so they could not be styled reliably.)
 *
 * Buttons are laid out in reading order: in Arabic the first one is on the right.
 */
object AppDialog {

    val GREEN = Color.parseColor("#2E9E5B")
    val RED = Color.parseColor("#D9443F")
    val GREY = Color.parseColor("#3A3F55")

    data class Btn(val text: String, val color: Int, val onClick: (() -> Unit)? = null)

    fun show(ctx: Context, title: String, message: String? = null, body: View? = null, buttons: List<Btn>): Dialog {
        val dp = ctx.resources.displayMetrics.density
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (18 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 22 * dp
                setColor(Color.parseColor("#1E2130"))
                setStroke((1 * dp).toInt(), Color.parseColor("#2C3044"))
            }
        }
        card.addView(TextView(ctx).apply {
            text = title; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, (10 * dp).toInt())
        })
        message?.let {
            card.addView(TextView(ctx).apply {
                text = it; textSize = 15f; setTextColor(Color.parseColor("#C9CFDD"))
                setPadding(0, 0, 0, (14 * dp).toInt())
            })
        }
        body?.let {
            val scroll = ScrollView(ctx).apply { addView(it) }
            card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (14 * dp).toInt(), 0, 0)
        }
        buttons.forEach { b ->
            row.addView(TextView(ctx).apply {
                text = b.text; textSize = 15f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding((18 * dp).toInt(), (11 * dp).toInt(), (18 * dp).toInt(), (11 * dp).toInt())
                background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(b.color) }
                isClickable = true; isFocusable = true
                setOnClickListener { d.dismiss(); b.onClick?.invoke() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (6 * dp).toInt(); marginEnd = (6 * dp).toInt()
            })
        }
        card.addView(row)

        d.setContentView(card)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = (ctx.resources.displayMetrics.widthPixels * 0.9).toInt()
            setLayout(w, if (body != null) (ctx.resources.displayMetrics.heightPixels * 0.82).toInt()
                         else ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.show()
        return d
    }
}
