package bridgesrc


/*
 * BridgeServer - the far side of BigRocket's "address as a tunnel" merge engine.
 *
 * THE REQUIREMENT THIS ANSWERS (docs/AddressTunnel.md):
 *   "Imagine a server: data from path 1 and path 2 goes there bit by bit, gets merged, sent
 *    and received - merge engine 2 must do that, but virtually and inside the phone. I want to
 *    mount a plain IP or even a plain DNS in place of the static IP."
 *
 * The phone side (engine 2, in-app) opens ONE channel to ONE address - an IP:port, an A-record
 * answer, or a DoH endpoint - and multiplexes every TCP/UDP flow into it. This file is the
 * matching peer: it receives those frames (over both radios, interleaved), puts them back in
 * order, opens ordinary sockets on the internet, and sends everything back split over the same
 * two radios so the *return* path is merged too.
 *
 * This is NOT a proxy protocol: there is no SOCKS handshake, no CONNECT prelude and no per-flow
 * round trip on the phone. It is a relay - one connection to the relay carries all flows.
 *
 * ---------------------------------------------------------------------------------------------
 * RUN IT ON YOUR SERVER (any Linux box with a JDK 17+; no Android, no project build):
 *
 *   ./run-bridge.sh                       # downloads the Kotlin compiler once, then runs
 *                                         # UDP 5300 + TCP 5301 with a random cookie
 *   ./run-bridge.sh --udp 5300 --any-cookie
 *   ./run-bridge.sh --help
 *
 * Or by hand, if Kotlin is already installed:
 *
 *   kotlinc BridgeServer.kt -include-runtime -d bridge.jar
 *   java -jar bridge.jar --udp 5300 --tcp 8443
 *
 * Then open the port(s) in the firewall (e.g. ufw allow 5300/udp) and, in the app's
 * "آدرس به‌عنوان تونل" card, put one of:
 *
 *   <server-ip>:5300                      UDP carrier (recommended: the two radios interleave frames)
 *   tcp://<server-ip>:8443                TCP carrier (one ordered stream per radio)
 *   https://<host>/dns-query              DoH carrier, behind your TLS terminator
 *
 * The app SELF-TESTS whatever you type and only switches to this engine if the bridge answers;
 * otherwise it keeps using engine 1 / direct, so a typo can never break your connectivity.
 *
 * The cookie is the 16-bit session tag both sides must agree on (the app derives it from the
 * address you typed). --any-cookie accepts any cookie: fine on a private test box only.
 *
 * NOTE on the DoH carrier: the app can carry frames in RFC 8484 `application/dns-message`
 * bodies. Put a TLS terminator (nginx/caddy with a real certificate) in front and proxy
 * POST /dns-query to this server's `--doh-port`. A plain public DNS server (1.1.1.1, 8.8.8.8)
 * will simply answer DNS and never tunnel - which is exactly why the app self-tests the address
 * and falls back to engine 1 when the far side is not this bridge.
 * ---------------------------------------------------------------------------------------------
 */

import java.io.DataInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// ---------------------------------------------------------------------------------------------
// Protocol (identical wire format to the app's com.bigrocket.tunnel.Brt - kept in this one file
// so the bridge is a single self-contained source file; a unit test cross-decodes both).
// ---------------------------------------------------------------------------------------------
object BrtWire {
    const val MAGIC = 0x42525431 // "BRT1"
    const val HEADER = 12
    const val MAX_PAYLOAD = 1400

    const val PING = 1
    const val PONG = 2
    const val HELLO = 3
    const val HELLO_ACK = 4
    const val OPEN = 5
    const val OPEN_OK = 6
    const val OPEN_ERR = 7
    const val DATA = 8
    const val FIN = 9
    const val RST = 10
    const val WINDOW = 11
    const val DGRAM = 12
    const val DGRAM_RX = 13
    const val NACK = 14

    class Frame(val type: Int, val sequence: Int, val payload: ByteArray) {
        override fun toString() = "type=$type seq=$sequence len=${payload.size}"
    }

    fun encode(type: Int, sequence: Int, cookie: Int, payload: ByteArray, length: Int = payload.size): ByteArray {
        val out = ByteArray(HEADER + length)
        out[0] = 'B'.code.toByte(); out[1] = 'R'.code.toByte()
        out[2] = 'T'.code.toByte(); out[3] = '1'.code.toByte()
        out[4] = ((cookie ushr 8) and 0xFF).toByte()
        out[5] = (cookie and 0xFF).toByte()
        out[6] = type.toByte()
        out[7] = 0
        out[8] = ((sequence ushr 24) and 0xFF).toByte()
        out[9] = ((sequence ushr 16) and 0xFF).toByte()
        out[10] = ((sequence ushr 8) and 0xFF).toByte()
        out[11] = (sequence and 0xFF).toByte()
        System.arraycopy(payload, 0, out, HEADER, length)
        return out
    }

