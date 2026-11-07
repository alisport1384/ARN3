package com.bigrocket.egress

import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.Base64

/**
 * HTTP/HTTPS proxy client: RFC 7231 `CONNECT` tunnelling plus Basic proxy authentication.
 *
 * Only used when the user's proxy is `http://` or `https://`. Tunnelling (CONNECT) rather
 * than absolute-URI forwarding keeps one code path for arbitrary protocols on top of the
 * proxy (TLS, the engines' own tunnels, ...) - the socket is byte-transparent afterwards,
 * exactly like the SOCKS5 result.
 *
 * Android-free on purpose, so it is unit-testable against a loopback server.
 */
object HttpProxyProtocol {

    /** Issues `CONNECT host:port` and validates a 2xx answer. Socket is transparent afterwards. */
    fun connect(socket: Socket, proxy: StaticProxyConfig, destHost: String, destPort: Int) {
        val authority = if (destHost.contains(':') && !destHost.startsWith("[")) "[$destHost]:$destPort"
        else "$destHost:$destPort"
        val request = buildString {
            append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
            append("Host: ").append(authority).append("\r\n")
            if (proxy.hasCredentials) {
                val raw = "${proxy.username}:${proxy.password.orEmpty()}"
                val encoded = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
                append("Proxy-Authorization: Basic ").append(encoded).append("\r\n")
            }
            append("Proxy-Connection: keep-alive\r\n")
            append("\r\n")
        }
        val out = socket.getOutputStream()
        out.write(request.toByteArray(Charsets.US_ASCII))
        out.flush()

        val statusLine = readLine(socket.getInputStream())
            ?: throw IOException("پروکسی HTTP پاسخی نداد")
        val code = parseStatusCode(statusLine)
        if (code == null) throw IOException("پاسخ پروکسی HTTP نامعتبر است: $statusLine")
        // Drain the rest of the response headers.
        var header: String?
        do {
            header = readLine(socket.getInputStream())
        } while (header != null && header.isNotEmpty())

        when (code) {
            in 200..299 -> Unit
            407 -> throw IOException("پروکسی HTTP نیاز به نام کاربری/رمز معتبر دارد")
            403 -> throw IOException("پروکسی HTTP اتصال به مقصد را رد کرد (403)")
            502, 503, 504 -> throw IOException("پروکسی HTTP نتوانست به مقصد وصل شود ($code)")
            else -> throw IOException("پروکسی HTTP خطا داد ($code)")
        }
    }

    /**
     * Absolute-form GET, i.e. `GET http://host/path HTTP/1.1` forwarded by the proxy itself
     * (no tunnel). Only used by the egress-IP check, where the proxy has to be the one doing
     * the request anyway.
     */
    fun getAbsolute(socket: Socket, proxy: StaticProxyConfig, url: String, host: String): String {
        val request = buildString {
            append("GET ").append(url).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            append("User-Agent: BigRocket-EgressCheck\r\n")
            append("Accept: */*\r\n")
            append("Connection: close\r\n")
            if (proxy.hasCredentials) {
                val raw = "${proxy.username}:${proxy.password.orEmpty()}"
                val encoded = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
                append("Proxy-Authorization: Basic ").append(encoded).append("\r\n")
            }
            append("\r\n")
        }
        val out = socket.getOutputStream()
        out.write(request.toByteArray(Charsets.US_ASCII))
        out.flush()
        return readToEnd(socket.getInputStream())
    }

    /**
     * Plain `GET / HTTP/1.1` for use on an already-tunnelled socket (SOCKS5 / HTTP CONNECT).
     * Used by the egress-IP check.
     */
    fun getThroughTunnel(socket: Socket, host: String, path: String = "/"): String {
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            append("User-Agent: BigRocket-EgressCheck\r\n")
            append("Accept: */*\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        val out = socket.getOutputStream()
        out.write(request.toByteArray(Charsets.US_ASCII))
        out.flush()
        return readToEnd(socket.getInputStream())
    }

    private fun readToEnd(input: InputStream): String {
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < 64 * 1024) {
            val n = try {
                input.read(buffer)
            } catch (_: IOException) {
                break
            }
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun parseStatusCode(statusLine: String): Int? {
        val parts = statusLine.trim().split(' ')
        if (parts.size < 2) return null
        if (!parts[0].startsWith("HTTP/")) return null
        return parts[1].toIntOrNull()
    }

    private fun readLine(input: InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else String(out.toByteArray(), Charsets.ISO_8859_1)
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 8192) throw IOException("هدر پاسخ پروکسی بیش از حد بلند است")
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    /** Body of an HTTP response (text), skipping headers. Tolerates chunked encoding. */
    fun extractBody(response: String): String {
        val idx = response.indexOf("\r\n\r\n")
        if (idx < 0) return ""
        val headers = response.substring(0, idx).lowercase()
        val body = response.substring(idx + 4)
        return if (headers.contains("transfer-encoding: chunked")) decodeChunked(body) else body
    }

    private fun decodeChunked(body: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < body.length) {
            val lineEnd = body.indexOf("\r\n", index)
            if (lineEnd < 0) break
            val size = body.substring(index, lineEnd).trim().substringBefore(';').toIntOrNull(16) ?: break
            if (size == 0) break
            val start = lineEnd + 2
            val end = minOf(start + size, body.length)
            out.append(body, start, end)
            index = end + 2
        }
        return out.toString()
    }
}
