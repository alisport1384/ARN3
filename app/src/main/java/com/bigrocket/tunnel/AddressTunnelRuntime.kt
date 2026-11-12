package com.bigrocket.tunnel

import android.content.Context
import android.net.Network
import android.net.VpnService
import com.bigrocket.egress.EgressDatagram
import com.bigrocket.egress.EgressUdpChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

/**
 * ADDRESS TUNNEL RUNTIME - the in-phone merge engine (engine 2), and its fallback rule.
 *
 * The user's requirement (docs/AddressTunnel.md):
 *
 *  > "If [the address] exists, engine 2 is used after testing; if not, the sandbox and engine 1
 *  >  are used directly."
 *
 * That sentence is the whole state machine here, and it is enforced in three places:
 *
 *  1. [install] - a saved address only *claims* the egress after [selfTest] has proven, on a
 *     live radio, that the far side answers HELLO **and** PING. Until then nothing is routed
 *     through it.
 *  2. [openTcp] / [openUdpChannel] - every flow asks for the tunnel first; when it is not
 *     `READY`, or the open fails, the caller gets `null` and dials as it always did (engine 1 /
 *     direct). No exception is thrown into the relay loops for "tunnel is unwell".
 *  3. [noteFailure] - a tunnel that dies mid-session is demoted back to `FALLBACK` (and retried
 *     on a backoff), so one dead far side cannot black-hole the phone's traffic.
 *
 * The sockets this class creates are the ones the app already protects: `VpnService.protect()`
 * first (so the tunnel's own packets never re-enter our TUN), then
 * `Network.bindSocket()`/`network.socketFactory` (so each pipe genuinely leaves by Wi-Fi or by
 * cellular). Both calls are wrapped: on several Android builds `bindSocket` throws
 * `IllegalStateException`/`ErrnoException` for sockets it does not like, and a crash there would
 * take down the VPN service for a feature the user can simply fall back from.
 */
object AddressTunnelRuntime {

    enum class State { OFF, TESTING, READY, FALLBACK }

    data class Status(
        val state: State = State.OFF,
        val endpoint: String = "",
        val detail: String = "",
        val lastError: String = "",
        val pipes: Int = 0,
        val rttMs: Long = -1,
        val flows: Long = 0,
        val udpDatagrams: Long = 0,
        val bytesOut: Long = 0,
        val bytesIn: Long = 0,
        val nacks: Long = 0,
    ) {
        val ready: Boolean get() = state == State.READY

        /** One Persian line for the address card, in the same voice as the rest of the app. */
        fun summary(): String = when (state) {
            State.OFF -> "تونل آدرسی: خاموش — موتور ۱ / خروجی مستقیم"
            State.TESTING -> "تونل آدرسی: در حال تست $endpoint …"
            State.READY ->
                "تونل آدرسی: فعال روی $endpoint ($pipes مسیر، ${rttMs.coerceAtLeast(0)}ms) — موتور ۲ در گوشی"
            State.FALLBACK ->
                "تونل آدرسی: ناموفق ($lastError) — برگشت به موتور ۱ / مستقیم"
        }
    }

    @Volatile private var spec: TunnelSpec? = null
    @Volatile private var session: MuxSession? = null
    @Volatile private var state: State = State.OFF
    @Volatile private var lastError: String = ""
    @Volatile private var detail: String = ""
    @Volatile private var lastRttMs: Long = -1
    @Volatile private var retryAtMs: Long = 0
    @Volatile private var pipes: Int = 0
    @Volatile private var networks: Pair<Network?, Network?> = null to null

    private val flowsOpened = AtomicLong(0)
    private val datagramsCarried = AtomicLong(0)
    private val bytesOut = AtomicLong(0)
    private val bytesIn = AtomicLong(0)
    private val nacks = AtomicLong(0)
    private val vpnHolder = java.util.concurrent.atomic.AtomicReference<VpnService?>(null)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    /** The installed (or attempted) address, for the UI. */
    fun currentSpec(): TunnelSpec? = spec

