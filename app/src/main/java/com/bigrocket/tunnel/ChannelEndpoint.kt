package com.bigrocket.tunnel

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/**
 * CARRIERS - the "just an address" part of the address tunnel.
 *
 * A carrier is whatever can move whole BRT1 frames to the address the user typed. There is
 * deliberately no proxy protocol here: the address is *only* where the channel goes.
 *
 * * [UdpChannelEndpoint]  - frames as UDP datagrams (`docs/AddressTunnel.md`: recommended; the
 *   two radios can interleave frames because datagrams are independent).
 * * [TcpChannelEndpoint]  - frames as length-prefixed records on one TCP connection per radio.
 * * [DohChannelEndpoint]  - frames inside RFC 8484 `application/dns-message` POST bodies, so the
 *   channel looks like DNS traffic to a middlebox; the far side queues replies for the next POST.
 * * [BondedEndpoint]      - several endpoints (one per radio) presented as ONE channel: sends are
 *   spread by [PathBond] (engine 2's allocator), arrivals are passed through as they come, and
 *   the stream-level [Reorderer] in the mux puts everything back in order. **This is where the
 *   merge physically happens** - it is just no longer on a server.
 *
 * Everything in this file is Android-free so the JVM tests exercise the real code. Android's
 * `protect()` / `Network.bindSocket()` are supplied through [SocketBinding], implemented in
 * `AddressTunnelRuntime.kt` where the `VpnService` and the `Network`s actually live.
 */
interface SocketBinding {
    /** A datagram socket already protected from the VPN and bound to the intended radio. */
    @Throws(IOException::class)
    fun openDatagram(): DatagramSocket

    /** A stream socket already protected from the VPN and bound to the intended radio. */
    @Throws(IOException::class)
    fun openStream(): Socket

    /** For the DoH carrier, which needs a socket factory rather than a single socket. */
    fun socketFactory(): SocketFactory
}

/** Carrier that needs nothing from Android (used by tests and by the desktop e2e client). */
object PlainBinding : SocketBinding {
    override fun openDatagram(): DatagramSocket = DatagramSocket()
    override fun openStream(): Socket = Socket()
    override fun socketFactory(): SocketFactory = SocketFactory.getDefault()
}

/** A channel that can carry BRT1 frames. Several of these together are one bonded channel. */
interface ChannelEndpoint {
    /** Short text for logs and the status card, e.g. `udp://1.2.3.4:5300`. */
    val label: String

    /** Start receiving. Frames are delivered on a carrier thread. */
    fun start(sink: FrameSink): Boolean

    fun send(frame: ByteArray): Boolean

    val isOpen: Boolean

    fun close()
}

/** What a carrier hands each received frame to (raw buffer included, so the cookie can be read). */
fun interface FrameSink {
    fun onFrame(raw: ByteArray, length: Int, frame: Brt.Frame)
}

// ---------------------------------------------------------------------------------------------
// UDP: the primary carrier. One socket per radio; datagrams are independent, so the merge is
// simply "alternate the sends and reassemble at the other end".
// ---------------------------------------------------------------------------------------------
class UdpChannelEndpoint(
    private val remote: InetSocketAddress,
    private val binding: SocketBinding = PlainBinding,
    private val socketBufferBytes: Int = 1 shl 20,
) : ChannelEndpoint {

    override val label: String get() = "udp://${remote.hostString}:${remote.port}"

    private val running = AtomicBoolean(false)
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var sink: FrameSink? = null
    @Volatile private var lastInboundAt = 0L

    override val isOpen: Boolean get() = running.get() && socket?.isClosed == false

    /** When the last frame arrived; used for liveness and for switching radios. */
    fun lastInboundMs(): Long = lastInboundAt

    override fun start(sink: FrameSink): Boolean {
        if (running.get()) return true
        this.sink = sink
        val sock = try {
            binding.openDatagram().also {
                it.soTimeout = RX_POLL_MS
                runCatching { it.sendBufferSize = socketBufferBytes }
                runCatching { it.receiveBufferSize = socketBufferBytes }
                // Connected UDP: the kernel drops datagrams from anyone else, and ICMP
                // "port unreachable" is reported on this socket instead of a random one.
                it.connect(remote)
            }
        } catch (e: Exception) {
            AppLog.warn("carrier", "UDP socket for $label could not be created: ${describe(e)}")
            return false
        }
        socket = sock
        running.set(true)
        Thread({ receiveLoop(sock) }, "brt-udp-rx-${remote.port}").apply { isDaemon = true }.start()
        AppLog.info("carrier", "UDP carrier up: $label")
        return true
    }

    private fun receiveLoop(sock: DatagramSocket) {
        val buffer = ByteArray(64 * 1024)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            val n = try {
                sock.receive(packet)
                packet.length
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (running.get()) {
                    // ICMP port-unreachable surfaces here on a connected socket; it must not
                    // kill the carrier - the far side may simply have no listener for a moment.
                    AppLog.warn("carrier", "UDP receive on $label: ${describe(e)}")
                    sleepQuietly(20)
                    continue
                }
                break
            }
            if (n <= 0) continue
            lastInboundAt = System.currentTimeMillis()
            val frame = Brt.decode(buffer, n) ?: continue
            sink?.onFrame(buffer.copyOf(n), n, frame)
        }
        AppLog.info("carrier", "UDP carrier stopped: $label")
    }

    override fun send(frame: ByteArray): Boolean {
        val sock = socket ?: return false
        return try {
            sock.send(DatagramPacket(frame, frame.size, remote))
            true
        } catch (e: Exception) {
            // A single failed datagram is normal on a flapping radio; the stream layer recovers.
            false
        }
    }

    override fun close() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
    }

    private companion object {
        const val RX_POLL_MS = 500
    }
}

