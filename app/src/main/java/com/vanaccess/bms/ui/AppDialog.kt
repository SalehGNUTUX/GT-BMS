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


    /** Simple information box with one OK button (green). */
    fun info(ctx: Context, title: String, message: String) =
        show(ctx, title, message = message, buttons = listOf(Btn(ctx.getString(android.R.string.ok), GREEN)))

    /** Dark text field for dialogs, styled like the app. */
    fun input(ctx: Context, value: String, hint: String, inputType: Int): android.widget.EditText {
        val dp = ctx.resources.displayMetrics.density
        return android.widget.EditText(ctx).apply {
            setText(value); this.hint = hint; this.inputType = inputType
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#6F7890")); textSize = 16f
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp; setColor(Color.parseColor("#262A3B"))
                setStroke((1 * dp).toInt(), Color.parseColor("#3A3F55"))
            }
        }
    }

    /**
     * @param items   optional choice list (tap = pick and close); [checked] marks the current one
     * @param tall    give the body most of the screen height (long reports)
     */
    fun show(ctx: Context, title: String, message: String? = null, body: View? = null, buttons: List<Btn> = emptyList(),
             items: List<String>? = null, checked: Int = -1, onItem: ((Int) -> Unit)? = null, tall: Boolean = false): Dialog {
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
        // Message and body scroll inside a capped area; the buttons below always stay visible
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        message?.let {
            content.addView(TextView(ctx).apply {
                text = it; textSize = 15f; setTextColor(Color.parseColor("#C9CFDD"))
                setLineSpacing(2 * dp, 1f)
                setPadding(0, 0, 0, (14 * dp).toInt())
            })
        }
        body?.let { content.addView(it) }
        if (message != null || body != null) {
            val screenH = ctx.resources.displayMetrics.heightPixels
            if (tall) {
                card.addView(ScrollView(ctx).apply { addView(content) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            } else {
                val maxH = (screenH * (if (items != null) 0.25 else 0.6)).toInt()
                card.addView(object : ScrollView(ctx) {
                    override fun onMeasure(w: Int, h: Int) =
                        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
                }.apply { isFillViewport = false; addView(content) })
            }
        }
        items?.let { list ->
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            list.forEachIndexed { i, label ->
                val sel = i == checked
                box.addView(TextView(ctx).apply {
                    text = (if (sel) "●  " else "") + label
                    textSize = 15f
                    setTextColor(if (sel) Color.parseColor("#4DA3FF") else Color.WHITE)
                    if (sel) setTypeface(typeface, Typeface.BOLD)
                    setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
                    background = GradientDrawable().apply {
                        cornerRadius = 12 * dp
                        setColor(Color.parseColor(if (sel) "#1F2E4A" else "#262A3B"))
                    }
                    isClickable = true; isFocusable = true
                    setOnClickListener { d.dismiss(); onItem?.invoke(i) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = (6 * dp).toInt() })
            }
            if (list.size > 7) {
                card.addView(ScrollView(ctx).apply { addView(box) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (ctx.resources.displayMetrics.heightPixels * 0.55).toInt()))
            } else card.addView(box)
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
        if (buttons.isNotEmpty()) card.addView(row)

        d.setContentView(card)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = (ctx.resources.displayMetrics.widthPixels * 0.9).toInt()
            setLayout(w, if (tall) (ctx.resources.displayMetrics.heightPixels * 0.82).toInt()
                         else ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.show()
        return d
    }
}
