package com.bigrocket.tunnel

/**
 * "DNS AS THE ADDRESS" - the wire details.
 *
 * The user asked to be able to mount a normal DNS in place of the static IP. Two different
 * things can be meant by that, and this file implements both, because they answer different
 * questions:
 *
 *  1. **A DNS server as the carrier** (any resolver: 1.1.1.1, 8.8.8.8, or one you run):
 *     the tunnel's frames are posted to it inside `application/dns-message` bodies, exactly like
 *     RFC 8484 DoH. See [DohChannelEndpoint]. A *plain* resolver will just answer DNS and never
 *     tunnel - which is why the app self-tests first and falls back to engine 1.
 *
 *  2. **A DNS *answer* as the endpoint** ("x1.t.example.com" answered by the far side):
 *     the A record does not carry a usable IPv4 address in the usual sense - it carries the
 *     `[port:2][cookie:2]` of the bridge. A single A record is 4 bytes, which is exactly
 *     `port(2) + cookie(2)`, so a normal DNS server (or the far side's own tiny DNS responder)
 *     can point the phone at a port number and a session tag in one ordinary answer.
 *
 * Encoding of the 4 A-record bytes:
 * ```
 *   byte 0..1   port    (big-endian; 0 means "use the default for the transport")
 *   byte 2..3   cookie  (big-endian; the same value Brt.cookieFor(address) yields)
 * ```
 * The address itself comes from the rest of the DNS machinery (the A record is only a carrier
 * for the port/cookie; the IP the phone talks to is the bridge's real address, given by the
 * resolver's own A/AAAA record for the name, or typed by the user).
 *
 * Both are pure byte-level helpers so the unit tests can prove the format without a network.
 */
object DnsRecords {

    /** Encoded form of an A-record answer that names a tunnel endpoint. */
    data class Endpoint(val port: Int, val cookie: Int) {
        fun encode(): ByteArray = byteArrayOf(
            ((port ushr 8) and 0xFF).toByte(),
            (port and 0xFF).toByte(),
            ((cookie ushr 8) and 0xFF).toByte(),
            (cookie and 0xFF).toByte(),
        )

        override fun toString(): String = "port=$port cookie=$cookie"
    }

    /**
     * Reads a `[port|cookie]` A record.
     * @return null when the 4 bytes are an ordinary address (which is the normal case, and must
     *         never be mistaken for an endpoint: an answer of 1.1.1.1 is a DNS server, not us).
     */
    fun decodeEndpoint(addressBytes: ByteArray): Endpoint? {
        if (addressBytes.size != 4) return null
        val port = ((addressBytes[0].toInt() and 0xFF) shl 8) or (addressBytes[1].toInt() and 0xFF)
        val cookie = ((addressBytes[2].toInt() and 0xFF) shl 8) or (addressBytes[3].toInt() and 0xFF)
        return Endpoint(port, cookie)
    }

    /** True when the port/cookie pair is plausible (guards against bogus records). */
    fun isPlausible(endpoint: Endpoint): Boolean = endpoint.port in 1..65535 && endpoint.cookie != 0

    /**
     * Builds a minimal DNS query for an A record. Duplicated (deliberately, and tested) from
     * `network/DohClient.kt` so the tunnel does not depend on the app's resolver stack: the
     * address lookup for the tunnel must work even while the phone's own DNS is the thing
     * being tunnelled.
     */
    fun buildQuery(domain: String, queryId: Int = 0x4252, type: Int = 1): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(out)
        dos.writeShort(queryId and 0xFFFF)
        dos.writeShort(0x0100) // standard query, recursion desired
        dos.writeShort(1)
        dos.writeShort(0)
        dos.writeShort(0)
        dos.writeShort(0)
        domain.trim('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8)
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0)
        dos.writeShort(type) // QTYPE: 1 = A, 28 = AAAA
        dos.writeShort(1) // QCLASS IN
        dos.flush()
        return out.toByteArray()
    }

    /** One answer section entry, as delivered by [parseAnswers]. */
    class Answer(val type: Int, val ttl: Long, val data: ByteArray) {
        fun ipv4(): String? =
            if (type == 1 && data.size == 4) {
                "${data[0].toInt() and 0xFF}.${data[1].toInt() and 0xFF}." +
                    "${data[2].toInt() and 0xFF}.${data[3].toInt() and 0xFF}"
            } else {
                null
            }
    }

    /**
     * Parses a DNS response (as produced by any resolver, or by the far side's own responder).
     * Handles name compression, and returns every answer record it can understand.
     */
    fun parseAnswers(response: ByteArray): List<Answer> {
        if (response.size < 12) return emptyList()
        val ancount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        var offset = 12
        // Skip the question section.
        repeat(((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)) {
            offset = skipName(response, offset) ?: return emptyList()
            if (offset + 4 > response.size) return emptyList()
            offset += 4
        }
        val answers = ArrayList<Answer>(ancount)
        repeat(ancount) {
            offset = skipName(response, offset) ?: return answers
            if (offset + 10 > response.size) return answers
            val type = ((response[offset].toInt() and 0xFF) shl 8) or (response[offset + 1].toInt() and 0xFF)
            val ttl = (Brt.readInt(response, offset + 4).toLong() and 0xFFFFFFFFL)
            val rdlength = ((response[offset + 8].toInt() and 0xFF) shl 8) or (response[offset + 9].toInt() and 0xFF)
            offset += 10
            if (offset + rdlength > response.size) return answers
            answers.add(Answer(type, ttl, response.copyOfRange(offset, offset + rdlength)))
            offset += rdlength
        }
        return answers
    }

    /** First A-record bytes, or null. */
    fun firstA(response: ByteArray): ByteArray? =
        parseAnswers(response).firstOrNull { it.type == 1 && it.data.size == 4 }?.data

    /** First AAAA-record bytes (16), or null. */
    fun firstAaaa(response: ByteArray): ByteArray? =
        parseAnswers(response).firstOrNull { it.type == 28 && it.data.size == 16 }?.data

    /**
     * The full picture for a bare DNS name the user typed: is this name answered by a far side
     * that wants to tell us a port+cookie, or is it just a name for an ordinary address?
     */
    fun interpret(response: ByteArray): TunnelDnsAnswer? {
        val a = firstA(response) ?: return null
        val endpoint = decodeEndpoint(a)
        return TunnelDnsAnswer(ipv4 = "${a[0].toInt() and 0xFF}.${a[1].toInt() and 0xFF}." +
            "${a[2].toInt() and 0xFF}.${a[3].toInt() and 0xFF}", endpoint = endpoint)
    }

    private fun skipName(data: ByteArray, start: Int): Int? {
        var pos = start
        var jumped = false
        var afterPointer = -1
        while (true) {
            if (pos >= data.size) return null
            val len = data[pos].toInt() and 0xFF
            if (len == 0) return if (jumped) afterPointer else pos + 1
            if (len and 0xC0 != 0) {
                if (!jumped) afterPointer = pos + 2
                jumped = true
                if (pos + 1 >= data.size) return null
                pos = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
            } else {
                pos += 1 + len
            }
        }
    }
}

/** What an A-record answer told us about a name the user typed. */
class TunnelDnsAnswer(val ipv4: String, val endpoint: DnsRecords.Endpoint?)
