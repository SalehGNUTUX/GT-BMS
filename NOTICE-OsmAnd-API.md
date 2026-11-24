# osmand-api

مجلّد `osmand-api/src/` ليس من شفرة هذا المشروع: نسخة حرفية من وحدة `OsmAnd-api`
(<https://github.com/osmandapp/OsmAnd>، المجلّد `OsmAnd-api/src`)، © OsmAnd BV ومساهمو OsmAnd،
برخصة **GPLv3** — رخصة هذا المشروع نفسها. لا تُعدَّل ملفاتها هنا.

هي عقد اتصال فقط (بلا خرائط). نستعمل منها `IOsmAndAidlInterface.getBitmapForGpx`: نمرّر مسار
الرحلة فيعيد OsmAnd صورة الخريطة مرسومة من خرائطه المحلية. طريقة الاستعمال
(`com.vanaccess.bms.map`) منقولة من مشروعنا GT-SPEEDOMETER (GPLv3).

`osmand-api/src/` is a verbatim copy of OsmAnd's `OsmAnd-api` module, © OsmAnd BV and contributors, GPLv3.
