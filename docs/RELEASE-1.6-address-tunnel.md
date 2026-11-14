# BigRocket 1.6-address-tunnel — release notes

## Two radios, one TLS stream — measured, not asserted

```
[measure] single TLS stream, 2048 KiB: 1 radio = 8.25 Mbps (2033ms),
          2 radios = 16.13 Mbps (1040ms), speedup = 1.95x, radios used = 2/2
```

A single HTTPS download, carried by a **real TLS 1.3 connection**, fragmented by the address
tunnel, spread over Wi-Fi **and** cellular at the same time, reassembled and verified
byte-for-byte. Each radio is capped at 1 MB/s in the test so the radios, not loopback, are the
bottleneck. Repeated runs: 1.95× – 2.01×.

This is engine 2 — the address tunnel you reach through a plain static IP, a DNS A-record answer,
or a DoH endpoint. No VPS in the middle, no CA, no TLS interception: the tunnel never looks inside
the TLS stream, it only moves bytes.

## What was broken (and is not any more)

Five defects, all of which had to be fixed before HTTPS-over-two-radios could work. The headline:

> With two carriers, `MuxSession.drain()` runs on two threads at once. `Reorderer.poll()` is atomic
> per chunk, but thread A can poll chunk *N* while thread B polls *N+1* and B reaches the queue
> first — so the stream is delivered **out of order**. Plain text survives that. TLS does not: the
> AEAD tag stops matching and the connection dies with `bad_record_mac` / *Tag mismatch*.

Measured on the real far side: **5 of 8** two-carrier 2 MiB downloads corrupted before the fix,
**0 of 26** after it.

| Fix | Where | Symptom it caused |
|---|---|---|
| A | `MuxSession.MuxSocket` | `isConnected()` was `false` (a fresh `Socket()` underneath), so JSSE refused to layer TLS: *Underlying socket is not connected* |
| B | `Brt.Reorderer.offer` | A duplicate at the same offset replaced a buffered chunk instead of being dropped |
| C | `far-side` `Stream.onData` | A NACK retry of a range still in flight was written to the destination twice |
| D | `far-side` `Router.replyOn` | `PING`/`HELLO_ACK` were round-robined, so the answer could leave on the other radio than the question |
| E | `MuxSession.drain` | **The ordering race.** Now serialised across poll → queue write → NACK decision, with the lock held across the blocking write on purpose. Plus a 60 ms NACK grace period so normal cross-radio reordering does not trigger retransmissions |

## Test suite

**24 suites / 212 tests, 0 failures.**

New in this release (`app/src/test/java/com/bigrocket/tunnel/`):

* `SeparatorTlsOverAddressTunnelTest` — 4 end-to-end tests against the real `BridgeServer`:
  * TLS 1.3, 512 KiB download **and** upload, byte-exact, two radios
  * 2 MiB multi-chunk download, byte-exact
  * 1 MiB stream that survives a radio dying mid-download
  * the bandwidth measurement above
* `MuxOrderingUnderTwoCarriersTest` — two carrier threads with deliberately inverted arrival order
  and real flow-control credit. **Fails with the drain lock removed, passes with it** (12 rounds).

The TLS keystore for the tests is `app/src/test/resources/separator-tls-test.p12`
(alias `bridge-tls`, password `bigrocket-test`); it is a throwaway self-signed certificate used
only by the origin inside the test JVM.

## Installing

Two APKs, one per architecture. Both are signed with the same key.

| File | For |
|---|---|
| `BigRocket-arm64-v8a.apk` | 64-bit ARM — most phones from ~2017 on |
| `BigRocket-armeabi-v7a.apk` | 32-bit ARM — older / low-end phones |

If a previous BigRocket is installed and this build refuses to update it, uninstall the old one
first: Android will not accept an update signed with a different key.

## Honest scope

* The **HTTP separator** (`VirtualSeparatorSocket`) still does not split TLS itself — it can only
  re-range a stream with `If-Range`, so an `https://` flow passes through it unchanged and is split
  *below* TLS by the address tunnel instead. What this release corrects is the older claim that
  "HTTPS is not split at all": the bytes are, at the tunnel layer.
* The two throttles in the bandwidth test model two comparable radios. They are not LTE latency,
  loss, or a handover. Real-radio figures have to be measured on a handset.