// ---------------------------------------------------------------------------------------------
// TCP: one ordered stream per radio. Same frame bytes, 2-byte length prefix.
// ---------------------------------------------------------------------------------------------
class TcpChannelEndpoint(
    private val remote: InetSocketAddress,
    private val binding: SocketBinding = PlainBinding,
    private val connectTimeoutMs: Int = 8_000,
) : ChannelEndpoint {

    override val label: String get() = "tcp://${remote.hostString}:${remote.port}"

    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var sink: FrameSink? = null
    @Volatile private var lastInboundAt = 0L

    override val isOpen: Boolean get() = running.get() && socket?.isClosed == false

    fun lastInboundMs(): Long = lastInboundAt

    override fun start(sink: FrameSink): Boolean {
        if (running.get()) return true
        this.sink = sink
        val sock = try {
            binding.openStream().also {
                it.tcpNoDelay = true
                it.soTimeout = connectTimeoutMs
                it.connect(remote, connectTimeoutMs)
                it.soTimeout = 0
            }
        } catch (e: Exception) {
            AppLog.warn("carrier", "TCP carrier to $label failed: ${describe(e)}")
            return false
        }
        socket = sock
        output = sock.getOutputStream()
        running.set(true)
        Thread({ receiveLoop(sock.getInputStream()) }, "brt-tcp-rx-${remote.port}").apply { isDaemon = true }.start()
        AppLog.info("carrier", "TCP carrier up: $label")
        return true
    }

    private fun receiveLoop(input: InputStream) {
        val din = DataInputStream(input)
        while (running.get()) {
            val length = try {
                din.readUnsignedShort()
            } catch (_: Exception) {
                break
            }
            if (length < Brt.HEADER || length > Brt.HEADER + Brt.MAX_PAYLOAD) break
            val buffer = ByteArray(length)
            try {
                din.readFully(buffer)
            } catch (_: Exception) {
                break
            }
            lastInboundAt = System.currentTimeMillis()
            val frame = Brt.decode(buffer, length) ?: continue
            sink?.onFrame(buffer, length, frame)
        }
        AppLog.info("carrier", "TCP carrier stopped: $label")
        running.set(false)
        runCatching { socket?.close() }
    }

    override fun send(frame: ByteArray): Boolean {
        val out = output ?: return false
        return try {
            synchronized(writeLock) {
                out.write((frame.size ushr 8) and 0xFF)
                out.write(frame.size and 0xFF)
                out.write(frame)
                out.flush()
            }
            true
        } catch (e: Exception) {
            running.set(false)
            runCatching { socket?.close() }
            false
        }
    }

    override fun close() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        output = null
    }
}

// ---------------------------------------------------------------------------------------------
// DoH: frames inside `application/dns-message` bodies (RFC 8484). Behind a TLS terminator this
// is indistinguishable from ordinary DNS-over-HTTPS to anything watching the wire.
// ---------------------------------------------------------------------------------------------
interface DohTransport {
    /** POST [body] to the endpoint and return the raw response body. */
    @Throws(IOException::class)
    fun post(body: ByteArray, timeoutMs: Int): ByteArray
}

