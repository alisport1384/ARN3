package com.bigrocket.tunnel

import java.util.TreeMap

/**
 * BRT1 - the wire format of the "address tunnel" (merge engine 2, running inside the phone).
 *
 * This object is deliberately **Android-free**: it is compiled straight into the JVM unit tests
 * and into the far-side bridge's own copy, so the exact bytes the phone emits are the bytes the
 * tests decode. Nothing here may import `android.*`.
 *
 * Why a new frame format at all? The existing bonding protocol (`com.bigrocket.bonding.frame`)
 * merges the *two radios* of one phone into one stream, but its peer is a bonding server that
 * speaks that protocol and nothing else. The user's requirement was to point the merged stream
 * at *any* address - a plain IP, a DNS answer, a DoH endpoint - and have the merge happen
 * virtually, in the phone (docs/AddressTunnel.md). So:
 *
 *   BRT1 = a tiny multiplexing envelope whose ONLY job is to carry many TCP flows and many UDP
 *          datagrams over ONE channel that is spread over both radios by this app.
 *
 * Layout (big-endian, 12 bytes):
 * ```
 *  0  4  magic 'B''R''T''1'
 *  4  2  cookie     - 16-bit session tag, derived from the address the user typed
 *  6  1  type       - one of the constants below
 *  7  1  flags      - reserved, 0
 *  8  4  sequence   - per sender, wraps; what the far side uses to reorder interleaved radios
 * 12  N  payload
 * ```
 *
 * Payloads that belong to a stream start with `[streamId:2]` (see [withStreamId]); the UDP
 * pseudo-stream uses [UDP_STREAM]. Addressing inside OPEN/DGRAM is SOCKS5-style (ATYP + host +
 * port) so that *names are resolved on the far side* and never leak from the phone.
 */
object Brt {

    const val HEADER = 12
    const val MAX_PAYLOAD = 1400

    /** Stream id reserved for UDP: one "flow" carrying every datagram, like a UDP association. */
    const val UDP_STREAM = 0x7FFF

    // Frame types (kept numerically identical to far-side/BridgeServer.kt -> BrtWire).
    const val PING = 1
    const val PONG = 2
    const val HELLO = 3
    const val HELLO_ACK = 4
    const val OPEN = 5
    const val OPEN_OK = 6
    const val OPEN_ERR = 7
    const val DATA = 8
    const val FIN = 9
    const val RST = 10
    const val WINDOW = 11
    const val DGRAM = 12
    const val DGRAM_RX = 13
    const val NACK = 14

    fun typeName(type: Int): String = when (type) {
        PING -> "PING"
        PONG -> "PONG"
        HELLO -> "HELLO"
        HELLO_ACK -> "HELLO_ACK"
        OPEN -> "OPEN"
        OPEN_OK -> "OPEN_OK"
        OPEN_ERR -> "OPEN_ERR"
        DATA -> "DATA"
        FIN -> "FIN"
        RST -> "RST"
        WINDOW -> "WINDOW"
        DGRAM -> "DGRAM"
        DGRAM_RX -> "DGRAM_RX"
        NACK -> "NACK"
        else -> "?$type"
    }

    /** One decoded frame. [sequence] is the sender's counter, not a per-stream offset. */
    class Frame(val type: Int, val sequence: Int, val payload: ByteArray) {
        fun streamId(): Int = streamId(payload)
        override fun toString(): String = "${typeName(type)} seq=$sequence len=${payload.size}"
    }

    fun encode(type: Int, sequence: Int, cookie: Int, payload: ByteArray, length: Int = payload.size): ByteArray {
        val out = ByteArray(HEADER + length)
        out[0] = 'B'.code.toByte()
        out[1] = 'R'.code.toByte()
        out[2] = 'T'.code.toByte()
        out[3] = '1'.code.toByte()
        out[4] = ((cookie ushr 8) and 0xFF).toByte()
        out[5] = (cookie and 0xFF).toByte()
        out[6] = type.toByte()
        out[7] = 0
        out[8] = ((sequence ushr 24) and 0xFF).toByte()
        out[9] = ((sequence ushr 16) and 0xFF).toByte()
        out[10] = ((sequence ushr 8) and 0xFF).toByte()
        out[11] = (sequence and 0xFF).toByte()
        System.arraycopy(payload, 0, out, HEADER, length)
        return out
    }

    /** @return null when the buffer is not a BRT1 frame (garbage, truncated, or a DNS answer). */
    fun decode(data: ByteArray, length: Int = data.size): Frame? {
        if (length < HEADER || length > data.size) return null
        if (data[0] != 'B'.code.toByte() || data[1] != 'R'.code.toByte() ||
            data[2] != 'T'.code.toByte() || data[3] != '1'.code.toByte()
        ) return null
        val type = data[6].toInt() and 0xFF
        if (type == 0) return null
        val seq = ((data[8].toInt() and 0xFF) shl 24) or ((data[9].toInt() and 0xFF) shl 16) or
            ((data[10].toInt() and 0xFF) shl 8) or (data[11].toInt() and 0xFF)
        return Frame(type, seq, data.copyOfRange(HEADER, length))
    }

