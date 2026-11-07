package com.bigrocket.bonding.sim

import java.security.MessageDigest
import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Local (fully in-app) split/join simulation: BOTH engines run inside the process.
 *
 *   TCP byte stream ──► MergeEngine (A) ──► [ path1 link ] ──┐
 *                                          └► [ path2 link ] ─┴► SeparationEngine (B) ──► byte-exact stream
 *
 * Unlike the loopback sandbox, the two links are *modelled*: each one has a bandwidth cap, a
 * latency, a jitter and a loss rate, and frames are delivered by a discrete-event clock (no
 * real sleeping), so a 2 MB stream finishes in milliseconds of CPU time and the result is
 * deterministic for a given seed.
 *
 * This proves exactly what a single SOCKS5 proxy cannot do: one TCP connection, split across
 * two paths at frame granularity, reassembled in order at the far end. It does NOT claim to
 * give real radio aggregation - both engines share the phone's CPU and the links are
 * simulated - but the splitter, the sequence numbers, the reorder buffer, the NACK/retransmit
 * path and the gap-skip fallback are the real algorithms that would run against a remote B.
 */
data class PathProfile(
    val name: String,
    /** Capacity in kbit/s. */
    val bandwidthKbps: Int,
    val latencyMs: Long,
    val jitterMs: Long = 0,
    /** Percent of frames lost on this path (each retransmit rolls the dice again). */
    val lossPercent: Int = 0,
)

data class SimConfig(
    /** Size of the simulated TCP stream. */
    val bytes: Int = 2 * 1024 * 1024,
    val frameBytes: Int = 1200,
    val wifi: PathProfile = PathProfile("wifi", 40_000, 20, 5, 0),
    val cellular: PathProfile = PathProfile("cellular", 15_000, 45, 10, 0),
    /** Share of frames the splitter sends over Wi-Fi (0..100) - the score ratio. */
    val wifiPercent: Int = 50,
    val rtoMs: Long = 250,
    val hardSkipMs: Long = 1_500,
    val maxRetransmitsPerFrame: Int = 3,
    val seed: Long = 42L,
)

data class SimReport(
    val config: SimConfig,
    val deliveredBytes: Int,
    val byteExact: Boolean,
    val completed: Boolean,
    val elapsedMs: Long,
    val goodputKbps: Int,
    val wifiBytes: Int,
    val cellularBytes: Int,
    val framesSent: Int,
    val retransmits: Int,
    val droppedFrames: Int,
    val skippedGaps: Int,
    val skippedBytes: Int,
    val maxReorderDepth: Int,
    val interleave: String,
    val notes: List<String>,
) {
    val splitPercent: Int
        get() {
            val total = wifiBytes + cellularBytes
            return if (total == 0) 0 else (wifiBytes * 100.0 / total).toInt()
        }

    fun summary(): String = buildString {
        appendLine("نتیجه: ${if (byteExact && completed) "موفق" else "ناموفق"} — ${if (byteExact) "بایت‌به‌بایت درست" else "خروجی مطابق ورودی نیست"}")
        appendLine("داده: ${fmt(deliveredBytes)} از ${fmt(config.bytes)}")
        appendLine("زمان شبیه‌سازی‌شده: ${elapsedMs} میلی‌ثانیه → سرعت مؤثر ${goodputKbps} kbps")
        appendLine("سهم مسیرها: وای‌فای ${fmt(wifiBytes)} (${splitPercent}%) / سلولار ${fmt(cellularBytes)} (${100 - splitPercent}%)")
        appendLine("فریم: ${framesSent} ارسالی، ${retransmits} ارسال مجدد، ${droppedFrames} افتاده")
        appendLine("بازچینی: حداکثر عمق بافر ${maxReorderDepth}، شکاف ردشده ${skippedGaps} (${fmt(skippedBytes)} بایت)")
        appendLine("ترتیب تخصیص (۲۰ فریم اول): $interleave")
        if (notes.isNotEmpty()) notes.forEach { appendLine("• $it") }
    }

    private fun fmt(n: Int): String = when {
        n >= 1024 * 1024 -> String.format("%.2f MB", n / 1048576.0)
        n >= 1024 -> String.format("%.1f KB", n / 1024.0)
        else -> "$n B"
    }
}

/** A frame on the wire: global sequence number + payload. */
internal class SimFrame(val seq: Int, val payload: ByteArray)

/** One physical path, modelled as a FIFO link with a bandwidth cap. */
private class LinkSim(val profile: PathProfile, private val random: Random) {
    private var freeAtMs = 0.0

