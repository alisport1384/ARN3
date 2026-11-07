package com.bigrocket.egress

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal, dependency-free SOCKS5 client (RFC 1928 + RFC 1929 username/password auth).
 *
 * Deliberately Android-free and operating on a plain [Socket]/[InputStream]/[OutputStream],
 * for two reasons:
 *  - it runs on top of a socket that is already bound to the chosen physical path
 *    (Path 1 / Path 2) and `protect()`-ed from the VPN, so the proxy itself is reached over
 *    the bonded transport, exactly like any other BigRocket flow;
 *  - it can be exercised by plain JVM unit tests against a loopback SOCKS5 server.
 *
 * The destination is always sent as a domain name when the caller passes one (ATYP=0x03),
 * i.e. DNS is resolved by the proxy, not by the phone. That matters for the whole point of
 * this feature: a static exit IP that also sees the same DNS answers regardless of which
 * physical path carried the request.
 */
object Socks5Protocol {

    private const val VERSION = 0x05
    private const val AUTH_NONE = 0x00
    private const val AUTH_USERPASS = 0x02
    private const val AUTH_NO_ACCEPTABLE = 0xFF
    private const val CMD_CONNECT = 0x01
    private const val CMD_UDP_ASSOCIATE = 0x03
    private const val ATYP_IPV4 = 0x01
    private const val ATYP_DOMAIN = 0x03
    private const val ATYP_IPV6 = 0x04

    /** RFC 1928 reply codes -> human readable Persian text for the UI/log. */
    fun replyMessage(code: Int): String = when (code) {
        0x00 -> "موفق"
        0x01 -> "خطای عمومی سرور پروکسی"
        0x02 -> "اتصال به پروکسی مجاز نیست (ACL)"
        0x03 -> "شبکه در دسترس نیست"
        0x04 -> "هاست در دسترس نیست"
        0x05 -> "اتصال رد شد"
        0x06 -> "TTL منقضی شد"
        0x07 -> "دستور پشتیبانی نمی‌شود"
        0x08 -> "نوع آدرس پشتیبانی نمی‌شود"
        else -> "خطای ناشناخته پروکسی ($code)"
    }

    /**
     * Method negotiation + optional RFC 1929 auth, then a CONNECT/UDP-ASSOCIATE request.
     * Leaves the socket's streams positioned right after a successful reply.
     */
    private fun handshake(
        input: InputStream,
        output: OutputStream,
        proxy: StaticProxyConfig,
        command: Int,
        destHost: String,
        destPort: Int,
    ): InetSocketAddress {
        val wantAuth = proxy.hasCredentials
        val methods = if (wantAuth) byteArrayOf(VERSION.toByte(), AUTH_NONE.toByte(), AUTH_USERPASS.toByte())
        else byteArrayOf(VERSION.toByte(), AUTH_NONE.toByte())
        output.write(byteArrayOf(VERSION.toByte(), methods.size.toByte()) + methods)
        output.flush()

        val choice = ByteArray(2)
        readFully(input, choice, "پاسخ انتخاب روش احراز هویت پروکسی")
        if (choice[0].toInt() != VERSION) throw IOException("پروکسی SOCKS5 پاسخ نامعتبر داد")
        when (choice[1].toInt() and 0xFF) {
            AUTH_NONE -> Unit
            AUTH_USERPASS -> {
                if (!wantAuth) throw IOException("پروکسی نام کاربری/رمز خواست ولی تنظیم نشده است")
                val user = proxy.username.orEmpty().toByteArray(Charsets.UTF_8)
                val pass = proxy.password.orEmpty().toByteArray(Charsets.UTF_8)
                if (user.size > 255 || pass.size > 255) throw IOException("نام کاربری/رمز پروکسی بیش از ۲۵۵ بایت است")
                output.write(byteArrayOf(0x01, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
                output.flush()
                val authReply = ByteArray(2)
                readFully(input, authReply, "پاسخ احراز هویت پروکسی")
                if (authReply[1].toInt() != 0x00) throw IOException("نام کاربری یا رمز پروکسی اشتباه است")
            }
            AUTH_NO_ACCEPTABLE -> throw IOException("پروکسی هیچ روش احراز هویتی را نپذیرفت")
            else -> throw IOException("روش احراز هویت ناشناخته از طرف پروکسی")
        }

        val request = java.io.ByteArrayOutputStream()
        request.write(VERSION)
        request.write(command)
        request.write(0x00) // RSV
        writeAddress(request, destHost)
        request.write((destPort ushr 8) and 0xFF)
        request.write(destPort and 0xFF)
        output.write(request.toByteArray())
        output.flush()

        val head = ByteArray(4)
        readFully(input, head, "پاسخ پروکسی")
        if (head[0].toInt() != VERSION) throw IOException("فرمت پاسخ پروکسی نامعتبر است")
        val rep = head[1].toInt() and 0xFF
        if (rep != 0x00) throw IOException("پروکسی درخواست را رد کرد: ${replyMessage(rep)}")
        return readBoundAddress(input, head[3].toInt() and 0xFF)
    }

    /** Opens a TCP tunnel to [destHost]:[destPort] through [proxy] over [socket]. */
    fun connect(socket: Socket, proxy: StaticProxyConfig, destHost: String, destPort: Int) {
        socket.soTimeout = socket.soTimeout.takeIf { it > 0 } ?: DEFAULT_HANDSHAKE_TIMEOUT_MS
        handshake(socket.getInputStream(), socket.getOutputStream(), proxy, CMD_CONNECT, destHost, destPort)
        socket.soTimeout = 0
    }

    /**
     * Opens a UDP association. The returned address is the proxy-side relay endpoint that
     * encapsulated datagrams must be sent to; the caller keeps [socket] open for as long as
     * the association is used (closing it tears the association down).
     */
    fun openUdpAssociation(socket: Socket, proxy: StaticProxyConfig, proxyAddress: InetAddress): InetSocketAddress {
        val bound = handshake(socket.getInputStream(), socket.getOutputStream(), proxy, CMD_UDP_ASSOCIATE, "0.0.0.0", 0)
        // A proxy that answers 0.0.0.0 means "same host as the control connection".
        return if (bound.address == null || bound.address.isAnyLocalAddress || bound.port == 0) {
            InetSocketAddress(proxyAddress, if (bound.port == 0) proxy.port else bound.port)
        } else {
            bound
        }
    }

    private fun writeAddress(out: java.io.ByteArrayOutputStream, host: String) {
        val literal = parseLiteral(host)
        if (literal != null) {
            val bytes = literal.address
            out.write(if (bytes.size == 16) ATYP_IPV6 else ATYP_IPV4)
            out.write(bytes)
        } else {
            val bytes = host.toByteArray(Charsets.UTF_8)
            if (bytes.size > 255) throw IOException("نام دامنه بیش از حد بلند است")
            out.write(ATYP_DOMAIN)
            out.write(bytes.size)
            out.write(bytes)
        }
    }

    /**
     * An IP literal is sent as such; anything else goes as a domain name (ATYP 0x03) so the
     * PROXY resolves it - which is also what keeps DNS inside the proxy.
     *
     * The literal test is textual and strict on purpose: a hex-looking hostname such as
     * `beef.cafe` must not be mistaken for an address (that would trigger a system-resolver
     * lookup on the phone, the exact DNS leak this protocol path avoids).
     */
    private fun parseLiteral(host: String): InetAddress? {
        val unbracketed = host.removePrefix("[").removeSuffix("]")
        val ipv4 = unbracketed.split('.')
        val looksIpv4 = ipv4.size == 4 && ipv4.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() }
        }
        val looksIpv6 = unbracketed.contains(':') &&
            unbracketed.all { it.isDigit() || it in "abcdefABCDEF:." }
        if (!looksIpv4 && !looksIpv6) return null
        return runCatching { InetAddress.getByName(unbracketed) }.getOrNull()
    }

