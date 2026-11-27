// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui

import android.content.Context
import com.vanaccess.bms.R
import com.vanaccess.bms.text.Bidi

/** Durations and values in the interface's language and direction (see core [Bidi]). */
object Fmt {
    fun dur(ctx: Context, seconds: Double): String =
        Bidi.duration(seconds, ctx.getString(R.string.dur_hm), ctx.getString(R.string.dur_m), ctx.getString(R.string.dur_s))

    fun value(s: String) = Bidi.value(s)

    fun rtl(ctx: Context) = ctx.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL

    /** Parts joined in the interface's direction, for views the system lays out (notifications, widgets). */
    fun line(ctx: Context, vararg parts: String?) = Bidi.paragraph(Bidi.joinAll(parts.toList()), rtl(ctx))
}
