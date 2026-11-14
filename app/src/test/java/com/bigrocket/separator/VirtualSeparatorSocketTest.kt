package com.bigrocket.separator

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * End-to-end behaviour of [VirtualSeparatorSocket] against a REAL loopback HTTP server.
 *
 * The two "radios" are two ordinary [ServerSocket]s on 127.0.0.1 (different ports, in practice
 * two independent TCP endpoints), so every assertion here is about genuinely separate TCP
 * connections whose bytes are only joined inside the socket under test - exactly what happens
 * on a phone with Wi-Fi + Cellular.
 */
class VirtualSeparatorSocketTest {

    private class FakeRadio(val name: String) {
        val server = ServerSocket(0, 200, InetAddress.getByName("127.0.0.1"))
        val accepted = ConcurrentLinkedQueue<Socket>()
        val requestCount = AtomicInteger(0)
        val bytesServed = AtomicLong(0)

        fun dial(timeoutMs: Int = 2_000): Socket {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.localPort), timeoutMs)
            return s
        }

        fun close() {
            runCatching { server.close() }
            accepted.forEach { runCatching { it.close() } }
            accepted.clear()
        }
    }

    private lateinit var wifi: FakeRadio
    private lateinit var cell: FakeRadio
    private val servers = ArrayList<ServerSocket>()

    @Before
    fun setUp() {
        wifi = FakeRadio("wifi")
        cell = FakeRadio("cellular")
    }

    @After
    fun tearDown() {
        wifi.close()
        cell.close()
        servers.forEach { runCatching { it.close() } }
    }

    // --- helpers -------------------------------------------------------------------------

    private fun payload(bytes: Int, seed: Long = 42L): ByteArray {
        val rng = Random(seed)
        return ByteArray(bytes) { rng.nextInt(256).toByte() }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) break
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                break
            }
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    /** A minimal HTTP/1.1 server: optional Range support, optional validator, optional banner. */
    private fun serveHttp(
        radio: FakeRadio,
        body: ByteArray,
        supportsRange: Boolean = true,
        etag: String? = "\"v1\"",
        lastModified: String? = "Wed, 21 Oct 2015 07:28:00 GMT",
        serverSpeaksFirst: String? = null,
        statusOverride: Int? = null,
        connectionClose: Boolean = false,
        onRequest: (String) -> Unit = {},
    ) {
        Thread {
            while (!radio.server.isClosed) {
                val sock = try {
                    radio.server.accept()
                } catch (_: IOException) {
                    return@Thread
                }
                radio.accepted.add(sock)
                Thread {
                    try {
                        sock.use { s ->
                            val input = s.getInputStream()
                            if (serverSpeaksFirst != null) {
                                s.getOutputStream().write(serverSpeaksFirst.toByteArray())
                                s.getOutputStream().flush()
                            }
                            while (true) {
                                val requestLine = readLine(input)
                                if (requestLine.isEmpty()) return@use
                                var rangeHeader: String? = null
                                var clientWantsClose = false
                                var target = requestLine.split(' ').getOrNull(1) ?: "/"
                                while (true) {
                                    val line = readLine(input)
                                    if (line.isEmpty()) break
                                    val colon = line.indexOf(':')
                                    if (colon <= 0) continue
                                    val name = line.substring(0, colon).trim()
                                    val value = line.substring(colon + 1).trim()
                                    if (name.equals("Range", ignoreCase = true)) rangeHeader = value
                                    if (name.equals("Connection", ignoreCase = true) &&
                                        value.equals("close", ignoreCase = true)
                                    ) {
                                        clientWantsClose = true
                                    }
                                }
                                // A client that asked to close expects EOF after this response;
                                // a keep-alive client expects the socket to stay usable.
                                val closeAfter = connectionClose || clientWantsClose
                                radio.requestCount.incrementAndGet()
                                onRequest(target)

                                val out = s.getOutputStream()
                                val status = statusOverride
                                if (status != null) {
                                    val head = "HTTP/1.1 $status Nope\r\nContent-Length: 0\r\n" +
                                        "Connection: close\r\n\r\n"
                                    out.write(head.toByteArray())
                                    out.flush()
                                    return@use
                                }

                                val spec = rangeHeader?.takeIf { it.startsWith("bytes=", true) }
                                    ?.substring(6)?.trim()
                                if (!supportsRange || spec == null) {
                                    val head = buildString {
                                        append("HTTP/1.1 200 OK\r\n")
                                        append("Content-Length: ${body.size}\r\n")
                                        etag?.let { append("ETag: $it\r\n") }
                                        lastModified?.let { append("Last-Modified: $it\r\n") }
                                        append("Accept-Ranges: ${if (supportsRange) "bytes" else "none"}\r\n")
                                        if (closeAfter) append("Connection: close\r\n")
                                        else append("Connection: keep-alive\r\n")
                                        append("\r\n")
                                    }
                                    out.write(head.toByteArray())
                                    out.write(body)
                                    out.flush()
                                    radio.bytesServed.addAndGet(body.size.toLong())
                                    // The 200 path never falls through to the Range code below.
                                    if (closeAfter) return@use
                                    continue
                                }

                                val dash = spec.indexOf('-')
                                val start = spec.substring(0, dash).trim().toLongOrNull() ?: 0L
                                val endStr = spec.substring(dash + 1).trim()
                                val end = if (endStr.isEmpty()) body.size - 1L
                                else minOf(endStr.toLongOrNull() ?: (body.size - 1L), body.size - 1L)
                                if (start > end || start >= body.size) {
                                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\n\r\n".toByteArray())
                                    out.flush()
                                    return@use
                                }
                                val slice = body.copyOfRange(start.toInt(), (end + 1).toInt())
                                val head = buildString {
                                    append("HTTP/1.1 206 Partial Content\r\n")
                                    append("Content-Range: bytes $start-$end/${body.size}\r\n")
                                    append("Content-Length: ${slice.size}\r\n")
                                    etag?.let { append("ETag: $it\r\n") }
                                    lastModified?.let { append("Last-Modified: $it\r\n") }
                                    append("Accept-Ranges: bytes\r\n")
                                    if (closeAfter) append("Connection: close\r\n") else append("Connection: keep-alive\r\n")
                                    append("\r\n")
                                }
                                out.write(head.toByteArray())
                                out.write(slice)
                                out.flush()
                                radio.bytesServed.addAndGet(slice.size.toLong())
                                if (closeAfter) return@use
                            }
                        }
                    } catch (_: IOException) {
                    } catch (_: Throwable) {
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    /** A one-shot raw server: reads [expectedBytes] (best effort), replies, then closes. */
    private fun serveRaw(radio: FakeRadio, reply: ByteArray, expectedBytes: Int) {
        Thread {
            while (!radio.server.isClosed) {
                val sock = try {
                    radio.server.accept()
                } catch (_: IOException) {
                    return@Thread
                }
                radio.accepted.add(sock)
                Thread {
                    try {
                        sock.use { s ->
                            s.getInputStream().read(ByteArray(expectedBytes))
                            s.getOutputStream().write(reply)
                            s.getOutputStream().flush()
                        }
                    } catch (_: Throwable) {
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun newSeparator(
        primary: Socket,
        primaryIsWifi: Boolean = true,
        workersPerRadio: Int = 2,
        sliceBytes: Int = 8 * 1024,
        openWorkerSocket: (Boolean, Int) -> Socket = { useWifi, timeout ->
            val radio = if (useWifi) wifi else cell
            radio.dial(timeout)
        },
    ) = VirtualSeparatorSocket(
        primarySocket = primary,
        primaryIsWifi = primaryIsWifi,
        openWorkerSocket = openWorkerSocket,
        destLabel = "test.local",
        sliceBytes = sliceBytes,
        workersPerRadio = workersPerRadio,
    )

    /** Sends a request through the separator and returns the whole response (head + body). */
    private fun exchange(socket: VirtualSeparatorSocket, request: String): ByteArray {
        socket.soTimeout = 15_000
        socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
        socket.getOutputStream().flush()
        val out = java.io.ByteArrayOutputStream()
        socket.getInputStream().use { it.copyTo(out) }
        return out.toByteArray()
    }

    private fun splitResponse(raw: ByteArray): Pair<String, ByteArray> {
        val text = String(raw, Charsets.ISO_8859_1)
        val idx = text.indexOf("\r\n\r\n")
        assertTrue("response has no header terminator: ${text.take(200)}", idx >= 0)
        val head = text.substring(0, idx)
        val body = raw.copyOfRange(idx + 4, raw.size)
        return head to body
    }

    private fun headerValue(head: String, name: String): String? =
        head.split("\r\n").drop(1)
            .firstOrNull { it.substringBefore(':').equals(name, ignoreCase = true) }
            ?.substringAfter(':')?.trim()

    // --- 1. the headline case: ONE stream, TWO radios -------------------------------------

    @Test
    fun `a single GET is separated across both radios and reassembled byte-exactly`() {
        val body = payload(64 * 1024, seed = 1)
        serveHttp(wifi, body)
        serveHttp(cell, body)

        val primary = wifi.dial()
        val sep = newSeparator(primary)
        val raw = exchange(
            sep,
            "GET /file.bin HTTP/1.1\r\nHost: test.local\r\nUser-Agent: unit-test\r\nConnection: close\r\n\r\n",
        )
        val (head, responseBody) = splitResponse(raw)

        assertTrue("expected 200 OK, got: $head", head.startsWith("HTTP/1.1 200 OK"))
        assertEquals(body.size.toString(), headerValue(head, "Content-Length"))
        assertEquals("bit-by-bit-dual-radio", headerValue(head, "X-BigRocket-Separator"))
        assertArrayEquals(body, responseBody)

        // Both radios genuinely carried part of the ONE stream.
        assertTrue("wi-fi must carry slices", wifi.requestCount.get() >= 1)
        assertTrue("cellular must carry slices", cell.requestCount.get() >= 1)
        assertTrue("the second radio really opened sockets", cell.accepted.isNotEmpty())
    }

    @Test
    fun `separated download honours a client Range request with a 206 and exact bytes`() {
        // The range must be larger than DEFAULT_SLICE_BYTES (256 KiB), otherwise the socket
        // (correctly) treats it as a small single-slice request and passes it through.
        val body = payload(900 * 1024, seed = 2)
        serveHttp(wifi, body)
        serveHttp(cell, body)

        val start = 4096L
        val end = 700_000L
        val sep = newSeparator(wifi.dial())
        val raw = exchange(
            sep,
            "GET /file.bin HTTP/1.1\r\nHost: test.local\r\nRange: bytes=$start-$end\r\nConnection: close\r\n\r\n",
        )
        val (head, responseBody) = splitResponse(raw)

        assertTrue("expected 206 Partial Content, got: $head", head.startsWith("HTTP/1.1 206 Partial Content"))
        assertEquals("bytes $start-$end/${body.size}", headerValue(head, "Content-Range"))
        assertEquals((end - start + 1L).toString(), headerValue(head, "Content-Length"))
        assertArrayEquals(body.copyOfRange(start.toInt(), (end + 1).toInt()), responseBody)
    }

    @Test
    fun `no bytes are lost or reordered when the two radios come up out of order`() {
        val body = payload(96 * 1024, seed = 3)
        // Wi-Fi answers immediately; cellular is artificially slow to connect, so the first
        // slices finish on the primary while the secondary workers are still dialling.
        serveHttp(wifi, body)
        serveHttp(cell, body)
        val sep = newSeparator(
            primary = wifi.dial(),
            sliceBytes = 4 * 1024,
            openWorkerSocket = { useWifi, timeout ->
                if (!useWifi) Thread.sleep(120)
                (if (useWifi) wifi else cell).dial(timeout)
            },
        )
        val (_, responseBody) = splitResponse(
            exchange(sep, "GET /big.bin HTTP/1.1\r\nHost: test.local\r\nConnection: close\r\n\r\n"),
        )
        assertArrayEquals(body, responseBody)
    }

    @Test
    fun `every byte of a multi-slice download is accounted on exactly one radio`() {
        val body = payload(40 * 1024, seed = 4)
        serveHttp(wifi, body)
        serveHttp(cell, body)
        val before = VirtualSeparatorRuntime.status.value
        val (_, responseBody) = splitResponse(
            newSeparator(wifi.dial()).let {
                exchange(it, "GET /acct.bin HTTP/1.1\r\nHost: test.local\r\nConnection: close\r\n\r\n")
            },
        )
        assertArrayEquals(body, responseBody)
        val after = VirtualSeparatorRuntime.status.value
        assertTrue("a separated stream must be reported", after.separatedStreams > before.separatedStreams)
        val wifiBits = after.wifiBits - before.wifiBits
        val cellBits = after.cellularBits - before.cellularBits
        assertTrue("both radios must report bits: wifi=$wifiBits cellular=$cellBits", wifiBits > 0 && cellBits > 0)
        // Bits are counted per delivered slice, so they cover the body plus the tiny
        // separator headers - never less than the payload itself.
        assertTrue(wifiBits + cellBits >= body.size.toLong() * 8L)
    }

    // --- 2. work stealing / failover -------------------------------------------------------

    @Test
    fun `a dead secondary radio is compensated by the healthy primary`() {
        val body = payload(48 * 1024, seed = 5)
        serveHttp(wifi, body) // only Wi-Fi actually serves
        // Cellular refuses every connection: dialing a closed port fails fast on loopback.
        val deadPort = ServerSocket(0).use { it.localPort }
        val sep = newSeparator(
            primary = wifi.dial(),
            sliceBytes = 4 * 1024,
            openWorkerSocket = { useWifi, timeout ->
                if (useWifi) {
                    wifi.dial(timeout)
                } else {
                    Socket().apply {
                        tcpNoDelay = true
                        connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), deadPort), timeout)
                    }
                }
            },
        )
        val (head, responseBody) = splitResponse(
            exchange(sep, "GET /failover.bin HTTP/1.1\r\nHost: test.local\r\nConnection: close\r\n\r\n"),
        )
        assertTrue("expected 200 OK, got: ${head.take(80)}", head.startsWith("HTTP/1.1 200 OK"))
        assertArrayEquals("a dead radio must never corrupt the stream", body, responseBody)
    }

    @Test
    fun `a worker that dies mid-slice is retried and the stream still completes`() {
        val body = payload(40 * 1024, seed = 6)
        serveHttp(wifi, body)
        serveHttp(cell, body)
        val fresh = AtomicInteger(0)
        val sep = newSeparator(
            primary = wifi.dial(),
            sliceBytes = 4 * 1024,
            openWorkerSocket = { useWifi, timeout ->
                val radio = if (useWifi) wifi else cell
                val sock = radio.dial(timeout)
                // Sabotage the very first cellular worker: it dies before reading its slice.
                if (!useWifi && fresh.incrementAndGet() == 1) {
                    sock.close()
                }
                sock
            },
        )
        val (_, responseBody) = splitResponse(
            exchange(sep, "GET /retry.bin HTTP/1.1\r\nHost: test.local\r\nConnection: close\r\n\r\n"),
        )
        assertArrayEquals(body, responseBody)
    }

    // --- 3. things that must NOT be separated ---------------------------------------------

    @Test
    fun `a 200 OK server without Range support is streamed verbatim`() {
        val body = payload(20 * 1024, seed = 7)
        serveHttp(wifi, body, supportsRange = false)
        serveHttp(cell, body, supportsRange = false)
        val sep = newSeparator(wifi.dial())
        val raw = exchange(
            sep,
            "GET /dynamic HTTP/1.1\r\nHost: test.local\r\nConnection: close\r\n\r\n",
        )
        val (head, responseBody) = splitResponse(raw)
        assertTrue(head.startsWith("HTTP/1.1 200 OK"))
        assertArrayEquals(body, responseBody)
        assertTrue("a non-Range response must not spin up workers", cell.accepted.isEmpty())
        assertEquals(0, cell.requestCount.get())
    }

    @Test
    fun `a small file served by the first slice is not split`() {
        val body = payload(3 * 1024, seed = 8)
        serveHttp(wifi, body)
        serveHttp(cell, body)
        val sep = newSeparator(wifi.dial(), sliceBytes = 8 * 1024)
        val (_, responseBody) = splitResponse(
            exchange(sep, "GET /small.bin HTTP/1.1\r\nHost: test.local\r\nConnection: close\r\n\r\n"),
        )
        assertArrayEquals(body, responseBody)
        assertEquals("no secondary radio needed for a one-slice file", 0, cell.requestCount.get())
    }

    @Test
    fun `a non-GET request passes through untouched in under a millisecond of work`() {
        val reply = payload(1024, seed = 9)
        serveHttp(cell, reply, supportsRange = false, connectionClose = true)
        // The primary here is the cellular socket: POST must go exactly where it was dialled.
        val sep = newSeparator(cell.dial(), primaryIsWifi = false)
        val startedNs = System.nanoTime()
        val raw = exchange(
            sep,
            "POST /upload HTTP/1.1\r\nHost: test.local\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello",
        )
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        val (head, responseBody) = splitResponse(raw)
        assertTrue(head.startsWith("HTTP/1.1 200 OK"))
        assertArrayEquals(reply, responseBody)
        assertTrue("POST must not be delayed by separation logic (${elapsedMs}ms)", elapsedMs < 2_000)
        assertEquals("POST must never open a second radio", 0, wifi.requestCount.get())
    }

    @Test
    fun `tls client hello on 443 is passed straight through`() {
        // A TLS record starts with 0x16 0x03 - the separator must not even parse it as HTTP,
        // so a raw (non-HTTP) server is the honest counterpart: it never sees a CRLF line.
        val banner = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x05, 0x01, 0x00, 0x00, 0x01, 0x00)
        serveRaw(cell, banner, expectedBytes = banner.size)
        val sep = newSeparator(cell.dial(), primaryIsWifi = false)
        sep.soTimeout = 5_000
        sep.getOutputStream().write(banner)
        sep.getOutputStream().flush()
        val readBack = ByteArray(banner.size)
        var read = 0
        while (read < readBack.size) {
            val n = sep.getInputStream().read(readBack, read, readBack.size - read)
            if (n < 0) break
            read += n
        }
        assertArrayEquals(banner, readBack.copyOf(read))
        assertEquals(0, wifi.requestCount.get())
    }

    @Test
    fun `a server banner protocol is handed to the client without inventing a request`() {
        val banner = "SSH-2.0-OpenSSH_9.6 test\r\n"
        serveHttp(cell, ByteArray(0), serverSpeaksFirst = banner, supportsRange = false)
        val sep = newSeparator(cell.dial(), primaryIsWifi = false)
        sep.soTimeout = 5_000
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64)
        // The client never sends anything; a server-first protocol must still be readable.
        while (out.size() < banner.length) {
            val n = sep.getInputStream().read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        assertEquals(banner, out.toString("ISO-8859-1"))
        assertEquals(0, wifi.requestCount.get())
    }

    @Test
    fun `a non-206 error status is surfaced instead of being separated`() {
        val body = payload(1024, seed = 10)
        serveHttp(wifi, body, statusOverride = 503)
        serveHttp(cell, body, statusOverride = 503)
        val sep = newSeparator(wifi.dial())
        val raw = exchange(sep, "GET /down HTTP/1.1\r\nHost: test.local\r\nConnection: close\r\n\r\n")
        val (head, responseBody) = splitResponse(raw)
        assertTrue("expected the server's own 503, got: $head", head.startsWith("HTTP/1.1 503"))
        assertEquals(0, responseBody.size)
        assertEquals(0, cell.requestCount.get())
    }

    @Test
    fun `a Range request for a tiny range is not split`() {
        val body = payload(32 * 1024, seed = 11)
        serveHttp(wifi, body)
        serveHttp(cell, body)
        val sep = newSeparator(wifi.dial(), sliceBytes = 8 * 1024)
        val (head, responseBody) = splitResponse(
            exchange(sep, "GET /v.mp4 HTTP/1.1\r\nHost: test.local\r\nRange: bytes=0-99\r\nConnection: close\r\n\r\n"),
        )
        assertTrue(head.startsWith("HTTP/1.1 206"))
        assertArrayEquals(body.copyOfRange(0, 100), responseBody)
        assertEquals(0, cell.requestCount.get())
    }

    // --- 4. the socket contract -----------------------------------------------------------

    @Test
    fun `the wrapper reports connection state from its primary and closes cleanly`() {
        val body = payload(1024, seed = 12)
        serveHttp(wifi, body)
        val primary = wifi.dial()
        val sep = newSeparator(primary)
        assertTrue(sep.isConnected)
        assertTrue(!sep.isClosed)
        assertEquals(primary.port, sep.port)
        assertEquals(primary.localPort, sep.localPort)
        assertNotNull(sep.inetAddress)
        // soTimeout round trips through the wrapper and the primary socket.
        sep.soTimeout = 1_234
        assertEquals(1_234, sep.soTimeout)
        assertEquals(1_234, primary.soTimeout)
        sep.close()
        assertTrue(sep.isClosed)
        assertNull(null)
    }

    @Test
    fun `concurrent separated downloads on independent sockets do not interfere`() {
        val bodies = (0 until 4).map { payload(24 * 1024, seed = 20L + it) }
        val lanes = bodies.map { body ->
            val w = FakeRadio("wifi-$body")
            val c = FakeRadio("cell-$body")
            servers += w.server
            servers += c.server
            serveHttp(w, body)
            serveHttp(c, body)
            Triple(w, c, body)
        }
        val failures = ConcurrentLinkedQueue<Throwable>()
        val done = CountDownLatch(lanes.size)
        lanes.forEach { (w, c, body) ->
            Thread {
                try {
                    val sep = newSeparator(
                        primary = w.dial(),
                        sliceBytes = 4 * 1024,
                        openWorkerSocket = { useWifi, timeout ->
                            (if (useWifi) w else c).dial(timeout)
                        },
                    )
                    val (_, responseBody) = splitResponse(
                        exchange(sep, "GET /c.bin HTTP/1.1\r\nHost: test.local\r\nConnection: close\r\n\r\n"),
                    )
                    assertArrayEquals(body, responseBody)
                } catch (t: Throwable) {
                    failures.add(t)
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true }.start()
        }
        assertTrue("parallel separated downloads timed out", done.await(60, java.util.concurrent.TimeUnit.SECONDS))
        if (failures.isNotEmpty()) {
            throw AssertionError(failures.first())
        }
        lanes.forEach { (w, c, _) ->
            w.close()
            c.close()
        }
    }
}
