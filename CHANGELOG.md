# سجل التغييرات · Changelog

## v1.1.0

- **السجل**: تصحيح معنى «Core over Voltage»؛ هو حدث **حماية جهد الخلية المرتفع** الذي يوقف به النظام الشحن
  (كان يُعرض خطأً «البطارية ممتلئة» بالأخضر). صار يُعرض تحذيراً برتقالياً، والسجل الفارغ يُعرض «عودة إلى الوضع
  الطبيعي (زالت الحماية)»، و«يشحن/يفرّغ» حالةً لحظة الحدث لا حدثاً، وتُنبَّه التواريخ السابقة لضبط ساعة النظام.
- إصلاح ترتيب «الجهد • النسبة» في السجل مع الواجهة العربية.
- **مشاركة التطبيق**: زر لمشاركة نبذة بالروابط والوسوم، وزر لمشاركة ملف التثبيت نفسه، مع زرّي الموقع والمستودع.

History: "Core over Voltage" is now shown as a cell over-voltage protection event (it was wrongly shown as
"battery full" in green); empty records mean the protection cleared. New: share the app as text with links
and hashtags, or as the install file itself.


## v1.0.0 — 2026-11-20

الإصدار الأول من GT-BMS، مبنيّ على VanAccess 1.2.

- دعم Bestway BWBM (بروتوكول ‎7F): البيانات المباشرة، والخلايا حتى 32، والحرارة، والسعة، والدورات،
  والتنبيهات، ومعلومات الجهاز، ومعاملات الحماية، وسجل الأحداث، ووقت النظام. مُختبر على BWBM-631M-23S40A.
- بنية برامج تشغيل قابلة للتوسعة مع تعرّف تلقائي: JBD، وJK-BMS (تجريبي)، وDaly (تجريبي)،
  وPowerQueen/LiTime، و123SmartBMS.
- شاشة بطارية جديدة بتبويبات: مباشر، الخلايا، المعلومات، السجل.
- منبّهات اكتمال الشحن وانخفاض البطارية بصوت المنبّه وإشعار إيقاف ومنع للتكرار.
- البطاريات المحفوظة، واتصال أسرع بالأجهزة المعروفة.
- عارضة سفلية تتكيّف مع الأجهزة المعيّنة.
- واجهة عربية كاملة.
- تحديث من داخل التطبيق عبر إصدارات GitHub.
- توقيع إصدار ثابت.

---

First GT-BMS release, based on VanAccess 1.2: Bestway BWBM support (live data, cells, temperatures,
capacity, alarms, device info, parameters, history, clock), pluggable drivers with auto-detection
(JBD, JK-BMS and Daly experimental, PowerQueen/LiTime, 123SmartBMS), new tabbed battery screen,
battery alarms, saved batteries and faster reconnect, adaptive bottom bar, full Arabic UI, in-app updates.
