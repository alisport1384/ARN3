package com.bigrocket.tunnel

import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The DoH carrier's HTTP side: POST an `application/dns-message` body to the endpoint and return
 * the raw response body. The body is opaque to the server (and to anything watching) - it is a
 * BRT1 frame, or a batch of frames in a response.
 *
 * Kept separate from [DohChannelEndpoint] for two reasons:
 *  - the endpoint logic (polling, framing, delivery) is pure and unit-testable with a fake
 *    transport, and
 *  - wiring a socket through the app's chosen [java.net.Network] (so the DoH request itself is
 *    carried by the radio we want, and protected from our own TUN) is an Android concern that
 *    belongs with the rest of the platform code.
 *
 * Note on the API shape: `RequestBody.create(mediaType, bytes)` is only a static helper on
 * specific OkHttp versions, so the Kotlin extension [toRequestBody] is used here - it exists on
 * every OkHttp 4.x/5.x line and compiles against the app's dependency.
 */
class OkHttpDohTransport(
    private val url: String,
    private val socketFactory: SocketFactory,
    private val timeoutMs: Long = 8_000,
) : DohTransport {

    private val mediaType: okhttp3.MediaType = DOH_TYPE.toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs * 2, TimeUnit.MILLISECONDS)
        // The socket factory decides which radio carries the request; see AddressTunnelRuntime.
        .socketFactory(socketFactory)
        .retryOnConnectionFailure(true)
        .build()

    @Throws(IOException::class)
    override fun post(body: ByteArray, timeoutMs: Int): ByteArray {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(mediaType))
            .header("Accept", DOH_TYPE)
            .header("Content-Type", DOH_TYPE)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("DoH endpoint $url returned HTTP ${response.code}")
            }
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    companion object {
        const val DOH_TYPE = "application/dns-message"
    }
}

/**
 * A resolver that answers A queries over plain DNS - used only for the *addressing* step when
 * the user types a bare name (never for the flows being tunnelled: those are resolved far away,
 * which is what keeps DNS from leaking out of a different radio).
 *
 * It is a [DohTransport] shape because "post a DNS message, get a DNS message" is the same
 * operation; only the framing differs (no length prefix, no HTTP).
 */
class PlainDnsTransport(
    private val server: String,
    private val port: Int = 53,
    private val binding: SocketBinding = PlainBinding,
    private val timeoutMs: Int = 3_000,
) : DohTransport {

    @Throws(IOException::class)
    override fun post(body: ByteArray, timeoutMs: Int): ByteArray {
        val socket = binding.openDatagram()
        try {
            socket.soTimeout = timeoutMs
            val address = InetSocketAddress(server, port)
            if (address.isUnresolved) throw IOException("cannot resolve DNS server $server")
            socket.send(java.net.DatagramPacket(body, body.size, address))
            val buffer = ByteArray(4096)
            val packet = java.net.DatagramPacket(buffer, buffer.size)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                socket.receive(packet)
                // Ignore answers to other questions (a shared resolver can be busy).
                if (packet.length >= 2 && body.size >= 2 &&
                    packet.data[0] == body[0] && packet.data[1] == body[1]
                ) {
                    return packet.data.copyOf(packet.length)
                }
            }
            throw IOException("no DNS answer from $server:$port")
        } finally {
            runCatching { socket.close() }
        }
    }
}

/** Wraps a plain-DNS exchange as a [DohTransport] so the same code path parses both. */
class DnsAnswerTransport(
    private val transport: DohTransport,
    private val labeller: (String) -> Unit = { AppLog.info("Tunnel", it) },
) : DohTransport {
    override fun post(body: ByteArray, timeoutMs: Int): ByteArray = transport.post(body, timeoutMs)
}