    fun decode(data: ByteArray, length: Int = data.size): Frame? {
        if (length < HEADER) return null
        if (data[0] != 'B'.code.toByte() || data[1] != 'R'.code.toByte() ||
            data[2] != 'T'.code.toByte() || data[3] != '1'.code.toByte()
        ) return null
        val type = data[6].toInt() and 0xFF
        if (type == 0) return null
        val seq = ((data[8].toInt() and 0xFF) shl 24) or ((data[9].toInt() and 0xFF) shl 16) or
            ((data[10].toInt() and 0xFF) shl 8) or (data[11].toInt() and 0xFF)
        return Frame(type, seq, data.copyOfRange(HEADER, length))
    }

    fun streamId(payload: ByteArray): Int =
        if (payload.size < 2) -1 else ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)

    fun withStreamId(id: Int, body: ByteArray): ByteArray {
        val out = ByteArray(2 + body.size)
        out[0] = ((id ushr 8) and 0xFF).toByte()
        out[1] = (id and 0xFF).toByte()
        System.arraycopy(body, 0, out, 2, body.size)
        return out
    }

    fun intBytes(v: Int) = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    fun readInt(d: ByteArray, off: Int): Int =
        if (off + 4 > d.size) 0
        else ((d[off].toInt() and 0xFF) shl 24) or ((d[off + 1].toInt() and 0xFF) shl 16) or
            ((d[off + 2].toInt() and 0xFF) shl 8) or (d[off + 3].toInt() and 0xFF)

    class Address(val host: String, val port: Int)

    fun decodeAddress(d: ByteArray, off: Int = 0): Address? {
        if (off >= d.size) return null
        var pos = off
        val atyp = d[pos].toInt() and 0xFF
        pos++
        val host: String
        when (atyp) {
            0x01 -> {
                if (pos + 4 > d.size) return null
                host = "${d[pos].toInt() and 0xFF}.${d[pos + 1].toInt() and 0xFF}." +
                    "${d[pos + 2].toInt() and 0xFF}.${d[pos + 3].toInt() and 0xFF}"
                pos += 4
            }
            0x03 -> {
                if (pos >= d.size) return null
                val len = d[pos].toInt() and 0xFF
                pos++
                if (pos + len > d.size) return null
                host = String(d, pos, len, Charsets.UTF_8)
                pos += len
            }
            0x04 -> {
                if (pos + 16 > d.size) return null
                val sb = StringBuilder()
                for (i in 0 until 16 step 2) {
                    if (i > 0) sb.append(':')
                    sb.append(Integer.toHexString(((d[pos + i].toInt() and 0xFF) shl 8) or (d[pos + i + 1].toInt() and 0xFF)))
                }
                host = sb.toString()
                pos += 16
            }
            else -> return null
        }
        if (pos + 2 > d.size) return null
        val port = ((d[pos].toInt() and 0xFF) shl 8) or (d[pos + 1].toInt() and 0xFF)
        return Address(host, port)
    }

    fun encodeAddress(host: String, port: Int): ByteArray {
        val ipv4 = parseIpv4(host)
        if (ipv4 != null) {
            val out = ByteArray(7)
            out[0] = 0x01
            System.arraycopy(ipv4, 0, out, 1, 4)
            out[5] = ((port ushr 8) and 0xFF).toByte()
            out[6] = (port and 0xFF).toByte()
            return out
        }
        val bytes = host.toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + 1 + bytes.size + 2)
        out[0] = 0x03
        out[1] = bytes.size.toByte()
        System.arraycopy(bytes, 0, out, 2, bytes.size)
        out[2 + bytes.size] = ((port ushr 8) and 0xFF).toByte()
        out[3 + bytes.size] = (port and 0xFF).toByte()
        return out
    }

    fun addressLength(d: ByteArray, off: Int): Int {
        if (off >= d.size) return 0
        return when (d[off].toInt() and 0xFF) {
            0x01 -> 7
            0x03 -> if (off + 1 >= d.size) 0 else 2 + (d[off + 1].toInt() and 0xFF) + 2
            0x04 -> 19
            else -> 0
        }
    }

    fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in parts.indices) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v.toByte()
        }
        return out
    }

    fun checksum(bytes: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i + 1 < bytes.size) {
            sum = (sum + (((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF))) and 0xFFFF
            i += 2
        }
        if (i < bytes.size) sum = (sum + ((bytes[i].toInt() and 0xFF) shl 8)) and 0xFFFF
        return sum
    }
}

// ---------------------------------------------------------------------------------------------
// Transport pipes: every pipe can send whole BRT1 frames. Several pipes = the return path is
// split over several client addresses/connections, i.e. merged in the other direction as well.
// ---------------------------------------------------------------------------------------------
interface Pipe {
    val label: String
    fun send(frame: ByteArray): Boolean
    fun close()
}

class UdpPipe(private val socket: DatagramSocket, @Volatile var peer: InetSocketAddress) : Pipe {
    /** Last time a frame actually arrived from this peer - stale peers are swept away. */
    @Volatile var lastSeenMs: Long = System.currentTimeMillis()

    /** Session tag the client announced in its HELLO (null until the first HELLO arrives). */
    @Volatile var session: String? = null

    override val label: String get() = "udp:${peer}"
    override fun send(frame: ByteArray): Boolean = try {
        socket.send(DatagramPacket(frame, frame.size, peer))
        true
    } catch (e: Exception) {
        false
    }