    /** Cookie carried by a raw frame buffer, without decoding the whole frame. */
    fun cookieOf(data: ByteArray, length: Int = data.size): Int =
        if (length < 6) -1 else ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)

    // --- stream framing -------------------------------------------------------------------

    fun streamId(payload: ByteArray): Int =
        if (payload.size < 2) -1 else ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)

    fun withStreamId(id: Int, body: ByteArray): ByteArray {
        val out = ByteArray(2 + body.size)
        out[0] = ((id ushr 8) and 0xFF).toByte()
        out[1] = (id and 0xFF).toByte()
        System.arraycopy(body, 0, out, 2, body.size)
        return out
    }

    fun intBytes(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    fun readInt(d: ByteArray, off: Int): Int =
        if (off + 4 > d.size) 0
        else ((d[off].toInt() and 0xFF) shl 24) or ((d[off + 1].toInt() and 0xFF) shl 16) or
            ((d[off + 2].toInt() and 0xFF) shl 8) or (d[off + 3].toInt() and 0xFF)

    fun unsignedIntToLong(v: Int): Long = v.toLong() and 0xFFFFFFFFL

    // --- SOCKS5-style addressing (names are resolved on the far side) ----------------------

    class Address(val host: String, val port: Int) {
        override fun toString(): String = "$host:$port"
    }

    fun encodeAddress(host: String, port: Int): ByteArray {
        val ipv4 = parseIpv4(host)
        if (ipv4 != null) {
            val out = ByteArray(7)
            out[0] = 0x01
            System.arraycopy(ipv4, 0, out, 1, 4)
            out[5] = ((port ushr 8) and 0xFF).toByte()
            out[6] = (port and 0xFF).toByte()
            return out
        }
        val ipv6 = parseIpv6(host)
        if (ipv6 != null) {
            val out = ByteArray(19)
            out[0] = 0x04
            System.arraycopy(ipv6, 0, out, 1, 16)
            out[17] = ((port ushr 8) and 0xFF).toByte()
            out[18] = (port and 0xFF).toByte()
            return out
        }
        val bytes = host.toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + bytes.size + 2)
        out[0] = 0x03
        out[1] = bytes.size.toByte()
        System.arraycopy(bytes, 0, out, 2, bytes.size)
        out[2 + bytes.size] = ((port ushr 8) and 0xFF).toByte()
        out[3 + bytes.size] = (port and 0xFF).toByte()
        return out
    }

    fun decodeAddress(d: ByteArray, off: Int = 0): Address? {
        if (off >= d.size) return null
        var pos = off
        val atyp = d[pos].toInt() and 0xFF
        pos++
        val host: String
        when (atyp) {
            0x01 -> {
                if (pos + 4 > d.size) return null
                host = "${d[pos].toInt() and 0xFF}.${d[pos + 1].toInt() and 0xFF}." +
                    "${d[pos + 2].toInt() and 0xFF}.${d[pos + 3].toInt() and 0xFF}"
                pos += 4
            }
            0x03 -> {
                if (pos >= d.size) return null
                val len = d[pos].toInt() and 0xFF
                pos++
                if (pos + len > d.size) return null
                host = String(d, pos, len, Charsets.UTF_8)
                pos += len
            }
            0x04 -> {
                if (pos + 16 > d.size) return null
                val sb = StringBuilder()
                for (i in 0 until 16 step 2) {
                    if (i > 0) sb.append(':')
                    sb.append(
                        Integer.toHexString(
                            ((d[pos + i].toInt() and 0xFF) shl 8) or (d[pos + i + 1].toInt() and 0xFF)
                        )
                    )
                }
                host = sb.toString()
                pos += 16
            }
            else -> return null
        }
        if (pos + 2 > d.size) return null
        val port = ((d[pos].toInt() and 0xFF) shl 8) or (d[pos + 1].toInt() and 0xFF)
        return Address(host, port)
    }

    fun addressLength(d: ByteArray, off: Int): Int {
        if (off >= d.size) return 0
        return when (d[off].toInt() and 0xFF) {
            0x01 -> 7
            0x03 -> if (off + 1 >= d.size) 0 else 2 + (d[off + 1].toInt() and 0xFF) + 2
            0x04 -> 19
            else -> 0
        }
    }

    /**
     * Minimal IPv6 parser (enough for literals a user can type, including `::` compression and a
     * trailing IPv4 part). Returns 16 bytes, or null when [host] is not an IPv6 literal.
     */
    fun parseIpv6(host: String): ByteArray? {
        if (!host.contains(':')) return null
        val text = host.trim()
        val halves = text.split("::")
        if (halves.size > 2) return null
        fun groups(part: String): List<String>? {
            if (part.isEmpty()) return emptyList()
            return part.split(':')
        }
        val head = groups(halves[0]) ?: return null
        val tail = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
        val all = head + tail
        val out = ByteArray(16)
        var pos = 0
        fun writeGroup(group: String): Boolean {
            val value = group.toIntOrNull(16) ?: return false
            if (value !in 0..0xFFFF) return false
            out[pos++] = ((value ushr 8) and 0xFF).toByte()
            out[pos++] = (value and 0xFF).toByte()
            return true
        }
        for (group in head) {
            if (group.isEmpty()) return null
            if (group.contains('.')) {
                val v4 = parseIpv4(group) ?: return null
                if (pos + 4 > 16) return null
                System.arraycopy(v4, 0, out, pos, 4)
                pos += 4
            } else if (!writeGroup(group)) {
                return null
            }
        }
        val headEnd = pos
        pos = 16
        for (group in tail.asReversed()) {
            if (group.isEmpty()) return null
            val value = group.toIntOrNull(16) ?: return null
            if (value !in 0..0xFFFF || pos - 2 < headEnd) return null
            pos -= 2
            out[pos] = ((value ushr 8) and 0xFF).toByte()
            out[pos + 1] = (value and 0xFF).toByte()
        }
        val totalGroups = all.sumOf { if (it.contains('.')) 2 else 1 }
        if (halves.size == 1 && totalGroups != 8) return null
        if (halves.size == 2 && totalGroups > 8) return null
        return out
    }

    fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in parts.indices) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v.toByte()
        }
        return out
    }

    // --- cookie ---------------------------------------------------------------------------

    /**
     * The 16-bit session tag, derived deterministically from the address the user typed.
     *
     * Deriving (rather than exchanging) it means the app already knows the cookie before the
     * first frame, so the bridge can reject stray traffic from the very first packet - and the
     * DNS answer form can *carry* the same value, which is how "a DNS server is the address"
     * stays honest: only a bridge that knows the address can produce the matching tag.
     */
    fun cookieFor(address: String): Int {
        val normalised = address.trim().lowercase()
        return checksum(normalised.toByteArray(Charsets.UTF_8)) and 0xFFFF
    }

    fun checksum(bytes: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i + 1 < bytes.size) {
            sum = (sum + (((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF))) and 0xFFFF
            i += 2
        }
        if (i < bytes.size) sum = (sum + ((bytes[i].toInt() and 0xFF) shl 8)) and 0xFFFF
        return sum
    }
}