    /** @return delivery time in ms, or null when the frame is lost on this path. */
    fun transmit(nowMs: Double, sizeBytes: Int): Double? {
        val start = maxOf(freeAtMs, nowMs)
        // bits / (kbit/s) = milliseconds of serialisation delay
        val serialMs = ceil(sizeBytes * 8.0 / profile.bandwidthKbps)
        val finish = start + serialMs
        freeAtMs = finish
        if (profile.lossPercent > 0 && random.nextInt(100) < profile.lossPercent) return null
        val jitter = if (profile.jitterMs > 0) random.nextLong(-profile.jitterMs, profile.jitterMs + 1) else 0L
        return (finish + profile.latencyMs + jitter).coerceAtLeast(finish)
    }
}

private sealed interface SimEvent {
    val timeMs: Double
}

private class DeliveryEvent(
    override val timeMs: Double,
    val frame: SimFrame,
    val overWifi: Boolean,
    val retransmit: Boolean,
) : SimEvent

private class NackEvent(
    override val timeMs: Double,
    val missingSeq: Int,
) : SimEvent

private class SkipEvent(
    override val timeMs: Double,
    val missingSeq: Int,
) : SimEvent

/**
 * Engine A - the merge/split side. Takes one ordered byte stream (what a single TCP connection
 * would produce), cuts it into frames and hands successive frames to alternating / proportional
 * paths. Keeps a cache so the separation side can ask for a resend.
 */
internal class MergeEngine(private val config: SimConfig) {

    private val cache = HashMap<Int, SimFrame>()
    private val assignment = HashMap<Int, Boolean>() // seq -> overWifi
    private var nextSeq = 0

    /** @return the frames in send order (one per [SimConfig.frameBytes] of input). */
    fun split(source: ByteArray): List<SimFrame> {
        val frames = ArrayList<SimFrame>(source.size / config.frameBytes + 1)
        var offset = 0
        var wifiCredit = 0
        while (offset < source.size) {
            val n = minOf(config.frameBytes, source.size - offset)
            val payload = source.copyOfRange(offset, offset + n)
            offset += n
            val frame = SimFrame(nextSeq, payload)
            // Score-ratio allocator, identical semantics to the production PathBondAllocator:
            // equal shares alternate strictly (the first unit goes to the secondary path at
            // exactly 50/50), unequal shares stay proportional across the whole run.
            wifiCredit += config.wifiPercent
            val overWifi = when {
                wifiCredit >= 100 -> { wifiCredit -= 100; true }
                else -> false
            }
            assignment[nextSeq] = overWifi
            cache[nextSeq] = frame
            frames.add(frame)
            nextSeq++
        }
        return frames
    }

    fun isOverWifi(seq: Int): Boolean = assignment[seq] ?: false

    fun frame(seq: Int): SimFrame? = cache[seq]
}

/** Ordered byte sink with the reorder/gap logic a real joiner needs. */
private class Joiner(private val config: SimConfig, private val expectedFrames: Int) {
    private val pending = java.util.TreeMap<Int, SimFrame>()
    var expected = 0
    var maxDepth = 0
    var skippedGaps = 0
    var skippedBytes = 0
    val out = java.io.ByteArrayOutputStream()

    /** @return true when [frame] was accepted (new), false when it was a duplicate/in-order-repeat. */
    fun receive(frame: SimFrame): Boolean {
        if (frame.seq < expected) return false
        if (frame.seq == expected) {
            deliver(frame)
            // cascade
            while (true) {
                val next = pending.remove(expected) ?: break
                deliver(next)
            }
            return true
        }
        if (pending.containsKey(frame.seq)) return false
        pending[frame.seq] = frame
        if (pending.size > maxDepth) maxDepth = pending.size
        return true
    }

    private fun deliver(frame: SimFrame) {
        out.write(frame.payload)
        expected = frame.seq + 1
    }

    /** Give up on [missingSeq]: jump forward to the next frame that actually arrived. */
    fun skipTo(missingSeq: Int) {
        if (missingSeq != expected) return
        val nextAvailable: Int? = pending.ceilingKey(expected)
        val jumpTo: Int = nextAvailable ?: expectedFrames
        if (jumpTo > expected) {
            skippedGaps++
            skippedBytes += (jumpTo - expected) * config.frameBytes
            expected = jumpTo
        }
        // Anything that is now contiguous behind the new expected number can be delivered.
        while (true) {
            val next = pending.remove(expected) ?: break
            deliver(next)
        }
    }

    fun done(): Boolean = expected >= expectedFrames
}

object LocalDuplexSimulation {

