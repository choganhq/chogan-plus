# Chogan Plus · چوگان پلاس

Chogan's games in a Google Play app that adds Play Games sign-in, saved games
in the cloud, leaderboards, and play with friends on nearby phones (no internet
needed). The games themselves come from [Chogan](https://github.com/choganhq/chogan),
pinned as the `chogan` submodule; this repository holds only the Android shell
and the Play-only parts. Chogan itself stays free of Google services, on F-Droid
and the web as before.

Status: early work. Not on Google Play yet.

## Build

    git clone --recursive https://github.com/choganhq/chogan-plus
    cd chogan-plus/android && gradle assembleDebug

Gradle's version is in `android/gradle/wrapper/gradle-wrapper.properties`.
Licence: MIT.

---

<div dir="rtl">

## چوگان پلاس

بازی‌های چوگان در یک اپ گوگل‌پلی، به‌اضافه‌ی ورود با پلی گیمز، ذخیره‌ی ابری
پیشرفت، جدول امتیاز و بازی با دوستان روی گوشی‌های نزدیک بدون نیاز به اینترنت.
بازی‌ها از [چوگان](https://github.com/choganhq/chogan) می‌آیند و این ریپو فقط
پوسته‌ی اندروید و بخش‌های مخصوص گوگل‌پلی را دارد. خود چوگان مثل قبل بدون
سرویس‌های گوگل روی اف‌دروید و وب می‌ماند.

وضعیت: تازه شروع شده و هنوز روی گوگل‌پلی نیست.

مجوز: MIT

</div>
