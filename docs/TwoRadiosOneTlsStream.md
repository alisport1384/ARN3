# Two radios, one TLS stream — what was fixed and what it measures

This is the release note for the change set that made HTTPS actually ride Wi-Fi **and** cellular at
the same time through the address tunnel (engine 2), and the concurrency bug that had to be fixed
before it held together.

Every number below was printed by a test in `app/src/test/java`, on this tree, with
`./gradlew :app:testDebugUnitTest`.

---

## The question this answers

> One TLS stream: is it really carried over both radios at once, and how much faster does it get?

Yes, and roughly **twice as fast**:

```
[measure] single TLS stream, 2048 KiB: 1 radio = 8.05 Mbps (2084ms),
          2 radios = 16.61 Mbps (1010ms), speedup = 2.06x, radios used = 2/2
```

* 2 MiB downloaded through a **real TLS 1.3 connection** whose bytes are fragmented, spread over two
  carrier pipes, re-ordered and reassembled — and verified byte-for-byte against the origin.
* Each radio is throttled to 1 MB/s so the radios, not loopback, are the bottleneck. With one pipe
  the stream cannot exceed that rate; with two it approaches double.
* Repeated runs: **1.98× – 2.06×** (16.10 – 16.78 Mbps on two radios). The variation is JIT and
  scheduling noise, not a behaviour change.

## The bug that made it impossible before

With two carriers, `MuxSession.drain()` runs on **two threads at once** (one per pipe). `Reorderer.poll()`
is atomic per chunk, but that is not enough:

1. thread A polls chunk *N*,
2. thread B polls chunk *N+1*,
3. **B reaches the inbound queue first**.

The byte stream is then handed over out of order. Plain text does not care. TLS does: the AEAD tag no
longer authenticates, and the connection dies with `bad_record_mac` / *Tag mismatch*. Measured on the
real far side before the fix: **5 of 8** two-carrier 2 MiB HTTPS downloads corrupted.

### The fix

`drain()` is serialised as a whole — poll, queue write, and NACK decision inside one critical section.
The lock is deliberately held across the *blocking* write: a reader that has stalled is exactly the
moment the other carrier thread must wait rather than race ahead.

A second, smaller fix came with it: a **NACK grace period** (`NACK_GRACE_MS = 60`). On two radios a
hole is usually a frame still in flight on the slower link, not a loss; NACKing the instant a gap
appears made every normal reordering trigger a retransmission.

Result, same jar, same far side, same origin: **0 of 26** corrupted (6×512 KiB+2 MiB, 12×2 MiB,
8×4 MiB), 2/2 pipes each time.

The other three fixes in this series, all required for the same end:

| Fix | Where | What was wrong |
|---|---|---|
| A | `MuxSession.MuxSocket` | A `MuxSocket` is `Socket()` underneath, so `isConnected()` was `false` and `getInetAddress()` `null`. JSSE refuses to layer TLS on that (`Underlying socket is not connected`). |
| B | `Brt.Reorderer.offer` | A duplicate offer at the same offset replaced a buffered chunk instead of being dropped. |
| C | `far-side/BridgeServer.kt` `Stream.onData` | A NACK retry of a range still in flight was written to the real socket a second time. |
| D | `far-side/BridgeServer.kt` `Router.replyOn` | `PING`/`HELLO_ACK` were round-robined across pipes, so an answer could leave on the *other* radio than the request. |
| E | `MuxSession.drain` | **This one** — the ordering race described above. |

## Regression tests that now guard it

| Test | Guards |
|---|---|
| `MuxOrderingUnderTwoCarriersTest` | Two carrier threads with deliberately inverted arrival order, flow-control honoured like the real bridge. **Fails without the drain lock, passes with it** (12 rounds; the unlocked build failed 1 run in 3). |
| `SeparatorTlsOverAddressTunnelTest` | 4 end-to-end tests: TLS 1.3 download + upload byte-exact over two radios, a 2 MiB multi-chunk download byte-exact, a 1 MiB stream that survives a radio dying mid-download, and the bandwidth measurement above. |
| `BitStreamSeparatorTest` | Bit-exact split/merge at every share 1..99 %, CRC rejection of a flipped bit, out-of-order/duplicate/beyond-window/NACK-timeout merge cases. |
| `VirtualSeparatorSocketTest` | Real dual-`ServerSocket` HTTP Range server: one GET split across two radios, `206` handling, dead-radio failover, mid-slice worker death, passthrough cases. |

