package com.bigrocket.tunnel

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The far side keeps the last [MuxSession.RESEND_CACHE] chunks so it can replay one the phone
 * missed. That cache is only worth anything if it is at least as deep as the flow-control window:
 * otherwise the far side may legally be [MuxSession.INITIAL_WINDOW] bytes ahead of the oldest
 * un-acknowledged chunk, and a hole that opens early in a fast burst can no longer be filled -
 * [MuxSession] NACKs, gets nothing back, gives up after [MuxSession.MAX_NACKS] and the stream
 * stalls until the reader times out.
 *
 * That is not hypothetical: it is what made `aStreamSplitAcrossTwoRadiosSurvivesADeadRadio`
 * time out on a fast CI runner while passing on a slower laptop. This test makes the hole
 * deterministic instead of racing a real socket, so the invariant is checked by arithmetic
 * rather than by luck.
 *
 * A hole is forced with a carrier that silently drops one frame in the middle of a large
 * transfer - no radio died, the frame was simply lost, which is what a lossy link does.
 */
class MuxRetransmitReachTest {

    private val closables = CopyOnWriteArrayList<AutoCloseable>()

    @Before
    fun quietLogs() {
        // Silent by default. Run with -DMUX_DEBUG=1 to see the mux's own log, which is what
        // shows NACKs, resend misses and FIN bookkeeping when a flow stalls.
        AppLog.useAppLogger { tag, msg ->
            if (System.getenv("MUX_DEBUG") != null) println("[$tag] $msg")
        }
    }

    @After
    fun tearDown() {
        closables.forEach { runCatching { it.close() } }
    }

    // --------------------------------------------------------------------------------------------
    // 1. The invariant, as arithmetic. No sockets, no timing.
    // --------------------------------------------------------------------------------------------

    @Test
    fun theReplayCacheIsAtLeastAsDeepAsTheFlowControlWindow() {
        val chunk = MuxSession.MAX_CHUNK
        val windowChunks = (MuxSession.INITIAL_WINDOW + chunk - 1) / chunk // round up: worst case

        assertTrue(
            "the far side may be $windowChunks chunks ahead of an un-acked chunk " +
                "(${MuxSession.INITIAL_WINDOW} B window / $chunk B chunk) but can only replay " +
                "${MuxSession.RESEND_CACHE}: a hole early in a full window is unfillable, so the " +
                "NACK loop gives up and the stream stalls until the reader times out",
            MuxSession.RESEND_CACHE >= windowChunks,
        )
    }

    // --------------------------------------------------------------------------------------------
    // 2. The behaviour that invariant buys: one silently dropped frame, transfer still exact.
    // --------------------------------------------------------------------------------------------

