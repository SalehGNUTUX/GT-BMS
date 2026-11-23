// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.vanaccess.bms.reports.Point
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * Offline route sketch of a trip (no map tiles): the GPS track drawn to scale, each segment
 * coloured by the battery current at that moment — green = light load, amber, red = heavy
 * throttle — so it shows where the battery was pushed. Green dot = start, red = end.
 */
class RouteView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private var pts: List<Point> = emptyList()
    private var maxA = 1.0
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint().apply { color = Color.parseColor("#15192A") }

    fun setTrack(points: List<Point>) {
        pts = points.filter { it.lat != null && it.lon != null }
        maxA = max(1.0, pts.maxOfOrNull { abs(it.i) } ?: 1.0)
        invalidate()
    }

    private fun colorFor(a: Double): Int {
        val r = (abs(a) / maxA).coerceIn(0.0, 1.0)
        return when {
            r < 0.4 -> Color.parseColor("#43D17A")
            r < 0.7 -> Color.parseColor("#F5B83D")
            else -> Color.parseColor("#F0544F")
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 14 * d, 14 * d, bg)
        if (pts.size < 2) return
        val lat0 = pts.map { it.lat!! }.average()
        val kx = cos(Math.toRadians(lat0))                    // equirectangular projection
        val xs = pts.map { it.lon!! * kx }; val ys = pts.map { it.lat!! }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val pad = 14 * d
        val span = max(maxX - minX, maxY - minY).takeIf { it > 0 } ?: return
        val scale = minOf((width - 2 * pad) / (maxX - minX).coerceAtLeast(span * 0.05),
                          (height - 2 * pad) / (maxY - minY).coerceAtLeast(span * 0.05))
        val offX = (width - (maxX - minX) * scale) / 2; val offY = (height - (maxY - minY) * scale) / 2
        fun x(i: Int) = (offX + (xs[i] - minX) * scale).toFloat()
        fun y(i: Int) = (height - offY - (ys[i] - minY) * scale).toFloat()   // north up
        for (i in 1 until pts.size) {
            line.color = colorFor(pts[i].i)
            canvas.drawLine(x(i - 1), y(i - 1), x(i), y(i), line)
        }
        dot.color = Color.parseColor("#43D17A"); canvas.drawCircle(x(0), y(0), 6 * d, dot)
        dot.color = Color.parseColor("#F0544F"); canvas.drawCircle(x(pts.size - 1), y(pts.size - 1), 6 * d, dot)
    }
}
