// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/**
 * GitHub release notes (Markdown) → readable text for the update dialog: headings bold and
 * larger, "- " lists as "•", **bold** kept bold, quotes and separators cleaned, and "←"
 * (missing from some phone fonts, shown as "IJ") replaced by "›".
 */
object ReleaseNotes {

    /** A parsed line: its kind and the text with inline markup still in it. */
    data class Line(val kind: Kind, val text: String)
    enum class Kind { H1, H2, BULLET, QUOTE, TEXT, BLANK, RULE }

    /** Pure line parser (unit-tested). */
    fun parse(md: String): List<Line> = md.replace("\r", "").replace("←", "›").lines().map { raw ->
        val l = raw.trimEnd()
        val t = l.trimStart()
        when {
            t.isEmpty() -> Line(Kind.BLANK, "")
            t.matches(Regex("^-{3,}$|^\\*{3,}$")) -> Line(Kind.RULE, "")
            t.startsWith("# ") || t.startsWith("## ") -> Line(Kind.H1, t.trimStart('#').trim())
            t.startsWith("#") -> Line(Kind.H2, t.trimStart('#').trim())
            t.startsWith("- ") || t.startsWith("* ") -> Line(Kind.BULLET, t.substring(2).trim())
            t.startsWith(">") -> Line(Kind.QUOTE, t.trimStart('>').trim())
            else -> Line(Kind.TEXT, t)
        }
    }.let { lines ->
        // collapse repeated blank lines
        lines.filterIndexed { i, ln -> !(ln.kind == Kind.BLANK && i > 0 && lines[i - 1].kind == Kind.BLANK) }
    }

    /** Strip inline markup, returning plain text and the ranges that were **bold**. */
    fun inline(s: String): Pair<String, List<IntRange>> {
        val out = StringBuilder(); val bold = mutableListOf<IntRange>()
        var i = 0
        while (i < s.length) {
            if (s.startsWith("**", i)) {
                val end = s.indexOf("**", i + 2)
                if (end > i + 2) {
                    val start = out.length
                    out.append(s.substring(i + 2, end).replace("`", ""))
                    bold += start until out.length
                    i = end + 2; continue
                }
            }
            if (s[i] == '`') { i++; continue }
            out.append(s[i]); i++
        }
        return out.toString() to bold
    }

    fun format(md: String): CharSequence {
        val sb = SpannableStringBuilder()
        parse(md).forEach { ln ->
            val start = sb.length
            when (ln.kind) {
                Kind.BLANK -> { sb.append("\n"); return@forEach }
                Kind.RULE -> { sb.append("─────────\n"); sb.setSpan(ForegroundColorSpan(Color.parseColor("#4A5068")), start, sb.length, 0); return@forEach }
                Kind.BULLET -> sb.append("•  ")
                else -> {}
            }
            val (text, bold) = inline(ln.text)
            val textStart = sb.length
            sb.append(text)
            bold.forEach { r -> sb.setSpan(StyleSpan(Typeface.BOLD), textStart + r.first, textStart + r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            when (ln.kind) {
                Kind.H1 -> { sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, 0); sb.setSpan(RelativeSizeSpan(1.2f), start, sb.length, 0)
                    sb.setSpan(ForegroundColorSpan(Color.WHITE), start, sb.length, 0) }
                Kind.H2 -> { sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, 0); sb.setSpan(ForegroundColorSpan(Color.parseColor("#4DA3FF")), start, sb.length, 0) }
                Kind.QUOTE -> sb.setSpan(ForegroundColorSpan(Color.parseColor("#9BA3B8")), start, sb.length, 0)
                else -> {}
            }
            sb.append("\n")
        }
        return sb.trimEnd()
    }

    private fun SpannableStringBuilder.trimEnd(): CharSequence {
        var e = length; while (e > 0 && this[e - 1] == '\n') e--
        return subSequence(0, e)
    }
}
