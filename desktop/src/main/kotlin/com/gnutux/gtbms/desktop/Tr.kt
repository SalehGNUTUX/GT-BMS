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
        "reports" to listOf("التقارير", "Reports", "Rapports"),
        "simCharge" to listOf("محاكاة شحن (مسرّعة)", "Simulated charge (fast)", "Charge simulée (accélérée)"),
        "srcRest" to listOf("تسجيل (راحة)", "Recording (at rest)", "Enregistrement (repos)"),
        "srcCharge" to listOf("محاكاة شحن", "Simulate a charge", "Simuler une charge"),
        "import" to listOf("استيراد نسخة الهاتف", "Import phone backup", "Importer la sauvegarde du téléphone"),
        "imported" to listOf("أُضيف %d تقريراً من الهاتف", "%d reports added from the phone", "%d rapports ajoutés depuis le téléphone"),
        "importFail" to listOf("ليست نسخة GT-BMS احتياطية", "Not a GT-BMS backup", "Ce n\u2019est pas une sauvegarde GT-BMS"),
        "charge" to listOf("الشحن", "Charging", "Charge"),
        "target" to listOf("منبّه الاكتمال عند", "Full-charge alarm at", "Alarme de fin à"),
        "off" to listOf("معطّل", "Off", "Désactivée"),
        "noReport" to listOf("اختر تقريراً لعرض تفاصيله", "Pick a report to see it", "Choisissez un rapport"),
        "fromPhone" to listOf("من الهاتف", "from the phone", "du téléphone"),
        "fromPc" to listOf("من هذا الحاسوب", "from this computer", "de cet ordinateur"),
        "readonly" to listOf("قراءة فقط: لا يُرسل إلى البطارية إلا أوامر القراءة", "Read-only: only read commands are sent to the battery", "Lecture seule : seules des commandes de lecture sont envoyées"),
    )

    operator fun get(key: String): String = t[key]?.get(when (lang) { "ar" -> 0; "fr" -> 2; else -> 1 }) ?: key
}
