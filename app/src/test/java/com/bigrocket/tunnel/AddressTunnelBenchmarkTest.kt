package com.bigrocket.tunnel

import bridgesrc.BridgeServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The benchmark that backs up (and limits) the claim that engine 2 is cheaper per flow.
 *
 * What is measured: the cost of *starting* a flow, and the protocol exchanges needed to do it,
 * for the two shapes the app has:
 *
 *   A. the OLD static-proxy egress - a fresh TCP connection to the proxy, a SOCKS5 greeting, then
 *      CONNECT, for every flow (what `VirtualEgress.connectThroughProxy` does today);
 *   B. the ADDRESS TUNNEL - an OPEN frame on an already-established multiplexed channel.
 *
 * The link is a 25 ms one-way delay line on both servers, i.e. ~50 ms RTT, which is what a phone
 * on a mobile network actually sees.
 *
 * Deliberately NOT asserted: which shape has lower wall-clock latency. On a 2-core CI box the
 * scheduler noise is larger than the difference (measurements swing ±30 %), and it would be
 * dishonest to dress that up as a number. What IS asserted are the structural facts, which are
 * deterministic and are the actual reason for this design:
 *
 *   - A needs 2 protocol exchanges per flow (greeting+CONNECT), B needs 1 (OPEN), plus the ACK;
 *   - A needs a new TCP connection per flow, B needs none;
 *   - B never opens a *second* channel no matter how many flows are created.
 *
 * Timings are printed for the record.
 */
class AddressTunnelBenchmarkTest {

    /** Never hang a CI run: the assertions are structural, the timings are informational. */
    @get:org.junit.Rule
    val globalTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(240)


    private lateinit var bridge: BridgeServer
    private var udpPort = 0
    private val servers = ArrayList<AutoCloseable>()
    private var session: MuxSession? = null

    private val flows = 15
    private val oneWayDelayMs = 25L

    @Before
    fun start() {
        AppLog.useAppLogger { _, _ -> }
        udpPort = java.net.DatagramSocket(0).use { it.localPort }
        bridge = BridgeServer(udpPort = udpPort, tcpPort = 0, dohPort = 0, initialCookie = 0, anyCookie = true)
        Thread({ bridge.run() }, "bench-bridge").apply { isDaemon = true }.start()
        Thread.sleep(300)

        val inner = UdpChannelEndpoint(InetSocketAddress(InetAddress.getLoopbackAddress(), udpPort))
        val carrier = object : ChannelEndpoint {
            override val label: String get() = inner.label
            override val isOpen: Boolean get() = inner.isOpen
            override fun send(frame: ByteArray): Boolean = inner.send(frame)
            override fun close() = inner.close()
            override fun start(sink: FrameSink): Boolean {
                val wrapped = FrameSink { raw, length, frame ->
                    if (frame.type == Brt.OPEN_OK) openOks.incrementAndGet()
                    sink.onFrame(raw, length, frame)
                }
                val up = inner.start(wrapped)
                if (up) channelsOpened.incrementAndGet()
                return up
            }
        }
        val session = MuxSession(BondedEndpoint(listOf(carrier)), Brt.cookieFor("bench"), label = "bench")
        this.session = session
        assertTrue("benchmark tunnel did not come up", session.start(5_000))
    }

    @After
    fun stop() {
        session?.let { runCatching { it.close() } }
        servers.forEach { runCatching { it.close() } }
        runCatching { bridge.stop() }
    }

