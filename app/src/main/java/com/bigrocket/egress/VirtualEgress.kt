package com.bigrocket.egress

import android.net.Network
import android.net.VpnService
import com.bigrocket.service.AppLogger
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket

/**
 * THE VIRTUAL VPS EGRESS.
 *
 * BigRocket merges Path 1 (Wi-Fi) and Path 2 (Cellular) at one logical boundary (the
 * "virtual VPS"): [com.bigrocket.service.BondingSocksServer] for the chained modes
 * (Direct+Aether / Direct+Xray / both chains) and
 * [com.bigrocket.service.TcpRelayEngine] + [com.bigrocket.service.UdpRelayEngine] for
 * Direct mode. Before this class existed, that boundary always finished by dialling the
 * destination itself, so the address the internet saw was whichever physical path had been
 * picked for that flow.
 *
 * [VirtualEgress] replaces that final dial with a single, stable one:
 *
 *   Path1 + Path2  ->  merge (bonding / Path3)  ->  egress  ->  static proxy  ->  Internet
 *                                                          \->  direct (proxy off)
 *
 * so the merged stream is what gets carried inside the proxy protocol, and the user's
 * static proxy is what the internet sees - in *every* mode, because every mode's last hop
 * goes through here. That is the app-level equivalent of "one VPS in the middle": the two
 * pipes join, then leave from one address.
 *
 * Guarantees kept from the pre-existing code:
 *  - the socket that reaches the proxy is created by the CHOSEN [Network] and `protect()`-ed,
 *    so the proxy connection itself is genuinely carried by Path 1 or Path 2 (never the
 *    default route, never the TUN - no loop);
 *  - destination names are resolved by the proxy (SOCKS5 ATYP=0x03 / HTTP CONNECT), not by
 *    the phone, so DNS cannot leak out of a different path;
 *  - on any proxy failure the call throws, and the caller's per-path fallback
 *    (BondingSocksServer tries the other physical path next) behaves exactly as before.
 */
object VirtualEgress {

    private const val CONNECT_TIMEOUT_MS = 5_000

    /** Hard cap on a path-scoped DNS lookup (see [resolveOn]). */
    private const val DNS_TIMEOUT_MS = 2_500L
    private const val PROXY_CONNECT_TIMEOUT_MS = 6_000