    @Test
    fun oneSilentlyDroppedFrameIsReplayedAndTheStreamStaysByteExact() {
        val size = 3 * 1024 * 1024
        val dropped = AtomicInteger(0)

        val bridge = startBridge()
        val echo = startEchoOrigin()

        // A carrier that is a perfectly healthy TCP pipe except that it swallows the 400th frame
        // it is asked to send, exactly once. Nothing is closed, nothing reports failure.
        val lossy: ChannelEndpoint = object : ChannelEndpoint {
            private var inner: ChannelEndpoint? = null
            private val sent = AtomicInteger(0)
            override val label: String = "lossy-carrier"
            override val isOpen: Boolean get() = inner?.isOpen == true
            override fun start(sink: FrameSink): Boolean {
                val tcp = TcpChannelEndpoint(InetSocketAddress("127.0.0.1", bridge))
                inner = tcp
                return tcp.start(sink)
            }

            override fun send(frame: ByteArray): Boolean {
                if (sent.incrementAndGet() == 400 && dropped.compareAndSet(0, 1)) return true
                return inner?.send(frame) ?: false
            }

            override fun close() {
                inner?.close()
            }
        }
        closables.add(AutoCloseable { runCatching { lossy.close() } })

        val session = MuxSession(BondedEndpoint(listOf(lossy)), 0, label = "retransmit-reach")
        closables.add(AutoCloseable { runCatching { session.close() } })
        assertTrue("session did not start", session.start(8_000))

        val tunnel: Socket = session.openTcp("127.0.0.1", echo, timeoutMs = 8_000)
        tunnel.soTimeout = 40_000
        closables.add(AutoCloseable { runCatching { tunnel.close() } })

        // The origin answers with `size` bytes; the client asks for them and must get them all.
        val out: OutputStream = tunnel.getOutputStream()
        out.write("GET /blob/$size HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
        out.flush()
        skipHeaders(tunnel.getInputStream())

        val body = ByteArray(size)
        readExact(tunnel.getInputStream(), body)

        assertEquals("the frame must actually have been dropped", 1, dropped.get())
        assertArrayEquals("a replayed hole must preserve every byte", originBody(size), body)
    }


    // --------------------------------------------------------------------------------------------
    // 3. The NACK budget is per HOLE, not per stream.
    // --------------------------------------------------------------------------------------------

    @Test
    fun manyHolesInOneLongStreamAreAllRecovered() {
        // A phone keeps one tunnel open for a whole session; a lossy radio drops many frames over
        // that time. The NACK budget used to be counted for the life of the stream and never
        // reset, so after MAX_NACKS holes the receiver stopped asking for repairs altogether and
        // the next hole stalled the stream until the reader timed out. Eight holes here, with
        // MAX_NACKS = 6, so the budget has to be per hole or this cannot finish.
        val size = 6 * 1024 * 1024
        val drops = 8
        val dropped = AtomicInteger(0)

        val bridge = startBridge()
        val origin = startEchoOrigin()

        val lossy = ReceiverDroppingEndpoint(
            InetSocketAddress("127.0.0.1", bridge),
            dropEvery = 120,   // generous spacing: one hole at a time, like real loss
            dropLimit = drops,
            dropped = dropped,
        )
        closables.add(AutoCloseable { runCatching { lossy.close() } })

        val session = MuxSession(BondedEndpoint(listOf(lossy)), 0, label = "many-holes")
        closables.add(AutoCloseable { runCatching { session.close() } })
        assertTrue("session did not start", session.start(8_000))

        val tunnel: Socket = session.openTcp("127.0.0.1", origin, timeoutMs = 8_000)
        tunnel.soTimeout = 40_000
        closables.add(AutoCloseable { runCatching { tunnel.close() } })

        val out: OutputStream = tunnel.getOutputStream()
        out.write("GET /blob/$size HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
        out.flush()
        skipHeaders(tunnel.getInputStream())

        val body = ByteArray(size)
        readExact(tunnel.getInputStream(), body)

        assertEquals("every intended drop must have happened", drops, dropped.get())
        assertArrayEquals("all eight holes must be repaired", originBody(size), body)
        assertEquals(
            "the far side must be able to honour every NACK: a miss means its replay cache is " +
                "shallower than the phone's receive window, which silently kills the flow",
            0L,
            resendMisses(),
        )
    }

    // --------------------------------------------------------------------------------------------
    // Harness.
    // --------------------------------------------------------------------------------------------

    private var lastBridge: bridgesrc.BridgeServer? = null

    private fun startBridge(): Int {
        val tcpPort = ServerSocket(0).use { it.localPort }
        val udpPort = java.net.DatagramSocket(0).use { it.localPort }
        val server = bridgesrc.BridgeServer(udpPort, tcpPort, 0, 0, anyCookie = true)
        lastBridge = server
        Thread({ server.run() }, "retransmit-bridge").apply { isDaemon = true }.start()
        closables.add(AutoCloseable { runCatching { server.stop() } })
        waitUntil("bridge ready", 5_000) { server.stats().startsWith("pipes=") }
        Thread.sleep(150)
        return tcpPort
    }

    /** The bridge's own view of the repair: how many NACKs it could NOT honour. */
    private fun resendMisses(): Long =
        Regex("resendMisses=(\\d+)").find(lastBridge!!.stats())!!.groupValues[1].toLong()

    /** A plain (non-TLS) origin: this test is about framing, not about TLS. */
    private fun startEchoOrigin(): Int {
        val server = ServerSocket(0)
        closables.add(AutoCloseable { runCatching { server.close() } })
        Thread({
            while (!server.isClosed) {
                val conn = runCatching { server.accept() }.getOrNull() ?: break
                Thread({
                    runCatching { serveBlob(conn) }
                    runCatching { conn.close() }
                }, "retransmit-origin").apply { isDaemon = true }.start()
            }
        }, "retransmit-origin-accept").apply { isDaemon = true }.start()
        return server.localPort
    }

    private fun serveBlob(conn: Socket) {
        val input = conn.getInputStream()
        // Read the request line so the size comes from the URL, not from a constant: the two
        // tests transfer different amounts and one origin code path serves both.
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return
            if (b == '\n'.code) break
            if (b != '\r'.code) line.append(b.toChar())
        }
        // Consume the rest of the headers.
        var state = 0
        while (state < 4) {
            val b = input.read()
            if (b < 0) return
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                b == '\r'.code -> 1
                else -> 0
            }
        }
        val requested = line.split("/blob/").getOrNull(1)
            ?.substringBefore(' ')?.trim()?.toIntOrNull() ?: DEFAULT_BLOB
        val body = originBody(requested)
        val out = conn.getOutputStream()
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        out.write(body)
        out.flush()
    }

