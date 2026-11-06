# نقشهٔ کار — قدمهای بعدی (0.6.2)

> فایلِ یادداشتِ داخلی. آخرین به‌روزرسانی: بیلد 0.6.2 / `versionCode 39` / `0727f2f`.
> وضعیت: بخشِ ۲ انجام شد. بخشِ ۱ **منتظر تأییدِ کارفرما** است.

---

## ۱) رشتههای بیمصرف (`strings.xml`)

یازده رشته تعریف شدهاند که جایی استفاده نمیشوند. تصمیم پیشنهادی:

### حذف (۶ رشته) — صفر ریسک
| رشته | متن | دلیل |
|---|---|---|
| `settings_cookies` | Cookies | هیچ کلیدِ کوکی در `SettingsRepository` نیست؛ ETP و TCP توسط GeckoView اداره میشوند. |
| `settings_cookies_summary` | …per-site cookie controls are planned | «نقشهٔ راه»ی که هرگز شروع نشد. |
| `settings_text_zoom` | Text size | زوم عمداً per-site شد؛ `settings_zoom_info_*` جایش را گرفته. |
| `settings_text_zoom_summary` | Scales text on pages; applied on next page load | همان. |
| `file_picker_title` | Choose an app | انتخاب فایل با `ActivityResultContracts.*` انجام میشود که عنوانِ chooser نمیگیرد. |
| `bug_report_shake_hint` | Shake the phone to report a bug | `about_report_hint` و `about_report_bug_summary` همین را میگویند. |

### فعالسازی (۵ رشته) — حدود ۴۰ خط
| رشته | متن | کارِ لازم |
|---|---|---|
| `action_tabs` | Tabs | `contentDescription` برای دکمهٔ شمارندهٔ تب، `BrowserScreen.kt` ~۱۹۲۹. **۲ خط. الان دکمه برای TalkBack بیمعنی است.** |
| `action_reset_zoom` | Reset text size to 100% | برچسبِ گفتاری برای دکمهٔ ریست در `SiteZoomPanel.kt` ~۱۷۶ (الان فقط متن «100%»). **۱ خط.** |
| `default_browser_already` | Hexora is already your default browser | در `MainActivity` ~۱۶۴: اگر کاربر از قبل پیشفرض است، الان هیچ فیدبکی نمیگیرد؛ یک Toast. **~۴ خط.** |
| `action_undo` | Undo | اسنکبارِ Undo بعد از «Close all». |
| `tabs_closed_message` | All tabs were closed | همان اسنکبار: «All tabs were closed · Undo» در `TabsScreen.kt` (که `Scaffold` دارد) ~۲۵ خط. `UndoCloseAllTabs` / `undoCloseAllTabs()` / `lastCloseAllIds` در VM از قبل کاملاند؛ چون کلیک و اسنکبار در یک صفحهاند، تغییرِ VM لازم نیست. |

### باگِ جانبی که در همین بررسی پیدا شد
`recentlyClosedCount` در `BrowserUiState` تعریف و در `BrowserViewModel` ~۴۴۸ خوانده میشود ولی **هیچجا نوشته نمیشود** — همیشه صفر است. اگر اسنکبار اضافه شود به آن نیازی نیست؛ بهتر است کل فیلد حذف شود.

---

## ۲) انجام‌شده در 0.6.2 — بازنویسیِ متنِ About + صفحهٔ Licences

سه بازخورد رسید (دقت، لحن، صدا). همه در یک پاس اعمال شدند: هیچ «I» نماند (صدای استودیو)، ادعاهای غلط اصلاح شدند (سینکِ پس‌زمینه، کوکیِ شخص‌ثالث، HTTPS-only، «چیزی برای لو رفتن»، حذفِ اپ، ایمیلِ گزارش، لایسنس)، بخشِ «What isn't here yet» اضافه شد و `LicencesScreen.kt` با لینکِ سورسِ GeckoView و متنِ MPL 2.0 ساخته شد. جزئیات: `AUDIT.md` §۲۷ و `PRIVACY-AUDIT-FA.md`.

---

## ۳) کارهای باقیمانده (از قبل)
- [ ] merge به `main` — کارفرما گفت فعلاً نگه داریم.
- [ ] تأیید روی دستگاه: تبِ سیاه بعد از resume / سوئیچ سریع، ورودِ Google، لایهٔ منابعِ 0.5.8.
- [ ] تأیید روی دستگاه: اندازهٔ فشردهٔ تنظیمات و حاشیهٔ آیکون (0.6.1).