    override fun close() = Unit
}

class TcpPipe(private val socket: Socket) : Pipe {
    private val out: OutputStream = socket.getOutputStream()
    private val lock = Any()
    override val label: String get() = "tcp:${socket.remoteSocketAddress}"
    override fun send(frame: ByteArray): Boolean = try {
        synchronized(lock) {
            out.write((frame.size ushr 8) and 0xFF)
            out.write(frame.size and 0xFF)
            out.write(frame)
            out.flush()
        }
        true
    } catch (e: Exception) {
        false
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}

/** Round-robin over every live pipe: this is the server-side half of the merge. */
class Router(private val log: (String) -> Unit) {
    private val pipes = ArrayList<Pipe>()
    private val counter = AtomicInteger(0)
    private val sequence = AtomicInteger(0)

    @Volatile var cookie: Int = 0

    @Synchronized
    fun add(pipe: Pipe) {
        if (pipes.none { it === pipe }) {
            pipes.add(pipe)
            log("pipe added: ${pipe.label} (now ${pipes.size})")
        }
    }

    @Synchronized
    fun remove(pipe: Pipe) {
        pipes.removeAll { it === pipe }
        log("pipe removed: ${pipe.label} (now ${pipes.size})")
    }

    @Synchronized
    fun pipeCount(): Int = pipes.size

    fun send(type: Int, payload: ByteArray, length: Int = payload.size): Boolean {
        val snapshot = synchronized(this) { pipes.toList() }
        if (snapshot.isEmpty()) return false
        val frame = BrtWire.encode(type, sequence.getAndIncrement(), cookie, payload, length)
        val start = Math.floorMod(counter.getAndIncrement(), snapshot.size)
        for (attempt in snapshot.indices) {
            val pipe = snapshot[(start + attempt) % snapshot.size]
            if (pipe.send(frame)) return true
        }
        return false
    }

    /**
     * Answers a frame on the pipe it arrived on.
     *
     * Only stream-scoped frames may be round-robined ([send]). A PING/PONG or HELLO_ACK sent
     * over *a different* pipe than the one that asked looks to a strict client like a lost
     * handshake: the phone's keep-alive would go unanswered on the pipe that sent it, and a
     * fresh session would retry HELLO on the other radio while its ACK trickled in on the first.
     */
    fun replyOn(pipe: Pipe?, type: Int, payload: ByteArray): Boolean {
        val direct = pipe ?: return send(type, payload)
        val frame = BrtWire.encode(type, sequence.getAndIncrement(), cookie, payload)
        return direct.send(frame) || send(type, payload)
    }

