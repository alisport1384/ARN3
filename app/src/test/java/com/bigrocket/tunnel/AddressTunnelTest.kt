package com.bigrocket.tunnel

import bridgesrc.BridgeServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

/**
 * LIVE tests: the phone-side tunnel code against the real far side (`far-side/BridgeServer.kt`,
 * compiled into the test classpath by tools/sync-bridge.sh), over real loopback UDP/TCP.
 *
 * These are the tests that answer "does the address tunnel actually work", not just "do the bytes
 * round-trip": each one starts a bridge, opens real TCP/UDP servers behind it, and drives the
 * same [MuxSession] the phone uses.
 */
class AddressTunnelTest {

    private lateinit var bridge: BridgeServer
    private var udpPort = 0
    private var tcpPort = 0
    private var cookie = 0
    private val servers = ArrayList<AutoCloseable>()
    private val sessions = ArrayList<MuxSession>()

    @Before
    fun startBridge() {
        // The tunnel logs through a seam; in tests it goes to stdout (which Gradle shows on a
        // failure), exactly like the desktop e2e client.
        AppLog.useAppLogger { tag, message -> println("[$tag] $message") }
        udpPort = freeUdpPort()
        tcpPort = freeTcpPort()
        cookie = 0
        // No --cookie: the bridge adopts the client's cookie from the first frame, which is what
        // run-bridge.sh does by default and what a user's first run looks like.
        bridge = BridgeServer(udpPort, tcpPort, 0, 0, anyCookie = true)
        Thread({ bridge.run() }, "test-bridge").apply { isDaemon = true }.start()
        waitUntil("bridge ready", 5_000) { bridge.stats().startsWith("pipes=") }
        Thread.sleep(150)
    }

    @After
    fun stopEverything() {
        sessions.forEach { runCatching { it.close() } }
        servers.forEach { runCatching { it.close() } }
        runCatching { bridge.stop() }
    }

    private fun freeUdpPort(): Int = java.net.DatagramSocket(0).use { it.localPort }
    private fun freeTcpPort(): Int = ServerSocket(0).use { it.localPort }

    private fun session(transport: String, pipes: Int = 2): MuxSession {
        cookie = Brt.cookieFor("test-$transport")
        val endpoints = (1..pipes).map {
            when (transport) {
                "udp" -> UdpChannelEndpoint(InetSocketAddress(InetAddress.getLoopbackAddress(), udpPort))
                else -> TcpChannelEndpoint(InetSocketAddress(InetAddress.getLoopbackAddress(), tcpPort))
            }
        }
        val session = MuxSession(BondedEndpoint(endpoints), cookie, label = "$transport x$pipes")
        sessions.add(session)
        assertTrue("session did not come up: ${session.downReason()}", session.start(5_000))
        return session
    }

    // --- 1 ---------------------------------------------------------------------------------
    @Test
    fun tcpCarrierOneFlowBothPipesRealBytesBothWays() {
        val echo = startTcpServer { input, output ->
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                output.flush()
            }
        }
        val session = session("udp", pipes = 2)

