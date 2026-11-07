package com.bigrocket.service

import studio.cluvex.aether.core.TunnelConfig

/**
 * Which local port the rest of the app should treat as "Aether's chain entry".
 *
 * THE BUG THIS EXISTS FOR (field log, .11): with the optional Psiphon hop on, the
 * engine serves TWO SOCKS listeners:
 *
 *  - `--bind` (**1819**, [TunnelConfig.SOCKS_PORT]) — the tunnel itself. Its exit is
 *    the engine's own transport (WARP), i.e. **not** Psiphon.
 *  - `--psiphon-bind` (**1821**, [PsiphonHop.BIND_PORT]) — the Psiphon stage, whose
 *    dials are carried by that same tunnel. Its exit IS a Psiphon exit. The engine
 *    says so itself: "[+] psiphon is ready; 127.0.0.1:1821 leaves through psiphon,
 *    carried by the tunnel".
 *
 * We always consumed 1819, so even on the runs where Psiphon came up perfectly the
 * traffic left through WARP: the device log shows "psiphon is ready" and, moments
 * later, "tunnel verified, exit ip=104.28.194.222 country=IR" - a Cloudflare WARP
 * address, not a Psiphon one. The switch looked broken while the engine was fine.
 *
 * So the entry has to follow the hop:
 *  - TCP goes to 1821 while the hop is live (that is what makes the exit a Psiphon
 *    exit).
 *  - UDP stays on 1819. Psiphon's local proxy speaks SOCKS5 CONNECT only - it has
 *    no UDP ASSOCIATE, which is exactly why the upstream app inserts its own udpgw
 *    front there (transport/PsiphonSocksFront.kt). Pointing UDP at 1821 would break
 *    DNS entirely; keeping it on 1819 keeps it inside the same tunnel, so it still
 *    never leaves outside Aether, it simply exits through the tunnel's own transport
 *    instead of Psiphon's. That split is deliberate and documented in
 *    docs/PsiphonClientGoFix-fa.md.
 *
 * Everything here is a plain @Volatile pair rather than a flow: the relays read it
 * per connection, and it must be readable from any thread without suspension.
 */
internal object AetherEntry {

    /** Port for every TCP flow that must leave through the active chain. */
    @Volatile var tcpPort: Int = TunnelConfig.SOCKS_PORT
        private set

    /** Port for UDP associations (never Psiphon's: it carries TCP only). */
    @Volatile var udpPort: Int = TunnelConfig.SOCKS_PORT
        private set

    /** True while the Psiphon stage owns [tcpPort]. */
    @Volatile var psiphonLive: Boolean = false
        private set

    /**
     * Publishes the entry for a launch. [hopLive] is true only once the Psiphon
     * listener has actually opened - see EmbeddedAetherRuntime, which calls this
     * after it has proven the stage is up, never merely because the switch is on.
     */
    fun update(hopLive: Boolean) {
        psiphonLive = hopLive
        tcpPort = if (hopLive) PsiphonHop.BIND_PORT else TunnelConfig.SOCKS_PORT
        udpPort = TunnelConfig.SOCKS_PORT
    }

    /** Back to the plain tunnel entry (every stop(), and every launch that is not hopped). */
    fun reset() = update(false)

    /** The port the TUN-side consumer (hev / relays / Xray's chain port) must dial. */
    fun entryPort(chainUsesAether: Boolean, xrayPort: Int): Int =
        if (chainUsesAether) tcpPort else xrayPort
}
