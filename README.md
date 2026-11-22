# Molido VPN • Holographic Plasma Edition 🔮

[![Cross-Platform Release](https://github.com/hidooch980/MolidoVPN-Ai/actions/workflows/cross-platform-release.yml/badge.svg)](https://github.com/hidooch980/MolidoVPN-Ai/actions)
[![Android CI](https://github.com/hidooch980/MolidoVPN-Ai/actions/workflows/android-ci.yml/badge.svg)](https://github.com/hidooch980/MolidoVPN-Ai/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**کلاینت وی‌پی‌ان ۱۰۰٪ رایگان، متن‌باز، هوشمند و چندسکویی با رابط کاربری انیمیشنی هسته پلاسما (Holographic Plasma Core) برای ویندوز، اندروید و آیفون (iOS).**

---

## 🌟 ویژگی‌های کلیدی (Key Features)

- 🔮 **رابط کاربری انیمیشنی ۶۰ فریم پلاسما:** شبیه‌سازی گوی انرژی هولوگرافیک و واکنش بصری به تبادل ترافیک با شتاب‌دهی سخت‌افزاری Canvas و Kotlin View بومی.
- ⚡ **هوشمندی و انتخاب خودکار (Smart Auto-Select):** تست مداوم پینگ سرورها با الگوریتم URL-Test و سوئیچ خودکار به سریع‌ترین نود بدون قطعی.
- 🛡️ **مسیریابی هوشمند ترافیک (Smart Routing):**
  - عبور مستقیم ترافیک سایت‌های داخلی و بانکی بدون افت سرعت (Bypass Iran).
  - مسدودسازی تبلیغات مزاحم و بدافزارها (Ad-Blocking).
  - رمزنگاری ترافیک بین‌الملل و فیلترشده با تونل ضد بازرسی عمیق بسته‌ها (DPI).
- 🌐 **پوشش پروتکل‌های نسل جدید و هسته‌های یکپارچه:**
  - **Sing-box Core & Xray Core**
  - **VLESS (Reality & Vision)**
  - **Hysteria 2 (Brutal UDP)**
  - **TUIC v5**
  - **Trojan & Shadowsocks 2022**
  - **WireGuard & Cloudflare WARP**
- 📱 **پشتیبانی کامل از تمام سیستم‌عامل‌ها:**
  - **ویندوز (Windows 10 / 11):** کلاینت دسکتاپ و PWA با قابلیت سوئیچ پروکسی سیستم.
  - **اندروید (Android):** اپلیکیشن بومی با سرویس `VpnService` و کامپوننت سخت‌افزاری `PlasmaCoreView`.
  - **آیفون و آیپد (iOS):** وب‌اپ PWA + پشتیبانی تک‌کلیکه از ایمپورت در Shadowrocket، Sing-box iOS، Stash و Streisand.

---

## 🚀 راهنمای نصب و راه‌اندازی در پلتفرم‌ها (How to Run)

### ۱. ویندوز (Windows)
1. به پوشه `client/` رفته و فایل `index.html` را در هر مرورگری اجرا کنید (یا از طریق Electron با دستور `npm start`).
2. برای ادغام با هسته یکپارچه محلی، کانفیگ تولید شده در `core/multiengine/singbox_universal.json` یا `clash_meta_rules.yaml` را در **Clash Verge Rev** یا **Sing-box** ایمپورت نمایید.

### ۲. اندروید (Android)
- کد پروژه در پوشه `android/` قرار دارد و با Gradle نسخه 8.11 قابل کامپایل مستقیم است.
- کامپوننت بومی `PlasmaCoreView` درون `MainActivity.kt` پیاده‌سازی شده و مستقیماً روی دیوایس رندر می‌شود.

### ۳. آیفون و آیپد (iOS)
1. لینک وب‌اپلیکیشن در پوشه `client/` را در مرورگر Safari باز کنید و دکمه **Add to Home Screen** را بزنید.
2. از منوی **اشتراک / لینک**، کانفیگ VLESS یا Hysteria 2 را کپی کرده و در اپلیکیشن‌های رایگان **Shadowrocket** یا **Sing-box iOS** پیست کنید.

---

## 📂 ساختار ماژولار پروژه

```text
MolidoVPN-Ai/
├── client/                     # کلاینت پلاسما وب، دسکتاپ و PWA (چندسکویی)
│   ├── index.html              # رابط کاربری اصلی با انیمیشن ۶۰fps پلاسما
│   ├── manifest.json           # مانیفست اپلیکیشن پیش‌رونده PWA
│   ├── sw.js                   # سرویس ورکر کش آفلاین
│   ├── electron-main.js        # ورودی دسکتاپ ویندوز
│   └── package.json            # اسکریپت‌های اجرایی دسکتاپ
├── android/                    # اپلیکیشن بومی اندروید
│   └── app/src/main/java/ai/molido/vpn/
│       ├── PlasmaCoreView.kt   # رندر بومی انیمیشن پلاسما با Canvas
│       ├── MainActivity.kt     # داشبورد یکپارچه با ناظر نشست TUN
│       └── core/               # ۸ هسته تکمیلی پلتفرم
├── core/multiengine/           # کانفیگ‌های هسته یکپارچه Sing-box و Clash
│   ├── singbox_universal.json  # قالب جامع Sing-box با TUN و قوانین هوشمند
│   └── clash_meta_rules.yaml   # قالب Clash Meta با گروه‌های هوشمند خودکار
└── .github/workflows/          # گیت‌هاب اکشن‌های اعتبارسنجی و بیلد
    ├── android-ci.yml          # تست واحد کلاینت اندروید
    └── cross-platform-release.yml # پکیج خودکار نسخه‌های وب و دسکتاپ
```

---

## ⚖️ تعهدات و شفافیت مهندسی
پروژه Molido VPN متعهد به شفافیت ۱۰۰٪ است: هیچ اندپوینت جعلی، ترافیک ساختگی یا لاگین‌های تجاری اجباری در این مخزن وجود ندارد. کلیه سرورها و پروتکل‌ها به‌صورت خودکار پایش و ارزیابی می‌شوند.
