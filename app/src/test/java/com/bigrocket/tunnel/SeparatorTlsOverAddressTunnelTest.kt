package com.bigrocket.tunnel

import bridgesrc.BridgeServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * The end-to-end proof for engine 2 (the address tunnel): a REAL TLS connection is carried by the
 * address-derived bond, over two carrier pipes at once.
 *
 * Nothing below the socket layer is mocked. The far side is the real, deployed
 * `far-side/BridgeServer.kt` (compiled in by tools/sync-bridge.sh), the origin is a real
 * [SSLServerSocket] with the keystore in `app/src/test/resources/separator-tls-test.p12`, and the
 * client is a real [SSLSocket] from JSSE - the same stack an `https://` request uses on the phone.
 * The bytes therefore have to survive framing on two pipes, reordering across them, and AEAD
 * verification at the top.
 *
 * The bandwidth test throttles each pipe to a fixed rate. That is not a trick to inflate the
 * numbers: it models what Wi-Fi and LTE are (two links of similar, limited rate). With one pipe a
 * single TLS stream cannot exceed that rate; with two it should approach double, because TLS is a
 * byte stream and the tunnel does not care where a record boundary falls.
 */
class SeparatorTlsOverAddressTunnelTest {

    private lateinit var bridge: BridgeServer
    private var tcpPort = 0
    private val closables = ArrayList<AutoCloseable>()
    private val sessions = ArrayList<MuxSession>()

    /**
     * Origin listeners, keyed by "what this port serves" (`blob:<bytes>` / `echo`).
     *
     * One listener per route, and it keeps accepting for the whole test: `serve()` answers exactly
     * one request per connection, so a second connection must be a *new* connection on the same
     * port, not a new request on the same connection.
     */
    private val origins = ConcurrentHashMap<String, Int>()

    private lateinit var serverContext: SSLContext

    /** The test certificate is self-signed, so the client trusts exactly this keystore. */
    private val clientContext: SSLContext by lazy {
        val ks = KeyStore.getInstance("PKCS12").apply { loadTestKeystore(this) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }

    @Before
    fun startBridge() {
        AppLog.useAppLogger { _, _ -> }
        val ks = KeyStore.getInstance("PKCS12").apply { loadTestKeystore(this) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PASSWORD)
        serverContext = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }

        tcpPort = ServerSocket(0).use { it.localPort }
        val udpPort = java.net.DatagramSocket(0).use { it.localPort }
        bridge = BridgeServer(udpPort, tcpPort, 0, 0, anyCookie = true)
        Thread({ bridge.run() }, "test-bridge").apply { isDaemon = true }.start()
        waitUntil("bridge ready", 5_000) { bridge.stats().startsWith("pipes=") }
        Thread.sleep(150)
    }

    @After
    fun stopEverything() {
        sessions.forEach { runCatching { it.close() } }
        closables.forEach { runCatching { it.close() } }
        runCatching { bridge.stop() }
    }

    // ---------------------------------------------------------------------------------------
    // The tests that back the claim.
    // ---------------------------------------------------------------------------------------

    @Test
    fun oneTlsStreamGetsFasterWhenItIsMergedOverTwoBandwidthCappedRadios() {
        val bytes = 2 * 1024 * 1024
        val cap = 1_000_000 // bytes/s per radio: whatever one link can do

        // Warm the JIT and the TLS session cache so the first measurement is not the slowest.
        repeat(2) { warm ->
            try {
                val conn = openTlsOver(radios = 1, capBytesPerSec = 0)
                runCatching { conn.download(256 * 1024) }
                conn.close()
            } catch (e: Exception) {
                throw AssertionError("warm-up $warm (1 radio, no cap) failed: $e", e)
            }
        }

        val one = measure(radios = 1, capBytesPerSec = cap, bytes = bytes)
        val two = measure(radios = 2, capBytesPerSec = cap, bytes = bytes)

        val speedup = two.mbps / one.mbps
        println(
            "[measure] single TLS stream, ${bytes / 1024} KiB: " +
                "1 radio = ${"%.2f".format(one.mbps)} Mbps (${one.ms}ms), " +
                "2 radios = ${"%.2f".format(two.mbps)} Mbps (${two.ms}ms), " +
                "speedup = ${"%.2f".format(speedup)}x, radios used = ${two.radios}/2",
        )

        assertEquals("both radios must actually carry frames", 2, two.radios)
        assertEquals("the single-radio run uses exactly one pipe", 1, one.radios)
        assertTrue(
            "two radios must beat one by a wide margin: " +
                "${"%.2f".format(one.mbps)} -> ${"%.2f".format(two.mbps)} Mbps",
            speedup >= 1.6,
        )
        assertTrue("the merged run must finish in less time", two.ms < one.ms)
    }