/**
 * Puts out-of-order chunks of ONE stream back in byte order.
 *
 * Both radios carry frames of the same stream, so the phone (and the bridge) *will* see them
 * interleaved and occasionally swapped. This is the reassembly point; it never blocks the
 * receiving thread, and it reports holes so a NACK can be sent for a datagram that was lost.
 */
class Reorderer(private val maxBufferedBytes: Int = 1 shl 20) {

    /**
     * Chunks by start offset. A LIST per offset, not one chunk: two writes can legitimately start
     * at the same offset (a short write followed by a retry, or a NACK retry of something we
     * already buffered), and a plain map silently dropped the second one - which truncated a
     * stream until this was found by the byte-queue test.
     */
    private val chunks = TreeMap<Long, ArrayDeque<ByteArray>>()
    private var consumed = 0L
    private var buffered = 0
    private var closed = false

    private val lock = Object()

    /** @return false when [offset] was already consumed (a duplicate from a NACK retry). */
    fun offer(offset: Long, data: ByteArray): Boolean = synchronized(lock) {
        if (closed) return false
        if (data.isEmpty()) {
            // A zero-length marker (FIN carries the total) only matters as "everything up to
            // here has been accounted for"; it must never remove buffered data.
            lock.notifyAll()
            return true
        }
        if (offset + data.size <= consumed) return false
        var start = offset
        var slice = data
        if (start < consumed) {
            val skip = (consumed - start).toInt()
            if (skip >= data.size) return false
            slice = data.copyOfRange(skip, data.size)
            start = consumed
        }
        chunks.getOrPut(start) { ArrayDeque() }.addLast(slice)
        buffered += slice.size
        if (buffered > maxBufferedBytes) {
            // A sender that runs away must not OOM the phone.
            while (buffered > maxBufferedBytes && chunks.isNotEmpty()) {
                val first = chunks.firstEntry()
                val dropped = first.value.removeFirst()
                buffered -= dropped.size
                if (first.value.isEmpty()) chunks.remove(first.key)
            }
        }
        lock.notifyAll()
        true
    }