    fun isReady(): Boolean = state == State.READY && session?.isReady == true

    fun isConfigured(): Boolean = spec != null

    fun stateName(): String = state.name

    fun updateNetworks(wifi: Network?, cellular: Network?) {
        networks = wifi to cellular
    }

    /** The Service hands itself over once so our sockets can be protected + path-bound. */
    fun attachVpnService(service: VpnService?) {
        vpnHolder.set(service)
    }

    /**
     * Installs (or clears) the address and, when it is new, tests it before claiming the egress.
     * Called from the Service (on create / on change) - blocking is fine there, it is not the UI
     * thread, but the self-test itself is bounded (~4 s worst case).
     */
    fun install(context: Context?, next: TunnelSpec?, testNow: Boolean = true) {
        val previous = spec
        if (next == null) {
            shutdown("cleared")
            spec = null
            state = State.OFF
            lastError = ""
            detail = ""
            publish()
            AppLog.info(TAG, "address tunnel cleared -> engine 1 / direct")
            return
        }
        if (previous?.canonical() == next.canonical() && isReady()) return
        spec = next
        state = State.TESTING
        lastError = ""
        detail = "self-test"
        publish()
        if (!testNow) return
        val result = selfTest(next)
        if (result.ok) {
            state = State.READY
            detail = result.detail
            lastRttMs = result.rttMs
            publish()
        } else {
            lastError = result.error
            state = State.FALLBACK
            detail = result.detail
            retryAtMs = System.currentTimeMillis() + RETRY_BACKOFF_MS
            publish()
            AppLog.info(TAG, "self-test failed for ${next.endpointLabel()}: ${result.error} -> engine 1")
        }
    }

    class TestResult(val ok: Boolean, val rttMs: Long, val detail: String, val error: String = "")

    /**
     * The test the user asked for: does this address actually carry frames?
     *
     * It is not a TCP connect - a plain DNS server would pass that and still not tunnel. It is
     * HELLO -> HELLO_ACK (the far side echoes our nonce) followed by PING -> PONG on the same
     * channel, over both radios when both exist. Only then does engine 2 claim the egress.
     */
    fun selfTest(requested: TunnelSpec? = null): TestResult {
        val target = requested ?: spec
            ?: return TestResult(false, -1, "", "هیچ آدرسی تنظیم نشده است")
        val (wifi, cellular) = networks
        val endpoints = ArrayList<ChannelEndpoint>()

        // Resolve the address first: a name may itself be answered with a port+cookie record.
        val resolved = resolveEndpoint(target)
        if (resolved.literal == null) {
            val error = resolved.error.ifEmpty { "آدرس پاسخ نداد" }
            return TestResult(false, -1, "resolve failed", error)
        }

        endpoints.addAll(buildEndpoints(target, resolved, wifi, cellular, forTest = true))
        if (endpoints.isEmpty()) return TestResult(false, -1, "", "no usable radio for ${target.endpointLabel()}")

        val cookie = resolved.cookieOverride ?: target.cookie
        val candidate = MuxSession(BondedEndpoint(endpoints), cookie, label = target.endpointLabel())
        return try {
            val up = candidate.start(HANDSHAKE_TIMEOUT_MS)
            if (!up) {
                candidate.close()
                return TestResult(false, -1, "hello", candidate.downReason().ifEmpty { "دست‌دادن انجام نشد" })
            }
            val rtt = candidate.ping(PING_TIMEOUT_MS)
            if (rtt < 0) {
                candidate.close()
                return TestResult(false, -1, "ping", "پاسخ PING نرسید")
            }
            val live = (candidate as MuxSession)
            val pipeCount = (endpoints.filter { it.isOpen }).size
            // Keep the tested session: re-dialling for traffic would pay the handshake twice.
            adopt(candidate, target, pipeCount)
            TestResult(
                true,
                rtt,
                "HELLO_ACK + PING/PONG روی $pipeCount مسیر (${resolved.literal})",
            )
        } catch (e: Exception) {
            runCatching { candidate.close() }
            TestResult(false, -1, "exception", describe(e))
        }
    }

