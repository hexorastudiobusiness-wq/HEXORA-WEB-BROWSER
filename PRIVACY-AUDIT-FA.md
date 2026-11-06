# واقعیت‌های فنی برای بازنویسی متن About

> اندازه‌گیری‌شده از خودِ کد و از پیش‌فرض‌های GeckoView (نسخهٔ ۱۵۸ که پروژه استفاده می‌کند).
> تاریخ: 2026-11-06. **هیچ متن یا کدی در این مرحله تغییر نکرده.**

## ۱) بکاپ اندروید — `allowBackup="false"`

`app/src/main/AndroidManifest.xml` خطِ ۳۰:
```xml
<application android:name=".BrowserApplication" android:allowBackup="false" ...>
```
یعنی Auto Backup گوگل و انتقالِ دستگاه‌به‌دستگاه هر دو بسته‌اند؛ Room و DataStore به Drive نمی‌روند. جملهٔ
«go no further» و «scrambled text never leaves the phone» از این نظر درست است.

نکات باقی‌مانده:
- ابزارهای OEM (Smart Switch، Mi Mover، …) گاهی مستقل از این فلگ کلِ پوشهٔ اپ را کپی می‌کنند.
- Manifest Merger: اگر روزی یک کتابخانه `allowBackup="true"` تزریق کند، باید `tools:replace="android:allowBackup"` اضافه شود. بدون بیلد نمی‌توان تأیید کرد.
- با `allowBackup=false` نیازی به `dataExtractionRules` نیست.

## ۲) اپ خودش به هیچ سروری وصل نمی‌شود

جست‌وجو در کل `app/src/main/java`:
- هیچ SDK آنالیتیکس/کرش/تبلیغات در `app/build.gradle.kts` نیست (فقط AndroidX، Compose، Room، DataStore، GeckoView).
- هیچ دامنهٔ هاردکدشده‌ای جز URL موتورهای جست‌وجو (`UrlUtils.kt`) و الگوهای تشخیص http/https وجود ندارد.
- هیچ فراخوانی `telemetry`, `CrashHandler`, `SafeBrowsing` در کد نیست → همه‌چیز روی پیش‌فرضِ GeckoView می‌ماند.
- گزارشِ باگ فقط یک `ACTION_SEND` به اپ میلِ خودِ کاربر است (`BugReport.mailIntent`).
- محتوای گزارش: توضیح کاربر + بلوک دستگاه (`DeviceInfo.summary`) + وضعیتِ اپ (شمارنده‌ها) + `AppLog`.
  `AppLog` و `buildDiagnostics` هیچ URL/عنوان صفحه/متن صفحه‌ای ثبت نمی‌کنند → ادعای «نه صفحاتی که باز کردی» **درست** است.
  ولی گزارش با ایمیلِ فرستنده به Gmail کارفرما می‌رسد.

## ۳) ترافیکِ خودِ GeckoView (پیش‌فرض‌ها از `gecko-dev`)

| مقصد | چرا | وضعیت |
|---|---|---|
| `safebrowsing.googleapis.com` (`/v4/threatListUpdates:fetch`، `/v4/fullHashes:find`) | Google Safe Browsing؛ `browser.safebrowsing.features.{malware,phishing}.update=true` در `geckoview-prefs.js` | **همیشه روشن** — راهی برای خاموش‌کردن از API گکوویو وجود ندارد. `dataSharing.enabled=false` (هیچ گزارش تهدیدی نمی‌رود) |
| `firefox.settings.services.mozilla.com` + `firefox-settings-attachments.cdn.mozilla.net` | Remote Settings: لیست‌های بلاکِ ردیاب‌ها و فیلترهای CRLite (`security.remote_settings.crlite_filters.enabled=true`)، پُلِ ۲۴ ساعته (`services.settings.poll_interval=86400`) | روشن |
| پاسخ‌دهنده‌های OCSP/CRL گواهی‌ها | بررسی ابطال گواهی | روشن |
| موتور جست‌وجوی انتخاب‌شده، خودِ سایت‌ها، favicon سایت | خواستهٔ کاربر | فقط با اقدام کاربر |
| DoH موزیلا | `network.trr.uri` خالی، mode صفر | **خاموش** — DNS به resolver سیستم می‌رود |
| تلمتری / کرش | `toolkit.telemetry.unified=false` و اپ هیچ API تلمتری صدا نمی‌زند | **خاموش** |