    private fun readBoundAddress(input: InputStream, atyp: Int): InetSocketAddress {
        val address: InetAddress
        when (atyp) {
            ATYP_IPV4 -> {
                val buf = ByteArray(4)
                readFully(input, buf, "آدرس پاسخ پروکسی")
                address = InetAddress.getByAddress(buf)
            }
            ATYP_IPV6 -> {
                val buf = ByteArray(16)
                readFully(input, buf, "آدرس پاسخ پروکسی")
                address = InetAddress.getByAddress(buf)
            }
            ATYP_DOMAIN -> {
                val len = ByteArray(1)
                readFully(input, len, "طول دامنه پاسخ پروکسی")
                val buf = ByteArray(len[0].toInt() and 0xFF)
                readFully(input, buf, "دامنه پاسخ پروکسی")
                address = runCatching { InetAddress.getByName(String(buf, Charsets.US_ASCII)) }
                    .getOrElse { InetAddress.getByName("0.0.0.0") }
            }
            else -> throw IOException("نوع آدرس پاسخ پروکسی پشتیبانی نمی‌شود")
        }
        val portBytes = ByteArray(2)
        readFully(input, portBytes, "پورت پاسخ پروکسی")
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        return InetSocketAddress(address, port)
    }

    fun readFully(input: InputStream, buffer: ByteArray, what: String) {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) throw IOException("اتصال به پروکسی بسته شد ($what)")
            read += n
        }
    }

    // --- UDP encapsulation (RFC 1928 section 7) -----------------------------------------

    /**
     * `RSV(2) FRAG(1) ATYP(1) DST.ADDR DST.PORT DATA`, FRAG always 0 (no fragmentation
     * support here - BigRocket's UDP flows are DNS and the engines' own tunnels, which keep
     * well under the datagram size a proxy will accept).
     */
    fun encodeUdpDatagram(host: String, port: Int, payload: ByteArray, length: Int = payload.size): ByteArray {
        val out = java.io.ByteArrayOutputStream(32 + length)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        writeAddress(out, host)
        out.write((port ushr 8) and 0xFF)
        out.write(port and 0xFF)
        out.write(payload, 0, length)
        return out.toByteArray()
    }

    data class UdpDatagram(val host: String, val port: Int, val payload: ByteArray)

    /** @return null when [length] does not contain a well-formed encapsulated datagram. */
    fun decodeUdpDatagram(data: ByteArray, length: Int): UdpDatagram? {
        if (length < 4) return null
        if (data[0].toInt() != 0 || data[1].toInt() != 0) return null
        if ((data[2].toInt() and 0xFF) != 0) return null // fragmented: unsupported
        val atyp = data[3].toInt() and 0xFF
        var offset = 4
        val host: String
        when (atyp) {
            ATYP_IPV4 -> {
                if (offset + 4 > length) return null
                host = InetAddress.getByAddress(data.copyOfRange(offset, offset + 4)).hostAddress ?: return null
                offset += 4
            }
            ATYP_IPV6 -> {
                if (offset + 16 > length) return null
                host = InetAddress.getByAddress(data.copyOfRange(offset, offset + 16)).hostAddress ?: return null
                offset += 16
            }
            ATYP_DOMAIN -> {
                if (offset >= length) return null
                val len = data[offset].toInt() and 0xFF
                offset += 1
                if (offset + len > length) return null
                host = String(data, offset, len, Charsets.US_ASCII)
                offset += len
            }
            else -> return null
        }
        if (offset + 2 > length) return null
        val port = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2
        return UdpDatagram(host, port, data.copyOfRange(offset, length))
    }

    const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 8_000
}