    private fun skipHeaders(input: InputStream) {
        var state = 0
        while (state < 4) {
            val b = input.read()
            check(b >= 0) { "connection closed inside the response headers" }
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                b == '\r'.code -> 1
                else -> 0
            }
        }
    }

    private fun readExact(input: InputStream, buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val n = DataInputStream(input).read(buffer, read, buffer.size - read)
            check(n >= 0) { "stream ended at $read/${buffer.size}" }
            read += n
        }
    }

    /**
     * Deterministic body: a repeatable function of the size, so the client can rebuild the
     * expectation without receiving it. Same generator the TLS test uses for `/blob`.
     */
    private fun originBody(bytes: Int): ByteArray = ByteArray(bytes) { i ->
        ((i * 31 + (i ushr 8)) and 0xFF).toByte()
    }

    private fun waitUntil(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private companion object {
        /** Size the origin serves when the request does not name one. */
        const val DEFAULT_BLOB = 3 * 1024 * 1024
    }

/**
 * A carrier that is a healthy TCP pipe except that it silently swallows inbound DATA frames,
 * one every [dropEvery] frames, up to [dropLimit]. Nothing is closed and nothing reports failure:
 * this is what a lossy radio looks like to the far side, and it forces the receiver to repair a
 * hole through NACK + replay instead of through timing luck.
 */
private class ReceiverDroppingEndpoint(
    private val remote: InetSocketAddress,
    private val dropEvery: Int,
    private val dropLimit: Int,
    private val dropped: AtomicInteger,
) : ChannelEndpoint {
    private var inner: ChannelEndpoint? = null
    private val seen = AtomicInteger(0)

    override val label: String get() = "lossy-rx"
    override val isOpen: Boolean get() = inner?.isOpen == true

    override fun start(sink: FrameSink): Boolean {
        val tcp = TcpChannelEndpoint(remote)
        inner = tcp
        return tcp.start { raw, length, frame ->
            if (frame.type == Brt.DATA) {
                val n = seen.incrementAndGet()
                val swallow = n % dropEvery == 0 && dropped.get() < dropLimit
                if (swallow) dropped.incrementAndGet()
                // A swallowed frame is simply never handed to the mux: the phone sees a hole.
                if (!swallow) sink.onFrame(raw, length, frame)
            } else {
                sink.onFrame(raw, length, frame)
            }
        }
    }

    override fun send(frame: ByteArray): Boolean = inner?.send(frame) ?: false
    override fun close() { inner?.close() }
}
}
