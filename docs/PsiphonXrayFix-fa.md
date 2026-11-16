# چرا «ایکس‌ری به سایفون وصل نمی‌شد» — و چه چیزی عوض شد (نسخهٔ ۱۰)

## علامت

زنجیرهٔ **Direct + Xray** با سوییچ Psiphon روشن می‌شد، Xray خودش سالم بالا می‌آمد،
ولی ترافیک هرگز از Psiphon بیرون نمی‌رفت. در لاگ `XrayPsiphon` این خط می‌آمد:

```
psiphon listener did not open on 127.0.0.1:1821 ...
```

## ریشهٔ اصلی (قطعی، نه حدس)

ماژول `XrayPsiphonHop` کانفیگ کلاینت را خودش می‌نوشت، ولی دو فیلد **اجباری** را
نداشت: `PropagationChannelId` و `SponsorId`.

در سورس رسمی `psiphon-tunnel-core` (`psiphon/config.go`، تابع `Commit`) این دو
فیلد صریحاً required هستند و نبودنشان خطای قطعی می‌دهد:

```
propagation channel ID is missing from the configuration file
sponsor ID is missing from the configuration file
```

همین دو رشته داخل خودِ باینری شیپ‌شده (`libpsiphon.so`) هم هست (با `strings`
قابل مشاهده است). نتیجه: کلاینت کنسول **قبل از باز کردن هر لیستنری** با کد ۱
خارج می‌شد؛ Xray سالم بود، مرحلهٔ جلوی آن مرده‌به‌دنیا می‌آمد.

پیاده‌سازی سالمِ همین اپ، یعنی `aether/src/psiphon.rs` (موتوری که زنجیرهٔ
Aether با همان باینری کار می‌کند)، هر دو را با مقدار `FFFFFFFFFFFFFFFF` می‌گذارد.
کانفیگ Kotlin حالا فیلدبه‌فیلد همان است.

## باگ‌های همراه که همان مسیر را می‌بستند

| # | مشکل | اصلاح |
|---|---|---|
| ۱ | نبود `PropagationChannelId`/`SponsorId`/`ClientPlatform`/`ClientVersion` | هر چهار فیلد، عین `psiphon.rs` |
| ۲ | نبود لیست سرور راه‌دور و کلید امضایش (`Commit` بدون کلید هم رد می‌کند) | `RemoteServerListURLs` (base64، عین موتور) + `RemoteServerListSignaturePublicKey` |
| ۳ | فایل کانفیگ با `setReadable(false, false)` برای **همه** غیرقابل خواندن می‌شد؛ کلاینت با همان UID اجرا می‌شود، پس فایل خودش را هم نمی‌توانست بخواند | مجوز owner-only واقعی (0600) |
| ۴ | هیچ CA store به پروسهٔ فرزند داده نمی‌شد؛ باینری استاتیک Go روی اندروید `/etc/ssl` ندارد و هر واکشی TLS با `x509: certificate signed by unknown authority` می‌مرد | آیینهٔ `ca_store_env` موتور: `SSL_CERT_FILE`/`SSL_CERT_DIR` (از جمله `/system/etc/security/cacerts`) |
| ۵ | فلگ `-dataRootDirectory` روی خط فرمان نبود (موتور همیشه می‌فرستد) | اضافه شد، با همان ترتیب argv موتور |
| ۶ | پروتکل‌های UDPدار (QUIC/INPROXY) به کلاینتی پیشنهاد می‌شد که پشت SOCKS5 است؛ از SOCKS رد نمی‌شوند | `LimitTunnelProtocols` = شکل chained موتور + `DisableTactics: true` |
| ۷ | «آماده» یعنی فقط بازشدن سوکت ۱۸۲۱؛ ورودی زنجیره روی پروکسی‌ای می‌رفت که هنوز تونل نداشت و پروب سلامت سوپروایزر هم ۱۸۳۹ را پروب می‌کرد، نه ورودی واقعی (۱۸۲۱) | آماده = notice `Tunnels` با count>0 **و** سوکت باز، روی StateFlow (`XrayPsiphonHop.ready`)؛ ورودی فقط آن‌وقت به ۱۸۲۱ می‌رود و `probeChain` همان ورودی را پروب می‌کند |
| ۸ | `start()` تا ۳۰۰ ثانیه روی نخِ فراخوان بلاک می‌شد؛ از مسیر کنترل سرویس صدا زده می‌شود که واچ‌داگ ۱۵ثانیه‌ای دارد و پروسه را می‌کشد | انتظار آماده‌شدن به نخ خود ماژول رفت؛ `start()` بعد از spawn برمی‌گردد |

## تست

* `XrayPsiphonHopTest`: ۱۱ تست قبلی + ۵ تست جدید (فیلدهای اجباری، کلید امضای
  لیست سرور، فقط-TCP بودن پروتکل‌ها، پارس noticeهای `Tunnels` و
  `ListeningSocksProxyPort`، انتخاب CA store) — مجموع **۱۶ تست**.
* کل سوئیت: **۲۶ سوئیت / ۲۳۱ تست / ۰ خطا** (`-PbrAbis=armeabi-v7a`).
* اتصال زنده به شبکهٔ Psiphon مثل همیشه نیاز به گوشی واقعی دارد؛ روی اولین اجرا
  در لاگ دنبال این خط بگرد:

```
psiphon stage live on 127.0.0.1:1821 -> 1839
```

(معنایش حالا واقعاً «تونل برقرار است» است، نه فقط «سوکت باز است».)

## انتشار

این اصلاح در ریلیز `v1.6-address-tunnel-b10` (versionCode 10) منتشر می‌شود؛ هر دو
معماری از GitHub Actions ساخته و امضا می‌شوند:

* `BigRocket-arm64-v8a.apk` — گوشی‌های ۶۴بیتی
* `BigRocket-armeabi-v7a.apk` — گوشی‌های ۳۲بیتی (روی ۶۴بیتی هم اجرا می‌شود)

یادداشت‌های کامل ریلیز: [`RELEASE-1.6-address-tunnel-b10.md`](RELEASE-1.6-address-tunnel-b10.md).