class DohChannelEndpoint(
    private val transport: DohTransport,
    override val label: String,
    private val pollIntervalMs: Long = 40,
    private val requestTimeoutMs: Int = 8_000,
) : ChannelEndpoint {

    private val running = AtomicBoolean(false)
    @Volatile private var sink: FrameSink? = null
    private val postLock = Any() // one request in flight: DoH is request/response, not a stream
    @Volatile private var lastInboundAt = 0L

    /** How many frames came back from the far side in the last response (diagnostics). */
    private val responses = AtomicInteger(0)

    override val isOpen: Boolean get() = running.get()

    fun lastInboundMs(): Long = lastInboundAt

    override fun start(sink: FrameSink): Boolean {
        if (running.get()) return true
        this.sink = sink
        running.set(true)
        Thread({ pollLoop() }, "brt-doh-poll").apply { isDaemon = true }.start()
        AppLog.info("carrier", "DoH carrier up: $label")
        return true
    }

    /**
     * A POST carries ONE raw frame as the `application/dns-message` body (that is what a DNS
     * server expects: one message per request), while the *response* body is a batch of
     * `[len:2][frame]...` - so one request frame and several reply frames still fit in a single
     * round trip. An empty body is a pure poll ("give me whatever you have queued").
     */
    override fun send(frame: ByteArray): Boolean {
        if (!running.get()) return false
        return exchange(frame)
    }

    /** A POST is both "here is a frame" and "give me yours", so polling with an empty body drains. */
    private fun exchange(body: ByteArray): Boolean = try {
        val raw = synchronized(postLock) { transport.post(body, requestTimeoutMs) }
        deliver(raw)
        true
    } catch (e: Exception) {
        AppLog.warn("carrier", "DoH exchange failed: ${describe(e)}")
        false
    }

    private fun deliver(raw: ByteArray) {
        var offset = 0
        var received = 0
        while (offset + 2 <= raw.size) {
            val length = ((raw[offset].toInt() and 0xFF) shl 8) or (raw[offset + 1].toInt() and 0xFF)
            offset += 2
            if (length < Brt.HEADER || offset + length > raw.size) break
            // Brt.decode takes (buffer, length-of-frame-starting-at-0). Passing an absolute end
            // offset made every non-first frame in a batch decode garbage, which silently broke
            // the DoH carrier: exactly one frame per response survived.
            val start = offset
            val frame = Brt.decode(raw.copyOfRange(start, start + length), length)
            offset += length
            if (frame == null) {
                AppLog.warn("carrier", "DoH response frame at $start did not decode (len=$length)")
                continue
            }
            lastInboundAt = System.currentTimeMillis()
            sink?.onFrame(raw.copyOfRange(start, start + length), length, frame)
            received++
        }
        if (received > 0) responses.addAndGet(received)
    }

    /** Drains anything the far side has queued, and keeps the session warm. */
    private fun pollLoop() {
        while (running.get()) {
            exchange(ByteArray(0))
            sleepQuietly(pollIntervalMs)
        }
        AppLog.info("carrier", "DoH carrier stopped: $label")
    }

    override fun close() {
        running.set(false)
    }
}

// ---------------------------------------------------------------------------------------------
// Bonded: one channel, several radios. This is engine 2's merge, in the phone.
// ---------------------------------------------------------------------------------------------
class BondedEndpoint(
    private val endpoints: List<ChannelEndpoint>,
    private val allocator: (() -> Int)? = null,
) : ChannelEndpoint {

    override val label: String get() = endpoints.joinToString(" + ") { it.label }

    private val running = AtomicBoolean(false)
    private val counter = AtomicInteger(0)

    override val isOpen: Boolean get() = running.get() && endpoints.any { it.isOpen }

    /** Pipes that are actually up (1 while only one radio has a route). */
    fun liveCount(): Int = endpoints.count { it.isOpen }

    override fun start(sink: FrameSink): Boolean {
        var ok = false
        // One shared sink: whichever radio a frame arrives on, it lands in the same mux.
        for (endpoint in endpoints) {
            if (endpoint.start(sink)) ok = true
        }
        running.set(ok)
        AppLog.info("carrier", "bonded carrier: ${liveCount()}/${endpoints.size} pipe(s) up ($label)")
        return ok
    }

    /**
     * Spreads frames over the live pipes - the same error-diffusion idea the app's
     * `PathBond`/`PathBondAllocator` use: strictly alternating at equal weights, ratio-following
     * when the caller supplies weights through [allocator].
     */
    override fun send(frame: ByteArray): Boolean {
        val live = endpoints.filter { it.isOpen }
        if (live.isEmpty()) return false
        val pick = when {
            live.size == 1 -> live[0]
            allocator != null -> live[Math.floorMod(allocator(), live.size)]
            else -> live[Math.floorMod(counter.getAndIncrement(), live.size)]
        }
        if (pick.send(frame)) return true
        // A dead radio must not swallow frames: try the others before reporting failure.
        for (other in live) if (other !== pick && other.send(frame)) return true
        return false
    }

    override fun close() {
        running.set(false)
        endpoints.forEach { runCatching { it.close() } }
    }
}

// ---------------------------------------------------------------------------------------------
// Small helpers shared by the carriers (kept here so the mux stays focused).
// ---------------------------------------------------------------------------------------------
internal fun describe(e: Throwable): String = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"

internal fun sleepQuietly(ms: Long) {
    try {
        Thread.sleep(ms)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}
