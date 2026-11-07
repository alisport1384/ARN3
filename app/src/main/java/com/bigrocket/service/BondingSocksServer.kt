package com.bigrocket.service

import android.net.Network
import com.bigrocket.egress.EgressUdpChannel
import com.bigrocket.egress.VirtualEgress
import android.net.VpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local SOCKS5 server that [HevTunnel] (hev-socks5-tunnel, a real userspace TCP/IP stack)
 * forwards every TUN packet to as plain SOCKS5, in place of BigRocket's own hand-rolled
 * IP-packet parser/relay ([TunPacketRouter]/[TcpRelayEngine]/[UdpRelayEngine]).
 *
 * hev already terminates the device's TCP connections properly (retransmission, ordering,
 * congestion control - the actual point of switching to it); this class only needs to do
 * what BigRocket's bonding has always done - pick Wi-Fi vs Cellular per new connection by
 * weight, protect() the socket, and relay real bytes - once per SOCKS5 request rather than
 * once per raw IP flow. That's also why this doesn't reuse TcpRelayEngine/UdpRelayEngine:
 * their code is built around parsing raw IP/TCP/UDP headers and hand-building reply packets
 * (RST, etc.), none of which applies here - hev owns that layer now.
 *
 * Kept as a self-contained alternate path (TunPacketRouter is untouched) specifically so
 * this can be A/B compared and trivially reverted - see BigRocketVpnService.USE_HEV_TUNNEL.
 */