    /**
     * Current physical paths, published by BigRocketVpnService.onNetworksUpdated. Needed here
     * because bonded UDP has to open ONE channel per path, and the relay engines only ever hand
     * us the single path they picked for the flow.
     */
    /**
     * Small pool dedicated to path-scoped DNS lookups. Cached (not fixed) so a burst of new
     * flows can all resolve, and daemon threads so nothing keeps the process alive.
     */
    private val resolverPool = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "br-dns").apply { isDaemon = true }
    }

    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var cellularNetwork: Network? = null

    fun updateNetworks(wifi: Network?, cellular: Network?) {
        wifiNetwork = wifi
        cellularNetwork = cellular
    }

    fun activeConfig(): StaticProxyConfig? = StaticProxyRuntime.config

    fun isEnabled(): Boolean = StaticProxyRuntime.config != null

    /** One-line description used in logs and the status card. */
    fun describe(): String = StaticProxyRuntime.config?.let { "static-proxy ${it.describe()}" } ?: "direct"

    /**
     * Opens a TCP connection to [destHost]:[destPort] over [network].
     *
     * With a static proxy configured the returned socket is already tunnelled through it:
     * the caller reads/writes the socket exactly as before - the proxy is invisible above
     * this line.
     */
    fun connectTcp(
        vpnService: VpnService,
        network: Network,
        destHost: String,
        destPort: Int,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        label: String = "",
    ): Socket {
        val configured = StaticProxyRuntime.config
        if (configured == null) {
            return connectDirect(vpnService, network, destHost, destPort, connectTimeoutMs, label)
        }
        val proxy = effectiveConfig(vpnService, network, configured)
        return connectThroughProxy(vpnService, network, proxy, destHost, destPort, connectTimeoutMs, label)
    }

    /**
     * Turns a portless ("auto") config into a concrete one by asking ProxyPortDetector.
     *
     * Only the FIRST connection pays for the probe: the result is published to
     * [StaticProxyRuntime] (and therefore visible in the UI as "پورت پیدا شد: …") and reused by
     * every later flow. A config with an explicit port is returned untouched.
     */
    private fun effectiveConfig(
        vpnService: VpnService,
        network: Network,
        configured: StaticProxyConfig,
    ): StaticProxyConfig {
        if (!configured.autoPort) return configured
        val already = StaticProxyRuntime.detectedPort()
        if (already > 0) {
            val kind = if (already == configured.port) configured.kind else configured.kind
            return configured.copy(port = already, autoPort = false, kind = kind)
        }
        val (found, attempts) = ProxyPortDetector.detect(
            host = configured.host,
            resolver = { resolveOn(network, it) },
        )
        if (found == null) {
            val message = ProxyPortDetector.describeFailure(configured.host, attempts)
            StaticProxyRuntime.noteTunnelFailed(message)
            throw IOException(message)
        }
        StaticProxyRuntime.noteDetectedPort(found.port, found.kind)
        return configured.copy(port = found.port, kind = found.kind, autoPort = false)
    }

    /** Exactly the pre-existing Direct-mode behaviour (kept byte-for-byte in spirit). */
    private fun connectDirect(
        vpnService: VpnService,
        network: Network,
        destHost: String,
        destPort: Int,
        connectTimeoutMs: Int,
        label: String,
    ): Socket {
        val socket = network.socketFactory.createSocket()
        try {
            if (!vpnService.protect(socket)) throw IOException("Unable to protect TCP socket from VPN")
            socket.tcpNoDelay = true
            val resolved = resolveOn(network, destHost)
                ?: throw IOException("DNS resolution failed for $destHost")
            socket.connect(InetSocketAddress(resolved, destPort), connectTimeoutMs)
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun connectThroughProxy(
        vpnService: VpnService,
        network: Network,
        proxy: StaticProxyConfig,
        destHost: String,
        destPort: Int,
        connectTimeoutMs: Int,
        label: String,
    ): Socket {
        var socket: Socket? = null
        try {
            val proxyAddress = resolveOn(network, proxy.host)
                ?: throw IOException("DNS resolution failed for proxy ${proxy.host}")
            socket = network.socketFactory.createSocket()
            if (!vpnService.protect(socket)) throw IOException("Unable to protect proxy socket from VPN")
            socket.tcpNoDelay = true
            socket.connect(
                InetSocketAddress(proxyAddress, proxy.port),
                minOf(connectTimeoutMs, PROXY_CONNECT_TIMEOUT_MS),
            )
            // `https://` proxies: the hop phone -> proxy is TLS as well.
            val channel: Socket = if (proxy.proxyOverTls) {
                wrapTls(socket, proxy.host, proxy.port)
            } else {
                socket
            }
            when (proxy.kind) {
                StaticProxyKind.SOCKS5 -> Socks5Protocol.connect(channel, proxy, destHost, destPort)
                StaticProxyKind.HTTP -> HttpProxyProtocol.connect(channel, proxy, destHost, destPort)
            }
            StaticProxyRuntime.noteTunnelOpened()
            AppLogger.log(
                "Egress",
                "tunnel ${proxy.describe()} -> $destHost:$destPort on $label network (${network.hashCode()})",
            )
            return channel
        } catch (e: Exception) {
            StaticProxyRuntime.noteTunnelFailed(e.message ?: e.javaClass.simpleName)
            AppLogger.log(
                "Egress",
                "tunnel ${proxy.describe()} -> $destHost:$destPort FAILED on $label: " +
                    "${e.javaClass.simpleName}: ${e.message}",
            )
            runCatching { socket?.close() }
            throw e
        }
    }

    // --- UDP ---------------------------------------------------------------------------

    /**
     * Creates a UDP channel over [network] for one flow.
     *
     * - static proxy off, or an HTTP(S) proxy (which cannot carry UDP): a plain protected,
     *   network-bound datagram socket - the historical behaviour;
     * - SOCKS5 proxy: UDP ASSOCIATE, so the datagrams leave from the proxy's address too.
     */
    fun openUdpChannel(vpnService: VpnService, network: Network, label: String = ""): EgressUdpChannel {
        val configured = StaticProxyRuntime.config
        if (configured == null) {
            return DirectUdpChannel(vpnService, network)
        }
        val proxy = runCatching { effectiveConfig(vpnService, network, configured) }
            .getOrElse { e ->
                AppLogger.log("Egress", "auto port detection failed for UDP: ${e.message} - going direct")
                return DirectUdpChannel(vpnService, network)
            }
        // Verified proxy + both paths up + SOCKS5 (which can carry UDP): datagrams are the one
        // place where a SINGLE flow can genuinely be spread over both links, one datagram each,
        // because they are independent by nature and each association is valid on its own. This
        // is the finest-grained simultaneous merge available without a remote reassembly peer.
        if (StaticProxyRuntime.isVerified()) {
            val wifi = wifiNetwork
            val cellular = cellularNetwork
            if (proxy.kind.supportsUdp && wifi != null && cellular != null) {
                val bonded = runCatching { BondedUdpChannel.open(vpnService, wifi, cellular, proxy) }
                    .getOrElse { e ->
                        AppLogger.log(
                            "Egress",
                            "bonded UDP unavailable (${e.javaClass.simpleName}: ${e.message}) - using one path",
                        )
                        null
                    }
                if (bonded != null) {
                    StaticProxyRuntime.noteUdpChannel()
                    return bonded
                }
            }
        }
        if (!proxy.kind.supportsUdp) {
            // Documented in docs/StaticProxyEgress.md: HTTP proxies are TCP-only. Silently
            // dropping UDP would break DNS and every UDP-based tunnel, so it goes direct and
            // the situation is surfaced in the log instead.
            AppLogger.log(
                "Egress",
                "proxy ${proxy.describe()} cannot carry UDP - this flow goes direct (TCP stays tunnelled)",
            )
            return DirectUdpChannel(vpnService, network)
        }
        return runCatching { Socks5UdpChannel.open(vpnService, network, proxy) }
            .getOrElse { e ->
                StaticProxyRuntime.noteTunnelFailed(e.message ?: e.javaClass.simpleName)
                AppLogger.log(
                    "Egress",
                    "UDP ASSOCIATE via ${proxy.describe()} failed (${e.javaClass.simpleName}: ${e.message}) - " +
                        "falling back to a direct UDP channel",
                )
                DirectUdpChannel(vpnService, network)
            }
            .also { StaticProxyRuntime.noteUdpChannel() }
    }

    // --- helpers -----------------------------------------------------------------------

    /**
     * Resolves [host] on [network] specifically. A literal IP needs no query; a name is
     * resolved by the network that will actually carry the connection, never by the
     * system default resolver (which would leak onto an unrelated path - or into our own TUN).
     */
    /**
     * Resolves [host] ON [network], bounded in time.
     *
     * Two things matter here, both learned the hard way on real radios:
     *
     *  1. Textual check FIRST: InetAddress.getByName() on a *name* performs a system-resolver
     *     lookup (an unprotected, path-agnostic one), which is exactly the DNS leak this class
     *     exists to avoid. Only genuine literals take that shortcut.
     *  2. The query is BOUNDED. A lookup on a path that is silently dead (Wi-Fi associated but
     *     not passing traffic) can block for many seconds, and every caller of connectTcp is a
     *     flow that then sits there instead of failing over to the other path. Bounding the
     *     lookup converts a multi-second stall into an immediate per-path retry, which is what
     *     makes the other radio take over.
     *
     * Returns null on timeout/failure so the caller's per-path retry decides what to do next -
     * there is deliberately no system-resolver fallback.
     */
    private fun resolveOn(network: Network, host: String, timeoutMs: Long = DNS_TIMEOUT_MS): InetAddress? {
        if (isIpLiteral(host)) {
            runCatching { InetAddress.getByName(host) }.getOrNull()?.let { return it }
        }
        val task = resolverPool.submit(java.util.concurrent.Callable { network.getAllByName(host).firstOrNull() })
        return try {
            task.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            task.cancel(true)
            AppLogger.log("Egress", "DNS lookup for $host on ${network.hashCode()} timed out after ${timeoutMs}ms")
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all { it.isDigit() } }
    }

    private fun wrapTls(socket: Socket, host: String, port: Int): Socket {
        val factory = javax.net.ssl.SSLContext.getDefault().socketFactory
        val ssl = factory.createSocket(socket, host, port, true) as SSLSocket
        ssl.startHandshake()
        return ssl
    }
}

/** One received datagram, normalised across direct and proxied UDP. */
class EgressDatagram(
    val host: String,
    val port: Int,
    val data: ByteArray,
    val length: Int,
)

/**
 * UDP transport used by [com.bigrocket.service.UdpRelayEngine] and
 * [com.bigrocket.service.BondingSocksServer], so the two proxy modes differ in exactly one
 * place instead of duplicating the relay logic.
 */
interface EgressUdpChannel {
    /** Physical path this channel was created on (used for pinning / eviction). */
    val network: Network

    /** Short description for logs: "direct" or "socks5-udp@host:port". */
    val label: String

    /** @return false when the send failed (caller decides to repin/drop). */
    fun send(host: String, port: Int, payload: ByteArray, length: Int): Boolean

    /** Blocks up to [timeoutMs]; null on timeout (so idle reaping still works). */
    fun receive(buffer: ByteArray, timeoutMs: Int): EgressDatagram?

    /** false once the underlying socket is gone, so a reader loop can stop instead of spinning. */
    val isOpen: Boolean

    fun close()
}

/** Historical behaviour: a protected datagram socket bound to the chosen physical path. */
class DirectUdpChannel(
    private val vpnService: VpnService,
    override val network: Network,
) : EgressUdpChannel {

    private val socket: DatagramSocket = DatagramSocket()

    init {
        if (!vpnService.protect(socket)) {
            socket.close()
            throw IOException("Unable to protect UDP socket from VPN")
        }
        network.bindSocket(socket)
    }

    override val label: String get() = "direct"

    override val isOpen: Boolean get() = !socket.isClosed

    override fun send(host: String, port: Int, payload: ByteArray, length: Int): Boolean = runCatching {
        val address = network.getAllByName(host).firstOrNull() ?: InetAddress.getByName(host)
        socket.send(DatagramPacket(payload, length, address, port))
    }.isSuccess

    override fun receive(buffer: ByteArray, timeoutMs: Int): EgressDatagram? {
        socket.soTimeout = timeoutMs
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            val source = packet.address
            EgressDatagram(
                host = source?.hostAddress ?: "",
                port = packet.port,
                data = buffer,
                length = packet.length,
            )
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/**
 * SOCKS5 UDP ASSOCIATE channel: one long-lived TCP control connection plus one datagram
 * socket to the proxy's relay endpoint. Every datagram is encapsulated per RFC 1928 §7.
 */
class Socks5UdpChannel private constructor(
    private val vpnService: VpnService,
    override val network: Network,
    private val proxy: StaticProxyConfig,
    private val control: Socket,
    private val datagram: DatagramSocket,
    private val relay: InetSocketAddress,
) : EgressUdpChannel {

    companion object {
        fun open(vpnService: VpnService, network: Network, proxy: StaticProxyConfig): Socks5UdpChannel {
            val proxyAddress = network.getAllByName(proxy.host).firstOrNull()
                ?: InetAddress.getByName(proxy.host)
            val control = network.socketFactory.createSocket()
            var datagram: DatagramSocket? = null
            try {
                if (!vpnService.protect(control)) throw IOException("Unable to protect proxy socket from VPN")
                control.tcpNoDelay = true
                control.connect(InetSocketAddress(proxyAddress, proxy.port), 6_000)
                val channel: Socket = if (proxy.proxyOverTls) {
                    val factory = javax.net.ssl.SSLContext.getDefault().socketFactory
                    (factory.createSocket(control, proxy.host, proxy.port, true) as SSLSocket).also {
                        it.startHandshake()
                    }
                } else {
                    control
                }
                control.soTimeout = 6_000
                val relay = Socks5Protocol.openUdpAssociation(channel, proxy, proxyAddress)
                control.soTimeout = 0

                datagram = DatagramSocket()
                if (!vpnService.protect(datagram)) throw IOException("Unable to protect UDP socket from VPN")
                network.bindSocket(datagram)
                AppLogger.log("Egress", "UDP ASSOCIATE via ${proxy.describe()} relay=$relay")
                return Socks5UdpChannel(vpnService, network, proxy, channel, datagram, relay)
            } catch (e: Exception) {
                runCatching { datagram?.close() }
                runCatching { control.close() }
                throw e
            }
        }
    }

    override val label: String get() = "socks5-udp@${proxy.authority()}"

    override val isOpen: Boolean get() = !datagram.isClosed && !control.isClosed

    override fun send(host: String, port: Int, payload: ByteArray, length: Int): Boolean = runCatching {
        val encapsulated = Socks5Protocol.encodeUdpDatagram(host, port, payload, length)
        datagram.send(DatagramPacket(encapsulated, encapsulated.size, relay))
        StaticProxyRuntime.noteBytes(length)
    }.isSuccess

    override fun receive(buffer: ByteArray, timeoutMs: Int): EgressDatagram? {
        datagram.soTimeout = timeoutMs
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            datagram.receive(packet)
            val decoded = Socks5Protocol.decodeUdpDatagram(packet.data, packet.length) ?: return null
            // The payload is re-packed into the caller's buffer so receivers do not have to
            // understand SOCKS5 framing.
            val payloadLength = decoded.payload.size
            val payload = decoded.payload
            if (payload.size > buffer.size) return null
            System.arraycopy(payload, 0, buffer, 0, payloadLength)
            StaticProxyRuntime.noteBytes(payloadLength)
            EgressDatagram(decoded.host, decoded.port, buffer, payloadLength)
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        runCatching { datagram.close() }
        runCatching { control.close() }
    }
}

/**
 * One UDP channel per physical path, datagrams handed out by the shared [PathBond] allocator -
 * so with equal scores they alternate (A,B,A,B: simultaneous, bit-for-bit) and with unequal
 * scores they follow the exact score ratio. Replies are merged transparently from both links.
 *
 * Only used while a VERIFIED static-IP proxy is the endpoint: the proxy hides which physical
 * path carried a datagram, so nothing above this class can tell (or cares) where it went.
 */
class BondedUdpChannel private constructor(
    private val primary: EgressUdpChannel,
    private val secondary: EgressUdpChannel,
    private val wifiPercent: Int,
    private val wifiIsPrimary: Boolean,
) : EgressUdpChannel {

    companion object {
        fun open(
            vpnService: VpnService,
            wifi: Network,
            cellular: Network,
            proxy: StaticProxyConfig,
        ): BondedUdpChannel {
            val wifiPercent = PathBond.wifiPercent
            val wifiFirst = wifiPercent >= 50
            val firstNetwork = if (wifiFirst) wifi else cellular
            val secondNetwork = if (wifiFirst) cellular else wifi
            val first = Socks5UdpChannel.open(vpnService, firstNetwork, proxy)
            val second = runCatching { Socks5UdpChannel.open(vpnService, secondNetwork, proxy) }
                .getOrElse { e ->
                    runCatching { first.close() }
                    throw e
                }
            AppLogger.log(
                "Egress",
                "bonded UDP active: $wifiPercent/${100 - wifiPercent} across both paths " +
                    "(one SOCKS5 UDP association per path)",
            )
            return BondedUdpChannel(first, second, wifiPercent, wifiFirst)
        }
    }

    override val network: Network get() = primary.network

    override val label: String
        get() = "bonded-udp(${if (wifiIsPrimary) "wifi+cellular" else "cellular+wifi"})"

    override val isOpen: Boolean get() = primary.isOpen || secondary.isOpen

    override fun send(host: String, port: Int, payload: ByteArray, length: Int): Boolean {
        val preferPrimary = PathBond.next(wifiPercent) == wifiIsPrimary
        val first = if (preferPrimary) primary else secondary
        val second = if (preferPrimary) secondary else primary
        if (first.send(host, port, payload, length)) return true
        // A path that is failing must not silently swallow the datagram: the other one is a
        // perfectly good carrier for it (the exit IP is the proxy either way).
        return second.send(host, port, payload, length)
    }

    override fun receive(buffer: ByteArray, timeoutMs: Int): EgressDatagram? {
        // Poll both links so replies from either are delivered promptly; the timeout is shared.
        val slice = (timeoutMs / 4).coerceIn(1, 50)
        var waited = 0
        while (waited < timeoutMs) {
            primary.receive(buffer, slice)?.let { return it }
            waited += slice
            secondary.receive(buffer, slice)?.let { return it }
            waited += slice
        }
        return null
    }

    override fun close() {
        runCatching { primary.close() }
        runCatching { secondary.close() }
    }
}
