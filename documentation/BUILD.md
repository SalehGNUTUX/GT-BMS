# البناء والإصدار · Build & release

## المتطلبات

- JDK 17 أو أحدث، وAndroid SDK (platform 34، build-tools 34+).
- أنشئ `local.properties` بالسطر: `sdk.dir=/path/to/Android/Sdk`

## البناء

```bash
./gradlew testDebugUnitTest      # اختبارات البروتوكولات والمحدّث
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

> إذا كان مسار المشروع يحوي حروفاً غير لاتينية، فإن `gradle.properties` يضبط
> `kotlin.compiler.execution.strategy=in-process` لأن خادم Kotlin يفسد هذه المسارات.

## التوقيع

يُوقَّع إصدار release تلقائياً إن وُجد `keystore.properties` في جذر المشروع (مستبعد من git):

```properties
storeFile=/home/USER/.android-keys/gt-bms-release.jks
storePassword=…
keyAlias=gt-bms
keyPassword=…
```

أو عبر متغيرات البيئة: `GTBMS_KEYSTORE_FILE`، `GTBMS_KEYSTORE_PASSWORD`، `GTBMS_KEY_ALIAS`، `GTBMS_KEY_PASSWORD`.
بصمة شهادة الإصدار (SHA-256):
`D1:D3:91:62:E2:AA:1C:C6:0D:BF:A6:EA:9B:3B:24:72:05:29:5C:FD:E6:51:32:C3:FE:BE:52:82:D9:DE:36:04`

احتفظ بنسخة احتياطية من المفتاح: بدونه لا يقبل أندرويد تحديث التطبيق المثبّت.

## نشر إصدار جديد

1. ارفع `versionCode` و`versionName` في `app/build.gradle` وأضف ملاحظات في `CHANGELOG.md`.
2. `./gradlew testDebugUnitTest assembleRelease`
3. انسخ الحزمة باسم `GT-BMS-vX.Y.Z-release.apk` وولّد `SHA256SUMS.txt`.
4. `gh release create vX.Y.Z GT-BMS-vX.Y.Z-release.apk SHA256SUMS.txt --notes-file …`
   (وسم Pre-release للإصدارات التجريبية).
5. حدّث رقم الإصدار وروابطه في `index.html`.

التطبيق يفحص `https://api.github.com/repos/SalehGNUTUX/GT-BMS/releases` ويختار أعلى وسم `vX.Y.Z`
مرفق بملف `.apk` (انظر `app/src/main/java/com/vanaccess/bms/update/Updater.kt`).
