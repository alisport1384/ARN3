package com.bigrocket.tunnel

import bridgesrc.BridgeServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
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

/**
 * The DoH carrier: frames inside RFC 8484 `application/dns-message` bodies.
 *
 * This was listed as "not tested" in the previous report because no TLS terminator was stood up.
 * The carrier's own logic - POST a frame, drain the frames the far side queued, split
 * `[len:2][frame]...` - does not need TLS to be *proven*, only an HTTP hop. So this suite runs the
 * real bridge with `--doh-port` behind a real (plain-HTTP) forwarder, and pushes a real flow
 * through it. What is still not covered here is the certificate/TLS-terminator hop itself; that is
 * a deployment step (nginx/caddy `proxy_pass`), not code.
 *
 * `OkHttpDohTransport` is exercised too, against the same forwarder, so the okhttp wiring (media
 * type, POST body, response reading) is real rather than mocked.
 */
class DohCarrierTest {

    private lateinit var bridge: BridgeServer
    private var dohPort = 0
    private var forwarderPort = 0
    private val servers = ArrayList<AutoCloseable>()
    private var session: MuxSession? = null

    @Before
    fun start() {
        AppLog.useAppLogger { tag, message -> println("[$tag] $message") }
        dohPort = ServerSocket(0).use { it.localPort }
        bridge = BridgeServer(udpPort = 0, tcpPort = 0, dohPort = dohPort, initialCookie = 0, anyCookie = true)
        Thread({ bridge.run() }, "test-bridge-doh").apply { isDaemon = true }.start()
        waitUntil("doh carrier listening", 5_000) {
            java.net.Socket().use { probe ->
                runCatching { probe.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), dohPort), 200) }
                    .isSuccess
            }
        }
        // A TLS terminator stands in front of the bridge in production (`nginx`/`caddy`
        // proxy_pass http://127.0.0.1:<doh-port>). This forwarder is the same hop without TLS,
        // which is all the carrier logic needs to be exercised end to end.
        forwarderPort = startHttpForwarder(dohPort)
    }

    @After
    fun stop() {
        session?.let { runCatching { it.close() } }
        servers.forEach { runCatching { it.close() } }
        runCatching { bridge.stop() }
    }

    @Test
    fun framesTravelInsideDnsMessageBodiesAndCarryARealFlow() {
        val echo = startTcpServer()
        val cookie = Brt.cookieFor("doh-test")
        val transport = OkHttpDohTransport(
            url = "http://127.0.0.1:$forwarderPort/dns-query",
            socketFactory = javax.net.SocketFactory.getDefault(),
        )
        val carrier = DohChannelEndpoint(transport, label = "doh://forwarder/dns-query")
        val session = MuxSession(BondedEndpoint(listOf(carrier)), cookie, label = "doh")
        this.session = session
        assertTrue("DoH carrier never came up: ${session.downReason()}", session.start(8_000))
        assertTrue("no PONG through the DoH carrier", session.ping(4_000) >= 0)

        // A real TCP flow through the same DoH-only channel.
        val socket = session.openTcp("127.0.0.1", echo, timeoutMs = 15_000)
        val message = "doh-carried-flow".toByteArray()
        socket.getOutputStream().write(message)
        socket.getOutputStream().flush()
        val reply = ByteArray(message.size)
        var read = 0
        while (read < reply.size) {
            val n = socket.getInputStream().read(reply, read, reply.size - read)
            if (n < 0) break
            read += n
        }
        assertArrayEquals(message, reply)
        socket.close()
        assertTrue("nothing came back through DoH", session.totalFramesIn.get() > 2)
    }

    // --- helpers ---------------------------------------------------------------------------

    /** Minimal HTTP/1.1 forwarder: POST body -> bridge's DoH port, response body <- bridge. */
    private fun startHttpForwarder(targetPort: Int): Int {
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
                    client.use { socket ->
                        val input = socket.getInputStream()
                        val header = StringBuilder()
                        while (true) {
                            val b = input.read()
                            if (b < 0) return@use
                            header.append(b.toChar())
                            if (header.endsWith("\r\n\r\n")) break
                        }
                        val length = Regex("(?i)content-length:\\s*(\\d+)")
                            .find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val body = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val n = input.read(body, read, length - read)
                            if (n < 0) return@use
                            read += n
                        }
                        // Forward as a real DoH request.
                        val upstream = java.net.Socket(InetAddress.getLoopbackAddress(), targetPort)
                        try {
                            upstream.soTimeout = 10_000
                            val out = upstream.getOutputStream()
                            out.write(
                                ("POST /dns-query HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                                    "Content-Type: application/dns-message\r\n" +
                                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                                    .toByteArray()
                            )
                            out.write(body)
                            out.flush()
                            val upstreamInput = upstream.getInputStream()
                            val responseHeader = StringBuilder()
                            while (true) {
                                val b = upstreamInput.read()
                                if (b < 0) break
                                responseHeader.append(b.toChar())
                                if (responseHeader.endsWith("\r\n\r\n")) break
                            }
                            val responseLength = Regex("(?i)content-length:\\s*(\\d+)")
                                .find(responseHeader.toString())?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            val responseBody = ByteArray(responseLength)
                            var got = 0
                            while (got < responseLength) {
                                val n = upstreamInput.read(responseBody, got, responseLength - got)
                                if (n < 0) break
                                got += n
                            }
                            val reply = socket.getOutputStream()
                            reply.write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\n" +
                                    "Content-Length: $got\r\nConnection: close\r\n\r\n").toByteArray()
                            )
                            reply.write(responseBody, 0, got)
                            reply.flush()
                        } finally {
                            runCatching { upstream.close() }
                        }
                    }
                }, "test-doh-fwd").apply { isDaemon = true }.start()
            }
        }, "test-doh-accept").apply { isDaemon = true }.start()
        return server.localPort
    }

    private fun startTcpServer(): Int {
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
                        socket.use { conn ->
                            val input: InputStream = conn.getInputStream()
                            val output: OutputStream = conn.getOutputStream()
                            val buffer = ByteArray(1024)
                            val n = input.read(buffer)
                            if (n > 0) {
                                output.write(buffer, 0, n)
                                output.flush()
                            }
                            Thread.sleep(200)
                        }
                    } catch (_: Exception) {
                    }
                }, "test-doh-echo").apply { isDaemon = true }.start()
            }
        }, "test-doh-echo-accept").apply { isDaemon = true }.start()
        return server.localPort
    }

    private fun waitUntil(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }
}