    /** Send to one specific pipe (used for replies that must come back on that pipe first). */
    fun sendOn(pipe: Pipe?, type: Int, payload: ByteArray): Boolean {
        val direct = pipe ?: return send(type, payload)
        val frame = BrtWire.encode(type, sequence.getAndIncrement(), cookie, payload)
        return direct.send(frame) || send(type, payload)
    }
}

// ---------------------------------------------------------------------------------------------
// One relayed TCP flow.
// ---------------------------------------------------------------------------------------------
class Stream(
    val id: Int,
    private val socket: Socket,
    private val router: Router,
    private val log: (String) -> Unit,
) {
    private val lock = Object()
    private val inbound = TreeMap<Long, ByteArray>() // what the phone sent, by byte offset
    private var writeOffset = 0L
    private val out = socket.getOutputStream()
    private val running = AtomicBoolean(true)
    private var credit = INITIAL_WINDOW // how much we may still push to the phone

    private val recentOut = LinkedHashMap<Long, ByteArray>()

    val bytesToRemote = AtomicLong(0)
    val bytesToPhone = AtomicLong(0)

    private var lastNackAt = 0L
    private var nackTries = 0

    private companion object {
        /** How far the phone may run ahead of what we have written to the real socket. */
        const val INITIAL_WINDOW = 256 * 1024
    }

    fun start() {
        Thread({ writerLoop() }, "bw-stream-$id-w").apply { isDaemon = true }.start()
        Thread({ readerLoop() }, "bw-stream-$id-r").apply { isDaemon = true }.start()
    }

    /** DATA from the phone (may arrive out of order - it is interleaved over two radios). */
    fun onData(offset: Long, data: ByteArray) {
        synchronized(lock) {
            if (!running.get()) return
            if (offset + data.size <= writeOffset) return
            val start = maxOf(offset, writeOffset)
            val skip = (start - offset).toInt()
            val slice = if (skip > 0) data.copyOfRange(skip, data.size) else data
            // IDEMPOTENT. Two radios mean holes are normal, and a hole makes the phone
            // re-send the range while the original is still in flight. Overwriting the entry
            // is safe; *duplicating* it is not - the duplicate would be written to the real
            // socket a second time and the destination would see corrupted bytes (a TLS
            // stream dies with "Illegal packet size" / AEAD tag mismatch at exactly this spot).
            if (inbound.containsKey(start)) return
            inbound[start] = slice
            (lock as Object).notifyAll()
        }
    }

    /** @param finalOffset total bytes the phone sent, or -1 when it did not say. */
    fun onFin(finalOffset: Long = -1) {
        synchronized(lock) {
            if (finalOffset >= 0) expectedFinalOffset = finalOffset
            expectedEof = true
            (lock as Object).notifyAll()
        }
    }

    @Volatile private var expectedFinalOffset: Long = -1
    @Volatile private var expectedEof = false

    fun onWindow(bytes: Int) {
        synchronized(lock) {
            credit += maxOf(0, bytes)
            (lock as Object).notifyAll()
        }
    }

    fun close() {
        running.set(false)
        try {
            socket.close()
        } catch (_: Exception) {
        }
        synchronized(lock) { (lock as Object).notifyAll() }
    }

    /** Writes contiguous phone data to the real socket, in order. */
    private fun writerLoop() {
        while (running.get()) {
            val chunk = nextChunk() ?: return
            try {
                out.write(chunk)
                out.flush()
                bytesToRemote.addAndGet(chunk.size.toLong())
                nackTries = 0
                // Credit back to the phone: it may now push another chunk of this size.
                router.send(BrtWire.WINDOW, BrtWire.withStreamId(id, BrtWire.intBytes(chunk.size)))
            } catch (e: Exception) {
                log("stream $id: write failed (${e.message}) - closing")
                log("stream $id: write to the real socket failed (${e.message}) - resetting the flow")
                router.send(BrtWire.RST, BrtWire.withStreamId(id, ("write failed: ${e.message}").toByteArray()))
                close()
                return
            }
        }
    }

    /** Blocks until the next in-order chunk from the phone is available (null = closed). */
    private fun nextChunk(): ByteArray? = synchronized(lock) {
        while (running.get()) {
            val entry = inbound[writeOffset]
            if (entry != null) {
                inbound.remove(writeOffset)
                writeOffset += entry.size
                return entry
            }
            if (expectedEof && (expectedFinalOffset < 0 || writeOffset >= expectedFinalOffset)) {
                // Everything the phone sent has been written: close the real socket politely.
                try {
                    socket.shutdownOutput()
                } catch (_: Exception) {
                }
                expectedEof = false
            }
            if (inbound.isNotEmpty()) {
                // A hole: ask the phone to re-send it after a short grace period (lost datagram).
                val now = System.currentTimeMillis()
                if (now - lastNackAt > 40 && nackTries < 6) {
                    lastNackAt = now
                    nackTries++
                    val missing = inbound.firstKey() - writeOffset
                    val length = minOf(BrtWire.MAX_PAYLOAD.toLong(), missing).toInt()
                    router.send(
                        BrtWire.NACK,
                        BrtWire.withStreamId(id, BrtWire.intBytes(writeOffset.toInt()) + BrtWire.intBytes(length)),
                    )
                }
            }
            try {
                (lock as Object).wait(50)
            } catch (_: InterruptedException) {
                return null
            }
        }
        return null
    }

    /** Reads the real socket and pushes it to the phone, split over all pipes by [Router]. */
    private fun readerLoop() {
        val buffer = ByteArray(16 * 1024)
        var offset = 0L
        try {
            while (running.get()) {
                val n = socket.getInputStream().read(buffer)
                if (n < 0) break
                if (n == 0) continue
                var cursor = 0
                while (cursor < n) {
                    val chunk = minOf(BrtWire.MAX_PAYLOAD - 6, n - cursor)
                    // Respect the phone's receive window: never push more than it can hold.
                    var waited = 0
                    while (true) {
                        val allowed: Int = synchronized(lock) { credit }
                        if (allowed >= chunk || !running.get()) break
                        try {
                            Thread.sleep(5)
                        } catch (_: InterruptedException) {
                            return
                        }
                        waited += 5
                        if (waited > 60_000) throw SocketTimeoutException("phone window stalled")
                    }
                    synchronized(lock) { credit -= chunk }
                    val payload = buffer.copyOfRange(cursor, cursor + chunk)
                    val offsetInt = offset.toInt()
                    val body = BrtWire.intBytes(offsetInt) + payload
                    synchronized(lock) {
                        recentOut[offset] = payload
                        while (recentOut.size > 128) {
                            val it = recentOut.keys.iterator()
                            it.next()
                            it.remove()
                        }
                    }
                    if (!router.send(BrtWire.DATA, BrtWire.withStreamId(id, body))) {
                        throw java.io.IOException("no pipe to send on")
                    }
                    offset += chunk
                    cursor += chunk
                    bytesToPhone.addAndGet(chunk.toLong())
                }
            }
            router.send(BrtWire.FIN, BrtWire.withStreamId(id, BrtWire.intBytes(offset.toInt())))
        } catch (e: Exception) {
            if (running.get()) router.send(BrtWire.RST, BrtWire.withStreamId(id, (e.message ?: "closed").toByteArray()))
        } finally {
            close()
        }
    }

    /** NACK from the phone: re-send a cached range (UDP carrier only). */
    fun resend(offset: Long, length: Int) {
        var cursor = offset
        val end = offset + length
        while (cursor < end) {
            val chunk = synchronized(lock) { recentOut[cursor] } ?: return
            router.send(BrtWire.DATA, BrtWire.withStreamId(id, BrtWire.intBytes(cursor.toInt()) + chunk))
            cursor += chunk.size
        }
    }
}

/** UDP datagram relaying (DNS, QUIC, WireGuard, ...): one socket for the whole bridge. */
class DatagramRelay(private val router: Router, private val log: (String) -> Unit) {
    private val socket = DatagramSocket()
    private val routes = ConcurrentHashMap<String, Long>() // "host:port" -> last activity

