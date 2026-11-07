package com.bigrocket.egress

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * End-to-end protocol tests for the static-IP egress: real loopback SOCKS5 and HTTP proxies,
 * real sockets, the production [Socks5Protocol]/[HttpProxyProtocol] code on the client side.
 *
 * These are the tests that would catch a regression in the bytes we put on the wire - the part
 * that cannot be checked by reading the UI, and the part where a silent mistake would mean
 * "traffic looks proxied but is not" (or simply stops working).
 */
class ProxyProtocolTest {

    private lateinit var target: ServerSocket
    private val servers = mutableListOf<AutoCloseable>()

    @Before
    fun setUp() {
        // A plain TCP "origin" server that answers every received byte with "OK:<byte>".
        target = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        servers += AutoCloseable { runCatching { target.close() } }
        Thread {
            while (!target.isClosed) {
                val socket = runCatching { target.accept() }.getOrNull() ?: break
                Thread {
                    runCatching {
                        val input = socket.getInputStream()
                        val out = socket.getOutputStream()
                        val buffer = ByteArray(64)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write("OK:${String(buffer, 0, n)}".toByteArray())
                            out.flush()
                        }
                    }
                    runCatching { socket.close() }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
    }

    // --- SOCKS5 ---------------------------------------------------------------------------

    @Test
    fun `socks5 connect without credentials tunnels bytes to the destination`() {
        val proxyPort = startSocks5Proxy(requireAuth = false)
        val proxy = StaticProxyParser.parse("socks5://127.0.0.1:$proxyPort")!!
        val socket = Socket("127.0.0.1", proxyPort)
        try {
            Socks5Protocol.connect(socket, proxy, "127.0.0.1", target.localPort)
            socket.getOutputStream().write("hello".toByteArray())
            socket.getOutputStream().flush()
            assertEquals("OK:hello", readN(socket.getInputStream(), 8))
        } finally {
            runCatching { socket.close() }
        }
    }

    @Test
    fun `socks5 connect authenticates with username and password`() {
        val proxyPort = startSocks5Proxy(requireAuth = true)
        val proxy = StaticProxyParser.parse("socks5://alice:secret@127.0.0.1:$proxyPort")!!
        val socket = Socket("127.0.0.1", proxyPort)
        try {
            Socks5Protocol.connect(socket, proxy, "127.0.0.1", target.localPort)
            socket.getOutputStream().write("abc".toByteArray())
            socket.getOutputStream().flush()
            assertEquals("OK:abc", readN(socket.getInputStream(), 6))
        } finally {
            runCatching { socket.close() }
        }
    }

    @Test
    fun `socks5 rejects a wrong password`() {
        val proxyPort = startSocks5Proxy(requireAuth = true)
        val proxy = StaticProxyParser.parse("socks5://alice:WRONG@127.0.0.1:$proxyPort")!!
        val socket = Socket("127.0.0.1", proxyPort)
        try {
            Socks5Protocol.connect(socket, proxy, "127.0.0.1", target.localPort)
            fail("expected auth failure")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().isNotEmpty())
        } finally {
            runCatching { socket.close() }
        }
    }

    @Test
    fun `socks5 surfaces a refused connect`() {
        val proxyPort = startSocks5Proxy(requireAuth = false)
        val proxy = StaticProxyParser.parse("socks5://127.0.0.1:$proxyPort")!!
        val socket = Socket("127.0.0.1", proxyPort)
        try {
            Socks5Protocol.connect(socket, proxy, "127.0.0.1", 1)
            fail("expected rejection")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("رد"))
        } finally {
            runCatching { socket.close() }
        }
    }

    // --- HTTP -----------------------------------------------------------------------------

    @Test
    fun `http connect tunnels bytes`() {
        val proxyPort = startHttpProxy()
        val proxy = StaticProxyParser.parse("http://127.0.0.1:$proxyPort")!!
        val socket = Socket("127.0.0.1", proxyPort)
        try {
            HttpProxyProtocol.connect(socket, proxy, "127.0.0.1", target.localPort)
            socket.getOutputStream().write("xy".toByteArray())
            socket.getOutputStream().flush()
            assertEquals("OK:xy", readN(socket.getInputStream(), 5))
        } finally {
            runCatching { socket.close() }
        }
    }

    @Test
    fun `http connect sends proxy authorization when credentials exist`() {
        val seen = java.util.concurrent.atomic.AtomicReference<String>()
        val proxyPort = startHttpProxy(onHeaders = { headers -> seen.set(headers) })
        val proxy = StaticProxyParser.parse("http://user:pw@127.0.0.1:$proxyPort")!!
        val socket = Socket("127.0.0.1", proxyPort)
        try {
            HttpProxyProtocol.connect(socket, proxy, "127.0.0.1", target.localPort)
        } finally {
            runCatching { socket.close() }
        }
        val expected = Base64.getEncoder().encodeToString("user:pw".toByteArray())
        assertTrue(seen.get().contains("Proxy-Authorization: Basic $expected"))
    }

