// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Vertical battery drawing filled to the state of charge, colour-coded by level. */
class BatteryGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var level: Int = 0
        set(v) { field = v.coerceIn(0, 100); invalidate() }

    /** Show a lightning bolt while charging. */
    var charging: Boolean = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1AFFFFFF") }
    private val bolt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val r = RectF()

    fun colorFor(level: Int): Int = Color.parseColor(
        when {
            level >= 50 -> "#43D17A"
            level >= 25 -> "#F5B83D"
            else -> "#F0544F"
        }
    )

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val capH = h * 0.06f; val capW = w * 0.38f
        val c = colorFor(level)
        outline.color = c
        fill.color = c
        // terminal cap
        r.set((w - capW) / 2, 0f, (w + capW) / 2, capH + 2 * d)
        canvas.drawRoundRect(r, 3 * d, 3 * d, fill)
        // body
        val pad = outline.strokeWidth / 2
        r.set(pad, capH, w - pad, h - pad)
        canvas.drawRoundRect(r, 12 * d, 12 * d, outline)
        // inner track + fill from the bottom
        val inset = 7 * d
        val inner = RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
        canvas.drawRoundRect(inner, 7 * d, 7 * d, track)
        val top = inner.bottom - inner.height() * level / 100f
        r.set(inner.left, top, inner.right, inner.bottom)
        if (level > 0) canvas.drawRoundRect(r, 7 * d, 7 * d, fill)
        if (charging) {
            val cx = w / 2; val cy = capH + (h - capH) / 2; val s = w * 0.22f
            val p = android.graphics.Path().apply {
                moveTo(cx + s * 0.2f, cy - s * 1.3f)
                lineTo(cx - s * 0.7f, cy + s * 0.15f)
                lineTo(cx - s * 0.05f, cy + s * 0.15f)
                lineTo(cx - s * 0.25f, cy + s * 1.3f)
                lineTo(cx + s * 0.7f, cy - s * 0.15f)
                lineTo(cx + s * 0.05f, cy - s * 0.15f)
                close()
            }
            canvas.drawPath(p, bolt)
        }
    }
}