    init {
        socket.soTimeout = 500
        Thread({ loop() }, "bw-dgram-rx").apply { isDaemon = true }.start()
    }

    fun sendTo(host: String, port: Int, data: ByteArray) {
        try {
            socket.send(DatagramPacket(data, data.size, InetAddress.getByName(host), port))
            routes[key(host, port)] = System.currentTimeMillis()
        } catch (e: Exception) {
            log("dgram $host:$port send failed: ${e.message}")
        }
    }

    private fun key(host: String, port: Int) = "$host:$port"

    private fun loop() {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                return
            }
            val from = packet.address.hostAddress ?: continue
            val fromPort = packet.port
            val data = packet.data.copyOfRange(0, packet.length)
            val address = BrtWire.encodeAddress(from, fromPort)
            val room = BrtWire.MAX_PAYLOAD - 2 - address.size
            if (data.size > room) {
                log("dgram reply of ${data.size} bytes from $from:$fromPort exceeds the tunnel limit - dropped")
                continue
            }
            val body = ByteArray(2 + address.size + data.size)
            body[0] = ((MuxStreamId.UDP ushr 8) and 0xFF).toByte()
            body[1] = (MuxStreamId.UDP and 0xFF).toByte()
            System.arraycopy(address, 0, body, 2, address.size)
            System.arraycopy(data, 0, body, 2 + address.size, data.size)
            router.send(BrtWire.DGRAM_RX, body)
        }
    }
}

object MuxStreamId {
    const val UDP = 0x7FFF
}

