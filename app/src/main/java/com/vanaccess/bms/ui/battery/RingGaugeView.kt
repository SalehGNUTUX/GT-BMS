// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/** "Ring" Live design: 270° arc filled to the state of charge, percentage in the middle. */
class RingGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var level: Int = 0
        set(v) { field = v.coerceIn(0, 100); invalidate() }

    var charging: Boolean = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 14 * d; strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#262A3B")
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 14 * d; strokeCap = Paint.Cap.ROUND
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = Color.WHITE
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = Color.parseColor("#9BA3B8")
    }
    private val r = RectF()

    fun colorFor(level: Int): Int = Color.parseColor(when {
        level >= 50 -> "#43D17A"; level >= 25 -> "#F5B83D"; else -> "#F0544F"
    })

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val pad = track.strokeWidth / 2 + 2 * d
        val left = (width - s) / 2 + pad; val top = (height - s) / 2 + pad
        r.set(left, top, left + s - 2 * pad, top + s - 2 * pad)
        canvas.drawArc(r, 135f, 270f, false, track)
        arc.color = colorFor(level)
        if (level > 0) canvas.drawArc(r, 135f, 270f * level / 100f, false, arc)
        big.textSize = s * 0.26f
        big.color = colorFor(level)
        val cy = height / 2f + big.textSize * 0.35f
        canvas.drawText("$level%", width / 2f, cy, big)
        small.textSize = s * 0.09f
        canvas.drawText(if (charging) "⚡" else "SOC", width / 2f, cy + small.textSize * 1.6f, small)
    }
}