class BondingSocksServer(
    private val vpnService: VpnService,
    private val path3Router: Path3Router
) {

    companion object {
        /** 127.0.0.1-only; picked to avoid AetherUpstream's own 1819 and any other local port. */
        const val PORT = 12347
        private const val CONNECT_TIMEOUT_MS = 5000
        // Per-attempt bound while another path is still left to try: a path that is
        // timing out right now must not eat the whole budget the engine's own connect
        // is willing to wait before the other path even gets its turn.
        private const val FALLBACK_CONNECT_TIMEOUT_MS = 3000
        // Consecutive failed UDP sends on the pinned socket before the association is
        // moved to a path that works (a dead Network object fails every send).
        private const val UDP_SEND_FAILURES_TO_REPIN = 3
        // Section: bounds how long a TCP relay direction blocks with no data at all after the
        // connection is established - separate from CONNECT_TIMEOUT_MS, which only bounds the
        // initial handshake. Well under Aether's own ~90s internal TLS-handshake timeout
        // observed in practice, so our own relay fails fast and Aether's retry/reconnect logic
        // gets a chance to try again (a different edge, a different path) sooner rather than
        // waiting out a much longer OS-level TCP stall.
        private const val RELAY_READ_TIMEOUT_MS = 20_000
        private const val UDP_IDLE_TIMEOUT_MS = 60_000L
        private const val UDP_RECEIVE_TIMEOUT_MS = 1000
    }

    @Volatile private var wifiWeight = 50
    @Volatile private var cellularWeight = 50
    @Volatile private var upstreamMode = UpstreamMode.NONE

    private val relayIdCounter = AtomicInteger(0)
    // Own threads (see ControlDispatchers): Aether's/Xray's outbound relay must never queue
    // behind the packet router's sessions for a thread - a handshake that waits is a handshake
    // that misses its window.
    private val scope = CoroutineScope(ControlDispatchers.relay + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    /** Every open relay (TCP or UDP), tagged with which physical Network it's using, so a
     * soft-failure eviction (see [notifySoftFailure]) can close exactly the ones pinned to a
     * path that just went bad - mirrors NetworkSessionTracker's role for the old router. */
    private val activeRelays = ConcurrentHashMap<Int, ActiveRelay>()

    /**
     * [repin] is non-null only for UDP associations: the engine keeps using the local UDP
     * port it was given even after it closes the control connection, so tearing the
     * association down leaves it sending into a closed port. Such a relay is moved to a
     * different physical path in place instead; TCP relays have no such option and close.
     */
    private class ActiveRelay(
        @Volatile var network: Network?,
        val repin: (() -> Unit)? = null,
        val close: () -> Unit,
    )

    fun start() {
        if (serverSocket != null) return
        val server = ServerSocket(PORT, 128, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        acceptJob = scope.launch {
            while (isActive) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                scope.launch { handleClient(client) }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        activeRelays.values.toList().forEach { runCatching { it.close() } }
        activeRelays.clear()
    }

    fun updateNetworks(wifi: Network?, cellular: Network?) {
        path3Router.updateNetworks(wifi, cellular)
        // A relay pinned to a Network that is gone or has been replaced by a new object
        // (Wi-Fi reconnect, cellular handover) can never carry another byte - its socket
        // is bound to the dead one - yet nothing used to notice: the health hysteresis only
        // evicted on a probe-confirmed failure of a still-present Network, never on the
        // Network disappearing outright. The engine's tunnel then stayed wedged on it even
        // after connectivity returned.
        val current = setOfNotNull(wifi, cellular)
        evictRelays { it !in current }
    }

    private fun evictRelays(shouldEvict: (Network) -> Boolean) {
        activeRelays.entries.toList().forEach { (id, relay) ->
            val pinned = relay.network ?: return@forEach
            if (!shouldEvict(pinned)) return@forEach
            val repin = relay.repin
            if (repin != null) {
                relay.network = null
                runCatching { repin() }
            } else {
                runCatching { relay.close() }
                activeRelays.remove(id)
            }
        }
    }

    fun updateWeights(wifiW: Int, cellularW: Int) {
        wifiWeight = wifiW
        cellularWeight = cellularW
        path3Router.updateWeights(wifiW, cellularW)
    }

    fun setUpstreamMode(mode: UpstreamMode) {
        upstreamMode = mode
    }

    /** Mirrors TunPacketRouter.notifySoftFailure: evict every relay pinned to [deadNetwork]
     * immediately instead of leaving it to fail silently - see the extended reasoning on
     * TunPacketRouter.notifySoftFailure itself, which applies identically here. */
    fun notifySoftFailure(deadNetwork: Network) {
        evictRelays { it == deadNetwork }
    }

    /** Deterministic best-path pick. This class has exactly one instance
     *  (BigRocketVpnService.bondingUpstream), used exclusively as Aether's own upstreamProxy -
     *  every connection/association it ever handles (TCP CONNECT for a GOOL/TCP tunnel, UDP
     *  ASSOCIATE for a WireGuard/MASQUE tunnel) is one long-lived flow for the whole VPN
     *  session, not one of many short-lived ones. A weighted-random sample is only meaningful
     *  when it is drawn many times so the outcome converges to the configured ratio; drawn
     *  exactly once, it just as often lands on the low-weight path outright and pins the
     *  entire session there. This never uses chance:
     *  unequal weights mean the app's own engine (DynamicWeightCalculator) has an authoritative
     *  answer already (a user-set score difference, or a measured quality difference), so the
     *  higher-weight network wins outright; an exact tie is resolved by that same engine's own
     *  identity tie-break rule (recent measured latency - see preferredIdentityPath), not by a
     *  fresh coin flip here. An exact tie only happens when the user has given both paths the
     *  same score (unequal scores always produce unequal weights - see
     *  DynamicWeightCalculator.preferredWeights), so it is rare and, either way, genuinely
     *  arbitrary: Wi-Fi is picked, fixed and not random. (DynamicWeightCalculator's own
     *  identity/IP tie-break is deliberately not reused here - it mutates a separate sticky
     *  identity-owner state meant for a different feature, and calling it here would silently
     *  decide/consume that state as a side effect of an unrelated bonding pin.) */
    private fun pickBestNetwork(): Network? = path3Router.selectNetwork()

    /**
     * Path for ONE relayed connection / UDP association.
     *
     * Both branches end in path3Router, but the meaning differs and that is the point of this
     * helper: while a verified static-IP proxy is the endpoint (Path3Router's bonded branch),
     * successive connections are handed out in the exact score ratio - alternating when the
     * scores are equal - so the two links are used SIMULTANEOUSLY by the aggregate traffic.
     * Without a verified proxy the old rule stays: one deterministic best path, because there
     * the physical path really does decide the public IP and a flip mid-session is a liability.
     *
     * A single TCP connection cannot be split across two source addresses (its 4-tuple is
     * fixed), so per-connection is the finest granularity available for TCP; UDP datagrams are
     * interleaved individually by VirtualEgress' bonded channel.
     */
    private fun pickRelayNetwork(): Network? = pickBestNetwork()

    // --- SOCKS5 server handshake ------------------------------------------------------

    private suspend fun handleClient(client: Socket) {
        val relayId = relayIdCounter.getAndIncrement()
        try {
            client.tcpNoDelay = true
            client.soTimeout = CONNECT_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val greeting = ByteArray(2)
            if (!readFully(input, greeting)) return closeQuietly(client)
            if (greeting[0].toInt() != 0x05) return closeQuietly(client)
            val methodCount = greeting[1].toInt() and 0xFF
            if (methodCount > 0 && !readFully(input, ByteArray(methodCount))) return closeQuietly(client)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            val head = ByteArray(4)
            if (!readFully(input, head)) return closeQuietly(client)
            val cmd = head[1].toInt() and 0xFF
            val destination = readAddress(input, head[3].toInt() and 0xFF) ?: return closeQuietly(client)

            client.soTimeout = 0
            when (cmd) {
                0x01 -> handleConnect(relayId, client, input, output, destination.first, destination.second)
                0x03 -> handleUdpAssociate(relayId, client, output)
                else -> {
                    output.write(socksReply(0x07))
                    output.flush()
                    closeQuietly(client)
                }
            }
        } catch (_: Exception) {
            activeRelays.remove(relayId)
            closeQuietly(client)
        }
    }

    private fun readAddress(input: InputStream, atyp: Int): Pair<String, Int>? {
        val host = when (atyp) {
            0x01 -> {
                val addr = ByteArray(4)
                if (!readFully(input, addr)) return null
                InetAddress.getByAddress(addr).hostAddress
            }
            0x03 -> {
                val lenByte = ByteArray(1)
                if (!readFully(input, lenByte)) return null
                val len = lenByte[0].toInt() and 0xFF
                val domain = ByteArray(len)
                if (len > 0 && !readFully(input, domain)) return null
                String(domain, Charsets.US_ASCII)
            }
            0x04 -> {
                val addr = ByteArray(16)
                if (!readFully(input, addr)) return null
                InetAddress.getByAddress(addr).hostAddress
            }
            else -> return null
        }
        val portBytes = ByteArray(2)
        if (!readFully(input, portBytes)) return null
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        return host to port
    }

    // --- TCP CONNECT -------------------------------------------------------------------

    private suspend fun handleConnect(
        relayId: Int,
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        destHost: String,
        destPort: Int,
    ) {
        val mode = upstreamMode
        val remote: Socket
        val network: Network?
        try {
            if (mode == UpstreamMode.AETHER) {
                network = null
                remote = AetherUpstream.openTcp(vpnService, destHost, destPort)
            } else {
                // pickBestNetwork(), not pickNetwork(): this branch only ever carries Aether's
                // own outbound connections (this server instance is Aether's dedicated
                // upstreamProxy - see BigRocketVpnService/EmbeddedAetherRuntime), and a
                // GOOL/TCP tunnel is one long-lived connection for the whole session, same as
                // the UDP-associate case above - a single weighted-random sample would just as
                // often pin the whole session to the low-weight path. See pickBestNetwork's doc.
                val (picked, socket) = connectViaNetworks(destHost, destPort)
                network = picked
                remote = socket
            }
        } catch (_: Exception) {
            runCatching { clientOut.write(socksReply(0x01)); clientOut.flush() }
            closeQuietly(client)
            return
        }

        activeRelays[relayId] = ActiveRelay(network) {
            runCatching { client.close() }
            runCatching { remote.close() }
        }

        try {
            clientOut.write(socksReply(0x00))
            clientOut.flush()
        } catch (_: Exception) {
            activeRelays.remove(relayId)
            runCatching { remote.close() }
            closeQuietly(client)
            return
        }

        val remoteIn = remote.getInputStream()
        val remoteOut = remote.getOutputStream()
        runCatching { client.soTimeout = RELAY_READ_TIMEOUT_MS }

        val upload = scope.launch { pipe(clientIn, remoteOut, "upload dest=$destHost:$destPort") }
        val download = scope.launch { pipe(remoteIn, clientOut, "download dest=$destHost:$destPort") }
        upload.join()
        download.join()

        activeRelays.remove(relayId)
        runCatching { remote.close() }
        closeQuietly(client)
    }

    /**
     * Opens the TCP connection on the best path and, if that path fails or times out, on
     * the other one. Previously a path that was timing out at that instant failed the
     * request outright - and a long-lived tunnel connection that fails to open is exactly
     * what leaves the engine "connecting" until a path happens to recover.
     */
    private suspend fun connectViaNetworks(destHost: String, destPort: Int): Pair<Network, Socket> {
        val first = pickRelayNetwork() ?: throw IOException("No usable network")
        val candidates = listOf(first) + path3Router.allNetworksSorted().filter { it != first }
        var lastError: Exception? = null
        for ((index, picked) in candidates.withIndex()) {
            val timeoutMs = if (index < candidates.lastIndex) FALLBACK_CONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS
            try {
                AppLogger.log(
                    "Path3",
                    "TCP CONNECT pin chosen=${path3Router.describeNetwork(picked)} wifiWeight=$wifiWeight cellularWeight=$cellularWeight " +
                        "dest=$destHost:$destPort egress=${VirtualEgress.describe()}",
                )
                // VirtualEgress IS this class's egress boundary ("the virtual VPS"):
                //  - it creates the socket from `picked` and protect()s it (protecting alone
                //    does not choose the uplink, the Network must create the socket);
                //  - it resolves the name on `picked` (a literal IP needs no query) - never
                //    through the system-default resolver, which is unbound and would leak onto
                //    another path or recurse into this VPN's own TUN;
                //  - and, when the user has set a static-IP proxy, that is what it dials: both
                //    merged pipes leave from ONE address, the proxy's.
                val connected = VirtualEgress.connectTcp(
                    vpnService = vpnService,
                    network = picked,
                    destHost = destHost,
                    destPort = destPort,
                    connectTimeoutMs = timeoutMs,
                    label = "path3-tcp",
                )
                connected.soTimeout = RELAY_READ_TIMEOUT_MS
                return picked to connected
            } catch (e: Exception) {
                lastError = e
                AppLogger.log(
                    "Path3",
                    "TCP CONNECT via ${path3Router.describeNetwork(picked)} failed (${e.javaClass.simpleName}: ${e.message})" +
                        if (index < candidates.lastIndex) " - trying the other path" else "",
                )
            }
        }
        throw lastError ?: IOException("No usable network")
    }

    private fun pipe(from: InputStream, to: OutputStream, label: String) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
                TrafficStats.recordBytes(n)
            }
        } catch (_: SocketTimeoutException) {
            // Section: no response within RELAY_READ_TIMEOUT_MS - most likely the remote (or a
            // DPI box in between) silently dropped the connection post-handshake rather than
            // resetting it, which a bare TCP read would otherwise block on indefinitely. Logged
            // distinctly from a normal close so this is visible without needing full verbosity.
            AppLogger.log("Path3", "relay timeout ($label) after ${RELAY_READ_TIMEOUT_MS}ms idle")
        } catch (_: Exception) {
        } finally {
            runCatching { to.flush() }
        }
    }

    // --- UDP ASSOCIATE -------------------------------------------------------------------

    private suspend fun handleUdpAssociate(relayId: Int, client: Socket, clientOut: OutputStream) {
        val localUdp = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        vpnService.protect(localUdp)
        AppLogger.log("Path3", "UDP ASSOCIATE opened, localUdp bound to 127.0.0.1:${localUdp.localPort}")
        val mode = upstreamMode
        val aetherAssociation = if (mode == UpstreamMode.AETHER) {
            try {
                AetherUpstream.openUdp(vpnService)
            } catch (_: Exception) {
                null
            }
        } else null

        val reply = socksReply(0x00, InetAddress.getByName("127.0.0.1"), localUdp.localPort)
        try {
            clientOut.write(reply)
            clientOut.flush()
        } catch (_: Exception) {
            runCatching { localUdp.close() }
            runCatching { aetherAssociation?.close() }
            closeQuietly(client)
            return
        }

        // Single-path state for the real-dial (NONE-mode, aetherAssociation == null) branch:
        // exactly one raw socket, bound once via the same deterministic pickBestNetwork() the
        // TCP CONNECT branch uses, for this association's entire lifetime.
        //
        // This branch carries Aether's own WireGuard/GOOL tunnel (BondingSocksServer is
        // Aether's configured upstreamProxy - see EmbeddedAetherRuntime). An earlier version
        // of this code alternated the source socket per outgoing packet on the theory that
        // WireGuard tolerates roaming (it identifies a peer by session key, not source IP).
        // That assumption is true for the CLIENT's outbound side but not for the resulting
        // downlink: a roaming-capable peer sends ALL return traffic to whichever source
        // address it most recently saw a valid packet from - a single "current endpoint",
        // not both at once. Alternating the source every packet made the server's notion of
        // "current endpoint" thrash on every packet, so the download direction (the bulk of
        // any real transfer) collapsed onto whichever path happened to win that race - in
        // practice mostly Wi-Fi - which is exactly the single-path-saturation symptom this
        // was meant to fix. Pinning one physical path per association removes the thrash;
        // weight is honored across associations/reconnects instead of within one, the same
        // flow-level granularity already used everywhere else in this file and in
        // TunPacketRouter.
        // The pinned egress for this association: a plain bound socket in Direct mode, or a
        // SOCKS5 UDP ASSOCIATE channel when a static-IP proxy is set - the relay logic below
        // is identical either way, which is the point of the EgressUdpChannel abstraction.
        var pinnedChannel: EgressUdpChannel? = null
        var pinnedNetwork: Network? = null
        var receiverJob: Job? = null
        // Set from other threads (network change / soft failure) to ask the loop below to
        // move this association to another physical path; consumed on the next packet.
        val repinRequested = java.util.concurrent.atomic.AtomicBoolean(false)
        var sendFailures = 0

        fun bindPinnedChannel(network: Network): EgressUdpChannel? = runCatching {
            VirtualEgress.openUdpChannel(vpnService, network, "path3-udp")
        }.getOrNull()

        fun startPinnedReceiver(channel: EgressUdpChannel, host: String, port: Int, clientAddr: InetSocketAddress): Job =
            scope.launch {
                val respBuf = ByteArray(64 * 1024)
                try {
                    while (isActive && channel.isOpen) {
                        val resp = channel.receive(respBuf, UDP_RECEIVE_TIMEOUT_MS) ?: continue
                        val encoded = encodeSocksUdp(host, port, resp.data.copyOf(resp.length))
                        runCatching { localUdp.send(DatagramPacket(encoded, encoded.size, clientAddr)) }
                        TrafficStats.recordBytes(resp.length)
                    }
                } catch (_: Exception) {
                }
            }

        // TUN ASSOCIATE: per strict SOCKS5 (RFC 1928), the UDP association should end when the
        // control TCP connection closes. In practice, Aether's own client appears to close the
        // control connection promptly after receiving the ASSOCIATE reply - reasonable when
        // scanning ~285 candidates in parallel, since holding that many TCP connections open
        // simultaneously just to keep each UDP association alive would be wasteful - and only
        // sends the actual UDP probe packet afterward. Tearing down localUdp immediately on
        // control-connection EOF would race against that and lose almost every time, which is
        // exactly what was observed: the ASSOCIATE handshake succeeding (Aether logs receiving
        // the port) but no UDP packet ever actually being decoded on our side. So this
        // deliberately does NOT close localUdp here - UDP_IDLE_TIMEOUT_MS below is the sole
        // cleanup mechanism, which is already generous enough (60s) relative to any single
        // probe's few-second window.
        val controlWatcher = scope.launch {
            try {
                val buf = ByteArray(1)
                while (client.getInputStream().read(buf) >= 0) { /* control channel stays open */ }
            } catch (_: Exception) {
            }
        }

        // Registered once the pinned path is known (first packet); a soft-failure on that
        // specific Network then tears this association down like any single-path relay,
        // letting the tunnel reconnect and get a fresh weighted pick on the surviving path.
        activeRelays[relayId] = ActiveRelay(
            network = null,
            repin = { repinRequested.set(true) },
        ) {
            runCatching { client.close() }
            runCatching { localUdp.close() }
            runCatching { aetherAssociation?.close() }
            runCatching { pinnedChannel?.close() }
            receiverJob?.cancel()
        }

        // Drops the current pin (socket + receiver) so the next packet picks a path again.
        // Safe to call only from the association loop below, which owns these variables.
        fun unpin(reason: String) {
            AppLogger.log(
                "Path3",
                "UDP ASSOCIATE unpin from ${path3Router.describeNetwork(pinnedNetwork)}: $reason",
            )
            receiverJob?.cancel()
            receiverJob = null
            runCatching { pinnedChannel?.close() }
            pinnedChannel = null
            pinnedNetwork = null
            sendFailures = 0
            activeRelays[relayId]?.network = null
        }

        val buffer = ByteArray(64 * 1024)
        var lastActivity = System.currentTimeMillis()
        localUdp.soTimeout = UDP_RECEIVE_TIMEOUT_MS
        try {
            while (System.currentTimeMillis() - lastActivity < UDP_IDLE_TIMEOUT_MS) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    localUdp.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                lastActivity = System.currentTimeMillis()
                AppLogger.log(
                    "Path3",
                    "UDP ASSOCIATE raw packet len=${packet.length} from=${packet.socketAddress} bytes=${packet.data.copyOfRange(0, minOf(packet.length, 16)).joinToString(",") { (it.toInt() and 0xFF).toString() }}",
                )
                val decoded = decodeSocksUdp(packet.data, packet.length)
                if (decoded == null) {
                    AppLogger.log("Path3", "UDP ASSOCIATE decode FAILED for that packet")
                    continue
                }
                val fromAddr = packet.socketAddress as? InetSocketAddress ?: continue

                if (aetherAssociation != null) {
                    runCatching { aetherAssociation.send(decoded.host, decoded.port, decoded.payload) }
                    val received = runCatching { aetherAssociation.receive(buffer) }.getOrNull()
                    if (received != null) {
                        val encoded = encodeSocksUdp(decoded.host, decoded.port, received.payload)
                        runCatching { localUdp.send(DatagramPacket(encoded, encoded.size, fromAddr)) }
                    }
                    continue
                }

                // The association outlives any single physical network: if the one it is
                // pinned to was lost, replaced or flagged failed, move it - the engine keeps
                // using this same local UDP port and cannot do it for us.
                if (pinnedChannel != null) {
                    if (repinRequested.getAndSet(false)) {
                        unpin("path changed or failed")
                    } else if (!path3Router.isCurrent(pinnedNetwork)) {
                        unpin("pinned network no longer current")
                    }
                } else {
                    repinRequested.set(false)
                }

                if (pinnedChannel == null) {
                    val network = pickRelayNetwork() ?: continue // both paths down - drop, same as before
                    AppLogger.log(
                        "Path3",
                        "UDP ASSOCIATE pin chosen=${path3Router.describeNetwork(network)} wifiWeight=$wifiWeight cellularWeight=$cellularWeight " +
                            "dest=${decoded.host}:${decoded.port} egress=${VirtualEgress.describe()}",
                    )
                    val channel = bindPinnedChannel(network) ?: continue
                    pinnedChannel = channel
                    pinnedNetwork = network
                    activeRelays[relayId]?.network = network
                    receiverJob = startPinnedReceiver(channel, decoded.host, decoded.port, fromAddr)
                }
                val channel = pinnedChannel ?: continue
                val sent = channel.send(decoded.host, decoded.port, decoded.payload, decoded.payload.size)
                if (sent) {
                    sendFailures = 0
                    TrafficStats.recordBytes(decoded.payload.size)
                } else if (++sendFailures >= UDP_SEND_FAILURES_TO_REPIN) {
                    // Every send failing means the pinned Network object is dead even if
                    // Android still reports it; do not keep feeding it.
                    unpin("$sendFailures consecutive send failures")
                }
            }
        } finally {
            controlWatcher.cancel()
            activeRelays.remove(relayId)
            runCatching { localUdp.close() }
            runCatching { aetherAssociation?.close() }
            runCatching { pinnedChannel?.close() }
            receiverJob?.cancel()
            closeQuietly(client)
        }
    }

    private data class DecodedUdp(val host: String, val port: Int, val payload: ByteArray)

    private fun decodeSocksUdp(data: ByteArray, length: Int): DecodedUdp? {
        if (length < 4) return null
        if (data[0].toInt() != 0 || data[1].toInt() != 0) return null
        val atyp = data[3].toInt() and 0xFF
        var offset = 4
        val host: String
        when (atyp) {
            0x01 -> {
                if (offset + 4 > length) return null
                host = InetAddress.getByAddress(data.copyOfRange(offset, offset + 4)).hostAddress
                offset += 4
            }
            0x03 -> {
                if (offset >= length) return null
                val len = data[offset].toInt() and 0xFF
                offset += 1
                if (offset + len > length) return null
                host = String(data, offset, len, Charsets.US_ASCII)
                offset += len
            }
            0x04 -> {
                if (offset + 16 > length) return null
                host = InetAddress.getByAddress(data.copyOfRange(offset, offset + 16)).hostAddress
                offset += 16
            }
            else -> return null
        }
        if (offset + 2 > length) return null
        val port = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2
        return DecodedUdp(host, port, data.copyOfRange(offset, length))
    }

    private fun encodeSocksUdp(host: String, port: Int, payload: ByteArray): ByteArray {
        val addr = InetAddress.getByName(host)
        val addrBytes = addr.address
        val out = java.io.ByteArrayOutputStream()
        out.write(0); out.write(0); out.write(0)
        out.write(if (addrBytes.size == 16) 0x04 else 0x01)
        out.write(addrBytes)
        out.write((port ushr 8) and 0xFF)
        out.write(port and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    // --- helpers -------------------------------------------------------------------------

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = try {
                input.read(buffer, read, buffer.size - read)
            } catch (_: Exception) {
                return false
            }
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun socksReply(rep: Int, boundAddr: InetAddress = InetAddress.getByName("0.0.0.0"), boundPort: Int = 0): ByteArray {
        val addrBytes = boundAddr.address
        val out = java.io.ByteArrayOutputStream()
        out.write(0x05); out.write(rep); out.write(0x00)
        out.write(if (addrBytes.size == 16) 0x04 else 0x01)
        out.write(addrBytes)
        out.write((boundPort ushr 8) and 0xFF)
        out.write(boundPort and 0xFF)
        return out.toByteArray()
    }

    private fun closeQuietly(socket: Socket) {
        runCatching { socket.close() }
    }
}