// ---------------------------------------------------------------------------------------------
// The bridge itself.
// ---------------------------------------------------------------------------------------------
class BridgeServer(
    private val udpPort: Int,
    private val tcpPort: Int,
    private val dohPort: Int,
    initialCookie: Int,
    private val anyCookie: Boolean,
    private val log: (String) -> Unit = { println("[bridge] $it") },
) {
    private val router = Router(log).also { it.cookie = initialCookie }
    private val streams = ConcurrentHashMap<Int, Stream>()

    /**
     * Frames that arrived for a stream that is still being opened.
     *
     * This is not paranoia: the phone spreads frames over TWO radios, so a DATA frame sent right
     * after an OPEN can overtake it and land here first. Dropping it (the previous behaviour)
     * silently truncated the request, and the visible symptom was an empty reply. Opening a
     * destination takes a real TCP connect, so this window is up to the connect timeout.
     */
    private val pending = ConcurrentHashMap<Int, java.util.ArrayDeque<Pair<Int, ByteArray>>>()

    private fun hold(id: Int, type: Int, payload: ByteArray) {
        val queue = pending.computeIfAbsent(id) { java.util.ArrayDeque() }
        synchronized(queue) {
            if (queue.size >= 256) return
            queue.addLast(type to payload)
        }
    }

    /** Replays what was held while the stream was being opened, in arrival order. */
    private fun replay(id: Int, stream: Stream) {
        val queue = pending.remove(id) ?: return
        synchronized(queue) {
            for ((type, payload) in queue) {
                when (type) {
                    BrtWire.DATA -> if (payload.size >= 6) {
                        stream.onData(
                            BrtWire.readInt(payload, 2).toLong() and 0xFFFFFFFFL,
                            payload.copyOfRange(6, payload.size),
                        )
                    }
                    BrtWire.FIN -> stream.onFin(
                        if (payload.size >= 6) BrtWire.readInt(payload, 2).toLong() and 0xFFFFFFFFL else -1L
                    )
                    BrtWire.RST -> stream.close()
                }
            }
        }
        log("replayed ${queue.size} early frame(s) for stream #$id")
    }
    private val datagrams = DatagramRelay(router, log)
    private val running = AtomicBoolean(true)
    private val accepted = AtomicLong(0)
    private val peerCounter = AtomicInteger(0)

    private companion object {
        /** A UDP peer silent for this long is gone (a phone radio that dropped). */
        const val UDP_PIPE_IDLE_MS = 25_000L
        const val MAX_UDP_PIPES = 4
    }

    /** Cookie this session actually uses (random if [anyCookie] and none was fixed). */
    @Volatile var cookie: Int = initialCookie
        private set

    private var udpSocket: DatagramSocket? = null
    /** Live UDP peers, keyed by source address:port (one per radio). */
    @Volatile private var udpPipes: ConcurrentHashMap<String, UdpPipe> = ConcurrentHashMap()
    private var tcpServer: ServerSocket? = null

    /** No --cookie and no --any-cookie: pick a random one, but accept the first one we see. */
    private val learnCookie = anyCookie

    fun run() {
        if (udpPort > 0) startUdp()
        if (tcpPort > 0) startTcp()
        if (dohPort > 0) startDoh()
        log(
            "ready. udp=$udpPort tcp=$tcpPort doh=$dohPort cookie=" +
                (cookie.toString()) + " (any-cookie=$anyCookie)"
        )
        while (running.get()) Thread.sleep(1_000)
    }

    fun stop() {
        running.set(false)
        try {
            udpSocket?.close()
        } catch (_: Exception) {
        }
        try {
            tcpServer?.close()
        } catch (_: Exception) {
        }
        streams.values.forEach { it.close() }
    }

    // --- UDP carrier ----------------------------------------------------------------------

    private fun startUdp() {
        val socket = DatagramSocket(udpPort)
        udpSocket = socket
        log("UDP carrier listening on 0.0.0.0:$udpPort")
        udpPipes = ConcurrentHashMap<String, UdpPipe>()
        val pipes = udpPipes
        // A peer (a phone radio) that goes away must not keep receiving round-robin traffic: an
        // old source address would silently swallow every other frame. Sweep idle peers, and keep
        // the set small (both radios + a reconnect or two is all a session ever has).
        Thread({
            while (running.get()) {
                try {
                    Thread.sleep(5_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val cutoff = System.currentTimeMillis() - UDP_PIPE_IDLE_MS
                pipes.entries.removeIf { entry ->
                    val stale = entry.value.lastSeenMs < cutoff
                    if (stale) {
                        router.remove(entry.value)
                        log("udp pipe expired: ${entry.key}")
                    }
                    stale
                }
            }
        }, "bw-udp-sweep").apply { isDaemon = true }.start()

        Thread({
            val buffer = ByteArray(64 * 1024)
            while (running.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: Exception) {
                    return@Thread
                }
                val from = InetSocketAddress(packet.address, packet.port)
                val frame = BrtWire.decode(packet.data, packet.length) ?: continue
                val frameCookie = ((packet.data[4].toInt() and 0xFF) shl 8) or (packet.data[5].toInt() and 0xFF)
                if (!acceptCookie(frameCookie)) continue
                val key = "$from"
                val pipe = pipes.getOrPut(key) { UdpPipe(socket, from).also { router.add(it) } }
                pipe.lastSeenMs = System.currentTimeMillis()
                if (pipes.size > MAX_UDP_PIPES) {
                    val oldest = pipes.entries.minByOrNull { it.value.lastSeenMs }
                    if (oldest != null && oldest.key != key) {
                        pipes.remove(oldest.key)
                        router.remove(oldest.value)
                        log("udp pipe evicted: ${oldest.key}")
                    }
                }
                handle(frame.type, frame.payload, pipe, frameCookie)
            }
        }, "bw-udp").apply { isDaemon = true }.start()
    }

    private fun acceptCookie(value: Int): Boolean {
        if (anyCookie) {
            if (cookie == 0) cookie = value
            router.cookie = cookie
            return true
        }
        if (learnCookie && cookie == 0) {
            cookie = value
            router.cookie = value
            log("learned cookie=$value from the first frame")
            return true
        }
        router.cookie = cookie
        return value == cookie
    }

    // --- TCP carrier ----------------------------------------------------------------------

    private fun startTcp() {
        val server = ServerSocket(tcpPort)
        tcpServer = server
        log("TCP carrier listening on 0.0.0.0:$tcpPort")
        Thread({
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    return@Thread
                }
                peerCounter.incrementAndGet()
                val pipe = TcpPipe(socket)
                router.add(pipe)
                Thread({
                    try {
                        socket.tcpNoDelay = true
                        val input = DataInputStream(socket.getInputStream())
                        while (running.get()) {
                            val length = input.readUnsignedShort()
                            if (length < BrtWire.HEADER || length > BrtWire.MAX_PAYLOAD + BrtWire.HEADER) break
                            val buffer = ByteArray(length)
                            input.readFully(buffer)
                            val frame = BrtWire.decode(buffer) ?: continue
                            val frameCookie = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
                            if (!acceptCookie(frameCookie)) continue
                            handle(frame.type, frame.payload, pipe, frameCookie)
                        }
                    } catch (_: Exception) {
                    } finally {
                        router.remove(pipe)
                        pipe.close()
                    }
                }, "bw-tcp-${socket.port}").apply { isDaemon = true }.start()
            }
        }, "bw-tcp-accept").apply { isDaemon = true }.start()
    }

    // --- DoH carrier (behind your TLS terminator) -----------------------------------------

    /** Frames waiting to be handed to a DoH client on its next POST. */
    private val dohOutbox = LinkedBlockingQueue<ByteArray>(512)

    /** A pipe whose "send" is "queue it for the next DoH response body". */
    private inner class DohPipe : Pipe {
        override val label: String get() = "doh"
        override fun send(frame: ByteArray): Boolean = dohOutbox.offer(frame)
        override fun close() = Unit
    }

    /**
     * Receives `application/dns-message` POST bodies and answers with the frames queued for the
     * client - the same shape a public DNS server uses, so nothing on the wire looks like a VPN.
     */
    private fun startDoh() {
        val server = ServerSocket(dohPort)
        log("DoH carrier listening on 0.0.0.0:$dohPort (put your TLS proxy in front)")
        val pipe = DohPipe()
        router.add(pipe)
        Thread({
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    return@Thread
                }
                Thread({
                    try {
                        socket.use {
                            it.soTimeout = 10_000
                            val request = readHttpRequest(it.getInputStream()) ?: return@use
                            if (request.body != null && request.body.size >= BrtWire.HEADER) {
                                val frame = BrtWire.decode(request.body)
                                val frameCookie =
                                    ((request.body[4].toInt() and 0xFF) shl 8) or (request.body[5].toInt() and 0xFF)
                                if (frame != null && acceptCookie(frameCookie)) {
                                    handle(frame.type, frame.payload, pipe, frameCookie)
                                }
                            }
                            // Reply body: [len:2][frame]... - what the phone's DohEndpoint splits.
                            val body = java.io.ByteArrayOutputStream()
                            while (true) {
                                val frame = dohOutbox.poll() ?: break
                                body.write((frame.size ushr 8) and 0xFF)
                                body.write(frame.size and 0xFF)
                                body.write(frame)
                                if (body.size() > 60_000) break
                            }
                            val bytes = body.toByteArray()
                            val reply = it.getOutputStream()
                            reply.write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\n" +
                                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
                            )
                            reply.write(bytes)
                            reply.flush()
                        }
                    } catch (_: Exception) {
                    }
                }, "bw-doh").apply { isDaemon = true }.start()
            }
        }, "bw-doh-accept").apply { isDaemon = true }.start()
    }

    private class HttpRequest(val body: ByteArray?)

    private fun readHttpRequest(input: java.io.InputStream): HttpRequest? {
        val header = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            header.append(b.toChar())
            if (header.endsWith("\r\n\r\n")) break
            if (header.length > 8192) return null
        }
        // NOTE the single backslashes: this is a Kotlin string, not a regex literal. With `\\s`
        // the pattern looks for a literal backslash, never matches, and every DoH request is
        // treated as body-less - i.e. the DoH carrier silently never works. Found by
        // DohCarrierTest, fixed here rather than worked around in the test.
        val contentLength = Regex("(?i)content-length:\\s*(\\d+)").find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (contentLength <= 0) return HttpRequest(null)
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) return null
            read += n
        }
        return HttpRequest(body)
    }

    // --- frame handling -------------------------------------------------------------------

    /**
     * @param pipeCookie the cookie the frame arrived with. In learn mode a HELLO may advertise a
     *        different one (a new address = a new session) and the bridge switches to it.
     */
    private fun handle(type: Int, payload: ByteArray, pipe: Pipe, pipeCookie: Int = cookie) {
        when (type) {
            BrtWire.PING -> router.replyOn(pipe, BrtWire.PONG, payload)

            BrtWire.HELLO -> {
                // A new session announced itself.
                //
                // In learn mode (no --cookie) the tag is adopted from the HELLO: the app derives
                // it from the address the user typed, so switching the address (or moving from
                // the UDP to the TCP carrier of the same host) is a *new session*, not a reason
                // to lock the old tag and reject everything. Without this, the first run after a
                // previous test silently failed with "no answer" - a trap for exactly the person
                // this bridge is for.
                if (learnCookie && pipeCookie in 1..0xFFFF && pipeCookie != cookie) {
                    cookie = pipeCookie
                    router.cookie = pipeCookie
                    log("adopted session cookie=$pipeCookie from HELLO")
                }
                // Every UDP peer that is not part of THIS session
                // is a socket from a previous session that nobody reads any more - leaving it in
                // the round-robin would silently swallow half of every reply, on every flow.
                // (The app uses one carrier at a time, so a TCP session also retires old UDP peers.)
                val tag = payload.joinToString("") { "%02x".format(it) }
                if (pipe is UdpPipe) pipe.session = tag
                val stale = udpPipes.values.filter { candidate ->
                    candidate !== pipe && (pipe !is UdpPipe || candidate.session != tag)
                }
                stale.forEach { dead ->
                    udpPipes.entries.removeIf { entry -> entry.value === dead }
                    router.remove(dead)
                    log("retired stale udp peer ${dead.label} (session $tag)")
                }
                router.replyOn(pipe, BrtWire.HELLO_ACK, payload)
            }

            BrtWire.OPEN -> {
                val id = BrtWire.streamId(payload)
                val address = BrtWire.decodeAddress(payload, 2)
                if (address == null) {
                    router.sendOn(pipe, BrtWire.OPEN_ERR, BrtWire.withStreamId(id, "bad address".toByteArray()))
                    return
                }
                accepted.incrementAndGet()
                // Start holding any frames that beat the connect, then replay them.
                pending[id] = java.util.ArrayDeque()
                // Connect off the receive thread: a slow destination must never stall the tunnel.
                Thread({
                    try {
                        val socket = Socket()
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(address.host, address.port), 10_000)
                        val stream = Stream(id, socket, router, log)
                        streams[id] = stream
                        router.sendOn(pipe, BrtWire.OPEN_OK, BrtWire.withStreamId(id, BrtWire.encodeAddress(socket.inetAddress.hostAddress, socket.port)))
                        log("open #$id ${address.host}:${address.port} -> ${socket.inetAddress.hostAddress}:${socket.port} (pipes=${router.pipeCount()})")
                        stream.start()
                        replay(id, stream)
                    } catch (e: Exception) {
                        pending.remove(id)
                        log("open #$id ${address.host}:${address.port} failed: ${e.message}")
                        router.sendOn(
                            pipe,
                            BrtWire.OPEN_ERR,
                            BrtWire.withStreamId(id, (e.message ?: "connect failed").toByteArray(Charsets.UTF_8)),
                        )
                    }
                }, "bw-open-$id").apply { isDaemon = true }.start()
            }

            BrtWire.DATA -> {
                if (payload.size < 6) return
                val id = BrtWire.streamId(payload)
                val stream = streams[id]
                if (stream == null) {
                    if (pending.containsKey(id)) hold(id, BrtWire.DATA, payload)
                    return
                }
                stream.onData(BrtWire.readInt(payload, 2).toLong() and 0xFFFFFFFFL, payload.copyOfRange(6, payload.size))
            }

            BrtWire.FIN -> {
                val id = BrtWire.streamId(payload)
                log("phone finished stream #$id at ${if (payload.size >= 6) BrtWire.readInt(payload, 2) else -1} bytes")
                val finalOffset = if (payload.size >= 6) BrtWire.readInt(payload, 2).toLong() and 0xFFFFFFFFL else -1L
                val stream = streams[id]
                if (stream == null) {
                    if (pending.containsKey(id)) hold(id, BrtWire.FIN, payload)
                } else {
                    stream.onFin(finalOffset)
                }
            }

            BrtWire.RST -> {
                val id = BrtWire.streamId(payload)
                val stream = streams.remove(id)
                if (stream == null) {
                    if (pending.containsKey(id)) hold(id, BrtWire.RST, payload)
                } else {
                    stream.close()
                }
            }

            BrtWire.WINDOW -> {
                if (payload.size < 6) return
                streams[BrtWire.streamId(payload)]?.onWindow(BrtWire.readInt(payload, 2))
            }

            BrtWire.NACK -> {
                if (payload.size < 10) return
                val stream = streams[BrtWire.streamId(payload)] ?: return
                stream.resend(BrtWire.readInt(payload, 2).toLong() and 0xFFFFFFFFL, BrtWire.readInt(payload, 6))
            }

            BrtWire.DGRAM -> {
                val address = BrtWire.decodeAddress(payload, 2) ?: return
                val start = 2 + BrtWire.addressLength(payload, 2)
                if (start > payload.size) return
                datagrams.sendTo(address.host, address.port, payload.copyOfRange(start, payload.size))
            }
        }
    }

    fun stats(): String =
        "pipes=${router.pipeCount()} streams=${streams.size} opens=$accepted cookie=$cookie"
}

