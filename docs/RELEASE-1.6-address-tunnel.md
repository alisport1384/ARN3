# BigRocket 1.6-address-tunnel — release notes

> **versionCode 9** (`v1.6-address-tunnel-b9`). Previous build was `b8`; this one carries the
> Psiphon hop for Direct + Xray, the three tunnel fixes below, and a CI that can build the tree
> from scratch.

## Two radios, one TLS stream — measured, not asserted

```
[measure] single TLS stream, 2048 KiB: 1 radio = 8.05 Mbps (2084ms),
          2 radios = 16.61 Mbps (1010ms), speedup = 2.06x, radios used = 2/2
```

A single HTTPS download, carried by a **real TLS 1.3 connection**, fragmented by the address
tunnel, spread over Wi-Fi **and** cellular at the same time, reassembled and verified
byte-for-byte. Each radio is capped at 1 MB/s in the test so the radios, not loopback, are the
bottleneck. Repeated runs across this build: **1.98× – 2.06×** (the spread is machine timing, not
a code change; the earlier 1.95× figure came from a slower run of the same test).

This is engine 2 — the address tunnel you reach through a plain static IP, a DNS A-record answer,
or a DoH endpoint. No VPS in the middle, no CA, no TLS interception: the tunnel never looks inside
the TLS stream, it only moves bytes.

## New: Psiphon in the Direct + Xray mode

The Psiphon hop used to exist only in Direct + Aether. It now works in **Direct + Xray** on the same
terms: off by default, region automatic by default, optional country from `ExitRegions`, and traffic
exits through Psiphon while the network only ever sees the tunnel.

Xray's core has no `--psiphon` switch, so the hop is a standalone `libpsiphon.so` that serves
`127.0.0.1:1821` as the **entry** of the chain and dials Xray on `:1839` through its
`UpstreamProxyURL`:

```
TUN/hev -> psiphon 127.0.0.1:1821 -> socks5 -> Xray :1839 -> outbound
        (dialerProxy=bonding-chain) -> network
```

`XrayConfigBuilder` is untouched: the user's outbound configuration is exactly what it was.

**Ownership is exclusive, and that matters.** Every chain is claimed by one mechanism only —
`XrayPsiphonHop` for Direct + Xray, the engine's own `PsiphonHop` for any chain containing Aether:

| Chain | Psiphon stage |
|---|---|
| Direct | none |
| Direct + Xray | `XrayPsiphonHop` |
| Direct + Aether | `PsiphonHop` (in-engine) |
| Direct + Aether + Xray | `PsiphonHop` (in-engine) |
| Direct + Xray + Aether | `PsiphonHop` (in-engine) |

A test written for exactly this caught a real double hop: `AETHER_XRAY` was claimed by both
mechanisms, which would have put two Psiphon clients on port 1821. `XrayPsiphonHop.appliesTo` now
returns true for `XRAY` alone, and the test asserts the exclusion.

UDP and DNS stay on Xray `:1839` on purpose: a Psiphon local proxy is TCP only (no UDP ASSOCIATE),
so tunnelling datagrams through it would silently break QUIC and DNS-over-UDP.

Full detail, in Persian: [`PsiphonOverXray-fa.md`](PsiphonOverXray-fa.md).

## Three tunnel bugs fixed — all of them "the flow dies and never recovers"

Found by chasing what looked like a flaky test. `aStreamSplitAcrossTwoRadiosSurvivesADeadRadio`
passed on a laptop and timed out on CI; it was not flakiness, it was the mux failing to repair a
hole, and a fast machine is what pushes enough data in flight to expose it.

The evidence, in the order it appeared:

```
NACK arrived stream=1 size=10
resend miss: no cached chunk at 161459 (have 128)
```

| # | Bug | Why it killed the flow | Fix |
|---|---|---|---|
| 1 | The replay cache was **shallower than the receive window**: 128 chunks kept, 189 needed (256 KiB / 1394 B) | The far side may legally be a full window ahead of the receiver's oldest hole, so a hole early in a fast burst could never be filled | Both ends derive the depth from the window: `INITIAL_WINDOW / MAX_CHUNK + 32` = 220 chunks, asserted by a test so the constants cannot drift apart again |
| 2 | A NACK was **only sent when a DATA frame arrived** | If a frame was lost and the sender then filled the window it stopped sending — no DATA, no NACK, deadlock until the reader timed out | A session watchdog re-checks every open stream every 100 ms, so recovery no longer depends on the link delivering something |
| 3 | The NACK budget was **per stream, not per hole** | `nackTries` was never reset, so after six holes anywhere in a tunnel's life (a phone keeps one open for hours) the receiver silently stopped asking for repairs | It resets when the hole resolves, with `maxOf` so a racing carrier thread cannot zero a live value |

