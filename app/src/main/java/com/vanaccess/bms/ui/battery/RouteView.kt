// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.battery

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.vanaccess.bms.map.OsmAndBridge
import com.vanaccess.bms.map.OsmAndProjection
import com.vanaccess.bms.reports.Point
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * Route of a trip, each segment coloured by the battery current at that moment (green = light
 * load, amber, red = heavy throttle). Drawn over OsmAnd's offline map when it delivers one, else
 * as a sketch on a plain background. Pinch, drag, double tap and the buttons zoom and move it;
 * line and markers keep their size. At full view one finger scrolls the page as usual.
 */
class RouteView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private var pts: List<Point> = emptyList()
    private var maxA = 1.0
    private var shot: OsmAndBridge.Shot? = null
    /** Track in view pixels at zoom 1, rebuilt with the size, track or map. */
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    /** Beyond the detail OsmAnd drew a picture only gets blurred; the sketch has no such limit. */
    private val maxZoom get() = shot?.detail?.toFloat()?.coerceAtLeast(2f) ?: 8f

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4.5f * d; strokeCap = Paint.Cap.ROUND }
    private val casing = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 7.5f * d; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#20304A") }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val photo = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bg = Paint().apply { color = Color.parseColor("#15192A") }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 11 * d; isFakeBoldText = true }
    private val clip = Path()

    val hasMap get() = shot != null

    /** Index of the first point of each piece (a day has one piece per trip). */
    private var starts: Set<Int> = emptySet()

    fun setTracks(tracks: List<List<Point>>) {
        val pieces = tracks.map { t -> t.filter { it.lat != null && it.lon != null } }.filter { it.size > 1 }
        pts = pieces.flatten()
        var n = 0
        starts = pieces.map { p -> n.also { n += p.size } }.toSet()
        maxA = max(1.0, pts.maxOfOrNull { abs(it.i) } ?: 1.0)
        project(); invalidate()
    }

    fun track(): List<Pair<Double, Double>> = pts.map { it.lat!! to it.lon!! }

    fun pieces(): List<List<Pair<Double, Double>>> = starts.sorted().let { st ->
        st.mapIndexed { k, from -> track().subList(from, st.getOrElse(k + 1) { pts.size }) } }

    fun setMap(s: OsmAndBridge.Shot?) { shot = s; fit(); project(); invalidate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        clip.reset(); clip.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 14 * d, 14 * d, Path.Direction.CW)
        fit(); project()
    }

    private fun project() {
        xs = FloatArray(0); ys = FloatArray(0)
        if (pts.size < 2 || width == 0 || height == 0) return
        val s = shot
        val p = s?.let { OsmAndProjection.of(track(), it.width, it.height, it.density) }
        if (s != null && p != null) {
            xs = FloatArray(pts.size) { p.x(pts[it].lon!!) * width / s.width }
            ys = FloatArray(pts.size) { p.y(pts[it].lat!!) * height / s.height }
            return
        }
        val kx = cos(Math.toRadians(pts.map { it.lat!! }.average()))          // equirectangular sketch
        val lx = pts.map { it.lon!! * kx }; val ly = pts.map { it.lat!! }
        val minX = lx.min(); val maxX = lx.max(); val minY = ly.min(); val maxY = ly.max()
        val pad = 18 * d
        val span = max(maxX - minX, maxY - minY).takeIf { it > 0 } ?: return
        val k = minOf((width - 2 * pad) / (maxX - minX).coerceAtLeast(span * 0.05),
                      (height - 2 * pad) / (maxY - minY).coerceAtLeast(span * 0.05))
        val offX = (width - (maxX - minX) * k) / 2; val offY = (height - (maxY - minY) * k) / 2
        xs = FloatArray(pts.size) { (offX + (lx[it] - minX) * k).toFloat() }
        ys = FloatArray(pts.size) { (height - offY - (ly[it] - minY) * k).toFloat() }   // north up
    }

    // ── Zoom and pan ─────────────────────────────────────────────────────────

    fun fit() { zoom = 1f; panX = 0f; panY = 0f; invalidate() }
    fun zoomIn() = zoomBy(1.6f, width / 2f, height / 2f)
    fun zoomOut() = zoomBy(1 / 1.6f, width / 2f, height / 2f)

    private fun zoomBy(f: Float, fx: Float, fy: Float) {
        val next = (zoom * f).coerceIn(1f, maxZoom)
        // what is under the finger stays under it
        panX = fx - (fx - panX) / zoom * next
        panY = fy - (fy - panY) / zoom * next
        zoom = next
        clampPan()
    }

    private fun clampPan() {
        panX = panX.coerceIn(width * (1f - zoom), 0f)
        panY = panY.coerceIn(height * (1f - zoom), 0f)
        invalidate()
    }

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(g: ScaleGestureDetector): Boolean { zoomBy(g.scaleFactor, g.focusX, g.focusY); return true }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(a: MotionEvent?, b: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom > 1f || b.pointerCount > 1) { panX -= dx; panY -= dy; clampPan() }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom >= maxZoom * 0.95f) fit() else zoomBy(2f, e.x, e.y)
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // The page keeps one-finger scrolling until the map is zoomed
        if (e.pointerCount > 1 || zoom > 1f) parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        gestures.onTouchEvent(e)
        return true
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    private fun colorFor(a: Double): Int {
        val r = (abs(a) / maxA).coerceIn(0.0, 1.0)
        return Color.parseColor(if (r < 0.4) "#2ECC71" else if (r < 0.7) "#F5A623" else "#F0443E")
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawPaint(bg)
        shot?.let { canvas.drawBitmap(it.bitmap, null, RectF(panX, panY, panX + width * zoom, panY + height * zoom), photo) }
        fun x(i: Int) = xs[i] * zoom + panX
        fun y(i: Int) = ys[i] * zoom + panY
        if (xs.size > 1) {
            // Points closer than a pixel and a half add nothing but work
            val keep = ArrayList<Int>(xs.size); keep += 0
            for (i in 1 until xs.size) { val k = keep.last()
                if (i == xs.size - 1 || i in starts || i + 1 in starts || hypot(x(i) - x(k), y(i) - y(k)) >= 1.5f * d) keep += i }
            if (shot != null) for (j in 1 until keep.size) if (keep[j] !in starts) canvas.drawLine(x(keep[j - 1]), y(keep[j - 1]), x(keep[j]), y(keep[j]), casing)
            for (j in 1 until keep.size) {
                if (keep[j] in starts) continue
                line.color = colorFor(pts[keep[j]].i)
                canvas.drawLine(x(keep[j - 1]), y(keep[j - 1]), x(keep[j]), y(keep[j]), line)
            }
            // Start = ring, end = diamond
            val sx = x(0); val sy = y(0); val ex = x(xs.size - 1); val ey = y(xs.size - 1)
            fill.color = Color.WHITE; canvas.drawCircle(sx, sy, 10 * d, fill)
            fill.color = Color.parseColor("#1E9E58"); canvas.drawCircle(sx, sy, 8 * d, fill)
            fill.color = Color.WHITE; canvas.drawCircle(sx, sy, 3 * d, fill)
            fill.color = Color.WHITE; diamond(canvas, ex, ey, 11.5f * d)
            fill.color = Color.parseColor("#C62828"); diamond(canvas, ex, ey, 9 * d)
        }
        if (shot != null) {
            val t = "OsmAnd"; val w = label.measureText(t)
            val r = RectF(width - w - 22 * d, 8 * d, width - 8 * d, 30 * d)
            fill.color = Color.parseColor("#CC1E2130"); canvas.drawRoundRect(r, 8 * d, 8 * d, fill)
            canvas.drawText(t, r.left + 7 * d, r.centerY() + 4 * d, label)
        }
        canvas.restore()
    }

    private val dia = Path()
    private fun diamond(c: Canvas, cx: Float, cy: Float, r: Float) {
        dia.reset(); dia.moveTo(cx, cy - r); dia.lineTo(cx + r, cy); dia.lineTo(cx, cy + r); dia.lineTo(cx - r, cy); dia.close()
        c.drawPath(dia, fill)
    }
}
