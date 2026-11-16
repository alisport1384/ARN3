# BigRocket 1.6-address-tunnel — release notes (b10)

> **versionCode 10** (`v1.6-address-tunnel-b10`). Previous build was `b9`; this one fixes the
> Direct + Xray Psiphon stage, which could not start at all in b9, and ships both architectures
> from CI: `BigRocket-arm64-v8a.apk` and `BigRocket-armeabi-v7a.apk`.

## Fixed: Xray would not connect through Psiphon

In b9 the standalone Psiphon client used by the **Direct + Xray** chain
(`XrayPsiphonHop`) exited before opening its listener, so traffic never left through
Psiphon even though Xray itself was healthy. Root cause and companion bugs — full detail,
in Persian: [`PsiphonXrayFix-fa.md`](PsiphonXrayFix-fa.md).

| # | Bug | Fix |
|---|---|---|
| 1 | The generated config omitted `PropagationChannelId` and `SponsorId`, which psiphon-tunnel-core's `Config.Commit()` **requires** — the console client exited immediately (`propagation channel ID is missing from the configuration file`) | The config now mirrors the Aether engine's working launcher (`aether/src/psiphon.rs`) field for field: distribution identity, `ClientPlatform`/`ClientVersion`, remote server list (base64) with its signature key |
| 2 | The config file was made unreadable to everyone (`setReadable(false, false)`), including the client itself, which runs as the same UID | Owner-only permissions (0600), like the engine's own config write |
| 3 | No CA store was handed to the static Go client; on Android every TLS fetch died with `x509: certificate signed by unknown authority` | The engine's `ca_store_env` mirrored: `SSL_CERT_FILE`/`SSL_CERT_DIR` (incl. `/system/etc/security/cacerts`) |
| 4 | `-dataRootDirectory` missing from the client's argv | Passed, in the engine's argument order |
| 5 | UDP-carrying protocols (QUIC/INPROXY) were offered to a client sitting behind a SOCKS5 hop | `LimitTunnelProtocols` = the engine's chained (TCP-only) list + `DisableTactics` |
| 6 | "Ready" meant only that socket 1821 accepted a connection; the chain entry (and the supervisor's health probe) could point at a stage with no tunnel | Ready = `Tunnels` notice with count > 0 **and** listener open, published on `XrayPsiphonHop.ready`; the entry flips to 1821 only then, and `probeChain` probes the real entry |
| 7 | `start()` blocked the VPN control path for up to 300 s, under a 15 s process watchdog | The establishment wait moved to the hop's own thread; `start()` returns after spawn |

Nothing changes while the Psiphon switch is off, and chains containing Aether still use
the engine's own in-tunnel Psiphon stage (`PsiphonHop`) exactly as before — each chain is
still owned by exactly one Psiphon mechanism.

## Test suite

**26 suites / 231 tests, 0 failures** (five regression tests added to
`XrayPsiphonHopTest`, 16 tests in that suite: required config fields, server-list
signature key, TCP-only protocol list, notice parsing, CA-store selection).

A live connection to the Psiphon network still needs a handset with a real network. On
first run, look for `psiphon stage live on 127.0.0.1:1821 -> 1839` — it now means the
tunnel itself is established, not merely that the socket is open.

## Installing

Two APKs, one per architecture, built and signed by GitHub Actions
(`.github/workflows/build.yml`) and published with `SHA256SUMS.txt`:

| File | For |
|---|---|
| `BigRocket-arm64-v8a.apk` | 64-bit ARM — most phones from ~2017 on |
| `BigRocket-armeabi-v7a.apk` | 32-bit ARM — older / low-end phones |

If a previous BigRocket is installed and this build refuses to update it, uninstall the
old one first: Android will not accept an update signed with a different key.