    @Test
    fun tlsDownloadAndUploadOnTwoRadiosAreByteExact() {
        val size = 512 * 1024
        val down = openTlsOver(radios = 2, capBytesPerSec = 0).use { conn ->
            val body = conn.download(size)
            assertEquals("download length", size, body.size)
            assertEquals("TLS 1.3 on the download", "TLSv1.3", conn.protocol)
            assertEquals("both radios used", 2, conn.liveRadios())
            body
        }
        assertArrayEquals("download bytes (two radios, TLS)", originBody(size), down)

        // A separate connection carries the upload, so both directions are proven independently:
        // the origin answers one request per connection (`Connection: close`).
        val up = ByteArray(128 * 1024) { (it * 7 and 0xFF).toByte() }
        val echoed = openTlsOver(radios = 2, capBytesPerSec = 0, route = "echo").use { conn ->
            conn.uploadAndEcho(up)
        }
        assertArrayEquals("upload bytes (two radios, TLS)", up, echoed)
    }

    @Test
    fun aLargeDownloadOverTwoRadiosStaysByteExact() {
        val size = 2 * 1024 * 1024
        val conn = openTlsOver(radios = 2, capBytesPerSec = 0)
        val down = conn.download(size)
        assertEquals("download length", size, down.size)
        assertArrayEquals("2 MiB download bytes over two radios", originBody(size), down)
        conn.close()
    }

    @Test
    fun aStreamSplitAcrossTwoRadiosSurvivesADeadRadio() {
        // One pipe vanishes mid-download (a real phone condition: Wi-Fi drops, LTE carries on).
        // BondedEndpoint retries on the surviving pipe, so the TLS stream must stay intact.
        val size = 1024 * 1024
        val conn = openTlsOver(radios = 2, capBytesPerSec = 0)
        val first = conn.download(256 * 1024)
        conn.killSecondRadio()
        val rest = conn.download(size - first.size)
        assertArrayEquals("stream survives a dead radio", originBody(size), first + rest)
        conn.close()
    }

    // ---------------------------------------------------------------------------------------
    // Harness.
    // ---------------------------------------------------------------------------------------

    private class Measurement(val ms: Long, val mbps: Double, val radios: Int)

    private fun measure(radios: Int, capBytesPerSec: Int, bytes: Int): Measurement {
        val conn = openTlsOver(radios, capBytesPerSec)
        val started = System.currentTimeMillis()
        val got = conn.download(bytes)
        val elapsed = System.currentTimeMillis() - started
        conn.close()
        check(got.size == bytes) { "measured only ${got.size} of $bytes bytes" }
        val mbps = bytes.toDouble() * 8.0 / 1_000_000.0 / (elapsed / 1000.0)
        return Measurement(elapsed, mbps, conn.liveRadiosAtStart)
    }

