// SPDX-License-Identifier: GPL-3.0-or-later
package com.vanaccess.bms.ui

import android.content.Context
import com.vanaccess.bms.R

/**
 * Localises the English texts produced by protocol drivers (alerts, parameter labels,
 * history events). Drivers stay locale-free; unknown keys are shown as-is.
 */
object L10n {
    private val ar = mapOf(
        // Alerts / events
        "Charging" to "يشحن", "Discharging" to "يفرّغ", "Battery full" to "البطارية ممتلئة",
        "AFE fault" to "عطل في دارة القياس (AFE)",
        "Balance wire resistance" to "مقاومة أسلاك الموازنة",
        "BMS fault code active" to "رمز عطل نشط في نظام الإدارة",
        "Cell count mismatch" to "عدد الخلايا غير مطابق",
        "Cell low voltage" to "جهد خلية منخفض",
        "Cell overvoltage" to "جهد خلية مرتفع", "Cell Overvoltage" to "جهد خلية مرتفع",
        "Cell undervoltage" to "جهد خلية منخفض جداً", "Cell Undervoltage" to "جهد خلية منخفض جداً",
        "Cell temperature sensor open" to "انقطاع حساس حرارة الخلايا",
        "Cell voltage difference too high" to "فرق جهد الخلايا مرتفع",
        "Cell voltage sense line open" to "انقطاع سلك قياس جهد الخلايا",
        "Charge MOS fault" to "عطل مفتاح الشحن",
        "Charge overcurrent" to "تيار شحن زائد", "Charge Overcurrent" to "تيار شحن زائد",
        "Charge overcurrent protection" to "حماية تيار الشحن الزائد",
        "Charge over-temperature" to "حرارة شحن مرتفعة", "High Temperature (Charge)" to "حرارة شحن مرتفعة",
        "Charge short circuit" to "قصر أثناء الشحن",
        "Charge temperature difference" to "فرق حرارة مرتفع أثناء الشحن",
        "Charge under-temperature" to "حرارة شحن منخفضة", "Low Temperature (Charge)" to "حرارة شحن منخفضة",
        "Charging disabled by command" to "الشحن موقوف بأمر",
        "Current sensor error" to "خطأ في حساس التيار",
        "Deep undervoltage" to "انخفاض جهد عميق",
        "Discharge MOS fault" to "عطل مفتاح التفريغ",
        "Discharge overcurrent" to "تيار تفريغ زائد", "Discharge Overcurrent" to "تيار تفريغ زائد",
        "Discharge overcurrent protection" to "حماية تيار التفريغ الزائد",
        "Discharge over-temperature" to "حرارة تفريغ مرتفعة", "High Temperature (Discharge)" to "حرارة تفريغ مرتفعة",
        "Discharge short circuit" to "قصر أثناء التفريغ",
        "Discharge temperature difference" to "فرق حرارة مرتفع أثناء التفريغ",
        "Discharge under-temperature" to "حرارة تفريغ منخفضة", "Low Temperature (Discharge)" to "حرارة تفريغ منخفضة",
        "Discharging disabled by command" to "التفريغ موقوف بأمر",
        "Front-end IC error" to "خطأ في دارة القياس",
        "Internal communication error" to "خطأ اتصال داخلي",
        "Low-voltage charge inhibit" to "منع الشحن لانخفاض الجهد",
        "MOS over-temperature" to "حرارة المفاتيح مرتفعة",
        "MOS software lock" to "قفل برمجي للمفاتيح",
        "MOS temperature difference" to "فرق حرارة المفاتيح مرتفع",
        "MOS temperature sensor open" to "انقطاع حساس حرارة المفاتيح",
        "Pack overvoltage" to "جهد البطارية مرتفع", "Pack undervoltage" to "جهد البطارية منخفض",
        "Short circuit" to "قصر في الدارة", "Short Circuit Protection" to "حماية القصر",
        "Thermal runaway warning" to "تحذير انفلات حراري",
        // Parameter groups
        "Temperature protection" to "حماية الحرارة", "Voltage protection" to "حماية الجهد",
        "Current protection" to "حماية التيار", "Balancing & calibration" to "الموازنة والمعايرة",
        "Pack configuration" to "إعدادات البطارية",
        // Parameter labels
        "MOS over-temperature protection" to "حماية حرارة المفاتيح",
        "MOS over-temperature release" to "تحرير حرارة المفاتيح",
        "MOS temperature difference protection" to "حماية فرق حرارة المفاتيح",
        "MOS temperature difference release" to "تحرير فرق حرارة المفاتيح",
        "Charge over-temperature protection" to "حماية حرارة الشحن العليا",
        "Charge over-temperature release" to "تحرير حرارة الشحن العليا",
        "Discharge over-temperature protection" to "حماية حرارة التفريغ العليا",
        "Discharge over-temperature release" to "تحرير حرارة التفريغ العليا",
        "Charge low-temperature protection" to "حماية حرارة الشحن الدنيا",
        "Charge low-temperature release" to "تحرير حرارة الشحن الدنيا",
        "Discharge low-temperature protection" to "حماية حرارة التفريغ الدنيا",
        "Discharge low-temperature release" to "تحرير حرارة التفريغ الدنيا",
        "Charge temperature difference protection" to "حماية فرق الحرارة (شحن)",
        "Charge temperature difference release" to "تحرير فرق الحرارة (شحن)",
        "Discharge temperature difference protection" to "حماية فرق الحرارة (تفريغ)",
        "Discharge temperature difference release" to "تحرير فرق الحرارة (تفريغ)",
        "Pack overvoltage protection" to "حماية جهد البطارية الأعلى",
        "Pack overvoltage release" to "تحرير جهد البطارية الأعلى",
        "Pack undervoltage protection" to "حماية جهد البطارية الأدنى",
        "Pack undervoltage release" to "تحرير جهد البطارية الأدنى",
        "Cell overvoltage protection (level 1)" to "حماية جهد الخلية الأعلى (مستوى 1)",
        "Cell overvoltage release" to "تحرير جهد الخلية الأعلى",
        "Cell undervoltage protection (level 1)" to "حماية جهد الخلية الأدنى (مستوى 1)",
        "Cell undervoltage release" to "تحرير جهد الخلية الأدنى",
        "Cell overvoltage protection (level 2)" to "حماية جهد الخلية الأعلى (مستوى 2)",
        "Cell undervoltage protection (level 2)" to "حماية جهد الخلية الأدنى (مستوى 2)",
        "Cell shutdown voltage" to "جهد إطفاء الخلية",
        "Cell charge-inhibit voltage" to "جهد منع الشحن للخلية",
        "Cell charge-inhibit voltage difference" to "فرق جهد منع الشحن",
        "Charge overcurrent activation" to "بدء حماية تيار الشحن",
        "Charge overcurrent threshold" to "حد تيار الشحن",
        "Discharge overcurrent activation" to "بدء حماية تيار التفريغ",
        "Discharge overcurrent threshold" to "حد تيار التفريغ",
        "Secondary overcurrent voltage" to "جهد التيار الزائد الثانوي",
        "Short-circuit trigger voltage" to "جهد تفعيل حماية القصر",
        "Secondary overcurrent delay" to "تأخير التيار الزائد الثانوي",
        "Short-circuit delay" to "تأخير حماية القصر",
        "Valid current threshold (low)" to "حد التيار الفعّال (أدنى)",
        "Valid current threshold (high)" to "حد التيار الفعّال (أعلى)",
        "Current sense resistor" to "مقاومة قياس التيار",
        "Balance start voltage" to "جهد بدء الموازنة",
        "Balance voltage difference" to "فرق جهد الموازنة",
        "SOC charge correction voltage" to "جهد تصحيح النسبة (شحن)",
        "SOC discharge correction voltage" to "جهد تصحيح النسبة (تفريغ)",
        "Design capacity" to "السعة التصميمية", "Cell count" to "عدد الخلايا",
        "Cell temperature sensors" to "حساسات حرارة الخلايا",
        "MOS temperature sensors" to "حساسات حرارة المفاتيح",
        "Empty-state cell impedance" to "مقاومة الخلية فارغة",
        "Full-state cell impedance" to "مقاومة الخلية ممتلئة",
        "Sleep" to "السكون", "Charging handshake" to "مصافحة الشاحن", "Heating" to "التسخين",
        "External temperature sensor" to "حساس حرارة خارجي",
    )

    private fun isArabic(ctx: Context) =
        ctx.resources.configuration.locales[0].language == "ar"

    fun t(ctx: Context, key: String): String = when (key) {
        "@on" -> ctx.getString(R.string.on)
        "@off" -> ctx.getString(R.string.off)
        else -> if (isArabic(ctx)) ar[key] ?: key else key
    }
}
