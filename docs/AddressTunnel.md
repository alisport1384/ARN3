# Address-as-a-Tunnel ("آدرس به‌عنوان تونل") — merge engine 2, in-phone

## The problem with what shipped before

`docs/StaticProxyEgress.md` made the *static proxy* the endpoint of the merged stream. That
solved "one stable exit IP", but it moved the merge OUTSIDE the phone and made it
RTT-bound:

```
TUN → merge(bonding) → SOCKS5/HTTP CONNECT to the proxy, ONE TCP flow at a time
                          ↑ DNS + TCP handshake + SOCKS negotiation, per flow
                          ↑ the proxy serialises everything behind itself
                          ↑ the proxy must be a *proxy* (port 1080/8080 …)
```

Measured consequences (all visible in the old code):

| Cost | Where |
|---|---|
| one forced `ProxyPortDetector` scan (up to ~12 ports × connect+read timeouts) for a bare IP | `VirtualEgress.effectiveConfig` |
| a fresh TCP handshake + SOCKS5 greeting + CONNECT **per flow** | `VirtualEgress.connectThroughProxy` |
| a path-scoped DNS lookup per flow | `VirtualEgress.resolveOn` |
| UDP only via `UDP ASSOCIATE`, re-pinned on failure | `Socks5UdpChannel` |
| the "merge" is per *flow* (one TCP connection cannot be split), so real aggregation only ever happened for UDP | `Path3Router` / `BondedUdpChannel` |

So the user's diagnosis was exactly right: **the merge speed depended on the proxy server**,
because the merge was happening *at* the proxy.

## What this change does

The user's own words (translated) were:

> *I want to give a normal IP or even a normal DNS, only and only as an address, and the data
> passes through it. Imagine a server where data from path 1 and path 2 goes bit-by-bit,
> gets merged, and is sent and received — merge engine 2 must do this, but virtually and
> inside the phone itself, not from outside. I want to mount a normal IP or even a normal DNS
> in place of the static IP.*

That is implemented literally:

```
                       ┌──────────────────────────── the phone ─────────────────────────┐
 Apps → TUN → hev-socks5-tunnel → 127.0.0.1:12347 (SOCKS, existing BondingSocksServer)
                                     │
                          AddressTunnel egress  ← NEW, the in-phone merge engine
                          ┌──────────┴───────────┐
                    MUX (many flows → one channel, framing + seq + flow control + reorder)
                          │                    │
                   P1 endpoint (Wi-Fi)   P2 endpoint (cellular)
                   protect()+bindSocket() protect()+bindSocket()
                          └──────────┬─────────┘
                                     │  ONE bare channel to ONE address:
                                     │    • IP   (e.g. 203.0.113.9)
                                     │    • DNS A/AAAA answer for a name
                                     │    • DoH endpoint (e.g. https://1.1.1.1/dns-query)
                                     ▼
                        far side (your server / your DNS): reorder → merge → real sockets
```

* **No proxy protocol, no per-flow handshake.** The address the user types is only *where the
  channel goes*, never a proxy: nothing has to listen on 1080/8080, no SOCKS greeting, no
  `CONNECT`.
* **One channel, many flows.** Every TCP flow and every UDP datagram the phone produces is
  multiplexed into that single channel with sequence numbers; the far side demultiplexes into
  real sockets. New connection = ~1 RTT on the channel, not DNS + TCP + SOCKS.
* **The merge is engine 2's, inside the phone.** Frames are allocated across P1/P2 by the same
  error-diffusion allocator that already existed (`PathBondAllocator`), i.e. strictly
  interleaved at equal weights; the far side reorders by sequence number and merges — and for
  the return path it alternates replies between the two client source addresses, which the
  phone reorders the same way. Both directions are genuinely spread over both radios.
* **DNS can be the endpoint.** A name whose A record is really `[port_hi port_lo cookie_hi
  cookie_lo]` (four bytes — exactly one A record) tells the app where to send frames and which
  session tag to use. An ordinary address (`1.1.1.1`) never looks like one, so a public resolver
  is never mistaken for a bridge. The app then sends its
  frames **to the DNS server's IP at that port** — a DNS server that is just a DNS server, no
  VPN product involved. Plain DNS (UDP/53) or DoH (`https://…/dns-query`, RFC 8484,
  `application/dns-message`) both work as the carrier too.

## Running the far side

```
cd far-side
./run-bridge.sh                 # JDK 17+; fetches kotlinc once, then listens on UDP 5300 + TCP 5301
./run-bridge.sh --any-cookie    # private test box
```

Then in the app's card: `<server-ip>:5300`, `tcp://<server-ip>:8443`, or
`https://<host>/dns-query` (DoH, behind your TLS terminator). The app self-tests it before
switching - see the fallback rule below.

## Fallback rule (as requested)

> *If [the endpoint] exists, engine 2 is used after testing; if not, the sandbox and engine 1
> are used directly.*

### What you may type