// ---------------------------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------------------------
fun main(args: Array<String>) {
    var udp = 5300
    var tcp = 5301
    var doh = 0
    var cookie = 0
    var anyCookie = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--udp" -> {
                udp = args.getOrNull(++i)?.toIntOrNull() ?: 5300
            }
            "--tcp" -> {
                tcp = args.getOrNull(++i)?.toIntOrNull() ?: 5301
            }
            "--doh-port" -> {
                doh = args.getOrNull(++i)?.toIntOrNull() ?: 0
            }
            "--cookie" -> {
                cookie = args.getOrNull(++i)?.toIntOrNull() ?: 0
            }
            "--any-cookie" -> anyCookie = true
            "--no-udp" -> udp = 0
            "--no-tcp" -> tcp = 0
            "--help", "-h" -> {
                println(
                    """
                    BridgeServer - far side of BigRocket's address tunnel (engine 2).

                      --udp <port>       UDP carrier port        (default 5300, 0 disables)
                      --tcp <port>       TCP carrier port        (default 5301, 0 disables)
                      --doh-port <port>  DoH carrier port        (default off; needs TLS in front)
                      --cookie <n>       16-bit session tag      (default: random)
                      --any-cookie       accept whatever the client sends
                      --no-udp / --no-tcp
                    """.trimIndent()
                )
                return
            }
            else -> println("[bridge] ignoring unknown argument ${args[i]}")
        }
        i++
    }
    // No --cookie given: LEARN it from the first frame instead of inventing one. The app derives
    // the cookie from the address the user typed, and a bridge nobody can talk to on the first
    // try is a bridge that looks broken. Pass --cookie N to lock it down instead.
    if (cookie == 0 && !anyCookie) {
        anyCookie = true
        println("[bridge] no --cookie given: the first frame's cookie will be adopted (use --cookie N to pin it)")
    }
    val bridge = BridgeServer(udp, tcp, doh, cookie, anyCookie)
    Runtime.getRuntime().addShutdownHook(Thread { bridge.stop() })
    println(
        "[bridge] put this in the app: <server-ip>:$udp   (or tcp://<server-ip>:$tcp, or https://<server>/dns-query)"
    )
    bridge.run()
}