    /**
     * Starts a TLS origin, opens the address tunnel with [radios] pipes, and layers a real TLS
     * client on the tunnel socket. [capBytesPerSec] > 0 throttles every pipe to that rate in both
     * directions, modelling two comparable radios.
     */
    private fun openTlsOver(
        radios: Int,
        capBytesPerSec: Int,
        route: String = "blob:${4 * 1024 * 1024}",
    ): Conn {
        val originPort = originFor(route)
        val endpoints = (0 until radios).map {
            val raw: ChannelEndpoint = TcpChannelEndpoint(InetSocketAddress("127.0.0.1", tcpPort))
            val pipe = if (capBytesPerSec > 0) ThrottledEndpoint(raw, capBytesPerSec) else raw
            closables.add(AutoCloseable { runCatching { pipe.close() } })
            pipe
        }
        val bonded = BondedEndpoint(endpoints)
        val session = MuxSession(bonded, 0, label = "tls-$radios")
        sessions.add(session)
        check(session.start(8_000)) { "session did not start: ${session.downReason()}" }
        val tunnel: Socket = session.openTcp("127.0.0.1", originPort, timeoutMs = 8_000)
        tunnel.soTimeout = 30_000

        val ssl = clientContext.socketFactory
            .createSocket(tunnel, "127.0.0.1", originPort, true) as SSLSocket
        ssl.useClientMode = true
        ssl.soTimeout = 30_000
        ssl.startHandshake()
        check(ssl.session.protocol.startsWith("TLS")) { "not TLS: ${ssl.session.protocol}" }

        // The blob route is fetched here (every download test starts by reading the body); the
        // echo route is driven by the caller, which has to write the POST first.
        if (route.startsWith("blob")) {
            ssl.outputStream.write("GET /blob HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            ssl.outputStream.flush()
            skipHeaders(ssl.inputStream)
        }
        return Conn(ssl, bonded, session)
    }

    /** One TLS-over-tunnel connection, plus the answers the tests assert on. */
    private inner class Conn(
        private val ssl: SSLSocket,
        private val bonded: BondedEndpoint,
        private val session: MuxSession,
    ) {
        val liveRadiosAtStart: Int = bonded.liveCount()
        val protocol: String = ssl.session.protocol

        fun download(bytes: Int): ByteArray {
            val buffer = ByteArray(bytes)
            readExact(ssl.inputStream, buffer)
            return buffer
        }

        fun uploadAndEcho(data: ByteArray): ByteArray {
            val out = ssl.outputStream
            out.write(
                ("POST /echo HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Content-Length: ${data.size}\r\n\r\n").toByteArray(),
            )
            out.write(data)
            out.flush()
            skipHeaders(ssl.inputStream)
            val echoed = ByteArray(data.size)
            readExact(ssl.inputStream, echoed)
            return echoed
        }

        fun killSecondRadio() {
            bonded.killPipe(1)
        }

        fun liveRadios(): Int = bonded.liveCount()

        /**
         * Tears the connection down completely - TLS socket, tunnel socket and the mux session.
         *
         * Closing the session matters for the test harness: the far side keeps stream ids in its
         * router until the session goes away, and every session here uses the same cookie (that
         * is how the app works - the cookie is derived from the address the user typed). A second
         * connection while the first session is still registered would collide on stream id 1.
         */
        fun close() {
            runCatching { ssl.close() }
            runCatching { session.close() }
            sessions.remove(session)
        }
    }

    private fun Conn.use(block: (Conn) -> ByteArray): ByteArray = try {
        block(this)
    } finally {
        runCatching { close() }
    }

    private fun readExact(input: InputStream, buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            check(n >= 0) { "stream ended at $read/${buffer.size}" }
            read += n
        }
    }

    /** Consumes an HTTP response head so the caller reads only body bytes. */
    private fun skipHeaders(input: InputStream) {
        var state = 0 // how many bytes of "\r\n\r\n" are matched
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

    /**
     * A long-lived TLS origin serving a deterministic body of [bytes] on `/blob`, and echoing
     * `Content-Length` bytes back on `/echo`. The body is a pure function of the size, so the
     * client regenerates the expectation without sharing any state with the server.
     */
    private fun originFor(route: String): Int = origins.computeIfAbsent(route) { key ->

        val server = serverContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        closables.add(AutoCloseable { runCatching { server.close() } })
        Thread({
            while (!server.isClosed) {
                val conn = runCatching { server.accept() as SSLSocket }.getOrNull() ?: break
                Thread({
                    runCatching { serve(conn, key) }
                    runCatching { conn.close() }
                }, "tls-origin-worker").apply { isDaemon = true }.start()
            }
        }, "tls-origin-$key").apply { isDaemon = true }.start()
        server.localPort
    }

    /** Answers exactly one request; [route] is `blob:<bytes>` or `echo`. */
    private fun serve(conn: SSLSocket, route: String) {
        val input = conn.inputStream
        val out = conn.outputStream
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return
            head.append(b.toChar())
        }
        val path = head.lineSequence().first().split(' ').getOrNull(1) ?: return
        when {
            path.startsWith("/blob") -> {
                val body = originBody(route.substringAfter("blob:").toInt())
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                )
                out.write(body)
                out.flush()
            }
            path.startsWith("/echo") -> {
                val length = Regex("(?i)content-length:\\s*(\\d+)")
                    .find(head)?.groupValues?.get(1)?.toInt() ?: 0
                val payload = ByteArray(length)
                readExact(input, payload)
                out.write("HTTP/1.1 200 OK\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(payload)
                out.flush()
            }
        }
    }

