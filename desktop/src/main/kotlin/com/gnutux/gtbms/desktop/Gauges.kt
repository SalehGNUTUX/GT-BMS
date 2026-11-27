// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** The phone's battery designs (Settings › Preferences on both), drawn the same way. */
object Gauges {
    /** (id, phone string) in the phone's order. */
    val DESIGNS = listOf("segments" to "design_segments", "classic" to "design_classic", "ring" to "design_ring",
        "liquid" to "design_liquid", "cells" to "design_cells", "cells_mono" to "design_cells_mono",
        "horizontal" to "design_horizontal", "arc" to "design_arc")

    val GREY = Color(0xFF4A5068)
    val RED = Color(0xFFF0544F); val ORANGE = Color(0xFFF28B3C); val YELLOW = Color(0xFFF5B83D)
    val GREEN = Color(0xFF43D17A); val BLUE = Color(0xFF4DA3FF)
    fun levelColor(p: Int) = when { p < 15 -> RED; p < 25 -> ORANGE; p < 50 -> YELLOW; else -> GREEN }
    fun ladder(i: Int) = when (i) { 0, 1 -> RED; 2 -> ORANGE; 3, 4 -> YELLOW; else -> GREEN }
}

/** One battery drawing; moves with the current (faster with more amps) like on the phone. */
@Composable
fun BatteryGauge(design: String, soc: Int, current: Double, modifier: Modifier = Modifier, height: Dp = 170.dp) {
    val flowing = abs(current) > 0.3
    val period = (2400 - abs(current).coerceAtMost(40.0) * 45).toInt().coerceAtLeast(500)
    val inf = rememberInfiniteTransition()
    val phase by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(if (flowing) period else 4000, easing = LinearEasing)))
    val level = soc.coerceIn(0, 100)
    val (w, h) = when (design) { "horizontal" -> height * 1.9f to height * 0.8f; "ring", "arc" -> height to height; else -> height * 0.56f to height }
    Canvas(modifier.size(w, h)) {
        when (design) {
            "ring" -> ring(level, current, phase)
            "arc" -> arc(level, current, phase)
            "horizontal" -> horizontal(level, current, phase, flowing)
            "liquid" -> liquid(level, current, phase, flowing)
            "cells" -> cells(level, current, phase, ladder = true)
            "cells_mono" -> cells(level, current, phase, ladder = false)
            "classic" -> classic(level, current)
            else -> segments(level, current, phase)
        }
    }
}

private fun DrawScope.body(): Rect {
    val d = density
    val capH = size.height * 0.06f; val capW = size.width * 0.38f
    drawRoundRect(Gauges.GREY, Offset((size.width - capW) / 2, 0f), Size(capW, capH + 2 * d), CornerRadius(3 * d))
    val sw = 3 * d
    drawRoundRect(Gauges.GREY, Offset(sw / 2, capH), Size(size.width - sw, size.height - capH - sw / 2), CornerRadius(12 * d), style = Stroke(sw))
    val inset = 7 * d
    return Rect(sw / 2 + inset, capH + inset, size.width - sw / 2 - inset, size.height - sw / 2 - inset)
}

private fun DrawScope.segments(level: Int, current: Double, phase: Float) {
    val r = body(); val d = density
    val n = 10; val gap = 3.5f * d
    val segH = (r.height - gap * (n - 1)) / n
    val filled = if (level <= 0) 0 else ceil(level / 10.0).toInt()
    val pulse = 0.25f + 0.75f * abs(sin(phase * Math.PI)).toFloat()
    for (i in 0 until n) {
        val y2 = r.bottom - i * (segH + gap)
        val top = i == filled - 1
        val c = when {
            i >= filled -> Color.White.copy(alpha = 0.1f)
            top && current > 0.3 -> Gauges.GREEN.copy(alpha = pulse)
            top && current < -0.3 -> Gauges.BLUE.copy(alpha = pulse)
            else -> Gauges.ladder(i)
        }
        drawRoundRect(c, Offset(r.left, y2 - segH), Size(r.width, segH), CornerRadius(4 * d))
    }
}

