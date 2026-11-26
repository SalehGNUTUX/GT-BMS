// SPDX-License-Identifier: GPL-3.0-or-later
package com.gnutux.gtbms.desktop

import java.util.Locale

/** Interface texts in Arabic, English and French (the phone's wording where it exists). */
object Tr {
    val lang: String = (System.getenv("GTBMS_LANG") ?: Locale.getDefault().language).let { if (it in setOf("ar", "fr")) it else "en" }
    val rtl get() = lang == "ar"

    private val t = mapOf(
        "app" to listOf("GT-BMS", "GT-BMS", "GT-BMS"),
        "live" to listOf("مباشر", "Live", "Direct"),
        "cells" to listOf("الخلايا", "Cells", "Cellules"),
        "sim" to listOf("محاكاة من تسجيل", "Replaying a recording", "Lecture d’un enregistrement"),
        "waiting" to listOf("في انتظار البيانات…", "Waiting for data…", "En attente des données…"),
        "soc" to listOf("نسبة الشحن", "State of charge", "Niveau de charge"),
        "voltage" to listOf("الجهد", "Voltage", "Tension"),
        "current" to listOf("التيار", "Current", "Courant"),
        "power" to listOf("القدرة", "Power", "Puissance"),
        "remain" to listOf("السعة المتبقية", "Remaining", "Restant"),
        "full" to listOf("السعة الكاملة", "Full capacity", "Capacité totale"),
        "cycles" to listOf("الدورات", "Cycles", "Cycles"),
        "temp" to listOf("الحرارة", "Temperature", "Température"),
        "mos" to listOf("حرارة الترانزستورات", "MOSFET temperature", "Température MOSFET"),
        "charging" to listOf("يشحن", "Charging", "En charge"),
        "discharging" to listOf("تفريغ", "Discharging", "Décharge"),
        "idle" to listOf("خامل", "Idle", "Au repos"),
        "highest" to listOf("أعلى خلية", "Highest cell", "Cellule la plus haute"),
        "lowest" to listOf("أدنى خلية", "Lowest cell", "Cellule la plus basse"),
        "delta" to listOf("الفرق", "Difference", "Écart"),
        "average" to listOf("المتوسط", "Average", "Moyenne"),
        "count" to listOf("الخلايا (%d)", "Cells (%d)", "Cellules (%d)"),
        "readonly" to listOf("قراءة فقط: لا يُرسل إلى البطارية إلا أوامر القراءة", "Read-only: only read commands are sent to the battery", "Lecture seule : seules des commandes de lecture sont envoyées"),
    )

    operator fun get(key: String): String = t[key]?.get(when (lang) { "ar" -> 0; "fr" -> 2; else -> 1 }) ?: key
}