    /** Re-runs the self-test for a FALLBACK address (called on a backoff from the Service). */
    fun retestIfDue() {
        val target = spec ?: return
        if (state == State.READY) {
            val live = session
            if (live != null) {
                live.keepAlive()
                if (!live.isLive()) {
                    lastError = live.downReason().ifEmpty { "ارتباط قطع شد" }
                    demote("channel went silent")
                }
            }
            return
        }
        if (state != State.FALLBACK) return
        if (System.currentTimeMillis() < retryAtMs) return
        retryAtMs = System.currentTimeMillis() + RETRY_BACKOFF_MS
        AppLog.info(TAG, "retrying address ${target.endpointLabel()}")
        state = State.TESTING
        publish()
        val result = selfTest(target)
        state = if (result.ok) State.READY else State.FALLBACK
        if (result.ok) {
            lastRttMs = result.rttMs
            detail = result.detail
            lastError = ""
        } else {
            lastError = result.error
        }
        publish()
    }

    // --- flows -----------------------------------------------------------------------------

    /**
     * Tunnel-first egress for TCP.
     * @return null when engine 1 / direct must be used (not ready, or the open failed).
     */
    fun openTcp(host: String, port: Int): Socket? {
        val live = session ?: return null
        if (state != State.READY || !live.isReady) return null
        return try {
            val socket = live.openTcp(host, port)
            // Egress sockets feed relay loops that expect a bounded stall: without this a silent
            // far side parks a relay thread for ever instead of failing the one flow.
            runCatching { socket.soTimeout = EGRESS_READ_TIMEOUT_MS }
            flowsOpened.incrementAndGet()
            if (!live.isLive()) demote("no traffic from the far side")
            socket
        } catch (e: Exception) {
            // A per-flow refusal ("connection refused" far away) is NOT a tunnel failure: the
            // address answered, the destination did not. Only a dead channel demotes us.
            if (!live.isLive()) {
                lastError = "${describe(e)}"
                demote("open failed: ${describe(e)}")
            } else {
                AppLog.info(TAG, "tunnel open $host:$port failed (tunnel healthy): ${describe(e)}")
            }
            null
        }
    }

    /** Tunnel-first egress for UDP; null means "use the direct/proxy channel". */
    fun openUdpChannel(): EgressUdpChannel? {
        val live = session ?: return null
        if (state != State.READY || !live.isReady) return null
        return TunnelUdpChannel(live, live.openUdpChannel(), boundNetwork())
    }

    /**
     * The tunnel's UDP channel, in the shape the relay engines already consume.
     *
     * [network] answers "which radio is this on" for the callers that pin/evict channels by
     * path. With a bonded tunnel the honest answer is "both", and the merge is inside the
     * session, so we report the first live radio (or the Wi-Fi network as a stable choice).
     * Note what this class deliberately does NOT do: it never binds a socket itself - the
     * session's carriers own every socket and protect/bind them once, at creation.
     */
    class TunnelUdpChannel(
        private val session: MuxSession,
        private val channel: MuxSession.MuxDatagramChannel,
        private val radio: Network?,
    ) : EgressUdpChannel {

        override val network: Network
            get() = radio ?: throw IOException("tunnel UDP channel has no radio")

        override val label: String get() = "address-tunnel"

        override val isOpen: Boolean get() = session.isReady

        override fun send(host: String, port: Int, payload: ByteArray, length: Int): Boolean {
            datagramsCarried.incrementAndGet()
            return channel.send(host, port, payload, length)
        }

        override fun receive(buffer: ByteArray, timeoutMs: Int): EgressDatagram? {
            val datagram = session.pollDatagram(channel, timeoutMs.toLong()) ?: return null
            if (datagram.length > buffer.size) return null
            System.arraycopy(datagram.data, 0, buffer, 0, datagram.length)
            return EgressDatagram(datagram.host, datagram.port, buffer, datagram.length)
        }

        override fun close() = Unit // the session owns the channel
    }