private fun DrawScope.classic(level: Int, current: Double) {
    val r = body(); val d = density
    val fillH = r.height * level / 100f
    drawRoundRect(Color.White.copy(alpha = 0.08f), r.topLeft, r.size, CornerRadius(8 * d))
    drawRoundRect(if (current > 0.3) Gauges.GREEN else Gauges.levelColor(level), Offset(r.left, r.bottom - fillH), Size(r.width, fillH), CornerRadius(8 * d))
}

private fun DrawScope.cells(level: Int, current: Double, phase: Float, ladder: Boolean) {
    val r = body(); val d = density
    val cols = 2; val rows = 10; val gap = 3 * d
    val cw = (r.width - gap * (cols - 1)) / cols; val ch = (r.height - gap * (rows - 1)) / rows
    val filled = if (level <= 0) 0 else ceil(level / 5.0).toInt()
    val on = 0.35f + 0.65f * abs(sin(phase * Math.PI)).toFloat()
    for (i in 0 until rows * cols) {
        val row = i / cols; val col = i % cols
        val y2 = r.bottom - row * (ch + gap); val x1 = r.left + col * (cw + gap)
        val c = when {
            i < filled -> (if (ladder) Gauges.ladder(row) else Gauges.levelColor(level)).let { if (current < -0.3 && i == filled - 1) it.copy(alpha = on) else it }
            current > 0.3 && i == filled -> Gauges.GREEN.copy(alpha = 0.8f * on)
            else -> Color.White.copy(alpha = 0.09f)
        }
        drawRoundRect(c, Offset(x1, y2 - ch), Size(cw, ch), CornerRadius(3 * d))
    }
}

/** Deep blue when full, paler as it empties; waves always a little, more with the current. */
private fun liquidColor(p: Int): Color {
    val f = 0.35f + 0.65f * p / 100f
    return Color((110 - 80 * f) / 255f, (175 - 65 * f) / 255f, 1f, (90 + 165 * f) / 255f)
}

private fun DrawScope.liquid(level: Int, current: Double, phase: Float, flowing: Boolean) {
    val r = body(); val d = density
    val surface = r.bottom - r.height * level / 100f
    val amp = if (level in 1..99) (if (flowing) 4.5f else 2f) * d else 0f
    val path = Path().apply {
        moveTo(r.left, r.bottom)
        for (k in 0..28) {
            val x = r.left + r.width * k / 28
            lineTo(x, (surface + amp * sin(k / 28f * 2 * Math.PI + phase * 2 * Math.PI).toFloat()).coerceIn(r.top, r.bottom))
        }
        lineTo(r.right, r.bottom); close()
    }
    val clip = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(r, CornerRadius(8 * d))) }
    clipPath(clip) {
        drawRect(Color.White.copy(alpha = 0.08f), r.topLeft, r.size)
        drawPath(path, Brush.verticalGradient(listOf(liquidColor((level - 10).coerceAtLeast(0)), liquidColor((level + 25).coerceAtMost(100))), surface, r.bottom))
        if (level in 1..99) drawLine(Color.White.copy(alpha = 0.35f), Offset(r.left, surface), Offset(r.right, surface), 2 * d)
        if (current > 0.3 && level > 3) for (b in 0 until 4) {
            val t = (phase + b / 4f) % 1f
            drawCircle(Color.White.copy(alpha = 0.45f), (2.2f + b % 2) * d, Offset(r.left + r.width * (0.2f + 0.2f * b), r.bottom - (r.bottom - surface) * t))
        }
    }
}

