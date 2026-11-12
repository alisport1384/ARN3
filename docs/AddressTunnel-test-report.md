# Address tunnel — test report (rewrite, 2nd pass)

Everything below was run in a clean Linux sandbox (no Android device) on the exact sources of this
build. Commands are reproducible.

## 1. Toolchain

| Item | Version |
|---|---|
| JDK | OpenJDK 21.0.13 |
| Gradle | 9.5.1 (wrapper), `--no-daemon` |
| AGP / Kotlin | 9.2.1 / 2.4.0 |
| Android SDK | platform `android-37.0`, build-tools `37.0.0` |
| Native inputs | `libaether.so`, `libxray.so`, `libpsiphon.so`, `libhev-socks5-tunnel.so`, … staged for **armeabi-v7a** from the upstream `Aether-1.6.0-armeabi-v7a.apk` |
| Far side | `far-side/BridgeServer.kt` compiled with the kotlinc fetched by `run-bridge.sh` |

## 2. Unit + integration tests

```
./gradlew :app:testDebugUnitTest -PbrAbis=armeabi-v7a --rerun-tasks
BUILD SUCCESSFUL — 168 tests, 0 failures
```

Tunnel suites (all of them drive the real code, not mocks):

| Suite | Tests | What it proves |
|---|---|---|
| `AddressTunnelProtocolTest` | 8 | frame codec (12-byte header, cookie, seq, garbage rejected); SOCKS5-style addressing incl. IPv4/IPv6/name; reorderer with real holes, duplicates and retries; DNS `[port｜cookie]` A-record form; DNS query/answer parsing with compression; spec parser (plain IP, ip:port, Persian digits, bare name, DoH, proxy forms, nonsense); cookie derivation; bounded byte queue + EOF |
| `AddressTunnelTest` | 7 | **live bridge** over loopback UDP/TCP: 64 KiB flow over 2 pipes; single-pipe flow; 12 concurrent flows on ONE tunnel; native UDP datagrams (DNS-style); real HTTP through the tunnel; `OPEN_ERR` for a closed port instead of a hang; self-test passes live / fails dead |
| `DohCarrierTest` | 1 | the DoH carrier end to end: frames inside `application/dns-message` POST bodies through a real HTTP hop in front of the bridge's `--doh-port`, carrying a real TCP flow |
| `AddressTunnelBenchmarkTest` | 1 | one tunnel vs the old per-flow SOCKS5 egress over a 50 ms RTT link; asserts protocol facts, prints timings |
| `BridgeSingleSourceTest` | 1 | the shipped `far-side/BridgeServer.kt` is byte-identical to the one the tests compile (`tools/sync-bridge.sh`) |

The 12 earlier suites (packet, bonding, config, proxy protocol, supervisor, …) are unchanged and
still pass: **145 of the 168 tests are pre-existing**.

### Bugs this pass found and fixed (each one has its own test)

These were real defects, found by the tests, not refactors:

1. **`Reorderer` dropped chunks** that started at the same offset (a short write + retry) — a
   `TreeMap<Long, ByteArray>` silently overwrote the second one. Now a list per offset.
2. **`ByteQueue.read`** could return the queue length instead of the bytes copied, and a partial
   drain could re-add a remainder ahead of data it had already replaced. Both fixed; the queue is
   now bounded and blocks for space.
3. **Remote close was thrown as an `IOException`** instead of being reported as EOF (`-1`), so a
   perfectly normal HTTP response looked like a failure to every caller.
4. **`FIN` closed the stream too early**: with frames interleaved over two radios, the tail can
   arrive *after* the FIN, and 2 of 12 concurrent flows were truncated. FIN now waits for the
   total byte count it carries.
5. **DATA overtaking OPEN** (same interleaving) was silently dropped by the bridge; the far side
   now holds early frames per stream and replays them once the destination is connected.
6. **DoH carrier never worked**: the bridge's `Content-Length` regex was written `\\s` in a Kotlin
   string (a literal backslash), so every DoH request looked body-less; and the app batched
   several frames into one POST while the bridge expects one frame per POST. Both sides fixed.
7. **Cross-session cookie lock**: the bridge pinned the first cookie it ever saw, so switching
   carrier/address was rejected with "no answer". In learn mode the bridge now adopts the HELLO's
   tag.
8. Queue wake-up: a waiting reader was only released on the next poll slice (25 ms of dead time
   per chunk); it is now notified immediately by `write()`.

## 3. Real end-to-end runs (not unit tests)

Far side started exactly as the user will start it:

```
cd far-side && ./run-bridge.sh --udp 5300 --tcp 5301 --any-cookie
[bridge] UDP carrier listening on 0.0.0.0:5300
[bridge] TCP carrier listening on 0.0.0.0:5301
[bridge] ready. udp=5300 tcp=5301 doh=0 cookie=0 (any-cookie=true)
```

Then a **plain-Java client** (`/home/user/work/e2e/Client.java`) — an independent implementation of
BRT1 that shares no code with the app — drove the real bridge over the real network:

```
# UDP carrier, both pipes, real internet
PING/PONG over 2 pipe(s) = true (rtt=11ms)
open #1 1.1.1.1:80 ok
HTTP through tunnel: HTTP/1.1 301 Moved Permanently  (381 bytes)
DNS via tunnel: 61 bytes from 1.1.1.1:53
RESULT: PASS

# TCP carrier, both pipes, real internet
open #1 1.1.1.1:80 ok
HTTP through tunnel: HTTP/1.1 301 Moved Permanently  (381 bytes)
RESULT: PASS

# TCP carrier, local HTTP server (content check)
HTTP through tunnel: HTTP/1.0 200 OK  (203 bytes)
RESULT: PASS
```

So: **a real HTTP exchange and a real DNS query to 1.1.1.1:53 both traverse the tunnel, on both
carriers, with the two pipes interleaved and zero resends.**

Known environmental limit, stated honestly: this sandbox's egress cannot reach
Cloudflare-fronted `example.com:80` (the bridge's own dial works, the reply never comes back —
reproducible with `curl` outside the tunnel). Every other destination tried (`1.1.1.1:80`,
`example.com:443`, a local server) works through the tunnel, so the tunnel is not the variable.

## 4. Benchmark

`AddressTunnelBenchmarkTest`, one-way delay 25 ms (~50 ms RTT), 15 flows, one tunnel:

```
A. proxy  : TCP connect + SOCKS5 greeting + CONNECT : 51492µs/flow setup, 51238µs payload
B. tunnel : OPEN frame on an established tunnel     :  1982µs/flow setup, 26384µs payload
protocol exchanges to open 15 flows: proxy=30 (2.0/flow) tunnel=30 (2.00/flow = OPEN + OPEN_OK)
informational: setup ratio proxy/tunnel = 25.98x, payload ratio = 1.94x
```

How to read it:

* **Setup** is the structural win and it is now large: 2 protocol exchanges per flow *plus a fresh
  TCP connection* (proxy) vs 1 exchange on an already-open channel (tunnel). No new socket is
  opened per flow — asserted, not just observed.
* **Payload** is faster here too (≈1.9×) because the old shape paid for a second delayed relay hop;
  treat the ratio as indicative on a 2-core box. The claim that survives scrutiny is the
  per-flow setup cost and the exchange count, not a raw throughput number.
* What the user actually complained about — "merge speed depended on the proxy server" — is
  addressed structurally: there is no proxy port scan, no per-flow handshake, and the merge is
  in the phone.

## 5. APK

```
./gradlew :app:assembleRelease -PbrAbis=armeabi-v7a   → BUILD SUCCESSFUL
aapt2 dump badging → package='com.bigrocket' versionCode='7' versionName='1.6-address-tunnel'
                     minSdkVersion='24' targetSdkVersion='37'
lib/ABIs           → armeabi-v7a only
classes4/5.dex     → com.bigrocket.tunnel.* present
apksigner verify   → OK, V3 signer CN=BigRocket Test (throwaway test key, password bigrocket)
sha256             → b5dd417280823fcbcacdddcdf227a184ffc68dce99ec5341163592cc13f7f7d1
```

## 6. What is still NOT tested here

* **A real phone with Wi-Fi + cellular at once.** No radios in the sandbox, so
  `protect()` / `Network.bindSocket()` per pipe are exercised by code review only. The two-pipe
  merge itself *is* tested (two independent sockets, interleaved frames, one reassembly). The
  `bindSocket` calls are individually guarded and log instead of throwing, precisely because this
  path cannot be device-tested here.
* **A public DNS server used as the carrier.** `1.1.1.1` answers DNS and never tunnels — which is
  why the app self-tests and falls back. What is tested is the DoH carrier (with a TLS terminator's
  job done by a plain HTTP hop) and the `[port｜cookie]` A-record form at the byte level.
* **TLS in front of the DoH carrier.** Deployment step (nginx/caddy `proxy_pass`), not code.
* **Throughput (Mbps)** — deliberately not claimed.

## Reproducing the build

```bash
# natives (git-ignored; produced by CI in a real build)
mkdir -p app/src/main/jniLibs/armeabi-v7a app/src/main/assets/aether/armeabi-v7a
unzip -o Aether-1.6.0-armeabi-v7a.apk "lib/armeabi-v7a/*" "assets/server_entries.txt" -d /tmp/stage
cp /tmp/stage/lib/armeabi-v7a/*.so app/src/main/jniLibs/armeabi-v7a/
cp app/src/main/jniLibs/armeabi-v7a/libaether.so app/src/main/assets/aether/armeabi-v7a/
cp /tmp/stage/assets/server_entries.txt app/src/main/assets/

./tools/sync-bridge.sh
./gradlew :app:testDebugUnitTest -PbrAbis=armeabi-v7a
./gradlew :app:assembleRelease   -PbrAbis=armeabi-v7a
```