    /**
     * Caps a carrier at [bytesPerSec] per direction. Throttling the *receive* path is what makes a
     * download measurement meaningful: sleeping in the pipe's reader thread stops that socket
     * being read, TCP pushes back, and the far side cannot send faster than the radio.
     *
     * Only DATA frames are charged. Control frames (HELLO/ACK/NACK/credit/FIN) are a handful of
     * bytes but are on the critical path - metering them behind the same bucket starves the flow
     * control window and stalls the download completely, which measures the throttle rather than
     * the radio. Separate rx/tx buckets so the two directions cannot starve each other either.
     */
    private class ThrottledEndpoint(
        private val inner: ChannelEndpoint,
        bytesPerSec: Int,
    ) : ChannelEndpoint by inner {
        private val rx = TokenBucket(bytesPerSec.toDouble())
        private val tx = TokenBucket(bytesPerSec.toDouble())

        override fun start(sink: FrameSink): Boolean =
            inner.start { raw, length, frame ->
                if (frame.type == Brt.DATA) rx.take(frame.payload.size)
                sink.onFrame(raw, length, frame)
            }

        override fun send(frame: ByteArray): Boolean {
            val decoded = Brt.decode(frame)
            if (decoded?.type == Brt.DATA) tx.take(decoded.payload.size)
            return inner.send(frame)
        }
    }

    /** Leaky bucket: sleep until [n] bytes are affordable at the configured rate. */
    private class TokenBucket(private val rate: Double) {
        private var tokens = 0.0
        private var last = System.nanoTime()

        @Synchronized
        fun take(n: Int) {
            while (true) {
                val now = System.nanoTime()
                tokens = (tokens + (now - last) / 1e9 * rate).coerceAtMost(rate)
                last = now
                if (tokens >= n) {
                    tokens -= n
                    return
                }
                Thread.sleep(maxOf(1L, ((n - tokens) / rate * 1000).toLong()))
            }
        }
    }

    /**
     * Loads `separator-tls-test.p12` (alias `bridge-tls`, password [PASSWORD]).
     *
     * Reads it from the classpath when the build puts it there, and otherwise from the source
     * tree: AGP does not always wire `src/test/resources` into the unit-test runtime classpath,
     * and a TLS test that silently skips because it cannot find its keystore would be worthless.
     * Gradle runs unit tests with the working directory set to `app/`, hence the relative path.
     */
    private fun loadTestKeystore(ks: KeyStore) {
        val classpath = javaClass.getResourceAsStream("/separator-tls-test.p12")
        if (classpath != null) {
            classpath.use { ks.load(it, PASSWORD) }
            return
        }
        val candidates = listOf(
            java.io.File("src/test/resources/separator-tls-test.p12"),
            java.io.File("app/src/test/resources/separator-tls-test.p12"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "separator-tls-test.p12 not found in any of $candidates " +
                    "(cwd=${java.io.File(".").canonicalPath})",
            )
        file.inputStream().use { ks.load(it, PASSWORD) }
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
        val PASSWORD = "bigrocket-test".toCharArray()
    }
}

/** The body the origin serves for a given size - deterministic, so both sides agree. */
fun originBody(size: Int): ByteArray = ByteArray(size) { ((it * 31 + 7) ushr 3 and 0xFF).toByte() }
