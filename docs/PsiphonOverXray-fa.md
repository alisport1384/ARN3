# مرحله Psiphon در زنجیرهٔ Direct + Xray

## چه چیزی اضافه شد

تا قبل از این تغییر، مرحلهٔ Psiphon فقط در زنجیره‌هایی بود که **Aether** داشتند
(Direct+Aether، Direct+Aether+Xray، Direct+Xray+Aether). دلیلش این بود که موتور Aether
خودش یک مرحلهٔ Psiphon داخلی دارد (`aether --psiphon`) و آن را روی `127.0.0.1:1821`
سرو می‌کند.

زنجیرهٔ **Direct + Xray** هیچ مرحله‌ای نداشت، چون **Xray** چنین قابلیتی ندارد: نه فلگ
`--psiphon` دارد و نه مرحله‌ای در داخلش. پس کلاینت باید بیرون از Xray اجرا شود.

از این نسخه، همین کار انجام می‌شود.

## زنجیره چطور بسته می‌شود

```
TUN → hev/JVM router → [Xray :1839 socks-in]
                        → [outbound کاربر] → dialerProxy
                        → [bonding :BondingSocksServer.PORT] → شبکه
```

با روشن بودن مرحله:

```
TUN → [Psiphon client :1821] → [upstream socks :1839 = Xray]
     → [outbound کاربر] → bonding → شبکه
```

یعنی:

* **ورودی زنجیره** از `1839` (Xray) به `1821` (کلاینت Psiphon) منتقل می‌شود.
* کلاینت Psiphon، تونل خودش را **از داخل Xray** می‌داند (`UpstreamProxyURL`)، پس ترافیک
  آن هم از bonding عبور می‌کند و هم از outbound کاربر.
* خروجی که اینترنت می‌بیند یک **خروجی Psiphon** است.

`UpstreamProxyURL` تنها خطی است که کل زنجیره را می‌سازد: بدون آن کلاینت مستقیم بیرون
می‌زند، مرحله «کار می‌کند» ولی خروجی Psiphon نیست — و سوییچ شکسته به نظر می‌رسد در حالی
که همهٔ اجزا سالم‌اند.

## چرا فقط Direct + Xray

`AETHER_XRAY` هم از نظر فنی Xray را بیرون‌ترین لایه دارد، ولی زنجیره‌اش داخلش Aether دارد
و Aether **خودش** مرحلهٔ Psiphon دارد. گذاشتن این کلاینت جلوی آن یعنی **دو Psiphon روی
هم**: نصف سرعت و خروجی‌ای که نمی‌شود درباره‌اش استدلال کرد.

پس قاعده این است:

| زنجیره | مکانیزم Psiphon |
|---|---|
| Direct | — |
| Direct + Xray | **این ماژول** (`XrayPsiphonHop`) |
| Direct + Aether | `PsiphonHop` (داخل موتور) |
| Direct + Aether + Xray | `PsiphonHop` (داخل موتور) |
| Direct + Xray + Aether | `PsiphonHop` (داخل موتور) |

`XrayPsiphonHopTest` تضمین می‌کند هیچ زنجیره‌ای هم‌زمان ادعای دو مکانیزم نباشد.

## پورت‌ها

| پورت | کاربرد |
|---|---|
| `1839` | Xray (`EmbeddedXrayRuntime.SOCKS_PORT`) |
| `1821` | کلاینت Psiphon (`XrayPsiphonHop.SOCKS_PORT`) |

`1821` عمداً همان شمارهٔ مرحلهٔ Aether است. این دو هرگز هم‌زمان زنده نیستند (هر مرحله به
یک زنجیره تعلق دارد و با عوض شدن زنجیره، مرحلهٔ قبلی بسته می‌شود) و یک شماره یعنی یک
جدول در بقیهٔ برنامه.

### UDP

Psiphon فقط SOCKS5 `CONNECT` دارد — `UDP ASSOCIATE` ندارد. پس UDP روی Xray (`1839`)
می‌ماند، دقیقاً مثل کاری که `AetherEntry` برای زنجیرهٔ Aether می‌کند. اگر DNS به `1821`
فرستاده می‌شد، هر کوئری می‌شکست.

## چرخهٔ عمر

کلاینت باید تا وقتی زنجیره بالاست زنده بماند و **نباید** بعد از پایین آمدن زنجیره زنده
بماند، چون:

1. پورت `1821` را نگه می‌دارد، و
2. قفل datastore مربوط به Psiphon را — که باعث می‌شود اجرای بعدی با
   `tryDatastoreOpenDB: timeout` بمیرد.

برای همین `XrayPsiphonHop.stop()` از هر جایی که Xray را متوقف می‌کند صدا زده می‌شود، و
هر اجرا یک دایرکتوری datastore تازه می‌گیرد (`PsiphonHop.stateDir`).

## رجیستر تست

`XrayPsiphonHopTest` — ۱۱ تست:

* ادعای مالکیت زنجیره‌ها (و اینکه دو مکانیزم روی هم نیفتند)
* `UpstreamProxyURL` همیشه به Xray اشاره کند
* پورت گوش دادن برابر پورت ورودی زنجیره باشد
* پروکسی HTTP محلی خاموش باشد
* ناحیهٔ خالی **حذف** شود نه `""` (Psiphon هر EgressRegion را فیلتر قطعی می‌داند)
* ناحیهٔ نامعتبر هرگز به کانفیگ نرسد
* JSON معتبر و بدون کاما اضافه

---

## آنچه تست نشده

این تست‌ها **منطق زنجیره** را می‌سنجند، نه اتصال واقعی به شبکهٔ Psiphon را. اتصال واقعی
نیاز به گوشی با شبکهٔ واقعی دارد و در محیط ساخت قابل اجرا نیست. اولین اجرای روی دستگاه
باید با لاگ `XrayPsiphon` بررسی شود: خط `psiphon stage live on 127.0.0.1:1821 -> 1839`
یعنی مرحله بالا آمده.

## یک باگ دیگر که سر راه پیدا شد

`scripts/stage-prebuilt-natives.sh` چهار کتابخانهٔ موتور (`libhev-socks5-tunnel`,
`libxray`, `libaethertun`, `libaether`) را از ریلیز **ARN2** برمی‌داشت:

```
https://github.com/alisport1384/ARN2/releases/download/v1.6-address-tunnel-b7
```

آن ریلیز حذف شد و مرحله با این خط مرد:

```
curl: (22) The requested URL returned error: 404
stage-prebuilt-natives: cannot download the BigRocket arm64-v8a APK
```

یعنی درختی که نمی‌توانست بیلد خودش را بازتولید کند. حالا همان چهار کتابخانه از **ریلیز
همین مخزن** گرفته می‌شوند: هر ریلیزی که این workflow منتشر می‌کند همان چهار فایل را دارد،
پس جدیدترین تگ همیشه چیزی را دارد که یک checkout قدیمی لازم دارد. `BIGROCKET_BASE` هنوز
قابل override است.

با پاک کردن کامل `jniLibs/` و `assets/aether/` تست شد: `stage-prebuilt-natives: OK` و ۸
کتابخانه در جای خود.
