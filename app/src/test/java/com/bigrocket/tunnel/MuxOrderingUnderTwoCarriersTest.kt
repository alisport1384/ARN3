package com.bigrocket.tunnel

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

/**
 * Regression guard for the concurrency bug that corrupted two-carrier TLS downloads.
 *
 * With two radios the phone runs one carrier thread per pipe, and both call into the same
 * [MuxSession]. `Reorderer.poll()` is atomic per chunk, but *two* drains running at once can still
 * hand the stream back in the wrong order: thread A polls chunk N, thread B polls chunk N + 1, and
 * B reaches the queue first. The bytes are then delivered out of order - invisible for plain text,
 * fatal for anything that verifies itself. TLS reports `bad_record_mac` / "Tag mismatch".
 *
 * The far side here is a stub, but its *threading* is exactly the phone's: two independent carrier
 * threads, no lock held while delivering, and the first thread deliberately parks so that
 * consecutive frames really do arrive inverted (a slow radio overtaken by a fast one). That
 * inversion is what makes this test fail when `MuxSession.drain()` is not serialised, and pass
 * when it is.
 */
class MuxOrderingUnderTwoCarriersTest {

    // More data than the mux's inbound queue can hold (2 MiB), so the reader MUST fall behind and
    // `drain()` really blocks on a full queue. That blocking window is the whole point: it is the
    // only place two carrier threads can be inside `drain()` at the same time for long enough to
    // invert - a fast test that never fills the queue cannot reproduce the bug.
    private val chunk = 1024
    private val chunks = 3 * 1024
    private val total = chunk * chunks
    private val payload = ByteArray(total) { (it / chunk).toByte() }

    /** A far side that speaks just enough of the protocol to open a stream, then floods it. */
    private inner class TwoRadioFarSide : ChannelEndpoint {
        private val inbound = LinkedBlockingQueue<Frame>()
        private val threads = ArrayList<Thread>()

        @Volatile private var running = true

        private inner class Frame(val raw: ByteArray, val decoded: Brt.Frame)

        override val label: String get() = "two-radio-stub"
        override val isOpen: Boolean get() = running

        override fun start(sink: FrameSink): Boolean {
            // A real bridge opens a stream with a full window of credit.
            credit.set(WINDOW_BYTES)
            repeat(2) { radio ->
                // The first carrier thread parks BEFORE delivering, so the next frame is
                // guaranteed to be picked up (and delivered) by the second thread while it waits.
                // A real two-radio condition, made deterministic here instead of hoping for it.
                val delay = if (radio == 0) DELIVER_INVERSION_MS else 0L
                val thread = Thread({
                    while (running) {
                        val next = inbound.poll(50, TimeUnit.MILLISECONDS) ?: continue
                        if (delay > 0) Thread.sleep(delay)
                        // No lock is held across this call: both carrier threads are inside the
                        // session at the same time, which is the whole point.
                        sink.onFrame(next.raw, next.raw.size, next.decoded)
                    }
                }, "fake-radio-$radio").apply { isDaemon = true }
                threads.add(thread)
                thread.start()
            }
            return true
        }

        /** Bytes the phone has advertised room for (WINDOW frames), exactly like the real bridge. */
        private val credit = AtomicInteger(0)
        private val creditLock = Object()

        override fun send(frame: ByteArray): Boolean {
            val decoded = Brt.decode(frame) ?: return true
            when (decoded.type) {
                Brt.HELLO -> enqueue(Brt.HELLO_ACK, ByteArray(4))
                Brt.OPEN -> enqueue(
                    Brt.OPEN_OK,
                    Brt.withStreamId(
                        Brt.streamId(decoded.payload),
                        Brt.encodeAddress("127.0.0.1", 443),
                    ),
                )
                // The phone returns credit as it consumes: honouring it is what throttles us to
                // the reader's pace, which is what fills the mux queue and blocks `drain()`.
                // WINDOW payload is [2-byte stream id][4-byte byte count] (Brt.withStreamId
                // prefixes exactly two bytes, not four).
                Brt.WINDOW -> synchronized(creditLock) {
                    val p = decoded.payload
                    credit.addAndGet(
                        ((p[2].toInt() and 0xFF) shl 24) or
                            ((p[3].toInt() and 0xFF) shl 16) or
                            ((p[4].toInt() and 0xFF) shl 8) or
                            (p[5].toInt() and 0xFF),
                    )
                    creditLock.notifyAll()
                }
                else -> Unit
            }
            return true
        }

        fun enqueue(type: Int, body: ByteArray) {
            val raw = Brt.encode(type, 0, cookie, body)
            inbound.put(Frame(raw, Brt.Frame(type, 0, body)))
        }

        /** Sends DATA only while the phone has advertised room, then waits for a WINDOW frame. */
        fun feedData(offset: Int, bytes: ByteArray) {
            synchronized(creditLock) {
                while (credit.get() <= 0 && running) {
                    creditLock.wait(50)
                }
                credit.addAndGet(-bytes.size)
            }
            enqueue(Brt.DATA, Brt.withStreamId(streamId, Brt.intBytes(offset) + bytes))
        }

        override fun close() {
            running = false
            threads.forEach { it.interrupt() }
        }
    }