    /** Next contiguous chunk, or null if the next offset is still missing. */
    fun poll(): ByteArray? = synchronized(lock) {
        val list = chunks[consumed] ?: return null
        val chunk = list.removeFirst()
        if (list.isEmpty()) chunks.remove(consumed)
        consumed += chunk.size
        buffered -= chunk.size
        chunk
    }

    /** Blocks up to [timeoutMs] for the next contiguous chunk (null on timeout / closed). */
    fun pollBlocking(timeoutMs: Long): ByteArray? = synchronized(lock) {
        if (chunks.containsKey(consumed)) return poll()
        if (closed) return null
        if (timeoutMs > 0) {
            try {
                lock.wait(timeoutMs)
            } catch (_: InterruptedException) {
                return null
            }
        }
        poll()
    }

    /** First offset the sender has delivered out of order (i.e. what a NACK should ask for). */
    fun firstMissing(): Long? = synchronized(lock) {
        val first = if (chunks.isEmpty()) null else chunks.firstKey()
        // A chunk sitting exactly at `consumed` is what poll() will hand out next - no hole.
        if (first != null && first > consumed) first else null
    }

    fun nextExpected(): Long = synchronized(lock) { consumed }

    fun bufferedBytes(): Int = synchronized(lock) { buffered }

    val isEmpty: Boolean get() = synchronized(lock) { chunks.isEmpty() }

    fun close() = synchronized(lock) {
        closed = true
        chunks.clear()
        buffered = 0
        lock.notifyAll()
    }
}

/**
 * A blocking FIFO of bytes - the thing a [java.net.Socket]'s InputStream is built on.
 *
 * Bounded on purpose: when a reader is slower than the far side, the mux stops handing out
 * window credit instead of growing this queue without limit.
 */
class ByteQueue(private val capacity: Int = 1 shl 20) {

    private val queue = ArrayDeque<ByteArray>()
    private var queued = 0
    private var closed = false
    private var failure: String? = null
    private val lock = Object()

    /** Non-blocking write. @return false when the queue is at capacity or closed. */
    fun write(data: ByteArray, length: Int = data.size): Boolean = synchronized(lock) {
        if (closed || length <= 0) return closed
        if (queued + length > capacity) return false
        queue.addLast(if (length == data.size) data else data.copyOf(length))
        queued += length
        lock.notifyAll() // wake a reader immediately: this is the latency path of every flow
        true
    }

    /** Waits for room. @return false on timeout or when the queue was closed. */
    fun writeBlocking(data: ByteArray, length: Int = data.size, timeoutMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (!closed && queued + length > capacity) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                try {
                    lock.wait(left)
                } catch (_: InterruptedException) {
                    return false
                }
            }
            if (closed) return false
            queue.addLast(if (length == data.size) data else data.copyOf(length))
            queued += length
            lock.notifyAll() // same reason as write(): a waiting reader must not wait for a slice
            return true
        }
    }

    /**
     * Reads up to [len] bytes, blocking up to [timeoutMs] for the FIRST one.
     *
     * Always returns whole buffer contents copied, and leaves no chunk half-consumed: the last
     * partially-taken chunk is put back as a remainder, never re-added in front of a queue whose
     * head it already replaced (that is how bytes went missing before this was tightened).
     * @return bytes copied, -1 at end of stream, 0 when nothing was available in time.
     */
    fun read(dst: ByteArray, off: Int, len: Int, timeoutMs: Long): Int {
        if (len <= 0) return 0
        synchronized(lock) {
            if (queue.isEmpty()) {
                if (closed) return -1
                if (timeoutMs <= 0) return 0
                try {
                    lock.wait(timeoutMs)
                } catch (_: InterruptedException) {
                    return -1
                }
            }
            if (queue.isEmpty()) return if (closed) -1 else 0
            var copied = 0
            while (copied < len) {
                val head = queue.firstOrNull() ?: break
                val take = minOf(head.size, len - copied)
                System.arraycopy(head, 0, dst, off + copied, take)
                copied += take
                queued -= take
                queue.removeFirst()
                if (take < head.size) {
                    // Keep the remainder at the FRONT as one chunk, then stop: the buffer is full.
                    queue.addFirst(head.copyOfRange(take, head.size))
                    break
                }
            }
            lock.notifyAll()
            return copied
        }
    }

    fun available(): Int = synchronized(lock) { queued }

    fun close() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }

    fun fail(message: String) = synchronized(lock) {
        failure = message
        closed = true
        lock.notifyAll()
    }

    fun failureReason(): String? = synchronized(lock) { failure }

    val isClosed: Boolean get() = synchronized(lock) { closed }
}