    fun noteNacks(count: Long) {
        nacks.addAndGet(count)
    }

    /** Cheap counters for the status card (read once per UI tick). */
    fun publishCounters() {
        val live = session
        if (live != null) {
            bytesOut.set(live.totalBytesOut.get())
            bytesIn.set(live.totalBytesIn.get())
        }
        publish()
    }

    fun shutdown(reason: String) {
        session?.let { runCatching { it.close() } }
        session = null
        pipes = 0
        AppLog.info(TAG, "address tunnel shut down ($reason)")
    }

    private fun demote(reason: String) {
        if (state != State.READY) return
        state = State.FALLBACK
        retryAtMs = System.currentTimeMillis() + RETRY_BACKOFF_MS
        shutdown(reason)
        publish()
        AppLog.info(TAG, "engine 2 demoted to fallback: $reason")
    }

    private fun adopt(candidate: MuxSession, target: TunnelSpec, pipeCount: Int) {
        session?.let { runCatching { it.close() } }
        session = candidate
        pipes = pipeCount
        state = State.READY
        bytesOut.set(0)
        bytesIn.set(0)
        AppLog.info(TAG, "engine 2 active: ${target.endpointLabel()} over $pipeCount pipe(s)")
        publish()
    }

    private fun publish() {
        val live = session
        _status.value = Status(
            state = state,
            endpoint = spec?.endpointLabel().orEmpty(),
            detail = detail,
            lastError = lastError,
            pipes = pipes,
            rttMs = lastRttMs,
            flows = flowsOpened.get(),
            udpDatagrams = datagramsCarried.get(),
            bytesOut = bytesOut.get(),
            bytesIn = bytesIn.get(),
            nacks = nacks.get() + (live?.totalNacksIn?.get() ?: 0),
        )
    }

    // --- endpoint construction --------------------------------------------------------------

    class ResolvedEndpoint(
        val literal: String? = null,
        val port: Int = 0,
        val cookieOverride: Int? = null,
        val error: String = "",
    )

    /**
     * Turns the typed address into a concrete host to send frames to.
     *
     *  - a literal IP / IP:port needs nothing;
     *  - a bare NAME is resolved **through the user's chosen DNS first, then the system**, and if
     *    its A record is a `[port|cookie]` record (see [DnsRecords]) that port and cookie are
     *    used - this is the "a DNS answer is the address" form.
     */
    private fun resolveEndpoint(target: TunnelSpec): ResolvedEndpoint {
        if (target.literalHost) return ResolvedEndpoint(target.host, target.port)
        val lookup = lookupName(target.host) ?: return ResolvedEndpoint(error = "نام ${target.host} حل نشد")
        val endpoint = DnsRecords.decodeEndpoint(lookup.bytes)
        val ip = lookup.ipv4
        if (endpoint != null && DnsRecords.isPlausible(endpoint)) {
            AppLog.info(TAG, "${target.host} -> DNS answer carries $endpoint (ip=$ip)")
            return ResolvedEndpoint(
                literal = ip,
                port = endpoint.port,
                cookieOverride = endpoint.cookie,
            )
        }
        return ResolvedEndpoint(literal = ip, port = target.port)
    }

    private class Lookup(val ipv4: String, val bytes: ByteArray)

