// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.battery

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * More Live designs, drawn by one view ([style]):
 *  - "liquid": the battery holds a liquid whose surface waves while current flows (faster with
 *    more current); bubbles rise while charging;
 *  - "cells": a grid of 20 small blocks (5 % each), the next block blinks while charging and the
 *    top one while discharging;
 *  - "horizontal": a wide battery filled from the start side of the reading direction;
 *  - "arc": a dial with red, orange, yellow and green zones and a needle at the charge.
 * Colours follow the level as in the other designs: red below 25 %, orange, yellow, green from 50 %.
 */
class StyledGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var style: String = "liquid"
        set(v) { field = v; invalidate() }

    var level: Int = 0
        set(v) { field = v.coerceIn(0, 100); invalidate() }

    /** + charging, − discharging (A). */
    var current: Double = 0.0
        set(v) { field = v; updateMotion() }

    /** Off for static rendering (widget). */
    var animate: Boolean = true

    private val d = resources.displayMetrics.density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = GREY }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    private val path = Path()
    private var phase = 0f
    private var animator: ValueAnimator? = null

    private fun levelColor(p: Int) = when { p < 15 -> RED; p < 25 -> ORANGE; p < 50 -> YELLOW; else -> GREEN }

    private fun updateMotion() {
        val flowing = abs(current) > 0.3 && animate
        if (!flowing) { animator?.cancel(); animator = null; phase = 0f; invalidate(); return }
        val period = (2400 - abs(current).coerceAtMost(40.0) * 45).toLong().coerceAtLeast(500)
        if (animator?.duration == period) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = period; repeatCount = ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        when (style) {
            "cells" -> drawCells(canvas)
            "horizontal" -> drawHorizontal(canvas)
            "arc" -> drawArc(canvas)
            else -> drawLiquid(canvas)
        }
        fill.alpha = 255
    }

    /** Vertical battery body; returns the inner rectangle. */
    private fun body(canvas: Canvas): RectF {
        val w = width.toFloat(); val h = height.toFloat()
        val capH = h * 0.06f; val capW = w * 0.38f
        fill.color = GREY
        r.set((w - capW) / 2, 0f, (w + capW) / 2, capH + 2 * d)
        canvas.drawRoundRect(r, 3 * d, 3 * d, fill)
        val pad = outline.strokeWidth / 2
        r.set(pad, capH, w - pad, h - pad)
        canvas.drawRoundRect(r, 12 * d, 12 * d, outline)
        val inset = 6 * d
        return RectF(pad + inset, capH + inset, w - pad - inset, h - pad - inset)
    }

    private fun drawLiquid(canvas: Canvas) {
        val inner = body(canvas)
        val surface = inner.bottom - inner.height() * level / 100f
        val flowing = abs(current) > 0.3
        val amp = if (flowing && level in 1..99) 4 * d else 0f
        path.reset()
        path.moveTo(inner.left, inner.bottom)
        val steps = 24
        for (k in 0..steps) {
            val x = inner.left + inner.width() * k / steps
            val y = surface + amp * sin((k.toFloat() / steps * 2 * Math.PI + phase * 2 * Math.PI)).toFloat()
            path.lineTo(x, y.coerceIn(inner.top, inner.bottom))
        }
        path.lineTo(inner.right, inner.bottom); path.close()
        canvas.save()
        r.set(inner); val clip = Path().apply { addRoundRect(r, 8 * d, 8 * d, Path.Direction.CW) }
        canvas.clipPath(clip)
        fill.color = Color.parseColor("#14FFFFFF"); canvas.drawRect(inner, fill)
        fill.color = levelColor(level); canvas.drawPath(path, fill)
        // A lighter band under the surface gives it depth
        fill.color = Color.WHITE; fill.alpha = 40
        canvas.drawRect(inner.left, surface + amp, inner.right, (surface + amp + 6 * d).coerceAtMost(inner.bottom), fill)
        fill.alpha = 255
        // Bubbles rise while charging
        if (current > 0.3 && level > 3) {
            fill.color = Color.WHITE; fill.alpha = 110
            for (b in 0 until 4) {
                val t = (phase + b / 4f) % 1f
                val x = inner.left + inner.width() * (0.2f + 0.2f * b)
                val y = inner.bottom - (inner.bottom - surface) * t
                canvas.drawCircle(x, y, (2.2f + b % 2) * d, fill)
            }
        }
        canvas.restore()
    }

    private fun drawCells(canvas: Canvas) {
        val inner = body(canvas)
        val cols = 2; val rows = 10; val gap = 3 * d
        val cw = (inner.width() - gap * (cols - 1)) / cols
        val ch = (inner.height() - gap * (rows - 1)) / rows
        val filled = if (level <= 0) 0 else ceil(level / 5.0).toInt()
        val blink = abs(current) > 0.3
        val on = 0.35f + 0.65f * abs(sin(phase * Math.PI)).toFloat()
        for (i in 0 until rows * cols) {
            val row = i / cols; val col = i % cols
            val y2 = inner.bottom - row * (ch + gap); val y1 = y2 - ch
            val x1 = inner.left + col * (cw + gap)
            r.set(x1, y1, x1 + cw, y2)
            when {
                i < filled -> {
                    fill.color = levelColor(level)
                    fill.alpha = if (blink && current < 0 && i == filled - 1) (255 * on).toInt() else 255
                }
                blink && current > 0 && i == filled -> { fill.color = GREEN; fill.alpha = (200 * on).toInt() }
                else -> { fill.color = Color.WHITE; fill.alpha = 22 }
            }
            canvas.drawRoundRect(r, 3 * d, 3 * d, fill)
        }
    }

    private fun drawHorizontal(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val capW = w * 0.05f; val capH = h * 0.4f
        val pad = outline.strokeWidth / 2
        val bodyL = if (rtl) capW else pad; val bodyR = if (rtl) w - pad else w - capW
        fill.color = GREY
        if (rtl) r.set(0f, (h - capH) / 2, capW + 2 * d, (h + capH) / 2) else r.set(w - capW - 2 * d, (h - capH) / 2, w, (h + capH) / 2)
        canvas.drawRoundRect(r, 3 * d, 3 * d, fill)
        r.set(bodyL, pad, bodyR, h - pad)
        canvas.drawRoundRect(r, 12 * d, 12 * d, outline)
        val inset = 6 * d
        val inner = RectF(bodyL + inset, pad + inset, bodyR - inset, h - pad - inset)
        fill.color = Color.WHITE; fill.alpha = 20; canvas.drawRoundRect(inner, 7 * d, 7 * d, fill)
        val len = inner.width() * level / 100f
        // Fills from where reading starts: the right in Arabic, the left otherwise
        if (rtl) r.set(inner.right - len, inner.top, inner.right, inner.bottom) else r.set(inner.left, inner.top, inner.left + len, inner.bottom)
        fill.color = levelColor(level); fill.alpha = 255
        if (len > 0) canvas.drawRoundRect(r, 7 * d, 7 * d, fill)
        // A light moving along the charge while current flows (towards the full side when charging)
        if (abs(current) > 0.3 && len > 8 * d) {
            val t = if (current > 0) phase else 1 - phase
            val x = if (rtl) r.right - r.width() * t else r.left + r.width() * t
            fill.color = Color.WHITE; fill.alpha = 70
            canvas.drawRect((x - 5 * d).coerceAtLeast(r.left), r.top, (x + 5 * d).coerceAtMost(r.right), r.bottom, fill)
        }
    }

    private fun drawArc(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val sw = 14 * d
        val size = minOf(w, h * 1.25f) - sw
        val cx = w / 2; val cy = sw / 2 + size / 2 + (h - size * 0.8f) / 4
        r.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        val start = 150f; val sweep = 240f
        stroke.strokeWidth = sw
        // Background track, then the zones up to the level
        stroke.color = Color.parseColor("#262B3A"); canvas.drawArc(r, start, sweep, false, stroke)
        val zones = listOf(0 to 15 to RED, 15 to 25 to ORANGE, 25 to 50 to YELLOW, 50 to 100 to GREEN)
        zones.forEach { (span, c) ->
            val (a, b) = span
            stroke.color = c; stroke.alpha = 70
            canvas.drawArc(r, start + sweep * a / 100f, sweep * (b - a) / 100f, false, stroke)
            if (level > a) { stroke.alpha = 255; canvas.drawArc(r, start + sweep * a / 100f, sweep * (minOf(level, b) - a) / 100f, false, stroke) }
        }
        stroke.alpha = 255
        // Needle
        val ang = Math.toRadians((start + sweep * level / 100f).toDouble())
        val len = size / 2 - sw * 1.2f
        stroke.color = Color.WHITE; stroke.strokeWidth = 3.5f * d
        canvas.drawLine(cx, cy, cx + (len * cos(ang)).toFloat(), cy + (len * sin(ang)).toFloat(), stroke)
        fill.color = if (current > 0.3) GREEN else if (current < -0.3) BLUE else GREY
        val pulse = if (abs(current) > 0.3) 1f + 0.3f * sin(phase * 2 * Math.PI).toFloat() else 1f
        canvas.drawCircle(cx, cy, 7 * d * pulse, fill)
    }

    companion object {
        private val GREY = Color.parseColor("#4A5068")
        private val RED = Color.parseColor("#F0544F")
        private val ORANGE = Color.parseColor("#F28B3C")
        private val YELLOW = Color.parseColor("#F5B83D")
        private val GREEN = Color.parseColor("#43D17A")
        private val BLUE = Color.parseColor("#4DA3FF")
        val STYLES = listOf("liquid", "cells", "horizontal", "arc")
    }
}
