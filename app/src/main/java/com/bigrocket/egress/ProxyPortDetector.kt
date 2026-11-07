package com.bigrocket.egress

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Finds the port (and the protocol) of a proxy the user described only by its IP.
 *
 * Why this exists: "95.85.252.115" is a legitimate thing to type, but a proxy without a port
 * is only half an address. Guessing a single default (1080) makes a wrong guess look exactly
 * like "the proxy is down" - the user cannot tell the two apart. So instead of guessing, the
 * app probes the ports a proxy realistically listens on and *asks the port itself* what it
 * speaks (a real SOCKS5 greeting, then a real HTTP `CONNECT`) - a port that merely accepts a
 * TCP connection proves nothing (firewalls and middleboxes accept every SYN).
 *
 * Android-free on purpose: unit-tested against loopback servers, and runnable on a plain JVM.
 */
object ProxyPortDetector {

    /** Ports worth trying, most likely first. */
    val DEFAULT_CANDIDATES: List<Int> = listOf(
        1080, 1081, 10808, 10809, // SOCKS5 conventions (Xray/v2ray/shadowsocks locals)
        3128, 3129, 8080, 8081, 8000, 8888, 8118, // HTTP proxy conventions
        9050, 9150, 4145, // Tor / old SOCKS
        443, 8443, 80, // TLS/plain on a shared endpoint
    )

    data class Detected(val port: Int, val kind: StaticProxyKind, val detail: String)

    /** Per-candidate outcome, for a message the user can actually act on. */
    data class Attempt(val port: Int, val outcome: String)

    /**
     * Probes [host] on every candidate port in parallel and returns the first port that
     * answers with a real proxy protocol.
     *
     * @param resolve how to turn [host] into an address (the caller passes a resolver bound to
     *        the chosen physical path, so this never leaks onto another path).
     * @param totalTimeoutMs overall budget; probing many hanging ports must not freeze the UI.
     */
    fun detect(
        host: String,
        resolver: ((String) -> InetAddress?)? = null,
        candidates: List<Int> = DEFAULT_CANDIDATES,
        connectTimeoutMs: Int = 2_000,
        readTimeoutMs: Int = 2_000,
        totalTimeoutMs: Long = 9_000,
    ): Pair<Detected?, List<Attempt>> {
        val address = (resolver?.invoke(host) ?: runCatching { InetAddress.getByName(host) }.getOrNull())
            ?: return null to candidates.map { Attempt(it, "هاست حل نشد") }

        val found = AtomicReference<Detected?>(null)
        val attempts = java.util.Collections.synchronizedList(mutableListOf<Attempt>())
        val pool: ExecutorService = Executors.newFixedThreadPool(minOf(8, candidates.size).coerceAtLeast(1))
        try {
            candidates.forEach { port ->
                pool.submit {
                    if (found.get() != null) return@submit
                    val result = probe(address, port, connectTimeoutMs, readTimeoutMs)
                    if (result != null) {
                        found.compareAndSet(null, result)
                    } else {
                        attempts.add(Attempt(port, "بدون پاسخ پروکسی"))
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(totalTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            pool.shutdownNow()
        }
        return found.get() to attempts.sortedBy { it.port }
    }

    /**
     * A single port probe: TCP connect, then SOCKS5 greeting, then HTTP `CONNECT`.
     * @return null when nothing on that port speaks a proxy protocol.
     */
    fun probe(
        address: InetAddress,
        port: Int,
        connectTimeoutMs: Int = 2_000,
        readTimeoutMs: Int = 2_000,
    ): Detected? {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs

            // --- SOCKS5? (RFC 1928 greeting) ---
            val socksReply = runCatching {
                socket.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
                socket.getOutputStream().flush()
                val buf = ByteArray(2)
                Socks5Protocol.readFully(socket.getInputStream(), buf, "socks greeting")
                buf
            }.getOrNull()
            if (socksReply != null && socksReply[0].toInt() == 0x05) {
                val method = socksReply[1].toInt() and 0xFF
                val detail = if (method == 0xFF) "SOCKS5 (نیاز به نام کاربری/رمز)" else "SOCKS5"
                return Detected(port, StaticProxyKind.SOCKS5, detail)
            }
        } catch (_: Exception) {
            // fall through to the next probe / next port
        } finally {
            runCatching { socket.close() }
        }

        // --- HTTP proxy? (CONNECT). A separate connection: the SOCKS5 attempt above left
        // the stream in an unknown state on a non-SOCKS5 server.
        val httpSocket = Socket()
        try {
            httpSocket.tcpNoDelay = true
            httpSocket.connect(InetSocketAddress(address, port), connectTimeoutMs)
            httpSocket.soTimeout = readTimeoutMs
            val request = "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n"
            httpSocket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            httpSocket.getOutputStream().flush()
            val line = readHttpStatusLine(httpSocket) ?: return null
            if (line.startsWith("HTTP/")) {
                val code = line.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                return Detected(port, StaticProxyKind.HTTP, "HTTP proxy ($code)")
            }
        } catch (_: Exception) {
            // not an HTTP proxy either
        } finally {
            runCatching { httpSocket.close() }
        }
        return null
    }

    private fun readHttpStatusLine(socket: Socket): String? {
        val input = socket.getInputStream()
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < 512) {
            val b = input.read()
            if (b < 0) break
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return if (out.size() == 0) null else String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    /** Human-readable summary of a failed detection, for the UI/log. */
    fun describeFailure(host: String, attempts: List<Attempt>): String {
        val tried = attempts.joinToString(", ") { it.port.toString() }
        return "روی $host هیچ پورت پروکسی پاسخ نداد (تلاش‌شده: $tried). " +
            "یعنی این آی‌پی یک سرور پروکسی فعال روی پورت‌های رایج نیست؛ پورت را دستی وارد کنید " +
            "یا IP پروکسی را بررسی کنید."
    }
}