    @Test
    fun `http body parsing handles a plain response`() {
        assertEquals("1.2.3.4\n", HttpProxyProtocol.extractBody("HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\n1.2.3.4\n"))
    }

    // --- UDP encapsulation ----------------------------------------------------------------

    @Test
    fun `socks5 udp datagram round trips`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val encoded = Socks5Protocol.encodeUdpDatagram("1.2.3.4", 53, payload)
        val decoded = Socks5Protocol.decodeUdpDatagram(encoded, encoded.size)!!
        assertEquals("1.2.3.4", decoded.host)
        assertEquals(53, decoded.port)
        assertTrue(payload.contentEquals(decoded.payload))
    }

    @Test
    fun `socks5 udp datagram carries domain names`() {
        val payload = "q".toByteArray()
        val encoded = Socks5Protocol.encodeUdpDatagram("example.com", 443, payload)
        val decoded = Socks5Protocol.decodeUdpDatagram(encoded, encoded.size)!!
        assertEquals("example.com", decoded.host)
        assertEquals(443, decoded.port)
        assertTrue(payload.contentEquals(decoded.payload))
    }

    @Test
    fun `socks5 udp associate handshake returns the relay endpoint`() {
        val relaySocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        servers += AutoCloseable { runCatching { relaySocket.close() } }
        val proxyPort = startSocks5Proxy(requireAuth = false, udpRelayPort = relaySocket.localPort)
        val proxy = StaticProxyParser.parse("socks5://127.0.0.1:$proxyPort")!!
        val socket = Socket("127.0.0.1", proxyPort)
        try {
            val relay = Socks5Protocol.openUdpAssociation(
                socket,
                proxy,
                InetAddress.getByName("127.0.0.1"),
            )
            assertEquals(relaySocket.localPort, relay.port)
            assertNotNull(relay.address)
        } finally {
            runCatching { socket.close() }
        }
    }

    @Test
    fun `egress ip extraction reads a body`() {
        assertEquals("203.0.113.9", EgressIpChecker.extractIp("HTTP/1.1 200 OK\r\n\r\n203.0.113.9\n"))
        assertEquals("203.0.113.9", EgressIpChecker.extractIp("HTTP/1.1 200 OK\r\n\r\n\n203.0.113.9"))
        assertEquals(null, EgressIpChecker.extractIp("HTTP/1.1 200 OK\r\n\r\n"))
    }

    // --- auto port detection ---------------------------------------------------------------

    @Test
    fun `detector finds a socks5 proxy listening on an unusual port`() {
        val proxyPort = startSocks5Proxy(requireAuth = false)
        val found = ProxyPortDetector.probe(InetAddress.getByName("127.0.0.1"), proxyPort)
        assertNotNull("a real SOCKS5 greeting must be recognised", found)
        assertEquals(proxyPort, found!!.port)
        assertEquals(StaticProxyKind.SOCKS5, found.kind)
    }

    @Test
    fun `detector finds an http proxy`() {
        val proxyPort = startHttpProxy()
        val found = ProxyPortDetector.probe(InetAddress.getByName("127.0.0.1"), proxyPort)
        assertNotNull(found)
        assertEquals(StaticProxyKind.HTTP, found!!.kind)
    }

    @Test
    fun `detector reports nothing when the port only accepts tcp`() {
        // The origin server accepts TCP and reads, but never speaks a proxy protocol.
        val found = ProxyPortDetector.probe(
            InetAddress.getByName("127.0.0.1"),
            target.localPort,
            connectTimeoutMs = 500,
            readTimeoutMs = 500,
        )
        assertEquals(null, found)
    }

    @Test
    fun `detect picks the working port out of a candidate list`() {
        val proxyPort = startSocks5Proxy(requireAuth = false)
        // A closed port first, then the real one - exactly the "bare IP" scenario.
        val closed = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).also { it.close() }.localPort
        val (found, attempts) = ProxyPortDetector.detect(
            host = "127.0.0.1",
            candidates = listOf(closed, proxyPort),
            connectTimeoutMs = 800,
            readTimeoutMs = 800,
            totalTimeoutMs = 6_000,
        )
        assertNotNull(found)
        assertEquals(proxyPort, found!!.port)
        assertTrue(attempts.none { it.port == proxyPort })
    }

    @Test
    fun `detect failure message names the host and the ports tried`() {
        val message = ProxyPortDetector.describeFailure(
            "95.85.252.115",
            listOf(ProxyPortDetector.Attempt(1080, "x"), ProxyPortDetector.Attempt(3128, "x")),
        )
        assertTrue(message.contains("95.85.252.115"))
        assertTrue(message.contains("1080"))
        assertTrue(message.contains("3128"))
    }

    // --- fake servers ---------------------------------------------------------------------

    /** Minimal SOCKS5 server: no-auth and/or user/pass, CONNECT + UDP ASSOCIATE. */
    private fun startSocks5Proxy(requireAuth: Boolean, udpRelayPort: Int = 0): Int {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        servers += AutoCloseable { runCatching { server.close() } }
        Thread {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    runCatching { serveSocks5(client, requireAuth, udpRelayPort) }
                        .also { runCatching { client.close() } }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return server.localPort
    }

    private fun serveSocks5(client: Socket, requireAuth: Boolean, udpRelayPort: Int) {
        val input = client.getInputStream()
        val out = client.getOutputStream()
        val greeting = ByteArray(2)
        Socks5Protocol.readFully(input, greeting, "greeting")
        assertEquals(0x05, greeting[0].toInt())
        val methods = ByteArray(greeting[1].toInt() and 0xFF)
        Socks5Protocol.readFully(input, methods, "methods")
        if (requireAuth) {
            assertTrue(methods.contains(0x02))
            out.write(byteArrayOf(0x05, 0x02))
            out.flush()
            val ver = ByteArray(1)
            Socks5Protocol.readFully(input, ver, "auth ver")
            val userLen = ByteArray(1)
            Socks5Protocol.readFully(input, userLen, "user len")
            val user = ByteArray(userLen[0].toInt() and 0xFF)
            Socks5Protocol.readFully(input, user, "user")
            val passLen = ByteArray(1)
            Socks5Protocol.readFully(input, passLen, "pass len")
            val pass = ByteArray(passLen[0].toInt() and 0xFF)
            Socks5Protocol.readFully(input, pass, "pass")
            val ok = String(user) == "alice" && String(pass) == "secret"
            out.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01))
            out.flush()
            if (!ok) throw IOException("bad credentials")
        } else {
            out.write(byteArrayOf(0x05, 0x00))
            out.flush()
        }

        val head = ByteArray(4)
        Socks5Protocol.readFully(input, head, "request head")
        val command = head[1].toInt() and 0xFF
        var host: String
        when (head[3].toInt() and 0xFF) {
            0x01 -> {
                val buf = ByteArray(4)
                Socks5Protocol.readFully(input, buf, "ipv4")
                host = InetAddress.getByAddress(buf).hostAddress!!
            }
            0x03 -> {
                val len = ByteArray(1)
                Socks5Protocol.readFully(input, len, "domain len")
                val buf = ByteArray(len[0].toInt() and 0xFF)
                Socks5Protocol.readFully(input, buf, "domain")
                host = String(buf)
            }
            0x04 -> {
                val buf = ByteArray(16)
                Socks5Protocol.readFully(input, buf, "ipv6")
                host = InetAddress.getByAddress(buf).hostAddress!!
            }
            else -> throw IOException("bad atyp")
        }
        val portBuf = ByteArray(2)
        Socks5Protocol.readFully(input, portBuf, "port")
        val port = ((portBuf[0].toInt() and 0xFF) shl 8) or (portBuf[1].toInt() and 0xFF)

        if (command == 0x03) {
            val relayPort = if (udpRelayPort != 0) udpRelayPort else 9
            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1) +
                byteArrayOf((relayPort ushr 8).toByte(), relayPort.toByte()))
            out.flush()
            Thread.sleep(200)
            return
        }

        val upstream = runCatching { Socket(host, port) }.getOrNull()
        if (upstream == null) {
            out.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            out.flush()
            return
        }
        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
        out.flush()
        pump(client, upstream)
    }

    private fun startHttpProxy(onHeaders: (String) -> Unit = {}): Int {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        servers += AutoCloseable { runCatching { server.close() } }
        Thread {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    runCatching {
                        val input = client.getInputStream()
                        val out = client.getOutputStream()
                        val headers = StringBuilder()
                        var line: String
                        while (true) {
                            line = readHttpLine(input) ?: break
                            if (line.isEmpty()) break
                            headers.append(line).append("\r\n")
                        }
                        onHeaders(headers.toString())
                        val requestLine = headers.lineSequence().first()
                        val targetSpec = requestLine.split(' ')[1]
                        val hostPort = targetSpec.removePrefix("CONNECT ").split(':')
                        val upstream = Socket(hostPort[0], hostPort[1].toInt())
                        out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                        out.flush()
                        pump(client, upstream)
                    }.also { runCatching { client.close() } }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return server.localPort
    }

    private fun readHttpLine(input: InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString("ISO-8859-1")
    }

    private fun pump(a: Socket, b: Socket) {
        val done = AtomicBoolean(false)
        fun copy(from: Socket, to: Socket) {
            try {
                val buffer = ByteArray(4096)
                val input: InputStream = from.getInputStream()
                val out: OutputStream = to.getOutputStream()
                while (!done.get()) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    out.flush()
                }
            } catch (_: Exception) {
            } finally {
                done.set(true)
                runCatching { a.close() }
                runCatching { b.close() }
            }
        }
        val t = Thread { copy(a, b) }.apply { isDaemon = true }
        t.start()
        copy(b, a)
    }

    private fun readN(input: InputStream, count: Int): String {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) break
            read += n
        }
        return String(buffer, 0, read)
    }
}
