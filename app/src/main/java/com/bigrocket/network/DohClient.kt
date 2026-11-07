package com.bigrocket.network

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.concurrent.thread
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBodyOrNull

/**
 * Minimal DNS-over-HTTPS (RFC 8484) client.
 *
 * Sends a binary DNS query to a DoH endpoint and parses the first IPv4 (A) record
 * from the response. Used to turn a user-supplied DoH URL into a concrete IP address.
 *
 * The implementation is intentionally small: it builds a minimal DNS message for a
 * single A-record query, sends it as `application/dns-message`, and parses the answer
 * section. No external DNS library is required.
 */
object DohClient {

    private const val DNS_QUERY_DOMAIN = "example.com"
    private const val REQUEST_TIMEOUT_MS = 8_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(REQUEST_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(REQUEST_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(REQUEST_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    private const val DNS_MEDIA_TYPE = "application/dns-message".toMediaTypeOrNull()

    /**
     * Resolves the first IPv4 address reachable through [dohUrl].
     *
     * @return the resolved IPv4 address, or null if the endpoint could not be queried
     *         or returned no A records.
     * @throws IOException on transport failure.
     */
    @Throws(IOException::class)
    fun resolveFirstIp(dohUrl: String): String? {
        val query = buildDnsQuery(DNS_QUERY_DOMAIN)
        val request = Request.Builder()
            .url(dohUrl)
            .post(query.toRequestBodyOrNull(DNS_MEDIA_TYPE))
            .header("Accept", "application/dns-message")
            .header("Content-Type", "application/dns-message")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("DoH endpoint returned HTTP ${response.code}")
        }
        val bytes = response.body?.bytes() ?: return null
        response.close()
        return parseFirstARecord(bytes)
    }

    /** Builds a minimal DNS query (header + one A-record question) for [domain]. */
    private fun buildDnsQuery(domain: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(out)

        // Header
        dos.writeShort(0x1234.toInt()) // transaction ID
        dos.writeShort(0x0100) // flags: standard query, recursion desired
        dos.writeShort(1) // QDCOUNT
        dos.writeShort(0) // ANCOUNT
        dos.writeShort(0) // NSCOUNT
        dos.writeShort(0) // ARCOUNT

        // Question: domain name
        domain.split(".").forEach { label ->
            dos.writeByte(label.length)
            dos.writeBytes(label)
        }
        dos.writeByte(0) // root label
        dos.writeShort(1) // QTYPE = A
        dos.writeShort(1) // QCLASS = IN

        dos.flush()
        return out.toByteArray()
    }

    /**
     * Parses a DNS response and returns the first IPv4 address found in an A record.
     * Skips the header and question section, then walks the answer section.
     */
    private fun parseFirstARecord(response: ByteArray): String? {
        if (response.size < 12) return null
        var offset = 12

        // Skip question section (we sent exactly one question)
        offset = skipDomain(response, offset) ?: return null
        if (offset + 4 > response.size) return null
        offset += 4 // QTYPE + QCLASS

        val ancount = (response[10].toInt() and 0xFF) shl 8 or (response[11].toInt() and 0xFF)
        for (i in 0 until ancount) {
            offset = skipDomain(response, offset) ?: return null
            if (offset + 10 > response.size) return null
            val type = (response[offset].toInt() and 0xFF) shl 8 or (response[offset + 1].toInt() and 0xFF)
            offset += 2 // TYPE
            offset += 2 // CLASS
            offset += 4 // TTL
            val rdlength = (response[offset].toInt() and 0xFF) shl 8 or (response[offset + 1].toInt() and 0xFF)
            offset += 2 // RDLENGTH
            if (type == 1 && rdlength == 4 && offset + 4 <= response.size) {
                // A record: 4 bytes = IPv4
                return "${response[offset] and 0xFF}.${response[offset + 1] and 0xFF}.${response[offset + 2] and 0xFF}.${response[offset + 3] and 0xFF}"
            }
            offset += rdlength
        }
        return null
    }

    /** Skips a DNS-encoded domain name starting at [offset], returning the offset just after it. */
    private fun skipDomain(data: ByteArray, offset: Int): Int? {
        var pos = offset
        var jumped = false
        var savePos = -1
        while (true) {
            if (pos >= data.size) return null
            val len = data[pos].toInt() and 0xFF
            if (len == 0) {
                return if (jumped) savePos else pos + 1
            }
            if (len and 0xC0 != 0) {
                // Pointer
                if (!jumped) savePos = pos + 2
                jumped = true
                val ptr = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                pos = ptr
            } else {
                pos += 1 + len
                if (!jumped) savePos = pos
            }
        }
    }
}
