# BigRocket

An Android VPN that can carry **one** connection over **two** radios at the same time — Wi-Fi and
cellular, merged in the phone, with no server in the middle.

## What this repository is

BigRocket ships two separate engines for using both radios at once:

| Engine | What it splits | Where |
|---|---|---|
| **1 — HTTP separator** | An HTTP download, by re-ranging it with `If-Range` (`206 Partial Content`) | `app/src/main/java/com/bigrocket/separator/` |
| **2 — Address tunnel** | *Any* byte stream, including TLS, by fragmenting and re-ordering it — you reach it through a plain static IP, a DNS A-record answer, or a DoH endpoint | `app/src/main/java/com/bigrocket/tunnel/` |

Engine 2 does not look inside what it carries, so **HTTPS works over both radios without a CA and
without interception** — the TLS session is still end-to-end between the phone and the origin.

Measured, in `SeparatorTlsOverAddressTunnelTest`, on a real TLS 1.3 connection with each radio
throttled to 1 MB/s:

```
single TLS stream, 2048 KiB: 1 radio = 8.05 Mbps (2084ms),
                             2 radios = 16.61 Mbps (1010ms), speedup = 2.06x, radios used = 2/2
```

## Documentation

| Document | Contents |
|---|---|
| [`docs/TwoRadiosOneTlsStream.md`](docs/TwoRadiosOneTlsStream.md) | The TLS-over-two-radios work: the five defects that blocked it, then the three hole-recovery bugs that hid behind a flaky test, and the numbers |
| [`docs/PsiphonOverXray-fa.md`](docs/PsiphonOverXray-fa.md) | Psiphon in the Direct + Xray chain: topology, why Xray alone owns the stage, port table, lifecycle (Persian) |
| [`docs/VirtualSeparator.md`](docs/VirtualSeparator.md) | The virtual separator: architecture, files, where it hooks into the data plane, honest limits |
| [`docs/RELEASE-1.6-address-tunnel.md`](docs/RELEASE-1.6-address-tunnel.md) | Release notes for this version, including how to install |
| [`docs/AddressTunnel.md`](docs/AddressTunnel.md) | The address tunnel protocol and the far-side bridge |

## Building

```bash
./gradlew :app:testDebugUnitTest -PbrAbis=armeabi-v7a      # 26 suites / 231 tests
./gradlew :app:assembleRelease    -PbrAbis=arm64-v8a,armeabi-v7a
```

The native engines (`libxray`, `libpsiphon`, `libhev-socks5-tunnel`, …) are not in git. Stage them
before building:

```bash
scripts/stage-prebuilt-natives.sh arm64-v8a armeabi-v7a
```

`make` CI (`.github/workflows/build.yml`) does the whole thing on every push to `main`: stages the
natives, builds both ABIs, runs the tests, signs the APKs and publishes a release.

## Downloads

Go to [Releases](https://github.com/alisport1384/ARN3/releases). Two APKs are published per release:

* `BigRocket-arm64-v8a.apk` — 64-bit ARM, most phones from ~2017 on
* `BigRocket-armeabi-v7a.apk` — 32-bit ARM, older and low-end phones

Install the one that matches the phone. If you already have BigRocket installed and Android refuses
the update, uninstall the old copy first — a differently-signed APK cannot update an existing one.

## The far side

Engine 2 needs *an address*, not a server of ours: any host you control that can run
`far-side/BridgeServer.kt` (a single-file Kotlin program). `far-side/run-bridge.sh` starts it. See
`docs/AddressTunnel.md`.
