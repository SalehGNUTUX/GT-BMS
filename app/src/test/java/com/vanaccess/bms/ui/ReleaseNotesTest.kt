package com.vanaccess.bms.ui

import com.vanaccess.bms.ui.ReleaseNotes.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReleaseNotesTest {
    private val md = """
## GT-BMS 1.6.0-beta.1 — نسخة تجريبية

> لتصلك من داخل التطبيق: **الإعدادات ← التحديثات ← «تضمين الإصدارات التجريبية»**.

### التنقل
- **«مباشر» شاشة مستقلة** بالزر الأوسط.


---
**English** — Beta: `-Pfdroid` build.
""".trimIndent()

    @Test fun parsesMarkdownLines() {
        val all = ReleaseNotes.parse(md)
        val l = all.filter { it.kind != Kind.BLANK }
        assertEquals(listOf(Kind.H1, Kind.QUOTE, Kind.H2, Kind.BULLET, Kind.RULE, Kind.TEXT), l.map { it.kind })
        assertEquals("GT-BMS 1.6.0-beta.1 — نسخة تجريبية", l[0].text)
        assertFalse(l[1].text.contains("←"))                       // arrow shown as "IJ" on some fonts
        assertEquals(0, all.zipWithNext().count { (a, b) -> a.kind == Kind.BLANK && b.kind == Kind.BLANK })
    }

    @Test fun stripsInlineMarkup() {
        val (t, bold) = ReleaseNotes.inline("**«مباشر» شاشة** بالزر `x` **B**")
        assertEquals("«مباشر» شاشة بالزر x B", t)
        assertEquals(listOf(0 until 12, t.length - 1 until t.length), bold)
        assertEquals("a ** b" to emptyList<IntRange>(), ReleaseNotes.inline("a ** b"))
    }
}