Also fixed: `totalNacksSent` was declared but never incremented, so the stats reported **zero repairs
during a repair**. The bridge now reports `nacks=` and `resendMisses=` so a shallow cache is
measurable instead of silent.

Effect, measured: the two-radio TLS suite **failed 3 of 8 runs** before these fixes and passes
**10 of 10** after them, with bandwidth unchanged. `MuxRetransmitReachTest` forces loss
deterministically (it drops inbound DATA frames on a schedule instead of racing a socket) and
asserts the bridge honours every NACK. Reverting either the `MuxSession` or the far-side change
makes those tests fail — which is how you know they test the bug and not themselves.

## CI can now build this tree from scratch

`scripts/stage-prebuilt-natives.sh` fetched the four engine libraries from **another repository's**
release (`alisport1384/ARN2`, tag `v1.6-address-tunnel-b7`). That release was deleted, so the
pipeline died with

```
curl: (22) The requested URL returned error: 404
stage-prebuilt-natives: cannot download the BigRocket arm64-v8a APK
```

before compiling anything — a tree that could not reproduce its own build. It now stages from
**this repository's own** release; every release this workflow publishes carries those same four
libraries, so the newest tag always has what any checkout needs. `BIGROCKET_BASE` still overrides
it for a one-off. Verified by staging from an empty `jniLibs/` and `assets/aether/`.

## Test suite

**26 suites / 226 tests, 0 failures.**

New in this release:

* `XrayPsiphonHopTest` — 11 tests: chain ownership (and that the two mechanisms never overlap),
  `UpstreamProxyURL` always points at Xray, the listen port is the chain's entry port, the local
  HTTP proxy is off, an empty region is **omitted** rather than set to `""` (Psiphon treats any
  `EgressRegion` as a hard filter and would refuse to connect), an invalid region never reaches the
  config, and the JSON is valid.
* `MuxRetransmitReachTest` — 3 tests: the cache/window invariant as arithmetic, one silently
  dropped frame replayed byte-exact, and eight holes in one 6 MiB stream all repaired with the
  bridge's `resendMisses` asserted to be zero.
* `SeparatorTlsOverAddressTunnelTest` — 4 end-to-end tests against the real `BridgeServer`:

  * TLS 1.3, 512 KiB download **and** upload, byte-exact, two radios
  * 2 MiB multi-chunk download, byte-exact
  * 1 MiB stream that survives a radio dying mid-download
  * the bandwidth measurement above

  The harness now gives each connection a **fresh** origin listener and a 60 s tunnel/TLS timeout.
  Caching the listener per route let a later handshake race the previous worker's close, which was
  the `SSLException` half of the original CI failure.

The TLS keystore for the tests is `app/src/test/resources/separator-tls-test.p12`
(alias `bridge-tls`, password `bigrocket-test`); it is a throwaway self-signed certificate used
only by the origin inside the test JVM.

## Installing

Two APKs, one per architecture. Both are signed with the same key as earlier 1.6 builds
(`CN=BigRocket`), so this installs **over** `b8` without uninstalling.

| File | For |
|---|---|
| `BigRocket-arm64-v8a.apk` | 64-bit ARM — most phones from ~2017 on |
| `BigRocket-armeabi-v7a.apk` | 32-bit ARM — older / low-end phones |

If a previous BigRocket is installed and this build refuses to update it, uninstall the old one
first: Android will not accept an update signed with a different key.

## Honest scope

* **A live connection to the Psiphon network is not covered by these tests.** It needs a handset
  with a real network. On first run, look for the log line
  `psiphon stage live on 127.0.0.1:1821 -> 1839` — that means the stage came up. The tests prove
  the chain logic and the config the stage is launched with, not that the Psiphon network answers.
* The **HTTP separator** (`VirtualSeparatorSocket`) still does not split TLS itself — it can only
  re-range a stream with `If-Range`, so an `https://` flow passes through it unchanged and is split
  *below* TLS by the address tunnel instead. What this release corrects is the older claim that
  "HTTPS is not split at all": the bytes are, at the tunnel layer.
* The two throttles in the bandwidth test model two comparable radios. They are not LTE latency,
  loss, or a handover. Real-radio figures have to be measured on a handset.