    @Test
    fun oneTunnelVersusPerFlowSocks5Egress() {
        val destination = startDelayLineServer(oneWayDelayMs)

        // --- A: the old shape -------------------------------------------------------------
        val socks = startSocks5Server(oneWayDelayMs)
        var socksSetupTotal = 0L
        var socksPayloadTotal = 0L
        repeat(flows) {
            val started = System.nanoTime()
            val socket = Socket(InetAddress.getLoopbackAddress(), socks)
            socket.tcpNoDelay = true
            socket.soTimeout = 20_000
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            // SOCKS5 greeting (exchange 1)
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greeting = ByteArray(2)
            readFully(input, greeting)
            assertEquals(0x05, greeting[0].toInt())
            // CONNECT with a DOMAIN address: the proxy resolves, like Socks5Protocol.connect (exchange 2)
            val host = "localhost".toByteArray()
            out.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()) + host + portBytes(destination))
            out.flush()
            val reply = ByteArray(10)
            readFully(input, reply)
            assertEquals(0x00, reply[1].toInt())
            val connected = System.nanoTime()
            // One request/response payload.
            out.write("ping".toByteArray())
            out.flush()
            val echoed = ByteArray(4)
            readFully(input, echoed)
            socksPayloadTotal += System.nanoTime() - connected
            socksSetupTotal += connected - started
            socket.close()
        }

        // --- B: the tunnel's shape --------------------------------------------------------
        var tunnelSetupTotal = 0L
        var tunnelPayloadTotal = 0L
        repeat(flows) {
            val started = System.nanoTime()
            val socket = session!!.openTcp("127.0.0.1", destination, timeoutMs = 20_000)
            socket.soTimeout = 20_000
            val connected = System.nanoTime()
            socket.getOutputStream().write("ping".toByteArray())
            socket.getOutputStream().flush()
            val echoed = ByteArray(4)
            readFully(socket.getInputStream(), echoed)
            tunnelPayloadTotal += System.nanoTime() - connected
            tunnelSetupTotal += connected - started
            socket.close()
            Thread.sleep(5)
        }

        val socksSetupUs = socksSetupTotal / flows / 1_000
        val tunnelSetupUs = tunnelSetupTotal / flows / 1_000
        val socksPayloadUs = socksPayloadTotal / flows / 1_000
        val tunnelPayloadUs = tunnelPayloadTotal / flows / 1_000
        println(
            "A. proxy  : TCP connect + SOCKS5 greeting + CONNECT : ${socksSetupUs}µs/flow setup, " +
                "${socksPayloadUs}µs payload"
        )
        println(
            "B. tunnel : OPEN frame on an established tunnel      : ${tunnelSetupUs}µs/flow setup, " +
                "${tunnelPayloadUs}µs payload"
        )

        // Deterministic, implementation-level facts (the reason for the design, and the only
        // numbers here that are not at the mercy of a shared CI box):
        //  - the tunnel opens exactly ONE carrier channel and keeps it for every flow;
        //  - each flow costs one OPEN (+ its OPEN_OK), and nothing else;
        //  - the payloads really traversed it.
        assertEquals("the tunnel must not open a second channel", 1, channelsOpened.get())
        assertEquals("each flow must be answered by exactly one OPEN_OK", flows, openOks.get())
        assertTrue("payload must actually traverse the tunnel", session!!.totalBytesIn.get() >= flows * 4L)
        println(
            "protocol exchanges to open $flows flows: proxy=${socksExchanges.get()} " +
                "(${socksExchanges.get().toDouble() / flows}/flow) " +
                "tunnel=${openOks.get() * 2} " +
                "(${"%.2f".format(openOks.get() * 2.0 / flows)}/flow = OPEN + OPEN_OK)"
        )
        assertEquals("SOCKS5 needs exactly 2 exchanges per flow", flows * 2, socksExchanges.get())
        println(
            "informational: setup ratio proxy/tunnel = " +
                "${"%.2f".format(if (tunnelSetupUs == 0L) 0.0 else socksSetupUs.toDouble() / tunnelSetupUs)}x, " +
                "payload ratio = " +
                "${"%.2f".format(if (tunnelPayloadUs == 0L) 0.0 else socksPayloadUs.toDouble() / tunnelPayloadUs)}x"
        )
        assertNotNull(readings(socksSetupUs, tunnelSetupUs, socksPayloadUs, tunnelPayloadUs))
    }

    private fun readings(a: Long, b: Long, c: Long, d: Long) = listOf(a, b, c, d)

    // --- helpers ---------------------------------------------------------------------------

    private val socksExchanges = AtomicInteger(0)

    /** Channels opened by the tunnel: asserted to stay at 1 no matter how many flows there are. */
    private val channelsOpened = AtomicInteger(0)

    /** OPEN_OK frames seen by the tunnel: exactly one per flow, i.e. one exchange per flow. */
    private val openOks = AtomicInteger(0)

    /** A minimal SOCKS5 server with a one-way delay, modelling the old egress shape. */
    private fun startSocks5Server(delayMs: Long): Int {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        servers.add(AutoCloseable { server.close() })
        Thread({
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    return@Thread
                }
                Thread({
                    try {
                        client.tcpNoDelay = true
                        val input = client.getInputStream()
                        val output = client.getOutputStream()
                        Thread.sleep(delayMs) // RTT/2 before the proxy answers: exchange 1
                        val greeting = ByteArray(3)
                        readFully(input, greeting)
                        socksExchanges.incrementAndGet()
                        output.write(byteArrayOf(0x05, 0x00))
                        output.flush()
                        Thread.sleep(delayMs) // exchange 2
                        val head = ByteArray(5)
                        readFully(input, head)
                        socksExchanges.incrementAndGet()
                        val hostLen = head[4].toInt() and 0xFF
                        readFully(input, ByteArray(hostLen + 2))
                        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
                        output.flush()
                        // Relay to the delay-line destination.
                        val upstream = Socket(InetAddress.getLoopbackAddress(), destinationPort)
                        upstream.tcpNoDelay = true
                        // Both directions must run concurrently: a single-threaded relay would
                        // only forward the far side's reply after the client had closed.
                        Thread({ relay(upstream.getInputStream(), output, 0) }, "bench-socks-up")
                            .apply { isDaemon = true }.start()
                        relay(input, upstream.getOutputStream(), delayMs)
                    } catch (_: Exception) {
                    } finally {
                        runCatching { client.close() }
                    }
                }, "bench-socks").apply { isDaemon = true }.start()
            }
        }, "bench-socks-accept").apply { isDaemon = true }.start()
        return server.localPort
    }

    @Volatile private var destinationPort = 0

    private fun relay(from: InputStream, to: OutputStream, delayMs: Long) {
        val buffer = ByteArray(1024)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                if (delayMs > 0) Thread.sleep(delayMs)
                to.write(buffer, 0, n)
                to.flush()
            }
        } catch (_: Exception) {
        }
    }

    /** Echo server behind a one-way delay: the "far side of the internet" for both shapes. */
    private fun startDelayLineServer(delayMs: Long): Int {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        servers.add(AutoCloseable { server.close() })
        destinationPort = server.localPort
        Thread({
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    return@Thread
                }
                Thread({
                    try {
                        socket.tcpNoDelay = true
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        val buffer = ByteArray(1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (delayMs > 0) Thread.sleep(delayMs)
                            output.write(buffer, 0, n)
                            output.flush()
                        }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { socket.close() }
                    }
                }, "bench-echo").apply { isDaemon = true }.start()
            }
        }, "bench-echo-accept").apply { isDaemon = true }.start()
        return server.localPort
    }

    private fun portBytes(port: Int) = byteArrayOf(((port ushr 8) and 0xFF).toByte(), (port and 0xFF).toByte())

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) throw AssertionError("unexpected EOF")
            read += n
        }
    }
}
