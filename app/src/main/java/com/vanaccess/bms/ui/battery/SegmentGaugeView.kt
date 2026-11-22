// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.battery

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil

/**
 * "Segments" Live design: a battery made of 10 bars. Filled bars follow the state of charge
 * and are coloured as a level ladder (red → orange → yellow → green from the bottom). The top
 * filled bar shows the flow: it pulses blue while discharging and green while charging, and
 * pulses faster the higher the current.
 */
class SegmentGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var level: Int = 0
        set(v) { field = v.coerceIn(0, 100); invalidate() }

    /** + charging, − discharging (A). */
    var current: Double = 0.0
        set(v) { field = v; updatePulse() }

    /** Off for static rendering (widget). */
    var animate: Boolean = true

    private val d = resources.displayMetrics.density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.parseColor("#4A5068")
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var pulse = 1f
    private var animator: ValueAnimator? = null

    private fun ladder(i: Int) = Color.parseColor(when (i) {
        0, 1 -> "#F0544F"; 2 -> "#F28B3C"; 3, 4 -> "#F5B83D"; else -> "#43D17A"
    })

    private fun updatePulse() {
        val flowing = abs(current) > 0.3 && animate
        if (!flowing) { animator?.cancel(); animator = null; pulse = 1f; invalidate(); return }
        val period = (1600 - abs(current).coerceAtMost(40.0) * 30).toLong().coerceAtLeast(400)
        if (animator?.duration == period) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(1f, 0.25f).apply {
            duration = period; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val capH = h * 0.06f; val capW = w * 0.38f
        fill.color = Color.parseColor("#4A5068")
        r.set((w - capW) / 2, 0f, (w + capW) / 2, capH + 2 * d)
        canvas.drawRoundRect(r, 3 * d, 3 * d, fill)
        val pad = outline.strokeWidth / 2
        r.set(pad, capH, w - pad, h - pad)
        canvas.drawRoundRect(r, 12 * d, 12 * d, outline)

        val inset = 7 * d; val gap = 3.5f * d; val n = 10
        val top = capH + inset; val bottom = h - pad - inset
        val segH = (bottom - top - gap * (n - 1)) / n
        val filled = if (level <= 0) 0 else ceil(level / 10.0).toInt()
        for (i in 0 until n) {
            val y2 = bottom - i * (segH + gap); val y1 = y2 - segH
            r.set(pad + inset, y1, w - pad - inset, y2)
            if (i < filled) {
                val isTop = i == filled - 1
                fill.color = when {
                    isTop && current > 0.3 -> Color.parseColor("#43D17A")
                    isTop && current < -0.3 -> Color.parseColor("#4DA3FF")
                    else -> ladder(i)
                }
                fill.alpha = if (isTop && abs(current) > 0.3) (255 * pulse).toInt() else 255
            } else {
                fill.color = Color.parseColor("#1AFFFFFF"); fill.alpha = 26
            }
            canvas.drawRoundRect(r, 4 * d, 4 * d, fill)
        }
        fill.alpha = 255
    }
}
