package com.bigrocket.tunnel

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * MuxSession - one tunnel, many flows.
 *
 * The whole point of the address tunnel is that a new connection costs **about one round trip
 * on an already-open channel**, and nothing else: no DNS on the phone, no TCP handshake to a
 * proxy, no SOCKS greeting, no CONNECT. This class is what turns that into sockets and UDP
 * channels the relay engines can use unchanged.
 *
 * Responsibilities (each one is a bug we would otherwise have per flow):
 *
 *  - **stream ids** — allocate locally, `1..0x7FFE`; `0x7FFF` is the UDP pseudo-stream.
 *  - **one clean channel** — [carrier] may be bonded (two radios); sequence numbers on every
 *    frame let the far side (and us, for the return path) reorder what the radios interleave.
 *  - **flow control** — credit-based: the far side may only send us what we have advertised.
 *    A slow reader therefore slows its own flow, never the phone's memory.
 *  - **recovery** — a hole in a stream is NACKed after a short grace period; the far side keeps
 *    its last 128 chunks for exactly that. A NACK loop that persists gives up and resets the
 *    flow instead of hanging the app.
 *  - **liveness** — PING/PONG on an idle channel, so a dead address is noticed in seconds and
 *    the runtime can fall back to engine 1 (the user's requested failure behaviour).
 */
class MuxSession(
    private val carrier: ChannelEndpoint,
    private val cookie: Int,
    private val label: String = "",
) {

    /** A whole TCP flow carried by the tunnel, presented as a [Socket]. */
    inner class MuxSocket(
        val streamId: Int,
        private val host: String,
        private val port: Int,
    ) : Socket() {

        /** Never `this`: this object IS a Socket, and locking a Socket invites deadlocks. */
        private val closeLock = Object()

        private val inbound = ByteQueue(2 shl 20)
        private val reorderer = Reorderer()
        private val closedByPeer = AtomicBoolean(false)
        private val resetByPeer = AtomicBoolean(false)
        private var closeSent = false

        /** Bytes handed to the far side (used for the FIN total and for stats). */
        private val sentBytes = AtomicLong(0)
        private var receiveCredit = INITIAL_WINDOW
        private val creditLock = Object()

        @Volatile var openedAtMs: Long = System.currentTimeMillis()
            private set

        @Volatile var remoteAddressLabel: String = ""
            private set

        /**
         * Socket read timeout in millis; 0 = wait forever (the Socket default for a VPN peer is
         * generous, but the runtime sets 60 s on egress sockets so a silent far side fails a
         * FLOW instead of parking a relay thread).
         */
        @Volatile private var readTimeoutMs = READ_TIMEOUT_MS

        override fun setSoTimeout(timeout: Int) {
            readTimeoutMs = if (timeout <= 0) 0 else timeout.toLong()
        }

        override fun getSoTimeout(): Int = readTimeoutMs.toInt()

        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                val n = inbound.read(one, 0, 1, READ_TIMEOUT_MS)
                if (n > 0) grantCredit(n)
                return if (n <= 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                // Socket streams are BLOCKING: a `read` of ours only returns 0 when the caller
                // asked for 0 bytes, never because a poll window expired. Underneath, the queue
                // is polled in short slices so a close/FIN is noticed promptly without a
                // thread parked forever on a dead stream.
                val budget = readTimeoutMs
                val deadline = if (budget <= 0) Long.MAX_VALUE else System.currentTimeMillis() + budget
                while (true) {
                    val n = inbound.read(b, off, len, READ_SLICE_MS)
                    if (n > 0) {
                        // Give the credit back one chunk at a time, exactly like the far side does.
                        grantCredit(n)
                        return n
                    }
                    if (n < 0) {
                        // A remote close is EOF, not a failure: `read` returns -1 so callers
                        // (HTTP clients, TLS, the relay engines) see an ordinary end of stream.
                        // Only a RESET is an error worth throwing.
                        val reason = inbound.failureReason()
                        if (reason != null) throw IOException(reason)
                        AppLog.info(
                            TAG,
                            "EOF stream=$streamId delivered=${reorderer.nextExpected()} fin=$finSeen/$finTotal",
                        )
                        return -1
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        throw SocketTimeoutException("stream $streamId: no data for ${budget}ms")
                    }
                }
            }

            override fun available(): Int = inbound.available()

            override fun close() {
                shutdownOutput()
            }
        }

        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                var cursor = off
                val end = off + len
                while (cursor < end) {
                    val chunk = minOf(MAX_CHUNK, end - cursor)
                    val payload = b.copyOfRange(cursor, cursor + chunk)
                    val offset = sentBytes.get().toInt()
                    val body = Brt.intBytes(offset) + payload
                    if (!send(Brt.DATA, Brt.withStreamId(streamId, body))) {
                        throw IOException("stream $streamId: tunnel is down")
                    }
                    sentBytes.addAndGet(chunk.toLong())
                    totalBytesOut.addAndGet(chunk.toLong())
                    cursor += chunk
                    recentSent[offset.toLong()] = payload
                    if (recentSent.size > RESEND_CACHE) {
                        val it = recentSent.keys.iterator()
                        it.next()
                        it.remove()
                    }
                }
            }

            override fun flush() = Unit

            override fun close() = shutdownOutput()
        }

        /** Re-sends a chunk the far side asked for (it had a hole). */
        fun resend(offset: Long, length: Int) {
            var cursor = offset
            val end = offset + length
            while (cursor < end) {
                val chunk = recentSent[cursor] ?: return
                val body = Brt.intBytes(cursor.toInt()) + chunk
                send(Brt.DATA, Brt.withStreamId(streamId, body))
                cursor += chunk.size
            }
        }

        private fun grantCredit(bytes: Int) = synchronized(creditLock) {
            receiveCredit += bytes
            send(Brt.WINDOW, Brt.withStreamId(streamId, Brt.intBytes(bytes)))
        }

        internal fun onData(offset: Long, data: ByteArray) {
            reorderer.offer(offset, data)
        }

        /** Called by the session after every DATA batch so a waiting FIN can complete. */
        internal fun afterData() {
            drain()
            awaitAndFinish()
        }

        internal fun drain() {
            while (true) {
                val chunk = reorderer.poll() ?: break
                if (!inbound.writeBlocking(chunk, chunk.size, 30_000)) {
                    if (inbound.isClosed) return
                    // Reader too slow for 30 s on a 2 MiB queue: drop the flow rather than OOM.
                    inbound.fail("stream $streamId: reader stalled")
                    return
                }
            }
            val missing = reorderer.firstMissing() ?: return
            val now = System.currentTimeMillis()
            if (now - lastNackAt >= NACK_INTERVAL_MS && nackTries < MAX_NACKS) {
                lastNackAt = now
                nackTries++
                val length = minOf(Brt.MAX_PAYLOAD.toLong(), missing - reorderer.nextExpected()).toInt()
                val body = Brt.intBytes(reorderer.nextExpected().toInt()) + Brt.intBytes(maxOf(1, length))
                send(Brt.NACK, Brt.withStreamId(streamId, body))
            }
        }

        private var lastNackAt = 0L
        private var nackTries = 0

        internal fun onOpenOk(remote: String?) {
            remoteAddressLabel = remote ?: "?"
            openedAtMs = System.currentTimeMillis()
        }

        internal fun onFin(totalFromPeer: Long) {
            // The far side sends FIN with the total number of bytes it wrote. Frames are
            // interleaved over two radios, so the tail can still be in flight when the FIN
            // arrives: closing here would truncate the stream (this was a real bug - 2 of 12
            // flows lost their reply). Wait for the total to be reassembled, then EOF.
            finTotal = totalFromPeer
            finSeen = true
            awaitAndFinish()
        }

        @Volatile private var finTotal = -1L
        @Volatile private var finSeen = false

        /** Completes a FIN once every byte the far side promised has been handed to the queue. */
        internal fun awaitAndFinish() {
            if (!finSeen) return
            if (finTotal < 0 || reorderer.nextExpected() < finTotal) return
            drain()
            if (reorderer.nextExpected() < finTotal) return
            synchronized(closeLock) {
                if (!eofDelivered) {
                    eofDelivered = true
                    closedByPeer.set(true)
                    inbound.close()
                }
            }
        }

        private var eofDelivered = false

        internal fun onReset(reason: String) {
            resetByPeer.set(true)
            inbound.fail("stream $streamId reset by far side: $reason")
        }

        /** Half-close: tell the far side the request body is complete (FIN carries the total). */
        override fun shutdownOutput() {
            synchronized(closeLock) {
                if (closeSent) return
                closeSent = true
            }
            val body = Brt.intBytes(sentBytes.get().toInt())
            send(Brt.FIN, Brt.withStreamId(streamId, body))
            // Deliberately NOT removed here: the reply to what we just sent is still coming, and
            // dropping the id now throws those bytes away. RST (or the far side's FIN + our
            // close) removes it.
        }

        override fun close() {
            shutdownOutput()
            inbound.close()
            reorderer.close()
            super.close()
        }

        override fun isClosed(): Boolean = closeSent && (markedClosed || closedByPeer.get())

        @Volatile private var markedClosed = false

        fun markClosed() {
            markedClosed = true
        }

        fun wasReset(): Boolean = resetByPeer.get()

        fun stats(): String =
            "stream#$streamId $host:$port sent=${sentBytes.get()} recvCredit=$receiveCredit " +
                "remote=$remoteAddressLabel"
    }

    /**
     * A UDP flow carried by the tunnel - one shared pseudo-stream, per-datagram addressing.
     *
     * This is what makes "DNS through the tunnel" work with no leak: the phone never resolves
     * the names it sends, the far side does, and replies arrive as DGRAM_RX frames carrying the
     * real source address.
     */
    inner class MuxDatagramChannel(private val receiveQueue: java.util.ArrayDeque<EgressDatagramLite>) {

        fun send(host: String, port: Int, payload: ByteArray, length: Int): Boolean {
            val address = Brt.encodeAddress(host, port)
            val body = ByteArray(2 + address.size + length)
            body[0] = ((Brt.UDP_STREAM ushr 8) and 0xFF).toByte()
            body[1] = (Brt.UDP_STREAM and 0xFF).toByte()
            System.arraycopy(address, 0, body, 2, address.size)
            System.arraycopy(payload, 0, body, 2 + address.size, length)
            totalDatagramsOut.incrementAndGet()
            return send(Brt.DGRAM, body)
        }

        internal fun deliver(host: String, port: Int, data: ByteArray, length: Int) {
            synchronized(receiveLock) {
                if (receiveQueue.size >= MAX_QUEUED_DATAGRAMS) receiveQueue.removeFirst()
                receiveQueue.addLast(EgressDatagramLite(host, port, data.copyOf(length), length))
                receiveLock.notifyAll()
            }
            totalDatagramsIn.incrementAndGet()
        }

        /** Its own monitor: java.util.ArrayDeque's monitor cannot be waited on in Kotlin. */
        internal val receiveLock = Object()
    }

    class EgressDatagramLite(val host: String, val port: Int, val data: ByteArray, val length: Int)

    // --- session state --------------------------------------------------------------------

    private val streams = ConcurrentHashMap<Int, MuxSocket>()
    private val nextStreamId = AtomicInteger(1)
    private val pendingOpens = ConcurrentHashMap<Int, OpenResult>()
    private val running = AtomicBoolean(false)
    private val outSequence = AtomicInteger(0)
    private val channelReady = AtomicBoolean(false)
    private val nonce = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
    @Volatile private var datagramChannel: MuxDatagramChannel? = null
    @Volatile private var lastInboundAt = 0L
    @Volatile private var lastPingAt = 0L
    @Volatile private var downReason: String = ""

    private val recentSent = object : java.util.LinkedHashMap<Long, ByteArray>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > RESEND_CACHE
    }

    val totalBytesOut = AtomicLong(0)
    val totalBytesIn = AtomicLong(0)
    val totalFramesOut = AtomicLong(0)
    val totalFramesIn = AtomicLong(0)
    val totalDatagramsOut = AtomicLong(0)
    val totalDatagramsIn = AtomicLong(0)
    val totalNacksIn = AtomicLong(0)
    val totalNacksSent = AtomicLong(0)

    /** Short description for the UI card. */
    val describe: String get() = "$label cookie=$cookie flows=${streams.size}${if (isReady) "" else " (not ready)"}"

    val isReady: Boolean get() = running.get() && channelReady.get()

    fun downReason(): String = downReason

    /** Opens the carrier and completes the HELLO handshake. Blocking; call off the UI thread. */
    fun start(handshakeTimeoutMs: Long = 5_000): Boolean {
        if (running.get() && channelReady.get()) return true
        running.set(true)
        val ok = carrier.start { raw, length, frame -> onFrame(raw, length, frame) }
        if (!ok) {
            downReason = "carrier ${carrier.label} did not come up"
            running.set(false)
            return false
        }
        val deadline = System.currentTimeMillis() + handshakeTimeoutMs
        val hello = Brt.encode(Brt.HELLO, outSequence.getAndIncrement(), cookie, nonce)
        while (System.currentTimeMillis() < deadline) {
            carrier.send(hello)
            val waitUntil = minOf(deadline, System.currentTimeMillis() + 400)
            while (System.currentTimeMillis() < waitUntil) {
                if (channelReady.get()) {
                    AppLog.info(TAG, "session up: $describe")
                    return true
                }
                sleepQuietly(20)
            }
        }
        downReason = "no HELLO_ACK from ${carrier.label} within ${handshakeTimeoutMs}ms"
        AppLog.info(TAG, "handshake failed: $downReason")
        return false
    }

    /** Keeps the channel warm and notices a dead far side. Call once per few seconds. */
    fun keepAlive() {
        if (!running.get()) return
        val now = System.currentTimeMillis()
        if (now - lastPingAt >= PING_INTERVAL_MS) {
            lastPingAt = now
            send(Brt.PING, nonce)
        }
        if (now - lastInboundAt > PING_INTERVAL_MS * 4) {
            downReason = "far side silent for ${(now - lastInboundAt) / 1000}s"
        }
    }

    fun lastInboundMs(): Long = lastInboundAt

    /** True if the far side answered recently enough to be trusted. */
    fun isLive(maxSilenceMs: Long = 30_000): Boolean =
        running.get() && lastInboundAt > 0 && System.currentTimeMillis() - lastInboundAt < maxSilenceMs

    /**
     * Opens [host]:[port] through the tunnel - the per-flow cost this whole design removes.
     * @return a socket the caller reads/writes unchanged.
     */
    @Throws(IOException::class)
    fun openTcp(host: String, port: Int, timeoutMs: Int = OPEN_TIMEOUT_MS): Socket {
        if (!running.get()) throw IOException("tunnel is not running")
        if (!channelReady.get()) throw IOException("tunnel handshake not complete (${downReason.ifEmpty { "no answer" }})")
        var lastError = "no answer"
        // The far side connects with a 10 s timeout of its own; a first OPEN can also be lost
        // with the HANDSHAKE, so retry a couple of times before declaring the flow dead.
        for (attempt in 1..OPEN_ATTEMPTS) {
            val id = allocateStreamId()
            val socket = MuxSocket(id, host, port)
            val result = OpenResult()
            streams[id] = socket
            pendingOpens[id] = result
            val address = Brt.encodeAddress(host, port)
            if (!send(Brt.OPEN, Brt.withStreamId(id, address))) {
                streams.remove(id)
                pendingOpens.remove(id)
                throw IOException("tunnel is down (${downReason.ifEmpty { "send failed" }})")
            }
            val outcome = result.await(timeoutMs)
            pendingOpens.remove(id)
            when {
                outcome == null -> {
                    streams.remove(id)
                    lastError = "OPEN #$id ${host}:$port timed out after ${timeoutMs}ms"
                }
                outcome.error != null -> {
                    streams.remove(id)
                    // A real refusal (port closed / name does not resolve) will not get better
                    // by asking again: report it to the caller immediately.
                    throw IOException("${host}:$port unreachable: ${outcome.error}")
                }
                else -> {
                    socket.onOpenOk(outcome.remote)
                    AppLog.info(
                        TAG,
                        "open #$id $host:$port -> ${outcome.remote} (attempt $attempt) over ${carrier.label}",
                    )
                    return socket
                }
            }
        }
        throw IOException(lastError)
    }

    /** Attaches a UDP channel backed by this session's DGRAM frames. */
    fun openUdpChannel(): MuxDatagramChannel {
        val queue = java.util.ArrayDeque<EgressDatagramLite>()
        return MuxDatagramChannel(queue).also {
            datagramChannel = it
            udpQueues[it] = queue
        }
    }

    private val udpQueues = ConcurrentHashMap<MuxDatagramChannel, java.util.ArrayDeque<EgressDatagramLite>>()

    /** Polls one datagram from a channel created by [openUdpChannel]. */
    fun pollDatagram(channel: MuxDatagramChannel, timeoutMs: Long): EgressDatagramLite? {
        val queue = udpQueues[channel] ?: return null
        val monitor = channel.receiveLock
        synchronized(monitor) {
            if (queue.isEmpty() && timeoutMs > 0) {
                try {
                    monitor.wait(timeoutMs)
                } catch (_: InterruptedException) {
                    return null
                }
            }
            return queue.pollFirst()
        }
    }

    /** Blocking PING/PONG used by the self-test: proves the address carries frames, both ways. */
    fun ping(timeoutMs: Long = 1_500): Long {
        val sent = System.currentTimeMillis()
        val probe = Brt.encode(Brt.PING, outSequence.getAndIncrement(), cookie, nonce)
        for (attempt in 0 until 3) {
            carrier.send(probe)
            val deadline = sent + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (lastPongAt >= sent) return System.currentTimeMillis() - sent
                sleepQuietly(20)
            }
            if (attempt == 2) break
        }
        return -1
    }

    @Volatile private var lastPongAt = 0L

    fun close() {
        running.set(false)
        channelReady.set(false)
        streams.values.forEach { runCatching { it.close() } }
        streams.clear()
        carrier.close()
        AppLog.info(TAG, "session closed ($label)")
    }

    // --- receive path ---------------------------------------------------------------------

    private fun onFrame(raw: ByteArray, length: Int, frame: Brt.Frame) {
        // Every carrier reaches this point; the cookie check is what keeps a shared UDP port
        // (or a DNS server that answered our probe) from injecting frames into our session.
        if (Brt.cookieOf(raw, length) != cookie) return
        lastInboundAt = System.currentTimeMillis()
        totalFramesIn.incrementAndGet()
        when (frame.type) {
            Brt.HELLO_ACK, Brt.PONG -> {
                if (frame.type == Brt.PONG) lastPongAt = System.currentTimeMillis()
                channelReady.set(true)
            }

            Brt.OPEN_OK -> {
                val id = frame.streamId()
                val address = Brt.decodeAddress(frame.payload, 2)
                pendingOpens.remove(id)?.complete(address?.toString() ?: "?", null)
            }

            Brt.OPEN_ERR -> {
                val id = frame.streamId()
                pendingOpens.remove(id)?.complete(
                    null,
                    String(frame.payload, 2, frame.payload.size - 2, Charsets.UTF_8),
                )
            }

            Brt.DATA -> {
                if (frame.payload.size < 6) return
                // A stream we removed locally (we sent FIN) can still receive the far side's
                // in-flight reply. Hold every stream in `streams` until it RESETS/CLOSES so those
                // last bytes are not dropped; a closed-and-drained stream simply has nowhere to
                // put them, which is fine.
                val stream = streams[frame.streamId()] ?: return
                stream.onData(Brt.readInt(frame.payload, 2).toLong() and 0xFFFFFFFFL, frame.payload.copyOfRange(6, frame.payload.size))
                totalBytesIn.addAndGet(frame.payload.size - 6L)
                stream.afterData()
            }

            Brt.FIN -> {
                val stream = streams[frame.streamId()] ?: return
                val total = if (frame.payload.size >= 6) Brt.readInt(frame.payload, 2).toLong() and 0xFFFFFFFFL else -1L
                stream.onFin(if (total >= 0) total else Long.MAX_VALUE)
            }

            Brt.RST -> {
                val id = frame.streamId()
                streams.remove(id)?.apply {
                    onReset(String(frame.payload, 2, (frame.payload.size - 2).coerceAtLeast(0), Charsets.UTF_8))
                    markClosed()
                }
            }

            Brt.WINDOW -> {
                // The far side granting us room would be flow control for the OTHER direction;
                // this design keeps the phone->far-side direction bounded by the caller's writes,
                // so the frame is accepted and ignored (it is still proof of life).
                Unit
            }

            Brt.NACK -> {
                if (frame.payload.size < 10) return
                totalNacksIn.incrementAndGet()
                val stream = streams[frame.streamId()] ?: return
                stream.resend(
                    Brt.readInt(frame.payload, 2).toLong() and 0xFFFFFFFFL,
                    Brt.readInt(frame.payload, 6),
                )
            }

            Brt.DGRAM_RX -> {
                if (frame.payload.size < 3) return
                val address = Brt.decodeAddress(frame.payload, 2) ?: return
                val start = 2 + Brt.addressLength(frame.payload, 2)
                if (start > frame.payload.size) return
                datagramChannel?.deliver(
                    address.host,
                    address.port,
                    frame.payload.copyOfRange(start, frame.payload.size),
                    frame.payload.size - start,
                )
            }
        }
    }

    // --- send path ------------------------------------------------------------------------

    private fun send(type: Int, payload: ByteArray): Boolean {
        val frame = Brt.encode(type, outSequence.getAndIncrement(), cookie, payload)
        val ok = carrier.send(frame)
        if (ok) totalFramesOut.incrementAndGet()
        return ok
    }

    private fun allocateStreamId(): Int {
        while (true) {
            val id = nextStreamId.getAndUpdate { if (it >= 0x7FFE) 1 else it + 1 }
            if (id in 1..0x7FFE && !streams.containsKey(id)) return id
        }
    }

    /** Result of one OPEN, awaited by [openTcp] and completed by the receive thread. */
    private class OpenResult {
        private val lock = Object()
        private var remote: String? = null
        private var error: String? = null
        private var done = false

        fun complete(remote: String?, error: String?) = synchronized(lock) {
            this.remote = remote
            this.error = error
            done = true
            lock.notifyAll()
        }

        fun await(timeoutMs: Int): Outcome? {
            val deadline = System.currentTimeMillis() + timeoutMs
            synchronized(lock) {
                while (!done) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) return null
                    try {
                        lock.wait(left)
                    } catch (_: InterruptedException) {
                        return null
                    }
                }
                return Outcome(remote, error)
            }
        }
    }

    private class Outcome(val remote: String?, val error: String?)

    companion object {
        private const val TAG = "Tunnel"

        /** How much the far side may have in flight toward us before we must grant more. */
        const val INITIAL_WINDOW = 256 * 1024
        const val MAX_CHUNK = Brt.MAX_PAYLOAD - 6
        const val READ_TIMEOUT_MS = 30_000L
        const val READ_SLICE_MS = 250L
        const val OPEN_TIMEOUT_MS = 12_000
        const val OPEN_ATTEMPTS = 2
        const val PING_INTERVAL_MS = 5_000L
        const val NACK_INTERVAL_MS = 40L
        const val MAX_NACKS = 6
        const val RESEND_CACHE = 128
        const val MAX_QUEUED_DATAGRAMS = 64
    }
}