Whole suite: **26 suites / 226 tests, 0 failures.**

## Honest scope

* The **HTTP separator** (`VirtualSeparatorSocket`) still does not split TLS itself: it can only
  re-range a stream with `If-Range`, so an `https://` flow passes through it untouched and is split
  *below* TLS by the address tunnel instead. No CA, no interception.
* The two throttles in the bandwidth test are a model of two comparable radios, not a simulation of
  LTE latency, loss, or a handover. Real-radio numbers have to be measured on a handset.

## Also in this release: Psiphon in the Direct + Xray chain

The Psiphon stage used to exist only where Aether does, because the Aether engine runs it
internally (`aether --psiphon`). Xray has no such stage, so in `direct + xray` the app now runs
the psiphon-tunnel-core client itself and chains it **in front of** Xray — see
[`PsiphonOverXray-fa.md`](PsiphonOverXray-fa.md).

The chain's TCP entry moves from Xray's listener (1839) to the client's (1821) while the stage is
armed; UDP stays on Xray, because the client carries TCP only. `XrayPsiphonHopTest` pins the two
things that can fail silently: the client always dials its own tunnel *through* the chain (a config
without `UpstreamProxyURL` would still run, just not through Psiphon), and an empty exit region is
omitted rather than emitted as `""` (Psiphon treats any `EgressRegion` as a hard filter).

## Hole recovery: three bugs that all looked like "the test is flaky"

An intermittent CI failure in `aStreamSplitAcrossTwoRadiosSurvivesADeadRadio` (it passed
locally, timed out on the runner) was not flakiness. It was the mux failing to repair a
hole, and the reason it only showed on a fast machine is that a fast machine is what
pushes enough data in flight to exhaust the replay cache.

The measurements, in the order they were found:

```
NACK arrived stream=1 size=10
resend miss: no cached chunk at 161459 (have 128)
```

1. **The replay cache was shallower than the receive window.** The far side may legally be
   `INITIAL_WINDOW` (256 KiB) ahead of the receiver's oldest un-acknowledged chunk - 189
   chunks of 1394 B - but both ends kept only **128** chunks (`MuxSession.RESEND_CACHE`
   and the far side's `recentOut`). A hole early in a full window was unfillable: the NACK
   arrives, the chunk is already evicted, the flow stalls. Both are now derived from the
   window, `256 KiB / 1394 + 32 = 220` chunks, and the derivation is asserted by a test so
   the two constants cannot drift again.

2. **A NACK was only sent when a DATA frame arrived.** `drain()` notices a hole and asks for
   the repair, and it was called from `onData()` alone. If a frame is lost and the sender
   then fills the window, it stops sending - so no DATA arrives, so no NACK is ever sent.
   Recovery depended on the link delivering more data, which is exactly what cannot happen.
   A session watchdog now re-checks open streams every 100 ms.

3. **The NACK budget was per stream, not per hole.** `nackTries` was incremented and never
   reset, so after `MAX_NACKS = 6` holes anywhere in a tunnel's life the receiver silently
   stopped requesting repairs. A phone keeps one tunnel open for hours; six lost frames over
   that time would have permanently disabled repair. It now resets when the hole resolves.

All three are covered by `MuxRetransmitReachTest`, which drops inbound DATA frames on a
deterministic schedule instead of racing a real socket: one hole, eight holes in one stream,
and the far side's own `resendMisses` counter asserted to be zero. Reverting either the
`MuxSession` or the far-side change makes the tests fail, which is how you know they test the
bug and not themselves.

Result: the suite that failed 3 of 8 runs before the fixes passes **10 of 10** consecutive
runs after them, and the measured bandwidth is unchanged (2 radio = 16.10-16.78 Mbps against
1 radio = 8.05-8.31 Mbps, speedup 2.00-2.02x).