| Input | Treated as |
|---|---|
| `203.0.113.9` | **address** → UDP carrier on port 5300 (no port scan, ever) |
| `203.0.113.9:5300` | address on that port |
| `udp://host:5300`, `tcp://host:8443` | address, carrier chosen explicitly |
| `bridge.example.org` | name: resolved; its A record may itself carry `port + cookie` |
| `https://host/dns-query` | DoH carrier (RFC 8484), behind your own TLS terminator |
| `1.2.3.4:1080`, `socks5://…`, `http://…`, `user:pass@host:port` | **proxy** — the old static-proxy egress, unchanged |

The one ambiguous case is a bare IP with no port. Before this change that meant "a proxy, go find
its port" (the per-flow port scan this feature exists to remove), so it now means **address**. A
proxy is recognised by an explicit scheme, credentials, or a well-known proxy port (1080, 8080,
8888, 3128, 9050 …); `TunnelSpecParser.looksLikeProxy` holds that rule in one place.

`AddressTunnelRuntime` only marks itself `READY` after a real handshake **and** a PING/PONG on
the chosen path(s). If the address does not answer:

1. the exact failure is recorded and shown in the UI ("آدرس پاسخ نداد: …"),
2. every flow falls back to the previous behaviour — engine 1 / direct egress on the bonded
   path (`VirtualEgress` direct branch), i.e. nothing breaks when the address is wrong.

A standalone address that *is* a proxy (`1.2.3.4:1080`, `socks5://…`, `http://…`) is still
accepted: `TunnelSpec.parse` recognises those forms and installs the old static-proxy egress
unchanged. Old saved proxies keep working — this change is additive.

## Protocol (BRT1)

12-byte header, big-endian, carried in any datagram/stream the transport provides:

```
0  4  magic 'B''R''T''1'
4  2  cookie          (from the DNS answer; rejects stray traffic)
6  1  type
7  1  flags           (reserved, 0)
8  4  sequence        (per sender, wraps; the reorder/merge key)
12 N  payload
```

Types: `PING/PONG`, `OPEN/OPEN_OK/OPEN_ERR` (atyp host+port, exactly like SOCKS5 addressing so
names are resolved **on the far side** — no DNS leak from the phone), `DATA`, `FIN`, `RST`,
`WINDOW` (credit-based flow control, so a slow reader can never OOM the phone), `DGRAM` /
`DGRAM_RX` (UDP, per datagram), `HELLO/HELLO_ACK`.

Three details the tests forced into existence, because frames really are interleaved over two
radios:

* **FIN carries the total byte count**, and the receiver waits for that many bytes before
  reporting EOF. A tail arriving *after* the FIN is normal — without this, 2 of 12 concurrent
  flows were truncated.
* **The far side buffers frames that beat the OPEN.** A DATA frame sent right after OPEN can
  arrive first; it is held per stream and replayed once the destination is connected.
* **DoH framing**: one raw frame per POST body (what a DNS server expects), while the *response*
  body is a batch of `[len:2][frame]…`, so many replies still fit in one round trip.

Reliability: this is a tunnel over UDP, so retransmission/ordering lives where it must —
in the TCP flows being carried (the far side terminates them as real TCP sockets, so the
internet's TCP does the retransmitting, exactly like any VPN). The bonding layer's frame
protocol (42-byte `BR01` frames, reorder buffer, recovery) is untouched and still available
for engine-1 paths.

## Files

Added:
* `tunnel/Brt.kt` — protocol constants, frame codec, address codec, `Reorderer`, blocking byte queue (Android-free, unit-tested)
* `tunnel/ChannelEndpoint.kt` — carrier abstraction + UDP/TCP/DoH carriers + dual-path (P1+P2) carrier
* `tunnel/MuxSession.kt` — multiplexing, per-stream flow control, `MuxSocket`, UDP datagram channel
* `tunnel/TunnelDiscovery.kt` — plain-DNS / DoH discovery of `port+cookie`
* `tunnel/AddressTunnel.kt` — runtime state, status flow, `openTcp` / `openUdpChannel`, self-test
* `far-side/BridgeServer.kt` — the far side (single Kotlin file, run with the JDK)
* `app/src/test/java/com/bigrocket/tunnel/*` — protocol, reorder, mux-over-loopback, live-HTTP,
  benchmark and single-source tests
* `far-side/run-bridge.sh` — runs the bridge on a server (fetches kotlinc once)
* `tools/sync-bridge.sh` — regenerates the unit-test copy of the bridge
* `docs/AddressTunnel-test-report.md` — what was tested, with numbers

Modified:
* `egress/VirtualEgress.kt` — the two egress calls try the address tunnel first, then fall back
* `service/BigRocketVpnService.kt` — publishes networks + installs the saved spec
* `ui/MainActivity.kt`, `res/layout`, strings — the new card + self-test button
* `app/build.gradle.kts` — `far-side/` is part of the unit-test source set so the bridge that
  the tests exercise is byte-for-byte the one that ships in `far-side/`
