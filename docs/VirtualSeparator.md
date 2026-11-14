# Virtual Separator Engine — bit-by-bit, no server

`com.bigrocket.separator` makes **one** TCP stream use **both** radios at the same time,
with no VPS, no BridgeServer and no remote peer. Everything below happens inside the phone.

## What was actually broken before this engine

| Symptom | Cause | Where it is fixed |
|---|---|---|
| A single download never went faster than the better radio | A TCP connection is one 4-tuple, so `PathSelector` could only ever put it on one path | `VirtualSeparatorSocket` splits the *payload* of one HTTP stream and reassembles it in order |
| Cellular sat at 0 % with no static proxy | `Path3Router.selectNetwork(slot = null)` fell through to `else -> wifi`; `BondingSocksServer` always passes `slot = null` | `Path3Router` now runs `PathBond` + `FlowBalancer` whenever the separator is enabled (or a verified static proxy exists) |
| Bonded upload/download stalled on keep-alive servers | The passthrough path read the socket to EOF, which never comes on `Connection: keep-alive` | `passthroughRemainingEntity()` frames by `Transfer-Encoding: chunked` → `Content-Length` → close-delimited (RFC 9112 §6) |
| Body bytes "leaked" onto the wrong radio in the UI counters | Proxied bytes were not attributed to a path | every slice, head and passthrough chunk is accounted through `PathTrafficStats` for the radio that carried it |

## Files

| File | Role |
|---|---|
| `BitStreamSeparator.kt` | Bit-level inverse multiplexer (`BSB1` frame: 30-byte header + packed bits), Bresenham bit scheduler, CRC32 verifier, `WindowedBitStreamMerger` |
| `VirtualSeparatorSocket.kt` | Transparent `java.net.Socket` wrapper: sniff → passthrough or separate, dual-radio work-stealing slice workers, `If-Range` validation, bounded 12-slice (3 MiB) reassembly window, server-first banner handling |
| `VirtualSeparatorRuntime.kt` | Lock-free telemetry + `StateFlow<Status>` (Persian summary) read by the UI |
| `VirtualSeparatorStore.kt` | SharedPreferences persistence (`bigrocket_virtual_separator`) |
| `ChannelEndpoint.kt` | Carriers (UDP/TCP/DoH), `BondedEndpoint` (one channel, N radios, error-diffusion send + failover) and `killPipe(index)` for radio loss |
| `VirtualSeparatorProbe.kt` | 2,097,152-bit concurrent out-of-order self-verifier + live single-stream dual-radio benchmark |

## Where it hooks into the data plane

* `VirtualEgress.connectTcp()` — wraps the freshly dialled socket in `VirtualSeparatorSocket`
  when **all** of these hold: AddressTunnel (engine 2) is not carrying the flow, the separator is
  enabled, both `wifiNetwork` and `cellularNetwork` are live and distinct, the Wi-Fi share is
  `1..99 %`, the destination port is not a TLS/server-first port
  (`21, 22, 25, 110, 143, 443, 465, 587, 853, 993, 995, 8443`) and the flow was dialled on one of
  the two physical radios. The `openWorkerSocket` callback re-dials through the same egress
  machinery, so Direct **and** Static-Proxy modes both work.
* `Path3Router.selectNetwork()` — flow-level bonding for every other flow (and for every flow
  when the separator is off but a verified proxy is set).
* `BigRocketVpnService` — `VirtualSeparatorStore.hydrate()` on `onCreate`, and the 4 s weight loop
  pushes `DynamicWeightCalculator`'s split into `VirtualSeparatorRuntime.updateShare()`.
* UI — Main screen card **«موتور جداکننده مجازی (بیت‌به‌بیت — ادغام واقعی بدون سرور)»** with a
  persistent switch, live status line and the **«تست زنده جداسازی و ادغام بیت‌به‌بیت»** button;
  `LiveAggregationActivity` additionally surfaces the single-stream benchmark.

## What it does NOT do (honest limits)

1. **The HTTP separator does not split TLS — the address tunnel does.** These are two different
   engines. `VirtualSeparatorSocket` is an HTTP-level engine: it can only parallelise a stream it
   can re-range with `If-Range`, so an `https://` flow on port 443 goes through it unchanged
   (0 extra sockets, < 1 ms) and is split *below* TLS instead. The AddressTunnel (engine 2 —
   the path you reach through the static IP / the DNS A record / the DoH answer) never parses the
   payload at all: it fragments the encrypted byte stream into chunks, sends consecutive chunks
   over Wi-Fi and cellular concurrently, and re-orders them at the far side. TLS therefore stays
   end-to-end and is never intercepted — no CA, no MITM — yet still uses both radios.
   Measured in `SeparatorTlsOverAddressTunnelTest` (real TLS 1.3 handshake against a real
   `BridgeServer`, both directions byte-exact, each radio throttled to 1 MB/s): a single 2 MiB TLS
   stream ran at **8.05 Mbps on one radio and 16.61 Mbps on two — 2.06×**, with
   `radios used = 2/2`. Repeated runs land between 1.98× and 2.06× (16.10–16.78 Mbps on two). The limit is real but
   narrower than "HTTPS is not split": what is *not* split is one TLS *record boundary* per
   HTTP-range slice; the byte stream underneath it is.
2. Servers that ignore `Range` (`200 OK`) are streamed verbatim — the engine never re-writes
   bytes it did not fetch itself.
3. The IP that leaves the phone is still whichever radio's address the server sees per slice; a
   single logical stream therefore shows both radios' IPs to the *same* host unless a static proxy
   is verified (in Static-Proxy mode every slice leaves through one exit IP).
4. Reassembly is bounded by design: 12 slices ≈ 3 MiB in flight, checked against
   `Content-Range` and a strong `ETag`/`Last-Modified` validator (`If-Range`).

## Verification

* `./gradlew :app:testDebugUnitTest -PbrAbis=armeabi-v7a` — 26 suites / 226 tests, all green, of
  which 44 are new: `BitStreamSeparatorTest` (bit-exact 1..99 percent, `BSB1` round trip, flipped-bit CRC rejection,
  out-of-order/duplicate/beyond-window/NACK-timeout merger cases) and
  `VirtualSeparatorSocketTest` (real dual-`ServerSocket` HTTP Range server: one GET split across two
  radios, client `Range` → `206`, dead-radio failover, mid-slice worker death, `200 OK` passthrough,
  small-file single slice, POST/TLS/banner passthrough, 4 concurrent separated downloads).
* `SeparatorTlsOverAddressTunnelTest` (4 tests) is the end-to-end proof for engine 2: a real TLS 1.3
  client handshakes through the address tunnel and its records are carried over **both** radios —
  512 KiB down + up byte-exact, a 2 MiB multi-chunk download byte-exact, a 1 MiB stream that
  survives a radio dying mid-download, and the bandwidth test that prints
  `[measure] single TLS stream, 2048 KiB: 1 radio = 8.05 Mbps (2084ms), 2 radios = 16.61 Mbps
  (1010ms), speedup = 2.06x, radios used = 2/2`.
* `MuxOrderingUnderTwoCarriersTest` guards the concurrency bug that used to corrupt exactly these
  downloads: two carrier threads feeding `MuxSession` at once with inverted arrival order. It fails
  when `MuxSession.drain()` is not serialised and passes when it is. Context: before the fix a
  2 MiB two-carrier TLS download was corrupted in **5 of 8** runs (`bad_record_mac` / `Tag
  mismatch`); after it, **0 of 26** (6×512 KiB + 2 MiB, 12×2 MiB, 8×4 MiB).
* UI: the test button runs the live proof on the device.