    private var cookie = 0
    private var streamId = 0

    @Test
    fun aTwoCarrierStreamIsDeliveredInOrder() {
        repeat(ROUNDS) { round ->
            val farSide = TwoRadioFarSide()
            val session = MuxSession(farSide, cookie, label = "ordering-$round")
            check(session.start(5_000)) { "session did not start: ${session.downReason()}" }
            val socket = session.openTcp("127.0.0.1", 443, timeoutMs = 5_000)
            streamId = (socket as MuxSession.MuxSocket).streamId
            socket.soTimeout = 10_000

            // Feed the whole body, then read it back. The far side hands consecutive frames to
            // the session on alternating threads, so the reorderer is fed out of order and two
            // drains can overlap - the exact condition that corrupted TLS.
            val feeder = Thread({
                for (i in 0 until chunks) {
                    farSide.feedData(i * chunk, payload.copyOfRange(i * chunk, (i + 1) * chunk))
                }
            }, "ordering-feeder-$round").apply { isDaemon = true }
            feeder.start()

            // The reader is SLOWER than the radios on purpose: a phone whose relay thread is busy
            // applies back-pressure, the mux queue fills up, and `drain()` ends up blocking on a
            // full queue - the only window in which two carrier threads can be inside `drain()` at
            // the same time for long enough to invert. A reader that keeps up never opens it, and
            // a reader that never reads at all stalls the stream on flow control instead.
            val received = ByteArray(total)
            val readCount = AtomicInteger(0)
            val reader = Thread({
                try {
                    while (readCount.get() < total) {
                        val at = readCount.get()
                        val want = minOf(SLOW_READ_BYTES, total - at)
                        val n = socket.getInputStream().read(received, at, want)
                        if (n < 0) break
                        readCount.set(at + n)
                        if (READER_STALL_MS > 0) Thread.sleep(READER_STALL_MS)
                    }
                } catch (_: Exception) {
                    // A timeout surfaces below as a short read with the exact offset.
                }
            }, "slow-reader-$round").apply { isDaemon = true }
            reader.start()

            feeder.join(60_000)
            reader.join(60_000)
            val read = readCount.get()

            assertEquals("round $round delivered $read/$total bytes", total, read)
            val firstBad = (0 until total).firstOrNull { received[it] != payload[it] } ?: -1
            assertEquals(
                "round $round delivered the stream out of order at byte $firstBad " +
                    "(chunk ${firstBad / chunk}, expected chunk tag ${(firstBad / chunk) and 0xFF})",
                -1,
                firstBad,
            )

            runCatching { socket.close() }
            runCatching { session.close() }
            runCatching { farSide.close() }
        }
    }

    private companion object {
        /** Rounds per run: the unfixed race is probabilistic, several rounds make it reliable. */
        const val ROUNDS = 12

        /** How long the first carrier thread parks before delivering, forcing inverted arrival. */
        const val DELIVER_INVERSION_MS = 3L

        /** Per-read pause. 0 = the reader keeps up; the radios still interleave the frames. */
        const val READER_STALL_MS = 0L

        /** Bytes per read (a small slice keeps the loops above interleaving). */
        const val SLOW_READ_BYTES = 64 * 1024

        /** Initial flow-control window the stub grants itself (matches MuxSession's). */
        const val WINDOW_BYTES = 256 * 1024
    }
}