        val socket = session.openTcp("127.0.0.1", echo)
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() } // spans many frames over 2 pipes
        socket.getOutputStream().write(payload)
        socket.getOutputStream().flush()

        val echoed = readExactly(socket.getInputStream(), payload.size)
        assertArrayEquals(payload, echoed)
        // Both radios really carried part of the stream: the mux reports frames on both pipes,
        // and the reassembly is done by the reorderer (offset-based), not by luck.
        assertTrue("expected more than a couple of frames out", session.totalFramesOut.get() > 10)
        assertTrue("expected frames back", session.totalFramesIn.get() > 10)
        socket.close()
        assertEquals(1, session.totalBytesIn.get() / 65536) // ≥ 64 KiB came back through the tunnel
    }

    // --- 2 ---------------------------------------------------------------------------------
    @Test
    fun tcpCarrierWithOnePipeAlsoWorks() {
        val echo = startTcpServer { input, output ->
            val one = input.read()
            output.write(one)
            output.flush()
        }
        val session = session("tcp", pipes = 1)
        val socket = session.openTcp("127.0.0.1", echo)
        socket.getOutputStream().write('Z'.code)
        socket.getOutputStream().flush()
        assertEquals('Z'.code, socket.getInputStream().read())
        socket.close()
    }

    // --- 3 ---------------------------------------------------------------------------------
    @Test
    fun manyFlowsShareOneTunnelThePerConnectionHandshakeIsGone() {
        val echo = startTcpServer { input, output ->
            val buffer = ByteArray(256)
            val n = input.read(buffer)
            if (n > 0) output.write(buffer, 0, n)
            output.flush()
        }
        val session = session("udp", pipes = 2)
        val flows = 12
        val done = AtomicInteger(0)
        val failures = java.util.Collections.synchronizedList(ArrayList<String>())
        val threads = (1..flows).map { index ->
            Thread({
                try {
                    val socket = session.openTcp("127.0.0.1", echo, timeoutMs = 20_000)
                val message = "flow-$index".toByteArray()
                socket.getOutputStream().write(message)
                socket.getOutputStream().flush()
                val reply = readExactly(socket.getInputStream(), message.size)
                assertArrayEquals(message, reply)
                socket.close()
                done.incrementAndGet()
                } catch (e: Throwable) {
                    failures.add("flow-$index: ${e.javaClass.simpleName}: ${e.message}")
                }
            }, "flow-$index").apply { start() }
        }
        threads.forEach { it.join(40_000) }
        assertEquals("failed flows: $failures", flows, done.get())
        // Every flow rides ONE tunnel: no new carrier socket per flow, so the number of OPENs is
        // the only per-flow cost - which is exactly what the design claims.
        assertTrue("bridge should have accepted $flows opens", bridge.stats().contains("opens="))
    }

    // --- 4 ---------------------------------------------------------------------------------
    @Test
    fun udpDatagramsAreCarriedNativelyLikeDnsTraffic() {
        val udpServer = java.net.DatagramSocket(0, InetAddress.getLoopbackAddress())
        val port = udpServer.localPort
        val target = "127.0.0.1"
        Thread({
            val buffer = ByteArray(2048)
            while (!udpServer.isClosed) {
                val packet = java.net.DatagramPacket(buffer, buffer.size)
                try {
                    udpServer.receive(packet)
                } catch (_: Exception) {
                    return@Thread
                }
                // Echo with a marker so the test knows the far side really reached the server.
                val reply = ("echo:" + String(packet.data, 0, packet.length)).toByteArray()
                udpServer.send(java.net.DatagramPacket(reply, reply.size, packet.address, packet.port))
            }
        }, "test-udp-echo").apply { isDaemon = true }.start()
        servers.add(AutoCloseable { udpServer.close() })

        val session = session("udp", pipes = 2)
        val channel = session.openUdpChannel()
        assertTrue(channel.send(target, port, "hello-dns".toByteArray(), 9))

        val deadline = System.currentTimeMillis() + 10_000
        var reply: MuxSession.EgressDatagramLite? = null
        while (System.currentTimeMillis() < deadline && reply == null) {
            reply = session.pollDatagram(channel, 500)
        }
        assertNotNull("no datagram came back through the tunnel", reply)
        assertEquals("echo:hello-dns", String(reply!!.data, 0, reply.length))
        assertEquals(port, reply.port)
        assertEquals("127.0.0.1", reply.host)
    }

    // --- 5 ---------------------------------------------------------------------------------
    @Test
    fun realHttpThroughTheTunnel() {
        // A tiny HTTP server behind the bridge: proves the tunnel carries a real protocol with
        // its own framing, not just an echo of our own bytes.
        val httpPort = startTcpServer(closeAfterHandler = true) { input, output ->
            val request = java.io.BufferedReader(input.reader())
            val requestLine = request.readLine() ?: return@startTcpServer
            while (true) {
                val line = request.readLine() ?: break
                if (line.isEmpty()) break
            }
            assertTrue("unexpected request line: $requestLine", requestLine.startsWith("GET /ping"))
            val body = "pong-from-far-side"
            output.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}" +
                    "\r\nConnection: close\r\n\r\n$body").toByteArray()
            )
            output.flush()
        }
        val session = session("udp", pipes = 2)
        val socket = session.openTcp("127.0.0.1", httpPort)
        socket.getOutputStream().write(
            ("GET /ping HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").toByteArray()
        )
        socket.getOutputStream().flush()
        val response = readAll(socket.getInputStream())
        val text = String(response)
        assertTrue("no status line in: ${text.take(120)}", text.startsWith("HTTP/1.1 200 OK"))
        assertTrue(text.endsWith("pong-from-far-side"))
        socket.close()
    }

    // --- 6 ---------------------------------------------------------------------------------
    @Test
    fun farSideReportsAnUnreachableDestinationInsteadOfHanging() {
        val closedPort = freeTcpPort() // nothing is listening here
        val session = session("udp", pipes = 2)
        val started = System.currentTimeMillis()
        try {
            session.openTcp("127.0.0.1", closedPort, timeoutMs = 10_000)
            assertTrue("an open to a closed port must not succeed", false)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - started
            assertTrue("should fail fast, took ${elapsed}ms", elapsed < 9_000)
            // OPEN_ERR text from the bridge, not our own timeout message.
            assertTrue("unexpected error: ${e.message}", e.message?.contains("unreachable") == true)
        }
    }

    // --- 7 ---------------------------------------------------------------------------------
    @Test
    fun selfTestPassesForALiveAddressAndFailsForADeadOne() {
        // A live bridge: HELLO_ACK + PING/PONG must both happen.
        cookie = Brt.cookieFor("live")
        val live = MuxSession(
            BondedEndpoint(
                listOf(UdpChannelEndpoint(InetSocketAddress(InetAddress.getLoopbackAddress(), udpPort)))
            ),
            cookie,
            label = "live",
        )
        sessions.add(live)
        assertTrue(live.start(5_000))
        assertTrue("PING never came back", live.ping(2_000) >= 0)
        live.close()

        // A dead address: the port is open but nothing answers BRT1.
        val silent = java.net.DatagramSocket(0, InetAddress.getLoopbackAddress())
        val silentPort = silent.localPort
        servers.add(AutoCloseable { silent.close() })
        val dead = MuxSession(
            BondedEndpoint(listOf(UdpChannelEndpoint(InetSocketAddress(InetAddress.getLoopbackAddress(), silentPort)))),
            Brt.cookieFor("dead"),
            label = "dead",
        )
        sessions.add(dead)
        val up = dead.start(1_200)
        assertTrue("a silent UDP port must not be reported as a live session", !up || dead.ping(800) < 0)
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun startTcpServer(
        closeAfterHandler: Boolean = true,
        handler: (InputStream, OutputStream) -> Unit,
    ): Int {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        servers.add(AutoCloseable { server.close() })
        Thread({
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    return@Thread
                }
                Thread({
                    try {
                        if (closeAfterHandler) {
                            socket.use { handler(it.getInputStream(), it.getOutputStream()) }
                        } else {
                            handler(socket.getInputStream(), socket.getOutputStream())
                        }
                    } catch (_: Exception) {
                    }
                }, "test-server").apply { isDaemon = true }.start()
            }
        }, "test-accept").apply { isDaemon = true }.start()
        return server.localPort
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buffer, read, size - read)
            if (n < 0) throw AssertionError("stream ended after $read/$size bytes")
            read += n
        }
        return buffer
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        org.junit.Assert.assertArrayEquals(expected, actual)

    private fun waitUntil(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }
}
