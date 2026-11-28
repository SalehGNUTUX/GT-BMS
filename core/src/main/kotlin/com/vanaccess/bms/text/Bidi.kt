// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.text

/**
 * One rule for every value shown, in any language and writing direction:
 *  - each part of a value is kept whole: numbers with units read left to right ("13.4 A"),
 *    words in the interface's language read in their own direction ("1 د");
 *  - the parts follow the direction of the interface: in Arabic the first part is on the right,
 *    so "63% – 50%" (a trip) reads from 63 down to 50, and a charge from low up to high;
 *  - the view showing it lays out by the interface's locale (not by its first letter).
 */
object Bidi {
    const val LRI = '⁦'
    const val RLI = '⁧'
    const val FSI = '⁨'
    const val PDI = '⁩'
    const val SEP = "  •  "
    private val SPLIT = Regex("\\s+[•·]\\s+")

    fun ltr(s: String) = "$LRI$s$PDI"

    fun hasRtl(s: String) = s.any {
        val d = Character.getDirectionality(it)
        d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
    }

    /** One part kept whole: already isolated parts are left as they are. */
    fun part(s: String): String {
        val t = s.trim()
        return when {
            t.isEmpty() -> t
            t[0] in LRI..FSI -> t
            // a part never breaks across lines: "0.52 km" stays whole
            hasRtl(t) -> "$FSI${t.replace(' ', '\u00A0')}$PDI"
            else -> ltr(t.replace(' ', '\u00A0'))
        }
    }

    /** A value made of parts joined by " • ": each part whole, in the interface's order. */
    fun value(s: String): String = s.split(SPLIT).filter { it.isNotBlank() }.joinToString(SEP) { part(it) }

    /** Parts (nulls and blanks skipped) joined by " • ". */
    fun join(vararg parts: String?): String = joinAll(parts.toList())
    fun joinAll(parts: List<String?>): String = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(SEP) { part(it) }

    /**
     * For text whose view we do not lay out ourselves (notifications, widgets, dialogs of the
     * system): a leading mark sets the paragraph to the interface's direction.
     */
    fun paragraph(s: String, rtl: Boolean) = (if (rtl) "\u200F" else "\u200E") + s

    /** From [a] to [b] in time order: in Arabic [a] is on the right. */
    fun range(a: String, b: String) = ltr(a) + " – " + ltr(b)

    /**
     * A duration in words of the interface: [hm] "%1$d h %2$02d min" form, [m] "%1$d min", [s] "%1$d s".
     * Under an hour only minutes, under a minute only seconds: "0 h 01 min" read like one hour.
     */
    fun duration(seconds: Double, hm: String, m: String, s: String): String {
        val total = Math.round(seconds).coerceAtLeast(0)
        val text = when {
            total < 60 -> String.format(java.util.Locale.US, s, total.toInt())
            total < 3600 -> String.format(java.util.Locale.US, m, (total / 60).toInt())
            else -> String.format(java.util.Locale.US, hm, (total / 3600).toInt(), ((total % 3600) / 60).toInt())
        }
        return part(text)
    }
}
