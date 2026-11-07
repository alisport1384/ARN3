# Static-IP proxy egress ("virtual VPS" exit)

## What the user asked for (translated from the original request)

> I want to introduce a proxy IP address as a static IP - a box where the user puts a custom
> proxy IP and presses Save, right above the Connect button. The plan is: after Path 1 and
> Path 2 reach the virtual VPS, one bit from Path 1 and one bit from Path 2 are combined there
> (like two hoses feeding one pipe). Then, because we need one IP anyway, instead of setting
> the IP from Path 1 or Path 2 (which is what the app does today), we put the proxy IP - so we
> have effectively implemented the VPS virtually. And finally this connects to whichever mode
> the user picked (Direct, Xray, ...).

## Where the two paths are already merged today

BigRocket has exactly two egress points in the live traffic path, and every mode ends in one
of them:

| Mode | Path | Egress point |
|---|---|---|
| Direct (nothing else selected) | TUN → `TunPacketRouter` → `TcpRelayEngine` / `UdpRelayEngine` | per-flow socket |
| Direct + Aether / Direct + Xray / Direct + Aether + Xray / Direct + Xray + Aether | TUN → hev-socks5-tunnel (or the JVM router) → local SOCKS → `BondingSocksServer` | per-flow socket |

`BondingSocksServer` (127.0.0.1:12347) **is** the app-level "virtual VPS": Path 1 and Path 2
are chosen there per flow by `Path3Router`, and that is where the two hoses join. What it did
not have was a single, stable address to leave the phone from.

## What was added

```
                       ┌───────────────────────────────┐
Path 1 (Wi-Fi) ────────┤                               │
                       │  merge (Path3 / bonding)      │──► VirtualEgress ──┬──► static proxy ──► Internet
Path 2 (Cellular) ─────┤  BondingSocksServer /         │                    │      (user's IP:port)
                       │  TcpRelayEngine + UdpRelay    │                    └──► direct (proxy off)
                       └───────────────────────────────┘
```

`com.bigrocket.egress.VirtualEgress` is now the **only** place the merged stream leaves the
device, for TCP and UDP, in every mode:

* `TcpRelayEngine` (Direct mode) → `VirtualEgress.connectTcp(...)`
* `UdpRelayEngine` (Direct mode) → `VirtualEgress.openUdpChannel(...)`
* `BondingSocksServer` (all chained modes, TCP + UDP ASSOCIATE) → the same two calls

So the user's chosen mode still decides *how* traffic is wrapped (Aether, Xray, or plain), and
the static proxy decides *where it comes out*. Both halves of the request are satisfied: the
proxy is the virtual VPS's egress, and it is also the peer the merged stream is carried to.

### Preserved guarantees

* the socket that reaches the proxy is created by the **chosen `Network`** and `protect()`-ed,
  so the proxy hop itself genuinely rides Path 1 or Path 2 (never the default route, never the
  TUN - no routing loop);
* destination hostnames are sent to the proxy (SOCKS5 `ATYP=0x03` / HTTP `CONNECT`), so DNS is
  resolved on the proxy side and cannot leak out of a different path;
* `VirtualEgress` never falls back to the system resolver: if the chosen path cannot resolve
  the proxy host, the error propagates and `BondingSocksServer`'s existing "try the other
  physical path" logic handles it exactly as before;
* when no proxy is configured, behaviour is byte-for-byte the old one (same socket creation,
  same `connect()` timeouts, same fallback order).

## Accepted input forms

The same box accepts all of these (see `StaticProxyParserTest`):

| Input | Result |
|---|---|
| `1.2.3.4` | SOCKS5, port 1080 |
| `1.2.3.4:1080` | SOCKS5 |
| `host.example.com:9050` | SOCKS5, name resolved on the chosen path |
| `socks5://user:pass@1.2.3.4:1080` | SOCKS5 with username/password |
| `socks5h://…`, `socks://…` | aliases of SOCKS5 (DNS is always remote) |
| `user:pass@1.2.3.4:1080` | SOCKS5 with credentials, no scheme |
| `1.2.3.4:1080:user:pass` | legacy colon form used by proxy lists |
| `http://[user:pass@]host:port` | HTTP `CONNECT` proxy (default port 8080) |
| `https://host:port` | HTTP `CONNECT` over TLS to the proxy itself |
| `[2001:db8::1]:1080` | IPv6 literal |
| *(empty)* | feature off - egress is direct again |

Percent-encoded credentials (`p%40ss`) are decoded; the password is never shown again in
logs or in the status line (`describe()` masks it).

## UDP

* SOCKS5: real UDP ASSOCIATE - datagrams leave from the proxy's address too.
* HTTP/HTTPS proxies cannot carry UDP (no standard encapsulation), so UDP flows go direct
  while TCP stays tunnelled. This is logged once per channel
  (`proxy … cannot carry UDP - this flow goes direct`) instead of failing silently.
  Dropping those flows instead would break DNS and every UDP-based tunnel.

