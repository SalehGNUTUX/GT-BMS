# النشر على F-Droid · Publishing on F-Droid

## ما جُهّز في المستودع

- **بناء خاص بـ F-Droid**: `./gradlew assembleRelease -Pfdroid` يعطّل المحدّث الذاتي (GitHub)، لأن F-Droid يبني
  التطبيق ويوقّعه بمفتاحه، فتحديثات GitHub الموقّعة بمفتاحنا لن تُقبل فوق نسخة F-Droid. قسم «التحديثات» يختفي
  في هذا البناء، ويتولى F-Droid التحديث.
- **بيانات المتجر**: `fastlane/metadata/android/{ar,en-US,fr-FR}` (العنوان والوصف وسجل التغييرات لكل `versionCode`).
- **وصفة البناء**: `documentation/fdroid/com.gnutux.gtbms.yml` جاهزة لمستودع fdroiddata.
- **الاعتماديات**: كلها حرة؛ MPAndroidChart من jitpack مقبولة (VanAccess الأصلي منشور على F-Droid بها).
- **مضادات المزايا (Anti-features)**: لا توجد — لا إعلانات ولا تتبع ولا خدمات جوجل. يتصل بالإنترنت فقط لفحص
  التحديثات (معطّل في نسخة F-Droid).

## خطوات التقديم (يقوم بها صاحب المستودع)

1. ضع وسماً للإصدار: `git tag v1.6.0 && git push --tags`.
2. انسخ مستودع https://gitlab.com/fdroid/fdroiddata (Fork) على GitLab.
3. أضف الملف `metadata/com.gnutux.gtbms.yml` من `documentation/fdroid/`.
4. تحقّق محلياً إن أمكن: `fdroid readmeta && fdroid lint com.gnutux.gtbms && fdroid build -v -l com.gnutux.gtbms`.
5. افتح طلب دمج (Merge Request) بعنوان «New app: GT-BMS».

The F-Droid build disables the in-app GitHub updater (`-Pfdroid`) because F-Droid signs with its own key.
The recipe in `documentation/fdroid/` is ready for a merge request to fdroiddata.