نتیجه: جملهٔ «nothing syncing in the background» غلط است. درست: «حساب و سرورِ خودم وجود ندارد؛ موتورِ مرورگر فقط لیست‌های بلاک و Safe Browsing را به‌روز می‌کند».

## ۴) سه ادعای امنیتی و کدِ واقعی

| ادعای متن | واقعیتِ کد |
|---|---|
| Tracker blocking always on | ✅ `GeckoSessionSettings.useTrackingProtection(trackingProtectionEnabled)` در ساختِ هر سشن، پیش‌فرضِ تنظیمات `true`، برای تب خصوصی هم اعمال می‌شود. سطح: لیستِ استاندارد (`privacy.trackingprotection.enabled`)، نه strict؛ محافظت از fingerprinting فعال نیست. |
| HTTPS-only همیشه | ❌ `httpsOnlyMode` پیش‌فرض **false** است (`SettingsRepository.kt:66`). فقط یک گزینهٔ کاربر است. |
| Third-party cookies per site جدا | ❌ **فعال نشده.** کامنتِ `GeckoBrowserEngine.kt:127-133` صریح می‌گوید TCP عمداً روشن نشده چون بدون Storage Access API ورودِ گوگل در حلقهٔ خطا می‌افتد. تا این روشن نشود، این جمله را نباید نوشت. |

## ۵) بقیهٔ مواردی که با کد تأیید شد

- رمزها: `CredentialCipher` = `AES/GCM/NoPadding`، تگ ۱۲۸ بیتی، کلید در `AndroidKeyStore` ✅ (فقط «nothing to leak» غلط است).
- حذفِ اپ: دیتابیس می‌رود، **فایل‌های دانلودشده در پوشهٔ Downloads می‌مانند**.
- تب خصوصی: تاریخچه ثبت نمی‌شود ✅ (`:2436`)، بوکمارک بی‌صدا بلاک می‌شود ✅ (`:2007-2009`)، در بازیابی تب‌ها برنمی‌گردد ✅ (`:961`)، **ولی دانلودهایش می‌مانند** (هیچ گاردِ `isPrivate` در `download/` نیست) و Safe Browsing همان‌جا هم به گوگل می‌زند.
- لایسنس: GeckoView تحت MPL 2.0 است و شرطِ MPL دسترسی به سورس را می‌خواهد → صفحهٔ Licences با لینک سورس لازم است. AndroidX/Compose/Room/DataStore آپاچی‌اند، ولی این جمله نباید طوری خوانده شود که کدِ خودِ اپ آپاچی است.

## ۶) چک‌لیست PCAPdroid (روی گوشی، چون اینجا شبیه‌ساز/دستگاه نیست)

اپ را نصب کن، PCAPdroid را برای Hexora روشن کن، ۱۰ دقیقه بچرخ و این‌ها را ببین:
1. `safebrowsing.googleapis.com` — انتظار می‌رود، طبیعی است.
2. `*.mozilla.com` / `*.mozilla.net` (settings + attachments CDN) — انتظار می‌رود، هر ۲۴ ساعت.
3. دامنهٔ موتور جست‌وجو و سایت‌های باز‌شده — طبیعی.
4. **نباید** ببینی: `incoming.telemetry.mozilla.org`، `firebase*`، `google-analytics`، `crashlytics`، هیچ دامنه‌ای با نام hexora.
5. اگر `incoming.telemetry.mozilla.org` دیدی، یعنی جایی تلمتری روشن شده و باید فوراً پیدا شود.