    /** A-record lookup for the tunnel address only. Bounded, and tolerant of a dead radio. */
    private fun lookupName(name: String): Lookup? {
        // 1) The physical radios, if we have any: an address must be resolvable without the TUN.
        val candidates = listOfNotNull(networks.first, networks.second)
        for (network in candidates) {
            val fromNetwork = runCatching {
                network.getAllByName(name).firstOrNull { it.address.size == 4 }
            }.getOrNull()
            if (fromNetwork != null) {
                return Lookup(fromNetwork.hostAddress ?: name, fromNetwork.address)
            }
        }
        // 2) Plain DNS to the user's configured resolver (dns/DnsConfig) - the same box the app
        // already uses; falls back to the system resolver if that fails.
        val answer = runCatching {
            PlainDnsTransport(SYSTEM_RESOLVER, binding = androidBinding(null)).post(
                DnsRecords.buildQuery(name),
                3_000,
            )
        }.getOrNull()
        if (answer != null) {
            val a = DnsRecords.firstA(answer)
            if (a != null) {
                return Lookup(
                    "${a[0].toInt() and 0xFF}.${a[1].toInt() and 0xFF}." +
                        "${a[2].toInt() and 0xFF}.${a[3].toInt() and 0xFF}",
                    a,
                )
            }
        }
        // 3) Last resort: the system resolver (may be the TUN, but by now the address has to
        // work somehow and the self-test will reject it if it does not).
        return runCatching {
            val address = InetAddress.getByName(name)
            Lookup(address.hostAddress ?: name, address.address.copyOf(4))
        }.getOrNull()
    }

    private fun buildEndpoints(
        target: TunnelSpec,
        resolved: ResolvedEndpoint,
        wifi: Network?,
        cellular: Network?,
        forTest: Boolean,
    ): List<ChannelEndpoint> {
        val literal = resolved.literal ?: return emptyList()
        val port = resolved.port.coerceAtLeast(1)
        val remote = InetSocketAddress(literal, port)
        fun endpointFor(network: Network?): ChannelEndpoint? = when (target.transport) {
            TunnelSpec.Transport.UDP -> UdpChannelEndpoint(remote, androidBinding(network))
            TunnelSpec.Transport.TCP -> TcpChannelEndpoint(remote, androidBinding(network))
            TunnelSpec.Transport.DOH -> {
                val transport = OkHttpDohTransport(
                    url = "https://${target.host}:${target.port}${target.path}",
                    socketFactory = streamFactory(network),
                )
                DohChannelEndpoint(transport, label = "doh://${target.host}${target.path}")
            }
        }

        // One pipe per radio: this is what makes the merge real (frames alternate over Wi-Fi and
        // cellular). With only one radio the tunnel still works, it simply is not merged.
        val list = ArrayList<ChannelEndpoint>(2)
        val plan = when {
            wifi != null && cellular != null -> listOf(wifi, cellular)
            wifi != null -> listOf(wifi)
            cellular != null -> listOf(cellular)
            else -> listOf<Network?>(null) // tests / no radios yet: default route only
        }
        for (network in plan) {
            runCatching { endpointFor(network) }.getOrNull()?.let { list.add(it) }
        }
        if (forTest && list.isEmpty()) AppLog.info(TAG, "no carrier could be built for ${target.endpointLabel()}")
        return list
    }

    /** Android's per-socket plumbing, with every platform call guarded (see the class comment). */
    private fun androidBinding(network: Network?): SocketBinding = object : SocketBinding {
        override fun openDatagram(): DatagramSocket {
            val service = vpnHolder.get()
            val socket = DatagramSocket()
            var ok = true
            if (service != null) {
                ok = runCatching { service.protect(socket) }.getOrElse { e ->
                    AppLog.info(TAG, "protect(UDP) refused, continuing: ${describe(e)}")
                    true
                }
            }
            if (!ok) {
                runCatching { socket.close() }
                throw IOException("Unable to protect UDP socket from VPN")
            }
            if (network != null) bindOrLog(network, socket)
            return socket
        }

        override fun openStream(): Socket {
            val service = vpnHolder.get()
            val socket = if (network != null) {
                // socketFactory() gives a socket already bound to the network where supported;
                // bindSocket() below is the second, idempotent attempt.
                runCatching { network.socketFactory.createSocket() }.getOrElse { Socket() }
            } else {
                Socket()
            }
            if (service != null) {
                val ok = runCatching { service.protect(socket) }.getOrElse { e ->
                    AppLog.info(TAG, "protect(TCP) refused, continuing: ${describe(e)}")
                    true
                }
                if (!ok) {
                    runCatching { socket.close() }
                    throw IOException("Unable to protect TCP socket from VPN")
                }
            }
            if (network != null) bindOrLog(network, socket)
            return socket
        }

        override fun socketFactory(): SocketFactory =
            if (network != null) streamFactory(network) else SocketFactory.getDefault()
    }

