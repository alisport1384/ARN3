# چرا Psiphon وصل نمی‌شد — و چه چیزی عوض شد (نسخه .11)

## علت دقیق (از لاگ دستگاه)

```
[engine] [aether::psiphon] [*] starting psiphon from .../lib/arm/libpsiphon.so
[engine] [aether::psiphon] [*] psiphon starts with the server entries in .../files/psiphon/server_entries.txt
[engine] [aether::psiphon] [*] psiphon trusts the ca store at /system/etc/security/cacerts
[engine] [aether::psiphon] [-] psiphon: panic: tls: ConnectionState is not equal to tls.ConnectionState:
                                     struct field count mismatch: 18 vs 17
[engine] [aether::psiphon] [-] psiphon:  github.com/Psiphon-Labs/psiphon-tls.init.0() unsafe.go:44
[engine] [aether]          [-] psiphon: other: psiphon stopped before it was ready
```

یعنی: **کلاینت Psiphon بلافاصله هنگام بالا آمدن کرش می‌کرد**، قبل از اینکه حتی یک بایت شبکه رد و بدل شود.
هیچ‌کدام از این‌ها مشکل نبود: آدرس‌ها، `server_entries.txt`، CA store، آپ‌استریم، کشور BE. همه درست بودند.

## چرا کرش می‌کرد

`psiphon-tls` (فورک TLS خود Psiphon) در `init()` ساختار `ConnectionState` خودش را با
`crypto/tls.ConnectionState` استاندارد مقایسه می‌کند و اگر تفاوتی ببیند **panic** می‌کند:

```go
func init() {
    if err := structsEqual(&tls.ConnectionState{}, &ConnectionState{}); err != nil {
        panic(...)
    }
}
```

- کلاینتی که در APK نسخه .9/.10 بود با **Go 1.27.2** ساخته شده بود.
- در Go 1.27 یک فیلد به `crypto/tls.ConnectionState` اضافه شده (`LocalCertificate`)؛
  پس تعداد فیلدها ۱۸ در برابر ۱۷ شد و همان `init()` کرش کرد.
- کلاینتی که خود پروژه‌ی Aether در ریلیزهایش می‌دهد با **Go 1.26.3** ساخته شده
  (CI آن‌ها `go-version: "1.22"`… عملاً باینری منتشرشده `go1.26.3` است) و همین جفت درست است.

## چه چیزی عوض شد

1. **`libpsiphon.so` هر دو ABI جایگزین شد** با باینری ساخته‌شده با Go 1.26.3
   (استاتیک، همان چیزی که پروژه‌ی مرجع در APK خودش می‌دهد) — ۲۰.۹MB در برابر ۲۳.۴MB قبلی،
   یعنی APK هم کوچک‌تر شد.
2. **Pre-flight تازه**: قبل از اینکه موتور Aether استارت شود، کلاینت یک‌بار با یک فلگ بی‌ضرر
   اجرا می‌شود (چند صد میلی‌ثانیه) و اگر همان لحظه کرش کند، اتصال **بلافاصله** با پیام فارسی
   متوقف می‌شود: «کلاینت Psiphon با موتور سازگار نیست…». دیگر لازم نیست ۵ دقیقه منتظر
   «stopped before it was ready» بمانید و دنبال مشکل شبکه بگردید.
3. تست‌های واحد برای همین تشخیص اضافه شد (۱۴۴ تست، ۰ خطا).

## چطور مطمئن شویم درست شد

بعد از نصب، در لاگ باید این‌ها را ببینید:

```
[*] psiphon is asked to leave from ...
[*] psiphon starts with the server entries in ...
[*] psiphon reached a server at ...
[+] psiphon is ready; ... leaves through psiphon
```

اگر خط `panic:` دیدید، pre-flight جدید همان را در صفحه نشان می‌دهد — آن متن را بفرستید.

## نکته برای بیلدهای بعدی

فایل‌های `.so` داخل ریلیز `.9` (که بیلد ما از آن‌ها بومی‌ها را برمی‌دارد) هنوز
`libpsiphon.so` خراب دارند. اسکریپت `setup_env.sh` در workspace آن را با نسخه‌ی
درست (`go1.26.3`) جایگزین می‌کند. اگر روزی دوباره از صفر بیلد کردید، این جایگزینی
را فراموش نکنید؛ یا کلاینت را خودتان با Go 1.26.x بسازید، نه ۱.۲۷.