private fun DrawScope.horizontal(level: Int, current: Double, phase: Float, flowing: Boolean) {
    val d = density; val rtl = Tr.rtl
    val capW = size.width * 0.05f; val capH = size.height * 0.4f; val sw = 3 * d
    val left = if (rtl) capW else sw / 2; val right = if (rtl) size.width - sw / 2 else size.width - capW
    drawRoundRect(Gauges.GREY, Offset(if (rtl) 0f else size.width - capW - 2 * d, (size.height - capH) / 2), Size(capW + 2 * d, capH), CornerRadius(3 * d))
    drawRoundRect(Gauges.GREY, Offset(left, sw / 2), Size(right - left, size.height - sw), CornerRadius(12 * d), style = Stroke(sw))
    val inset = 7 * d
    val r = Rect(left + inset, sw / 2 + inset, right - inset, size.height - sw / 2 - inset)
    drawRoundRect(Color.White.copy(alpha = 0.08f), r.topLeft, r.size, CornerRadius(7 * d))
    val len = r.width * level / 100f
    val x0 = if (rtl) r.right - len else r.left
    if (len > 0) drawRoundRect(Gauges.levelColor(level), Offset(x0, r.top), Size(len, r.height), CornerRadius(7 * d))
    if (flowing && len > 8 * d) {
        val t = if (current > 0) phase else 1 - phase
        val x = if (rtl) r.right - len * t else r.left + len * t
        drawRect(Color.White.copy(alpha = 0.28f), Offset((x - 5 * d).coerceAtLeast(x0), r.top), Size(10 * d, r.height))
    }
}

private fun DrawScope.ring(level: Int, current: Double, phase: Float) {
    val d = density; val sw = 14 * d
    val s = minOf(size.width, size.height) - sw
    val tl = Offset((size.width - s) / 2, (size.height - s) / 2)
    drawArc(Color(0xFF262B3A), 0f, 360f, false, tl, Size(s, s), style = Stroke(sw))
    val c = if (current > 0.3) Gauges.GREEN else Gauges.levelColor(level)
    drawArc(c, -90f, 360f * level / 100f, false, tl, Size(s, s), style = Stroke(sw, cap = StrokeCap.Round))
    if (abs(current) > 0.3) {
        val a = Math.toRadians((-90 + 360 * phase).toDouble())
        drawCircle(Color.White.copy(alpha = 0.7f), 4 * d, Offset(size.width / 2 + (s / 2 * cos(a)).toFloat(), size.height / 2 + (s / 2 * sin(a)).toFloat()))
    }
}

private fun DrawScope.arc(level: Int, current: Double, phase: Float) {
    val d = density; val sw = 14 * d
    val s = minOf(size.width, size.height) - sw
    val tl = Offset((size.width - s) / 2, (size.height - s) / 2 + s * 0.08f)
    val start = 150f; val sweep = 240f
    drawArc(Color(0xFF262B3A), start, sweep, false, tl, Size(s, s), style = Stroke(sw))
    listOf(Triple(0, 15, Gauges.RED), Triple(15, 25, Gauges.ORANGE), Triple(25, 50, Gauges.YELLOW), Triple(50, 100, Gauges.GREEN)).forEach { (a, b, c) ->
        drawArc(c.copy(alpha = 0.28f), start + sweep * a / 100f, sweep * (b - a) / 100f, false, tl, Size(s, s), style = Stroke(sw))
        if (level > a) drawArc(c, start + sweep * a / 100f, sweep * (minOf(level, b) - a) / 100f, false, tl, Size(s, s), style = Stroke(sw))
    }
    val cx = tl.x + s / 2; val cy = tl.y + s / 2
    val ang = Math.toRadians((start + sweep * level / 100f).toDouble())
    val len = s / 2 - sw * 1.2f
    drawLine(Color.White, Offset(cx, cy), Offset(cx + (len * cos(ang)).toFloat(), cy + (len * sin(ang)).toFloat()), 3.5f * d, StrokeCap.Round)
    val pulse = if (abs(current) > 0.3) 1f + 0.3f * sin(phase * 2 * Math.PI).toFloat() else 1f
    drawCircle(if (current > 0.3) Gauges.GREEN else if (current < -0.3) Gauges.BLUE else Gauges.GREY, 7 * d * pulse, Offset(cx, cy))
}
