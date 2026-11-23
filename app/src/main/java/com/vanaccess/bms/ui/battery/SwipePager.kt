// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui.battery

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Page container that switches the Battery sub-tabs with a side swipe, without disturbing
 * vertical scrolling:
 *  - it only takes the gesture when the finger moves clearly sideways (|dx| > 2·|dy|, past the
 *    touch slop); once a page has started scrolling vertically the scroll view keeps it;
 *  - a swipe that starts on something that scrolls sideways itself (chips row, charts, tables)
 *    is left to that view;
 *  - the page follows the finger, then either changes (¼ width or a fling) or springs back.
 * Direction follows the layout: in Arabic (RTL) the next tab is to the left, so a swipe to the
 * right moves forward.
 */
class SwipePager @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {

    /** Called with true for the next tab, false for the previous; return false if there is none. */
    var onSwipe: ((next: Boolean) -> Boolean)? = null
    var enabledSwipe = true

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity * 4
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var blocked = false
    private var velocity: VelocityTracker? = null

    private val rtl get() = layoutDirection == View.LAYOUT_DIRECTION_RTL
    private fun page(): View? = (0 until childCount).map { getChildAt(it) }.firstOrNull { it.visibility == View.VISIBLE }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (!enabledSwipe || onSwipe == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragging = false
                blocked = horizontalScrollerAt(this, e.rawX.toInt(), e.rawY.toInt())
                velocity?.recycle(); velocity = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val dx = e.x - downX; val dy = e.y - downY
                if (!blocked && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 2) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return e.actionMasked == MotionEvent.ACTION_DOWN && onSwipe != null && enabledSwipe
        velocity?.addMovement(e)
        val dx = e.x - downX
        val p = page()
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> { p?.translationX = dx * 0.6f; p?.alpha = 1f - minOf(0.5f, abs(dx) / width) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val go = e.actionMasked == MotionEvent.ACTION_UP &&
                    (abs(dx) > width / 4f || (abs(vx) > minFling && vx * dx > 0))
                val next = if (rtl) dx > 0 else dx < 0
                val changed = go && onSwipe?.invoke(next) == true
                if (changed) {
                    p?.translationX = 0f; p?.alpha = 1f
                    // the new page slides in from the side the finger came from
                    page()?.let { np ->
                        np.translationX = if (dx < 0) width * 0.25f else -width * 0.25f
                        np.alpha = 0.3f
                        np.animate().translationX(0f).alpha(1f).setDuration(180).start()
                    }
                } else p?.animate()?.translationX(0f)?.alpha(1f)?.setDuration(160)?.start()
                dragging = false
                velocity?.recycle(); velocity = null
            }
        }
        return true
    }

    /** Is there, under this screen point, a view that scrolls sideways by itself? */
    private fun horizontalScrollerAt(v: View, x: Int, y: Int): Boolean {
        if (v.visibility != View.VISIBLE) return false
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        if (x < loc[0] || y < loc[1] || x > loc[0] + v.width || y > loc[1] + v.height) return false
        if (v !== this && (v.canScrollHorizontally(1) || v.canScrollHorizontally(-1) ||
                v is com.github.mikephil.charting.charts.Chart<*>)) return true
        if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) if (horizontalScrollerAt(v.getChildAt(i), x, y)) return true
        return false
    }
}
