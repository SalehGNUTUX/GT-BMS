// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import com.vanaccess.bms.reports.Advisor
import org.w3c.dom.Element
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The phone's own texts (app/src/main/res), merged at build time: the desktop says things with
 * the same words. English is the fallback, as on the phone.
 */
object Res {
    private fun load(lang: String): Map<String, String> = runCatching {
        val doc = Res::class.java.getResourceAsStream("/android-res/strings-$lang.xml")!!.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val out = HashMap<String, String>()
        val list = doc.getElementsByTagName("string")
        for (i in 0 until list.length) (list.item(i) as Element).let { out[it.getAttribute("name")] = unescape(it.textContent) }
        val pl = doc.getElementsByTagName("plurals")
        for (i in 0 until pl.length) (pl.item(i) as Element).let { e ->
            val items = e.getElementsByTagName("item")
            for (j in 0 until items.length) (items.item(j) as Element).let { out[e.getAttribute("name") + "#" + it.getAttribute("quantity")] = unescape(it.textContent) }
        }
        out
    }.getOrDefault(emptyMap())

    private val en = load("en")
    private val own = if (Tr.lang == "en") en else load(Tr.lang)

    private fun unescape(s: String): String {
        var t = s.trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length - 1)
        val b = StringBuilder(); var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length) {
                when (val n = t[i + 1]) { 'n' -> b.append('\n'); 't' -> b.append('\t'); else -> b.append(n) }
                i += 2; continue
            }
            b.append(c); i++
        }
        return b.toString()
    }

    fun has(key: String) = own.containsKey(key) || en.containsKey(key)

    /** Like Android's getString: formatted only when there are arguments; numbers with a dot. */
    fun s(key: String, vararg args: Any?): String {
        val raw = own[key] ?: en[key] ?: return key
        return if (args.isEmpty()) raw else runCatching { String.format(Locale.US, raw, *args) }.getOrDefault(raw)
    }

    fun plural(key: String, n: Int): String {
        val q = when {
            Tr.lang == "ar" -> when { n == 0 -> "zero"; n == 1 -> "one"; n == 2 -> "two"; n % 100 in 3..10 -> "few"; n % 100 in 11..99 -> "many"; else -> "other" }
            n == 1 -> "one"; else -> "other"
        }
        val raw = own["$key#$q"] ?: own["$key#other"] ?: en["$key#other"] ?: return "$n"
        return runCatching { String.format(Locale.US, raw, n) }.getOrDefault(raw)
    }

    /** A recommendation with its degree word (طبيعي، مقبول…), as the phone writes it. */
    fun advice(r: Advisor.Rec): String {
        val key = "adv_${r.code}"
        if (!has(key)) return r.code
        val t = s(key, *r.args.toTypedArray())
        if (r.code == "all_ok") return t
        return s(when (r.level) {
            Advisor.Level.CRITICAL -> "degree_critical"; Advisor.Level.WARNING -> "degree_warning"
            Advisor.Level.INFO -> "degree_info"; Advisor.Level.OK -> "degree_ok"
        }) + " " + t
    }

    /** Under an hour only minutes, under a minute only seconds (core Bidi). */
    fun dur(seconds: Double): String = com.vanaccess.bms.text.Bidi.duration(seconds, s("dur_hm"), s("dur_m"), s("dur_s"))
}