## Persistence and live application

* `StaticProxyStore` saves the line **encrypted** (AES-256-GCM, Android Keystore key, the same
  `SecureTextCipher` `XrayConfigStore` uses), because a proxy line usually contains a
  password. Plain SharedPreferences were deliberately avoided; reads are synchronous for the
  same reason `EmbeddedAetherRuntime.readUpstreamChoice()` is - the Service must never apply a
  stale default to live traffic.
* `StaticProxyRuntime` is the process-wide live value. Egress points read it **per new flow**,
  so pressing Save applies to the next connection without a reconnect.
* `BigRocketVpnService` re-reads it in `onCreate()`, so a system-restarted service still has
  the user's egress even if no Activity ran in this process.
* The one case that cannot apply live is a chained mode (Aether/Xray): it holds a single
  long-lived tunnel through `BondingSocksServer`. Saving therefore sends
  `ACTION_PROXY_CHANGED`, which restarts the chain once (Direct mode needs nothing - each flow
  dials by itself).

## UI

A card directly above the Connect button:

* the input box (`1.2.3.4:1080` / `socks5://user:pass@host:port` / `http://host:port`),
* **ذخیره** (Save) and **حذف** (Clear),
* a live status line (active/inactive, tunnel count, last error),
* **بررسی آی‌پی خروجی** (check egress IP): dials the proxy exactly like real traffic and asks
  `api.ipify.org` who it sees - the only honest way to prove the static IP is in effect.

## Files

Added:

* `egress/StaticProxyConfig.kt` - model + parser (Android-free, unit-tested)
* `egress/Socks5Protocol.kt` - SOCKS5 client: no-auth, RFC 1929, CONNECT, UDP ASSOCIATE
* `egress/HttpProxyProtocol.kt` - HTTP/HTTPS `CONNECT` + Basic proxy auth
* `egress/VirtualEgress.kt` - the single egress object; `EgressUdpChannel`,
  `DirectUdpChannel`, `Socks5UdpChannel`
* `egress/StaticProxyRuntime.kt` - live config + counters/status `StateFlow`
* `egress/StaticProxyStore.kt` - encrypted persistence
* `egress/EgressIpChecker.kt` - "what IP does the internet see" probe
* `src/test/java/com/bigrocket/egress/StaticProxyParserTest.kt` (20 tests)
* `src/test/java/com/bigrocket/egress/ProxyProtocolTest.kt` (11 tests, real loopback
  SOCKS5/HTTP proxies)

Modified:

* `service/TcpRelayEngine.kt` - Direct-mode TCP dial goes through `VirtualEgress`
* `service/UdpRelayEngine.kt` - UDP sessions now hold an `EgressUdpChannel`
* `service/BondingSocksServer.kt` - the "virtual VPS" egress: TCP dial and the pinned UDP
  association both go through `VirtualEgress`
* `service/BigRocketVpnService.kt` - install on `onCreate`, `ACTION_PROXY_CHANGED`
* `ui/MainActivity.kt`, `res/layout/activity_main.xml` - the card above Connect

No existing behaviour was removed: with the box empty, every path is identical to before.

## Build / test

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
88 tests, 0 failures
```

## خلاصه فارسی

* یک کادر «استاتیک آی‌پی» بالای دکمه اتصال اضافه شد؛ `IP:PORT`، `socks5://user:pass@ip:port`،
  `http://ip:port` و حتی IP خالی (پورت پیش‌فرض ۱۰۸۰) را قبول می‌کند.
* خروجی هر دو مسیر (Wi-Fi و دیتای همراه) حالا از یک نقطه واحد به نام `VirtualEgress` بیرون
  می‌رود؛ همان‌جایی که قبلاً مسیر۱ و مسیر۲ در «VPS مجازی» (`BondingSocksServer`) به هم می‌رسیدند.
  پس آی‌پی عمومی = آی‌پی پروکسی، در همه مودها (Direct / Aether / Xray / زنجیره‌ها).
* اتصال به پروکسی هم روی همان مسیر فیزیکی انتخاب‌شده (پروتکت‌شده) برقرار می‌شود و DNS توسط
  خود پروکسی حل می‌شود؛ آدرس مقصد و DNS از گوشی لو نمی‌رود.
* UDP: با SOCKS5 کامل پشتیبانی می‌شود (UDP ASSOCIATE)؛ پروکسی HTTP فقط TCP را می‌برد و UDP
  مستقیم می‌رود (در لاگ اعلام می‌شود).
* ذخیره‌سازی رمزنگاری‌شده است و با زدن «ذخیره» از اتصال بعدی اعمال می‌شود؛ دکمه «بررسی آی‌پی
  خروجی» واقعاً از مسیر پروکسی به api.ipify.org وصل می‌شود و آی‌پی دیده‌شده را نشان می‌دهد.