    /**
     * Runs the whole thing: A splits, both links deliver (with bandwidth/latency/jitter/loss),
     * B reorders, asks for resends (NACK) and finally the delivered bytes are compared with the
     * source.
     */
    fun run(config: SimConfig): SimReport {
        val random = Random(config.seed)
        val source = ByteArray(config.bytes).also { Random(config.seed xor 0x5EED).nextBytes(it) }
        val merger = MergeEngine(config)
        val frames = merger.split(source)
        val joiner = Joiner(config, frames.size)

        val wifiLink = LinkSim(config.wifi, Random(config.seed + 1))
        val cellLink = LinkSim(config.cellular, Random(config.seed + 2))
        val queue = PriorityQueue<SimEvent>(compareBy({ it.timeMs }, { it.hashCode() }))

        var wifiBytes = 0
        var cellBytes = 0
        var retransmits = 0
        var dropped = 0
        val remainingRetransmits = HashMap<Int, Int>()
        val nackTimers = HashSet<Int>()
        val skipTimers = HashSet<Int>()

        fun transmit(frame: SimFrame, overWifi: Boolean, nowMs: Double, isRetransmit: Boolean) {
            val link = if (overWifi) wifiLink else cellLink
            val at = link.transmit(nowMs, frame.payload.size)
            if (at == null) {
                dropped++
                return
            }
            if (!isRetransmit) {
                if (overWifi) wifiBytes += frame.payload.size else cellBytes += frame.payload.size
            }
            queue.add(DeliveryEvent(at, frame, overWifi, isRetransmit))
        }

        fun armTimers(nowMs: Double) {
            val expected = joiner.expected
            if (expected >= frames.size) return
            if (nackTimers.add(expected)) {
                queue.add(NackEvent(nowMs + config.rtoMs, expected))
            }
            if (skipTimers.add(expected)) {
                queue.add(SkipEvent(nowMs + config.hardSkipMs, expected))
            }
        }

        frames.forEach { transmit(it, merger.isOverWifi(it.seq), 0.0, false) }

        var now = 0.0
        var guard = 0
        while (queue.isNotEmpty() && !joiner.done() && guard++ < 4_000_000) {
            val event = queue.poll() ?: break
            now = maxOf(now, event.timeMs)
            when (event) {
                is DeliveryEvent -> {
                    joiner.receive(event.frame)
                    if (!joiner.done()) armTimers(now)
                }
                is NackEvent -> {
                    nackTimers.remove(event.missingSeq)
                    if (joiner.expected != event.missingSeq) continue
                    val tries = remainingRetransmits.getOrDefault(event.missingSeq, 0)
                    if (tries >= config.maxRetransmitsPerFrame) continue
                    remainingRetransmits[event.missingSeq] = tries + 1
                    val frame = merger.frame(event.missingSeq)
                    if (frame != null) {
                        retransmits++
                        // Resent on the same path it was assigned to: if that path is the one
                        // that is down, this loops until the skip timer fires - exactly the
                        // behaviour we want to observe.
                        transmit(frame, merger.isOverWifi(frame.seq), now, true)
                    }
                    if (nackTimers.add(event.missingSeq)) {
                        queue.add(NackEvent(now + config.rtoMs, event.missingSeq))
                    }
                }
                is SkipEvent -> {
                    skipTimers.remove(event.missingSeq)
                    if (joiner.expected != event.missingSeq) continue
                    joiner.skipTo(event.missingSeq)
                    if (!joiner.done()) armTimers(now)
                }
            }
        }

        val delivered = joiner.out.toByteArray()
        val exact = delivered.size == source.size && MessageDigest.isEqual(
            MessageDigest.getInstance("SHA-256").digest(delivered),
            MessageDigest.getInstance("SHA-256").digest(source),
        )
        val elapsed = now.coerceAtLeast(1.0)
        val goodputKbps = (delivered.size * 8.0 / elapsed).toInt()

        val notes = ArrayList<String>()
        if (joiner.skippedGaps > 0) {
            notes.add("شکاف رد شد: داده‌ی ازدست‌رفته با ارسال مجدد برنگشت (مسیر افتاده یا RTO کوتاه است).")
        }
        if (retransmits > 0) {
            notes.add("$retransmits ارسال مجدد لازم شد — همان کاری که یک پروکسی SOCKS5 نمی‌تواند انجام دهد.")
        }
        if (!joiner.done()) notes.add("استریم کامل نشد (شبیه‌سازی به سقف رویدادها رسید).")

        return SimReport(
            config = config,
            deliveredBytes = delivered.size,
            byteExact = exact,
            completed = joiner.done(),
            elapsedMs = elapsed.toLong(),
            goodputKbps = goodputKbps,
            wifiBytes = wifiBytes,
            cellularBytes = cellBytes,
            framesSent = frames.size,
            retransmits = retransmits,
            droppedFrames = dropped,
            skippedGaps = joiner.skippedGaps,
            skippedBytes = joiner.skippedBytes,
            maxReorderDepth = joiner.maxDepth,
            interleave = frames.take(20).joinToString(",") { if (merger.isOverWifi(it.seq)) "W" else "C" },
            notes = notes,
        )
    }

    /** Baseline: the same stream squeezed through ONE path - what a single link would do. */
    fun runSinglePath(config: SimConfig, overWifi: Boolean): SimReport =
        run(config.copy(wifiPercent = if (overWifi) 100 else 0))
}