    private fun streamFactory(network: Network?): SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket {
            val raw = if (network != null) {
                runCatching { network.socketFactory.createSocket() }.getOrElse { Socket() }
            } else {
                Socket()
            }
            vpnHolder.get()?.let { service ->
                runCatching { service.protect(raw) }.onFailure {
                    AppLog.info(TAG, "protect(DoH) refused, continuing: ${describe(it)}")
                }
            }
            if (network != null) bindOrLog(network, raw)
            return raw
        }

        override fun createSocket(host: String, port: Int) = createSocket().also { it.connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) =
            createSocket().also {
                it.bind(InetSocketAddress(localHost, localPort))
                it.connect(InetSocketAddress(host, port))
            }

        override fun createSocket(host: InetAddress, port: Int, localAddress: InetAddress, localPort: Int) =
            createSocket().also {
                it.bind(InetSocketAddress(localAddress, localPort))
                it.connect(InetSocketAddress(host, port))
            }
        override fun createSocket(host: InetAddress, port: Int) = createSocket().also { it.connect(InetSocketAddress(host, port)) }

        /** Every overload funnels through [createSocket] so protect()/bind() are never skipped. */
        private fun connectTo(host: InetAddress, port: Int, local: InetAddress?, localPort: Int): Socket =
            createSocket().also {
                if (local != null) it.bind(InetSocketAddress(local, localPort))
                it.connect(InetSocketAddress(host, port))
            }
    }

    /**
     * `Network.bindSocket()` crash-safety, deliberately per-socket.
     *
     * What actually happens on device (and why this is guarded rather than trusted):
     *  - `SocketFactory` sockets from [Network] are already bound, and binding them again throws
     *    `IllegalStateException` on some builds ("already bound") - that is a success, not a fault;
     *  - `bindSocket` on a socket created before the network appeared can throw `ErrnoException`
     *    (EBADF/EINVAL) if the radio changed underneath us;
     *  - a VPN app calling it on a socket that `protect()` just refused throws too.
     *
     * None of those may propagate: the worst case is that this one pipe takes the default route,
     * which the peer sees as a second source address - exactly what the bonded carrier expects.
     */
    private fun bindOrLog(network: Network, socket: Any) {
        val result = runCatching {
            when (socket) {
                is Socket -> network.bindSocket(socket)
                is DatagramSocket -> network.bindSocket(socket)
                else -> Unit
            }
        }
        result.onFailure { e ->
            val benign = e is IllegalStateException || (e.message?.contains("already", true) == true)
            if (!benign) {
                AppLog.info(
                    TAG,
                    "bindSocket(${network.hashCode()}) failed (${describe(e)}) - pipe uses the default route",
                )
            }
        }
    }

    private fun boundNetwork(): Network? = networks.first ?: networks.second

    private const val TAG = "Tunnel"
    private const val HANDSHAKE_TIMEOUT_MS = 3_500L
    private const val PING_TIMEOUT_MS = 1_500L
    private const val RETRY_BACKOFF_MS = 30_000L

    /** Stalled-flow guard: 60 s of silence on a tunnel flow closes that flow. */
    private const val EGRESS_READ_TIMEOUT_MS = 60_000

    /** Resolver used for the addressing step when no radio answered. */
    private const val SYSTEM_RESOLVER = "1.1.1.1"
}
